# Benchmarking

How to reproduce the measurements in [performance.md](performance.md), and what the harness refuses to
do.

## What it runs against

Its own stack. `benchmark/compose.bench.yaml` starts PostgreSQL on 55435 and Kafka on 19094, which
collide with neither the development stack (55434/19093) nor the disposable test stack (55433/19092).
The application runs as the packaged jar on port 8081.

This separation is not tidiness. Benchmarking against the development database would spend the seeded
demo balances, which do not refill; stopping the development broker would break the operator demo; and
running beside the integration suite would race its fault injection. Each of those corrupts a
measurement in a way that is hard to notice afterwards.

**Never run the benchmark and the fault-injection tests against the same infrastructure at the same
time.** The suite installs triggers that make storage fail on purpose.

## Running it

```bash
./mvnw package                         # the jar under test must be the packaged one
./benchmark/run.sh spread 30 60s 1     # scenario, offered rate per second, duration, repetition
```

| Scenario | What it measures |
| --- | --- |
| `spread` | Authorize, then capture or void, spread across 24 dedicated accounts |
| `hot` | The same mix aimed at one account, so row locking is the constraint |
| `retries` | Identical commands resent under their original key, three at a time, concurrently. **No warmup**: it is a correctness check under concurrency, not a latency measurement, so the whole run is the population and is tagged `measured`. |

Environment knobs: `TRACING_SAMPLE_RATE` (default 1.0), `OTLP_EXPORT_ENABLED` (default false),
`ACCOUNT_COUNT` (24), `ACCOUNT_BALANCE_MINOR` (100000000), `WARMUP` (15s), `SEED`, `BENCH_PORT` (8081),
`KEEP_STACK=true` to leave the stack up for inspection.

A one-off fault demonstration:

```bash
BROKER_OUTAGE=true OUTAGE_SECONDS=25 ./benchmark/run.sh spread 30 60s 1
```

The outage is injected after `OUTAGE_DELAY` (default 25s), which is past the warmup, so it falls inside
the measured phase. Two recovery clocks are reported separately because they answer different questions:

- **`drainSecondsFromLoadEnd`** — how long the tail took once nothing new was arriving.
- **`drainSecondsFromBrokerReachable`** — how long recovery itself took. **Recovery** means the broker
  answered a topic listing again, not that the container start command returned.

## What each run does

1. Starts isolated PostgreSQL and Kafka and waits for both to be healthy.
2. Starts the packaged jar against them, and waits for `/actuator/health`.
3. Seeds 24 accounts with 1,000,000.00 CAD each — large enough that a run cannot exhaust them and
   quietly turn into insufficient-funds declines, which would stop it measuring payment work.
4. Runs a **warmup** scenario at half rate, whose samples are excluded, then the **measured** scenario.
5. Waits for delivery to drain, where **drained** means both that no unpublished outbox row remains *and*
   that every payment's projection matches its authoritative status. Backlog is sampled from before the
   load starts until it has drained, with timestamps, into a `.backlog.tsv` beside the result.

   The sampler queries the database and then sleeps for a configured delay, so that delay is a floor on
   the spacing, not the spacing itself: each cycle costs the delay plus however long the queries took.
   The result therefore reports the **configured delay** and the **observed gaps** (min, median, max)
   separately, and the backlog figure is an **observed maximum across those observations**, not a peak.
   Event markers — `broker_stop`, `broker_reachable`, `load_end`, `drained` — are counted apart from
   observations, and a query that returned nothing is reported as a failed observation rather than as a
   backlog of zero.
6. Runs `benchmark/verify.sql`, scoped to that run's own accounts.
7. Writes a sanitised result to `benchmark/results/`.
8. Destroys the stack, including its volumes.

## The load model

Open, not closed. k6's `constant-arrival-rate` keeps offering the configured rate whether or not the
application keeps up, and reports `dropped_iterations` when it cannot start work on time. A closed-loop
client would slow itself down instead, and the run would then be described as sustaining a rate it
never offered.

**`droppedIterations` above zero means the offered rate was not achieved.** The latency figures from
such a run describe less load than the headline number suggests, and the report says so.

Percentiles are per run. They are never averaged across runs, because an average of percentiles is not
a percentile of anything.

## What counts as a sustained rate

Fixed before the measurements were taken, so the rate is found rather than chosen. A rate is sustained
only if **every repetition** satisfies all of:

1. **Zero dropped iterations in the measured scenario.** The executor always had a virtual user free to
   start the work it offered.
2. **Zero failed HTTP requests and zero unexpected errors.**
3. **Zero business declines.** A run that drifts into insufficient-funds declines has stopped measuring
   payment work.
4. **Achieved request rate within 2% of offered**, computed over the declared window.
5. **The backlog drains within the run's own duration** after the load stops.
6. **At least three repetitions**, all of them passing.

Warmup drops are reported separately and do not disqualify a rate: the warmup exists to absorb cold
starts, and dropping work while the pools fill says nothing about the steady state. They are published
because hiding them would make the measured zero look more impressive than it is.

## The measured population

Warmup and measurement are separate k6 scenarios, and every reported figure comes from the measured one.

