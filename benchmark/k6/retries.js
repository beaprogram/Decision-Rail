import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import encoding from 'k6/encoding';

/**
 * Identical commands resent under their original key.
 *
 * The point is not throughput. It is that repeating a command byte for byte, concurrently and often,
 * produces one financial effect and one stored answer. Each iteration authorizes once and then resends
 * exactly the same request with exactly the same key several times, in parallel, which is the shape a
 * real client retry storm takes after a timeout.
 *
 * The harness checks the database afterwards; this script only asserts what a client can see, which is
 * that every replay returns the same payment id and the same status as the original.
 */

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8081';
const MERCHANT = __ENV.MERCHANT || 'demo-merchant';
const PASSWORD = __ENV.MERCHANT_PASSWORD;
const ACCOUNTS = (__ENV.ACCOUNT_IDS || '').split(',').filter(Boolean);
const RATE = Number(__ENV.RATE || 20);
const DURATION = __ENV.DURATION || '30s';
const REPLAYS = Number(__ENV.REPLAYS || 3);
const AMOUNT_MINOR = Number(__ENV.AMOUNT_MINOR || 300);

if (!PASSWORD) throw new Error('MERCHANT_PASSWORD is required; it is read from the generated .env');
if (ACCOUNTS.length === 0) throw new Error('ACCOUNT_IDS is required; the harness seeds them');

const AUTHORIZATION = `Basic ${encoding.b64encode(`${MERCHANT}:${PASSWORD}`)}`;
const divergentReplay = new Counter('divergent_replays');
const replayCount = new Counter('replays_sent');
const originals = new Counter('originals_sent');
// An original that never produced a payment is counted here, not left to be inferred from the gap
// between originals sent and idempotency records in the database. Only successful originals are
// replayed, so without this the replay total silently describes a smaller population than the
// original total, and a report can end up claiming every original was replayed.
const originalsSucceeded = new Counter('originals_succeeded');
const originalsFailed = new Counter('originals_failed');

export const options = {
  // p99 and sample counts are not in k6's default export. A percentile without the number of samples
  // behind it is not a measurement anyone can judge.
  summaryTrendStats: ['count', 'min', 'med', 'avg', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    // One phase, no warmup. This scenario is a correctness check under concurrency, not a latency
    // measurement, so there is nothing a warmup would protect; the whole run is the population and it
    // is tagged `measured` so the collector reads it the same way as every other scenario rather than
    // through a special case.
    measured: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // Sized on the same rule as payments.js: the generator must not be the constraint.
      preAllocatedVUs: Math.max(50, RATE * 3),
      maxVUs: Math.max(100, RATE * 6),
      tags: { phase: 'measured' },
      exec: 'replay',
    },
  },
  // Selected on the built-in scenario tag for the same reason as payments.js: a custom tag never
  // reaches an executor-dropped iteration. This scenario has no warmup, so its one scenario is the
  // whole run and its drops are all measured drops.
  // Note what is and is not a pass criterion. Divergence must be zero: a replay returning a different
  // result is the failure this scenario exists to catch. Failed originals are tolerated to a small
  // rate because a connection that never reached the application says nothing about idempotency - but
  // they are counted and reported, and a run with any of them is not a zero-failure demonstration.
  thresholds: {
    divergent_replays: ['count<1'],
    'divergent_replays{scenario:measured}': ['count<1'],
    'originals_sent{scenario:measured}': ['count>=0'],
    'originals_succeeded{scenario:measured}': ['count>=0'],
    'originals_failed{scenario:measured}': ['count>=0'],
    'replays_sent{scenario:measured}': ['count>=0'],
    'http_req_duration{scenario:measured}': ['max>=0'],
    'http_reqs{scenario:measured}': ['count>=0'],
    'iterations{scenario:measured}': ['count>=0'],
    'dropped_iterations{scenario:measured}': ['count>=0'],
    'http_req_failed{scenario:measured}': ['rate<0.01'],
  },
};

export function replay() {
  const account = ACCOUNTS[Math.floor(Math.random() * ACCOUNTS.length)];
  const key = `bench-replay-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({
    accountId: account,
    amountMinor: AMOUNT_MINOR,
    currency: 'CAD',
    country: 'CA',
  });
  const params = {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key, Authorization: AUTHORIZATION },
    tags: { operation: 'authorize-replayed' },
  };

  const first = http.post(`${BASE}/v1/payments/authorizations`, body, params);
  originals.add(1);
  if (first.status !== 201) {
    // Status 0 is a request that never got a response at all - a connection that was not established,
    // or one lost before any reply. The tag is the status code, which is bounded, never the error text.
    originalsFailed.add(1, { status: String(first.status) });
    // Deliberately not replayed. A client whose original failed would retry it, but this scenario
    // exists to show that a *successful* command replayed concurrently produces one effect, and mixing
    // in recovery of unknown-outcome originals would measure two different things at once.
    return;
  }
  originalsSucceeded.add(1);
  const original = first.json();

  // The same bytes under the same key, sent together rather than one after another: a client that
  // times out and retries does not wait politely for the first attempt to finish.
  const batch = [];
  for (let i = 0; i < REPLAYS; i += 1) {
    batch.push(['POST', `${BASE}/v1/payments/authorizations`, body, params]);
  }
  const responses = http.batch(batch);
  for (const response of responses) {
    replayCount.add(1);
    const replayed = response.status === 201 ? response.json() : null;
    const same = replayed && replayed.id === original.id && replayed.status === original.status;
    if (!same) divergentReplay.add(1, { status: String(response.status) });
    check(response, { 'replay returns the original result': () => Boolean(same) });
  }
}
