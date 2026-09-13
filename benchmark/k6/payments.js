import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { randomSeed } from 'k6';
import encoding from 'k6/encoding';

/**
 * Ordinary payment traffic, and the same traffic aimed at one account.
 *
 * Open model on purpose. `constant-arrival-rate` keeps offering the configured rate whether or not the
 * application keeps up, and reports `dropped_iterations` when it cannot start work on time. A
 * closed-loop client would quietly slow down instead, and the run would then be described as
 * sustaining a rate it never offered.
 *
 * Business outcomes are checked, not just HTTP status. An authorization that declines for insufficient
 * funds is a 201 with DECLINED in it: a run that silently turns into declines halfway through has
 * stopped measuring the thing it claims to measure, so declines are counted separately and the report
 * states them.
 *
 * Warmup and measurement are separate scenarios and the collector reads only the measured one. This
 * matters more than it looks: a 60-second run at 30/s behind a 15-second warmup at 15/s produces 2027
 * authorizations in the aggregate and 1800 in the measured scenario, so an aggregate percentile is
 * computed over a population that is an eighth warmup traffic while claiming warmup was excluded.
 */

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8081';
const MERCHANT = __ENV.MERCHANT || 'demo-merchant';
const PASSWORD = __ENV.MERCHANT_PASSWORD;
const ACCOUNTS = (__ENV.ACCOUNT_IDS || '').split(',').filter(Boolean);
const HOT_ACCOUNT = __ENV.HOT_ACCOUNT_ID || ACCOUNTS[0];
const MODE = __ENV.MODE || 'spread';
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';
const WARMUP = __ENV.WARMUP || '15s';
const AMOUNT_MINOR = Number(__ENV.AMOUNT_MINOR || 500);

if (!PASSWORD) throw new Error('MERCHANT_PASSWORD is required; it is read from the generated .env');
if (ACCOUNTS.length === 0) throw new Error('ACCOUNT_IDS is required; the harness seeds them');

randomSeed(Number(__ENV.SEED || 20260913));

const authorized = new Counter('business_authorized');
const declined = new Counter('business_declined');
const captured = new Counter('business_captured');
const voided = new Counter('business_voided');
const unexpected = new Counter('unexpected_errors');
const authorizeLatency = new Trend('op_authorize', true);
const captureLatency = new Trend('op_capture', true);
const voidLatency = new Trend('op_void', true);

export const options = {
  // p99 and sample counts are not in k6's default export. A percentile without the number of samples
  // behind it is not a measurement anyone can judge.
  summaryTrendStats: ['count', 'min', 'med', 'avg', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    // Warmup is a separate scenario so its samples never enter the measured window: JIT compilation,
    // connection pools and page cache all make the first seconds unrepresentative.
    warmup: {
      executor: 'constant-arrival-rate',
      rate: Math.max(1, Math.round(RATE / 2)),
      timeUnit: '1s',
      duration: WARMUP,
      preAllocatedVUs: Math.max(10, RATE),
      maxVUs: Math.max(50, RATE * 4),
      tags: { phase: 'warmup' },
      exec: 'lifecycle',
    },
    measured: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      startTime: WARMUP,
      preAllocatedVUs: Math.max(10, RATE),
      maxVUs: Math.max(50, RATE * 4),
      tags: { phase: 'measured' },
      exec: 'lifecycle',
    },
  },
  // Thresholds do two jobs. The first two are pass criteria. The rest exist because k6 only writes a
  // sub-metric into the summary export when a threshold names it, and the measured scenario is the
  // population this benchmark reports. Without them the export holds only aggregates that mix warmup
  // samples into every percentile and count. An always-true `>=0` is the documented way to materialise
  // a sub-metric; it can never fail and never masks a real breach.
  //
  // Everything selects on the **built-in `scenario` tag**, not on a custom one. Custom scenario tags
  // reach ordinary samples but not the iterations an executor drops: those carry only global run tags
  // and the built-in scenario tag. Verified against this pinned k6 version by
  // benchmark/k6-attribution-check.sh, which exists because the failure is silent - the sub-metric is
  // created, matches nothing, and reports a confident zero while the aggregate holds hundreds.
  thresholds: {
    unexpected_errors: ['count<1'],
    'http_req_failed{scenario:measured}': ['rate<0.01'],

    'op_authorize{scenario:measured}': ['max>=0'],
    'op_capture{scenario:measured}': ['max>=0'],
    'op_void{scenario:measured}': ['max>=0'],
    'http_req_duration{scenario:measured}': ['max>=0'],
    'http_reqs{scenario:measured}': ['count>=0'],
    'iterations{scenario:measured}': ['count>=0'],
    'dropped_iterations{scenario:measured}': ['count>=0'],
    'business_authorized{scenario:measured}': ['count>=0'],
    'business_declined{scenario:measured}': ['count>=0'],
    'business_captured{scenario:measured}': ['count>=0'],
    'business_voided{scenario:measured}': ['count>=0'],
    'unexpected_errors{scenario:measured}': ['count>=0'],
    // The warmup is materialised too, so its drops are reported rather than implied to be absent.
    'op_authorize{scenario:warmup}': ['max>=0'],
    'iterations{scenario:warmup}': ['count>=0'],
    'dropped_iterations{scenario:warmup}': ['count>=0'],
  },
  discardResponseBodies: false,
};

