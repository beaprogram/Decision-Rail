import { Trend } from 'k6/metrics';
import { sleep } from 'k6';

/**
 * A deterministic generator of dropped iterations, with no application behind it.
 *
 * Two scenarios offer more work than their bounded virtual users can serve, so the executor has to
 * drop iterations it cannot start. Nothing here touches HTTP, the payment application, the database or
 * the broker: the contract under test is between k6 and the summariser, and involving the application
 * would only add ways for the check to fail for unrelated reasons.
 *
 * Set PROBE_MODE=quiet for the opposite case - an offered rate the pool can comfortably serve, which
 * must report zero drops rather than merely appearing to.
 */
const QUIET = __ENV.PROBE_MODE === 'quiet';
const latency = new Trend('probe_latency');

// Saturating: 50/s against 2 VUs each taking 200ms serves about 10/s, so roughly 80% is dropped.
// Quiet: 4/s against 8 VUs taking 50ms is served comfortably, so nothing should drop.
const RATE = QUIET ? 4 : 50;
const VUS = QUIET ? 8 : 2;
const WORK_SECONDS = QUIET ? 0.05 : 0.2;

export const options = {
  summaryTrendStats: ['count', 'med', 'max'],
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: '3s',
      preAllocatedVUs: VUS, maxVUs: VUS,
      tags: { phase: 'warmup' },
      exec: 'work',
    },
    measured: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: '4s', startTime: '4s',
      preAllocatedVUs: VUS, maxVUs: VUS,
      tags: { phase: 'measured' },
      exec: 'work',
    },
  },
  thresholds: {
    // The selector the harness uses, and the one it used to use. Both are materialised so the check
    // can show that one matches the run's drops and the other silently matches nothing.
    'dropped_iterations{scenario:measured}': ['count>=0'],
    'dropped_iterations{scenario:warmup}': ['count>=0'],
    'dropped_iterations{phase:measured}': ['count>=0'],
    'dropped_iterations{phase:warmup}': ['count>=0'],
    'iterations{scenario:measured}': ['count>=0'],
    'iterations{scenario:warmup}': ['count>=0'],
    // Built-in HTTP metrics the summariser requires. This probe makes no requests, so they are empty:
    // that is fine and is itself worth asserting, since an empty metric must not become a failure.
    'http_reqs{scenario:measured}': ['count>=0'],
    'http_req_failed{scenario:measured}': ['rate<0.01'],
    // A custom tag does reach ordinary samples. Shown for contrast: this is why the defect was
    // invisible - every other filtered figure was correct.
    'probe_latency{phase:measured}': ['max>=0'],
  },
};

export function work() {
  const started = Date.now();
  sleep(WORK_SECONDS);
  latency.add(Date.now() - started);
}