k6 tags each sample with its scenario's tags, including samples of custom metrics, and writes a
sub-metric into the summary export for every tag combination a threshold names. The scripts therefore
declare always-true thresholds on `{phase:measured}` for each metric that is reported, and the
summariser reads only those. A missing sub-metric is a hard failure: falling back to the aggregate is
the defect this was corrected for, and it is invisible in a passing run — the result file looks fine,
with percentiles computed over a population that is part warmup, beside a field saying warmup was
excluded. A 20-second run at 10/s behind a warmup produced 200 measured samples and a 276-sample
aggregate whose maximum came entirely from warmup.

**Rates are computed here, not taken from k6.** A sub-metric's `rate` field divides by the whole run's
duration, warmup included. A probe of 6 measured hits in a 2.07-second run with a 2-second warmup was
reported by k6 as 2.90/s. Every rate in a result file is `count ÷ the declared measured window`, and
that window is recorded in the file so the arithmetic can be checked.

**Late completions.** An iteration that starts inside the window but finishes after it stays in the
population and its samples are included; k6 lets in-flight iterations finish during graceful stop rather
than discarding them. The window is therefore when work was *offered*, not when the last response
arrived, and the result file says so.

### Dropped iterations are attributed by the built-in scenario tag

k6 attaches a scenario's **custom** tags to ordinary samples but **not** to the iterations its executor
drops: those carry global run tags and the built-in `scenario` tag only. Verified against the pinned
0.55.0 image — a saturating probe produced 284 dropped iterations, of which
`dropped_iterations{scenario:measured}` held 162 and `{scenario:warmup}` 122, while
`dropped_iterations{phase:measured}` and `{phase:warmup}` held **zero**.

Every selector in this harness therefore uses the built-in tag. The failure it replaces was silent: the
sub-metric existed, matched nothing, and reported a confident zero beside an aggregate holding hundreds.

The summariser **reconciles** the partitions against the aggregate and refuses a run where drops are
unaccounted for. A declared sub-metric's existence is not evidence its selector matched anything, and
that is the only thing separating "no work was dropped" from "no work was counted".

### The checks

| Check | What it establishes |
| --- | --- |
| `./benchmark/collector-check.sh` | The summariser reports the measured population, computes rates over the declared window, and reads a backlog timeline correctly: markers are not measurements, a failed query is not a zero, and observed spacing is reported separately from the configured delay. Fails if any figure comes from an aggregate. |
| `./benchmark/k6-attribution-check.sh` | Runs the pinned k6 image against a workload that really drops iterations, with no application, database or broker. Establishes that drops occur, that the built-in tag separates the populations, that they reconcile with the aggregate, that the summariser reports the measured count, that a zero-drop workload reports a true zero, and that the old selector is refused. |

Both run in CI.

## Database reconciliation covers the whole run

`benchmark/verify.sql` is deliberately **not** phase-filtered. It reconciles every account, payment,
journal and event the run created, warmup included, because a warmup authorization moves real synthetic
money and a correctness check that ignored it would be checking the wrong thing. Latency figures
describe the measured phase; financial correctness covers everything the run did.

## Correctness after load

`benchmark/verify.sql` asserts, scoped to the accounts the run created: available funds never exceeded;
balances and holds reconcile to the operations performed, **including anything returned**; each
payment's returned total agrees with its return operations *and* with the journals those wrote, and
never exceeds what was captured; exactly one balanced **capture** journal per captured payment and
exactly one journal per return operation; voids release without a journal of either kind and no
uncaptured payment carries a return; one payment per idempotency key; every committed payment has
durable event intent; the lifecycle event count matches two plus one per return; per-payment event
sequences are dense from 1; the projection caught up in order with one effect per event; no duplicate
consumer effect; nothing quarantined; nothing left undelivered.

The return terms were added in checkpoint 9 and are zero in every workload measured so far, because
none of these scenarios issues a refund. They are written explicitly rather than omitted for exactly
that reason: left out, the balance check would silently pass for an account credited back money the run
never accounted for, and it would start failing the moment a future workload did issue one.

Any row it returns is a failure and fails the run. The historical integration suite is necessary but
does not substitute for this: it proves these properties about constructed scenarios, not about the
hundred thousand rows a benchmark just wrote.

## Results

`benchmark/results/<scenario>-<rate>-rep<n>-<timestamp>.json`, committed. They contain no credentials,
no account identifiers, no payment identifiers and no trace identifiers. The k6 summary beside each one
is the raw generator output.

## Cleanup

`run.sh` tears its stack down on every exit path, including failure. If a run was interrupted:

```bash
docker compose -f benchmark/compose.bench.yaml down --volumes --remove-orphans
rm -rf ~/.decisionrail-bench-stage        # staging directory for the containerised generator
```

The staging directory exists because this repository lives on an external volume that Docker Desktop
does not share by default; scripts and results are copied through `$HOME`, which it does. No credential
is ever written there.

## In CI

CI runs a short smoke execution — a few seconds at a low rate — which checks that the harness executes
and that the correctness checks pass. **Its timings are not a benchmark.** A shared runner with unknown
neighbours cannot produce a latency figure anyone should quote, and none is published from it.
