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
   that every payment's projection matches its authoritative status. Backlog is sampled on a fixed
   interval (default 2s) from before the load starts until it has drained, with timestamps, into a
   `.backlog.tsv` beside the result. The reported figure is an **observed maximum at that resolution**,
   not a true peak.
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

`./benchmark/collector-check.sh` runs the summariser against a crafted summary whose warmup and measured
populations are impossible to confuse, and fails if any reported figure comes from the aggregate. It is
part of the required checks.

## Database reconciliation covers the whole run

`benchmark/verify.sql` is deliberately **not** phase-filtered. It reconciles every account, payment,
journal and event the run created, warmup included, because a warmup authorization moves real synthetic
money and a correctness check that ignored it would be checking the wrong thing. Latency figures
describe the measured phase; financial correctness covers everything the run did.

## Correctness after load

`benchmark/verify.sql` asserts, scoped to the accounts the run created: available funds never exceeded;
balances and holds reconcile to the operations performed; one balanced journal per captured payment;
voids release without a journal; one payment per idempotency key; every committed payment has durable
event intent; per-payment event sequences are dense from 1; the projection caught up in order with one
effect per event; no duplicate consumer effect; nothing quarantined; nothing left undelivered.

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