// Built once and sent as a header. Credentials never go in a URL, where they would reach access logs
// and k6's own request tags. Basic authentication stays enabled for the whole run: verifying a
// password is real work the API does on every call, and removing it to improve a number would be
// measuring a different system.
const AUTHORIZATION = `Basic ${encoding.b64encode(`${MERCHANT}:${PASSWORD}`)}`;

function headers(key) {
  return {
    'Content-Type': 'application/json',
    'Idempotency-Key': key,
    Authorization: AUTHORIZATION,
  };
}

function newKey(prefix) {
  return `bench-${prefix}-${__VU}-${__ITER}-${Date.now()}`;
}

function pickAccount() {
  if (MODE === 'hot') return HOT_ACCOUNT;
  return ACCOUNTS[Math.floor(Math.random() * ACCOUNTS.length)];
}

export function lifecycle() {
  const account = pickAccount();
  const body = JSON.stringify({
    accountId: account,
    amountMinor: AMOUNT_MINOR,
    currency: 'CAD',
    country: 'CA',
  });

  const created = http.post(`${BASE}/v1/payments/authorizations`, body, {
    headers: headers(newKey('auth')),
    tags: { operation: 'authorize' },
  });
  authorizeLatency.add(created.timings.duration);

  if (created.status !== 201) {
    unexpected.add(1, { operation: 'authorize', status: String(created.status) });
    return;
  }
  const payment = created.json();
  if (payment.status === 'AUTHORIZED') {
    authorized.add(1);
  } else {
    // Expected only if the seeded balance ran out, which invalidates the run rather than failing it.
    declined.add(1, { failureCode: payment.failureCode || 'none' });
    return;
  }

  // Two thirds captured, one third voided: both paths write, and only capture writes a journal.
  const capturing = Math.random() < 0.66;
  const url = `${BASE}/v1/payments/${payment.id}/${capturing ? 'capture' : 'void'}`;
  const settled = http.post(url, null, {
    headers: headers(newKey(capturing ? 'cap' : 'void')),
    tags: { operation: capturing ? 'capture' : 'void' },
  });
  (capturing ? captureLatency : voidLatency).add(settled.timings.duration);

  if (settled.status === 200) {
    (capturing ? captured : voided).add(1);
  } else {
    unexpected.add(1, { operation: capturing ? 'capture' : 'void', status: String(settled.status) });
  }
  check(settled, { 'lifecycle command accepted': (r) => r.status === 200 });
}
