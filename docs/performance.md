# Measured performance

Real numbers from the harness in [benchmarking.md](benchmarking.md), on the hardware named below.
Nothing here is estimated, extrapolated, or carried over from a previous run.

> **Two rounds of figures are superseded and must not be quoted.** The first computed percentiles over a
> population that included warmup traffic. The second fixed that but reported dropped iterations through
> a selector k6 never populates, so runs that dropped work were published as having dropped none — which
> is why the sustained rate in this document is now lower than the one it replaces. Both sets are kept
> in `benchmark/results/` with their original timestamps rather than rewritten.

## What was measured on

| | |
| --- | --- |
| Measured revision | `ac7f487`, sources clean at the start of each run |
| Artifact | `decisionrail-0.1.0.jar`, sha256 `86d2414c69c5126c…`, identical across every run |
| Machine | macOS 26.6.2, arm64, 10 CPUs |
| Runtime | OpenJDK 21.0.11, packaged Spring Boot jar |
| Database | PostgreSQL 16 in Docker, **tmpfs storage, `fsync=off`**, `max_connections=200`, `shared_buffers=256MB` |
| Broker | Apache Kafka 3.9.1, single node, replication factor 1 |
| Topology | Load generator, application, database and broker on **one machine**, competing for the same CPUs |
| Authentication | HTTP Basic, enabled, password verification included in every measured request |
| Dataset | 24 accounts, 1,000,000.00 CAD each, fresh database per run |
| Workload | Open model, seed 20260913, 15s warmup excluded by scenario, 60s measured |
| Tracing | 100% sampling unless stated; OTLP export off; Prometheus not scraped during runs |

One *iteration* is a full business operation: an authorization, then a capture or a void. Each is two
HTTP requests, so 25 iterations/s is 50 requests/s.

Every figure comes from the measured scenario. Rates are `count ÷ 60s`, the declared window. Dropped
iterations are attributed by the built-in `scenario` tag and reconciled against the aggregate.

## Sustained rate

The criteria were fixed in [benchmarking.md](benchmarking.md) before these runs: zero measured drops,
zero failures, zero declines, achieved rate within 2% of offered, backlog drained within the run's own
duration, and every repetition passing.

**25 iterations/s (50 HTTP requests/s) meets them across three repetitions.**

| Rep | Samples (of 1500 offered) | Measured drops | Warmup drops | p50 | p95 | p99 | Achieved req/s | Drain after load |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1501 | **0** | 0 | 119.5 ms | 792.1 ms | 1118.1 ms | 50.03 | 3 s |
| 2 | 1501 | **0** | 0 | 266.4 ms | 1025.7 ms | 1449.0 ms | 50.03 | 10 s |
| 3 | 1501 | **0** | 0 | 275.0 ms | 795.7 ms | 978.2 ms | 50.03 | 9 s |

Zero declines and zero failed requests in every run. Virtual users in use peaked at 44, 51 and 68
against 75 preallocated, so the generator was not the constraint.

## Where it stops

**30 iterations/s is the nearby failing level**, and it fails intermittently rather than cleanly.

| Rep | Samples (of 1800) | Measured drops | p50 | p95 | p99 | Observed max backlog | Drain after load |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1801 | 0 | 204.5 ms | 428.7 ms | 561.9 ms | 448 | 7 s |
| 2 | 1801 | 0 | 186.8 ms | 569.3 ms | 788.5 ms | 294 | 4 s |
| 3 | 1780 | **21** | 495.3 ms | 1781.2 ms | 2207.1 ms | 2231 | 14 s |

Two repetitions of three would have passed. The third degraded across every dimension at once — latency
roughly tripled at p50 and quadrupled at p95, the backlog reached 2231 against 294–448, and the
concurrency the workload demanded rose to 103 virtual users against 75 preallocated, at which point the
executor dropped 21 iterations while the pool grew.

The criteria require every repetition to pass, so 30/s is not sustained. That the same rate passed twice
is the reason the criteria were written down in advance.

**The previously published headline of "30 iterations/s with zero dropped work" was never established.**
The runs behind it dropped 9, 3 and 4 iterations; the selector reading those counts matched nothing and
reported zero.

## Dropped iterations, and why they were invisible

k6 attaches a scenario's custom tags to ordinary samples but not to the iterations its executor drops:
those carry global run tags and the built-in `scenario` tag only. Verified against the pinned 0.55.0
image by `benchmark/k6-attribution-check.sh`, which drives a workload that really drops work:

| Selector | Count |
| --- | --- |
| `dropped_iterations` (aggregate, all scenarios) | 284 |
| `dropped_iterations{scenario:measured}` | 162 |
| `dropped_iterations{scenario:warmup}` | 122 |
| `dropped_iterations{phase:measured}` — the selector previously used | **0** |
| `dropped_iterations{phase:warmup}` | **0** |

The partitions reconcile with the aggregate; the custom-tag selectors match nothing. Every other
filtered figure in the harness was correct, which is why this went unnoticed: ordinary samples do carry
the custom tag.

The summariser now reconciles the partitions against the aggregate and refuses a run whose drops are
unattributed, because a declared sub-metric matching zero events is indistinguishable from a genuine
zero unless something checks.

## What the iteration counts do and do not say

Four quantities, kept apart:

| Quantity | Kind | At 25/s rep 1 |
| --- | --- | --- |
| Offered | configured — rate × window | 1500 |
| Dropped | measured — no free virtual user when due | 0 |
| Completed in window | measured — finished and carried the scenario tag | 1501 |
| Unaccounted | derived — offered − dropped − completed | −1 |

