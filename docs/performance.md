# Measured performance

Real numbers from the harness in [benchmarking.md](benchmarking.md), on the hardware named below.
Nothing here is estimated, extrapolated, or carried over from a previous run.

> **Earlier figures are superseded and must not be quoted.** Everything below was re-measured after the
> harness was corrected. The results published before that correction computed their percentiles and
> counts over a population that included warmup traffic, and sampled backlog only after the load had
> already stopped. Those files are kept in `benchmark/results/` with their original timestamps rather
> than rewritten, because restating old measurements to look as though they came from a later harness
> is exactly what evidence exists to prevent.

## What was measured on

| | |
| --- | --- |
| Measured revision | `df18d83`, sources clean |
| Artifact | `decisionrail-0.1.0.jar`, sha256 `86d2414c69c5126c…`, identical across all twelve runs |
| Machine | macOS 26.6.2, arm64, 10 CPUs |
| Runtime | OpenJDK 21.0.11, packaged Spring Boot jar |
| Database | PostgreSQL 16 in Docker, **tmpfs storage, `fsync=off`**, `max_connections=200`, `shared_buffers=256MB` |
| Broker | Apache Kafka 3.9.1, single node, replication factor 1 |
| Topology | Load generator, application, database and broker on **one machine**, competing for the same CPUs |
| Authentication | HTTP Basic, enabled, password verification included in every measured request |
| Dataset | 24 accounts, 1,000,000.00 CAD each, fresh database per run |
| Workload | Open model, seed 20260913, 15s warmup **excluded by phase tag**, 60s measured |
| Tracing | 100% sampling unless stated; OTLP export off; Prometheus not scraped during runs |

One *iteration* is a full business operation: an authorization, then a capture or a void. Each is two
HTTP requests, so 30 iterations/s is 60 requests/s.

Every figure comes from the `{phase:measured}` population. Rates are `count ÷ 60s`, the declared window,
not k6's own rate field, which divides by the whole run including warmup.

The recorded `workingTreeDirtyAtStart` reads true from the second run of the batch onward. That is the
batch's own result files being untracked, not a source change: the jar sha256 is identical across all
twelve runs, and the check has since been narrowed to ignore `benchmark/results/`.

## Sustained rate

**30 iterations/s (60 HTTP requests/s), three repetitions, no dropped work, no failed requests.**

| Rep | Samples (of 1800 offered) | p50 | p95 | p99 | Achieved req/s | Observed max backlog | Drain after load |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | 1792 | 297.4 ms | 583.2 ms | 725.7 ms | 59.73 | 879 | 15 s |
| 2 | 1798 | 264.7 ms | 454.4 ms | 549.5 ms | 59.93 | 547 | 7 s |
| 3 | 1797 | 247.6 ms | 446.9 ms | 559.9 ms | 59.90 | 513 | 7 s |

Business outcomes were as designed: roughly two thirds captured, one third voided, **zero declines** in
every run. The sample count sits a few below the 1800 offered because iterations still in flight when
graceful stop ended contribute no sample; `droppedIterations` was zero throughout, so no work was
refused a start.

## Where it stops

| Offered | Iterations completed (of offered) | Achieved req/s (of 80) | p50 | p95 | p99 | Dropped | Drain after load |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 30/s | 1792–1798 of 1800 | 59.7–59.9 of 60 | 247.6–297.4 ms | 446.9–583.2 ms | 549.5–725.7 ms | 0 | 7–15 s |
| 40/s | 2031–2097 of 2400 | 67.7–69.9 of 80 | 2037.4–2133.0 ms | 2464.0–2697.9 ms | 2641.9–3254.8 ms | 0 | 24–28 s |

**Highest tested rate meeting the criteria: 30 iterations/s (60 requests/s)** — the offered rate was
achieved, latency stayed under 750 ms at p99, and the backlog cleared within 15 s of the load stopping.

**First observed failing level: 40 iterations/s.** Latency rose roughly eightfold and the offered rate
was not achieved: only 2031–2097 of 2400 iterations completed, and 67.7–69.9 of the offered 80
requests/s were served.

A nuance worth stating, because it contradicts a natural reading of the harness: `droppedIterations`
stayed **zero** at 40/s. k6 drops an iteration when no virtual user is free to start it, and there
always was one; the shortfall appears instead as iterations that started and had not finished when the
run ended. Both counts are published for that reason — reading only the dropped counter at 40/s would
suggest the rate was sustained when it was not.

## Asynchronous delivery falls behind before the API does

Backlog is sampled every 2 seconds from before the load starts until it has drained, and the figure
below is the **observed maximum at that resolution**, not a true peak. The per-sample timeline is in the
`.backlog.tsv` beside each result.

