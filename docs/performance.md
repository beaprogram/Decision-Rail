# Measured performance

Real numbers from the harness in [benchmarking.md](benchmarking.md), on the hardware named below.
Nothing here is estimated, extrapolated, or carried over from a previous run.

> **Superseded figures.** Every number below was re-measured with the corrected harness. The earlier
> results, published before that correction, are kept in `benchmark/results/` with their original
> timestamps and are **not** to be quoted: their percentiles and counts were computed over a population
> that included warmup traffic, and their backlog figure was sampled after the load had already
> stopped. They are retained rather than rewritten, because deleting or restating measurements to look
> as though they came from a later harness is the thing evidence is for preventing.

## What was measured on

| | |
| --- | --- |
| Measured revision | `5f3f178ceaea486cbea82ba3cdb52d57859429e3` plus the checkpoint 8 working tree; the committed revision is named in PROGRESS.md |
| Machine | macOS 26.6.2, arm64, 10 CPUs |
| Runtime | OpenJDK 21.0.11, packaged Spring Boot jar |
| Database | PostgreSQL 16 in Docker, **tmpfs storage, `fsync=off`**, `max_connections=200`, `shared_buffers=256MB` |
| Broker | Apache Kafka 3.9.1, single node, replication factor 1 |
| Topology | Load generator, application, database and broker on **one machine**, competing for the same CPUs |
| Authentication | HTTP Basic, enabled, password verification included in every measured request |
| Dataset | 24 accounts, 1,000,000.00 CAD each, fresh database per run |
| Workload | Open model (constant arrival rate), seed 20260913, 15s warmup excluded, 60s measured |
| Tracing | 100% sampling unless stated; OTLP export off; Prometheus not scraped during runs |

One *iteration* is a full business operation: an authorization, then a capture or a void. Each is two
HTTP requests, so 30 iterations/s is 60 requests/s.

## Sustained rate

**30 iterations/s (60 HTTP requests/s), three repetitions, no dropped work.**

| Rep | Authorize p50 | p95 | p99 | Samples | Dropped | Drain after load |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 100.1 ms | 132.5 ms | 174.2 ms | 2027 | 0 | 1 s |
| 2 | 124.1 ms | 188.9 ms | 233.7 ms | 2027 | 0 | 0 s |
| 3 | 122.6 ms | 194.6 ms | 249.9 ms | 2025 | 0 | 3 s |

Capture p95 ranged 138.3–218.1 ms across the same runs. Every run completed 4050–4054 HTTP requests,
wrote one durable event per command, and drained within three seconds of the load stopping — delivery
kept pace with the API.

Business outcomes were as designed and stated in advance: about two thirds captured, one third voided,
**zero declines**. A run that drifts into insufficient-funds declines has stopped measuring payment
work, so the seeded balances are deliberately far larger than any run can consume.

## Where it stops

| Offered | Achieved requests/s | Dropped iterations | Authorize p50 | p95 | Drain after load |
| --- | --- | --- | --- | --- | --- |
| 30/s | 53.9 | **0** | 100–124 ms | 133–195 ms | 0–3 s |
| 40/s | 59.2–70.6 | 28 / 252 / 448 | 386 / 1808 / 2054 ms | 748 / 2363 / 3106 ms | 105–113 s |
| 50/s | 70.7 | 664 | 2344 ms | 2837 ms | 116 s |
| 60/s | 82.2 | 877 | 2518 ms | 2929 ms | 122 s |
| 80/s | 74.4 | 2491 | 4005 ms | 4425 ms | 125 s |
| 120/s | — | 5006 | — | — | run failed: 3 request timeouts |

**Highest tested rate meeting the criteria: 30 iterations/s (60 requests/s)** — no dropped iterations,
no unexpected errors, sub-250 ms p99, and a backlog that drained in seconds.

**First observed failing level: 40 iterations/s.** Work was dropped in all three repetitions, latency
degraded by an order of magnitude, and the three repetitions disagreed wildly (p50 386 ms, 1808 ms,
2054 ms) — the signature of an operating point past saturation, where the result depends on what the
machine happened to be doing. A single run at 40/s would have looked acceptable; three did not.

At 120/s the run failed outright: 5006 iterations never started and three requests timed out.

## The bottleneck is delivery, not the API

The drain column is the finding. At 30/s the backlog is empty within three seconds of the load
stopping. At 40/s and above it takes **105–125 seconds after a 60-second run** — the backlog grew
throughout and the dispatcher spent twice the run's duration catching up.