The earlier report described the unaccounted remainder as "iterations still running when graceful stop
ended". Nothing measured here establishes that. An arrival-rate executor schedules on a tick and the
boundary arithmetic need not land on an exact multiple, which is why the remainder here is *negative* by
one. No interrupted-iteration count is collected, so any explanation of the remainder would be an
assumption, and it is reported as derived and unexplained.

## Asynchronous delivery

Backlog is sampled from before the load starts until it has drained. The sampler queries the database
and then sleeps for a configured delay, so that delay is a floor on the spacing rather than the spacing
itself.

| | |
| --- | --- |
| Configured sampler delay | 2 s |
| **Observed gaps across these runs** | min 2 s, median 2–4 s, max 3–5 s |
| Observations per run | 29–35 |
| Event markers (not measurements) | 2, or 4 for the outage run |
| Failed observations | 0 |

At 25/s the backlog reaches 258–743 undelivered events and clears in 3–10 s. At 30/s it reaches 294–448
in the two clean runs and 2231 in the degraded one.

Delivery is therefore already behind commitment at the sustained rate and catches up quickly. That this
is what *causes* API latency to degrade at 30/s is a hypothesis, not a measurement: no per-component CPU
accounting was collected.

## A broker outage under load

One-off fault demonstration at the sustained rate, with the broker stopped for 25 s from 25 s into the
run, inside the measured window.

| | |
| --- | --- |
| HTTP failures | **0 of 3000** |
| Measured dropped iterations | **0** |
| Authorize p50 / p95 / p99 | 130.1 / 216.8 / 355.7 ms — better than the runs without an outage |
| Observed max undelivered backlog | **1692 events**, across 35 observations and 4 markers |
| Drain from load end | 7 s |
| **Drain from broker reachable** | **22 s** |
| Correctness checks | all passed |

*Recovery* means the broker answered a topic listing again, not that the container start command
returned. The broker came back before the load ended, which is why the recovery clock is the longer one.

## Contention on one account

Same mix and rate against a single account: p50 **149.1 ms**, p95 854.6 ms, p99 1320.4 ms, zero dropped,
against 119.5–275.0 ms p50 spread across 24 accounts. Row locking serialises everything aimed at one
account. At 25/s the effect is visible in the tail rather than the median.

## Identical commands under one key

576 original authorizations, each followed by **three concurrent replays of the same bytes under the
same key** — 1707 replays.

| | |
| --- | --- |
| Replays returning a different payment id or status | **0** |
| Payments created per idempotency key | exactly 1 |
| HTTP failures | 0 |
| Measured dropped iterations | 23 |

The 23 drops are reported rather than hidden: this scenario offers four requests per iteration, so 20
iterations/s is 80 requests/s, above the sustained rate established above. It is a correctness check
under concurrency, not a latency measurement, and its correctness result does not depend on every
iteration starting.

## Does telemetry cost anything here

Matched load at the sustained rate, three repetitions each.

| Tracing | p50 | p95 | Measured drops |
| --- | --- | --- | --- |
| Sampled 100% | 119.5 / 266.4 / 275.0 ms | 792.1 / 1025.7 / 795.7 ms | 0 / 0 / 0 |
| Disabled (probability 0) | 862.2 / 148.9 / 166.5 ms | 1632.5 / 391.3 / 302.2 ms | **9** / 0 / 0 |

**This comparison is inconclusive and, on this evidence, cannot be made.** One of the three
tracing-disabled runs did not meet the sustained criteria at all: it dropped 9 iterations, reached a
backlog of 2402, and ran slower than every sampled run. Excluding it would leave two runs against three
and would be choosing the arm that flatters the conclusion.

Taken at face value the two clean unsampled runs are faster than the sampled ones, which is the opposite
of the previous report's "no measurable difference" and is not supported either: run-to-run variance at
this rate spans the entire gap, as the degraded run demonstrates.

## Correctness during and after load

Every run, including the ones that failed the rate criteria, passed the post-run checks scoped to its
own accounts: available funds never exceeded; balances and holds reconciling; exactly one balanced
journal per captured payment; no journal for a void; one payment per idempotency key; durable event
intent for every committed payment; dense per-payment event sequences from 1; the projection caught up
in order with one effect per event; no duplicate consumer effect; nothing quarantined; nothing left
undelivered.

Those checks cover the **whole run**, warmup included, because a warmup authorization moves the same
synthetic money as a measured one. Only the latency figures are scenario-filtered.

## Limitations

- **Everything shares one machine**, ten CPUs between generator, application, database and broker.
- **The database is not durable.** tmpfs with `fsync=off` flatters write latency.
- **One JVM, one broker node, no replication, no network between tiers.**
- **Percentiles are per run**, never averaged; where runs disagree, every value is shown.
- **Run-to-run variance is large and uncontrolled**, and at 25–30/s it spans the size of any effect this
  harness is being used to look for. The tracing comparison is the casualty.
- **The generator has its own limits, and both were hit.** Too few preallocated virtual users and it
  drops work during a latency spike while the pool grows; too many and connection establishment through
  the Docker network fails with `dial: i/o timeout`. Two runs were lost to the latter and re-run. Users
  are sized at three times the offered rate, which covers a three-second iteration by Little's law.
- **No causal claim is made about what degrades API latency at 30/s.**
- **No throughput claim is made for any hardware other than the one named above.**