At 30/s the backlog rises to 513–879 undelivered events during the run and clears in 7–15 s. At 40/s it
reaches 3807–3924 and takes 24–28 s. So publication is already behind commitment at the sustained rate;
it simply catches up quickly.

What the evidence supports: **delivery falls behind first, and further behind as load rises.** What it
does not establish on its own is *why* API latency degrades at 40/s. That the backlog competes with the
API for the same CPUs is a plausible explanation and no more — no per-process CPU accounting was
collected, and the earlier report's causal claim went beyond its evidence. Testing it would need CPU
attribution per component, or a run with the dispatcher disabled, neither of which was done.

## A broker outage under load

One-off fault demonstration, reported separately from the steady-state figures: 30 iterations/s with
the broker stopped for 25 s from 25 s into the run, which is inside the measured window.

| | |
| --- | --- |
| HTTP failures | **0 of 3590** |
| Authorize p50 / p95 / p99 | 164.6 / 434.6 / 570.8 ms — no worse than the runs without an outage |
| Observed max undelivered backlog | **2923 events**, across 42 samples at 2 s resolution |
| Drain from load end | 18 s |
| **Drain from broker reachable** | **25 s** |
| Correctness checks | all passed |

The two drain figures answer different questions and are not interchangeable. *Recovery* means the
broker answered a topic listing again, not that the container start command returned; the broker came
back before the load ended, which is why the recovery clock is the longer of the two.

Payments kept committing at full rate throughout with their event intent retained, and the entire
backlog cleared once the broker returned.

## Contention on one account

Same mix and rate, every payment against a single account: p50 **484.6 ms**, p95 1020.6 ms, p99
1492.2 ms, against 247.6–297.4 ms p50 spread across 24 accounts. No dropped work, no failures.

That is row locking doing its job — authorization takes `FOR UPDATE` on the account row, which
serialises everything aimed at one account. It is reported separately because quoting the spread figure
as the system's capacity would describe a workload shape nobody guaranteed.

## Identical commands under one key

534 original authorizations, each followed immediately by **three concurrent replays of the same bytes
under the same key** — 1602 replays.

| | |
| --- | --- |
| Replays returning a different payment id or status | **0** |
| Payments created per idempotency key | exactly 1 |
| HTTP failures | 0 |

This scenario runs no warmup and measures no latency; it is a correctness check under concurrency, and
its whole run is the reported population.

## Does telemetry cost anything here

Matched load at the sustained rate, three repetitions each, taken in the same batch minutes apart.

| Tracing | Authorize p50 across repetitions | p95 across repetitions |
| --- | --- | --- |
| Sampled 100% | 297.4 / 264.7 / 247.6 ms | 583.2 / 454.4 / 446.9 ms |
| Disabled (probability 0) | 286.7 / 267.4 / 262.3 ms | 492.4 / 478.2 / 446.0 ms |

The ranges overlap almost entirely: two of the three sampled runs sit inside the unsampled range on both
percentiles. **Three repetitions cannot distinguish tracing enabled from tracing disabled at this load
on this hardware.** That is the measurement, not a claim that the cost is zero.

"Disabled" here means sampling probability 0, so spans are created non-recording. That is a different
condition from export being off — the default, where spans are recorded in-process and dropped — and
different again from an enabled exporter whose collector is unreachable. All three are described in
[observability.md](observability.md), and the third is covered by `UnreachableCollectorTest` rather than
by a benchmark.

## Correctness during and after load

Every run, including the ones at the failing level, passed the post-run checks scoped to its own
accounts: available funds never exceeded; balances and holds reconciling to the operations performed;
exactly one balanced journal per captured payment; no journal for a void; one payment per idempotency
key; durable event intent for every committed payment; dense per-payment event sequences from 1; the
projection caught up in order with one effect per event; no duplicate consumer effect; nothing
quarantined; nothing left undelivered.

Those checks deliberately cover the **whole run**, warmup included, because a warmup authorization moves
the same synthetic money as a measured one. Only the latency figures are phase-filtered.

## Limitations

- **Everything shares one machine.** The load generator competes with the application, the database and
  the broker for ten CPUs.
- **The database is not durable.** tmpfs with `fsync=off` flatters write latency. This measures
  application behaviour, not storage.
- **One JVM, one broker node, no replication, no network between tiers.**
- **Percentiles are per run**, never averaged across runs; where runs disagree, every value is shown.
- **Host drift is real and uncontrolled.** These runs are markedly slower than the pre-correction ones
  at the same offered rate (p50 248–297 ms against 100–124 ms). The corrected harness is not the cause:
  the measured and aggregate percentiles within these runs differ by only a few percent. The machine was
  busier, and no attempt was made to control for it.
- **No causal claim is made about what degrades API latency at 40/s**, only that delivery falls behind
  first. See above.
- **No throughput claim is made for any hardware other than the one named above.**