So the constraint is not authorization latency. Between 60 and 80 events per second, publication stops
keeping pace with commitment; the backlog then competes with the API for the same CPUs on the same
machine, which is why request latency degrades at the same time. Two consequences worth stating: the
API degrades *after* delivery does, not before it, and the payment path stays correct throughout — no
run at any rate lost an event, double-spent an account, or produced a second journal.

No improvement was attempted. The measurement identifies where to look; making a change now and
claiming credit for it would need equivalent before-and-after runs, and the honest report is that this
is where the ceiling is on this hardware.

## A broker outage under load

One-off fault demonstration, labelled separately from the steady-state figures: 30 iterations/s with
the broker stopped for 25 seconds mid-run.

| | |
| --- | --- |
| HTTP failures | **0 of 4054** |
| Authorize p50 / p95 / p99 | 114.0 / 192.9 / 227.0 ms — indistinguishable from the runs without an outage |
| Peak undelivered backlog | 800 events |
| Drain after load stopped | 7 s |
| Correctness checks | all passed |

This is the architecture's central claim, measured: a broker outage degrades one capability and takes
nothing else with it. Payments kept committing at full rate with their event intent retained, and the
backlog cleared in seven seconds once the broker returned.

## Contention on one account

Same mix, same rate, every payment against a single account: authorize p50 **1908 ms**, p95 2683 ms,
303 iterations dropped, against 100–124 ms p50 when spread across 24 accounts.

That is row locking doing its job. Authorization takes `FOR UPDATE` on the account row so two callers
cannot both pass a stale balance check, which serialises everything aimed at one account. It is the
reason a hot-account workload is reported separately: quoting the spread figure as the system's
capacity would describe a workload shape nobody guaranteed.

## Identical commands under one key

584 original authorizations, each followed immediately by **three concurrent replays of the same bytes
under the same key** — 1752 replays in total.

| | |
| --- | --- |
| Replays returning a different payment id or status | **0** |
| Payments created per idempotency key | exactly 1 |
| Capture journals created by replays | 0 |
| HTTP failures | 0 |

## Does telemetry cost anything here

Matched load, the stable operating point, three repetitions each.

| Tracing | Authorize p95 across repetitions |
| --- | --- |
| Sampled 100% | 132.5 / 188.9 / 194.6 ms |
| Disabled | 114.6 / 193.9 / 208.4 ms |

The ranges overlap almost completely. **At 30 iterations/s on this hardware, three repetitions cannot
distinguish tracing enabled from tracing disabled.** That is the measurement, not a claim that the cost
is zero — a finer effect would need more repetitions, a controlled environment, or higher load, and
none of those were run.

The comparison was deliberately taken at the stable point. An earlier attempt at 40/s was discarded as
uninterpretable: the tracing-disabled run landed in the middle of the tracing-enabled spread, because
run-to-run variance past saturation is far larger than any effect being looked for.

Spans were created at full sampling in the "enabled" runs but not exported, so this measures span
creation and context propagation, not network export.

## Correctness during and after load

Every run, including the failing ones, passed the post-run checks scoped to its own accounts:
available funds never exceeded; balances and holds reconciling to the operations performed; exactly one
balanced journal per captured payment; no journal for a void; one payment per idempotency key; durable
event intent for every committed payment; dense per-payment event sequences from 1; the projection
caught up in order with one effect per event; no duplicate consumer effect; nothing quarantined;
nothing left undelivered.

Across all runs: 22,000+ authorizations, one journal per capture, zero declines, zero quarantines.

## Limitations

- **Everything shares one machine.** The load generator competes with the application, the database and
  the broker for ten CPUs. A real deployment would separate them, and the numbers would change.
- **The database is not durable.** tmpfs with `fsync=off` flatters write latency. This is a laptop
  measurement of application behaviour, not of storage.
- **One JVM, one broker node, no replication, no network between tiers.**
- **Percentiles are per run.** They are never averaged across runs; where runs disagree, all the values
  are shown.
- **The 40/s repetitions degraded monotonically** (386 → 1808 → 2054 ms p50). Whether that is the
  machine warming up, the backlog from earlier runs' containers, or genuine instability past saturation
  was not isolated. It is reported because it is what happened.
- **No throughput claim is made for any hardware other than the one named above**, and nothing here
  should be read as a production capacity figure.
