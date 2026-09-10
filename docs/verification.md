# Verification strategy

The central question is whether payment and ledger state remain consistent under retries, rejected requests, and overlapping mutations, and now also whether the asynchronous path loses, reorders, or duplicates the effects of committed events. A green happy-path HTTP response alone cannot establish either.

Infrastructure behaviour is verified against real PostgreSQL and a real Kafka broker. These checks are never skipped: if either dependency is unavailable the suite fails with instructions, because a skipped check reported as success is worse than no check.

## Run the suite

JDK 21, Docker, and the checked-in Maven wrapper (pinned to 3.9.12) are required. Infrastructure comes from a dedicated, disposable stack on deliberately non-default loopback ports, so it cannot collide with a local development database or broker:

```bash
docker compose -f compose.test.yaml up -d --wait
JDBC_URL=jdbc:postgresql://127.0.0.1:55433/decisionrail_test \
JDBC_USERNAME=decisionrail \
JDBC_PASSWORD=local-test-only \
KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:19092 \
  ./mvnw --batch-mode --no-transfer-progress verify
```

Those are also the test profile's defaults, so `./mvnw verify` alone works once the stack is up.

To reset the stack between runs:

```bash
docker compose -f compose.test.yaml down -v && docker compose -f compose.test.yaml up -d --wait
```

**Use a dedicated database and broker.** Integration tests deliberately install database triggers that inject storage failures, publish synthetic events, and create a throwaway database for the migration upgrade check. Never point the suite at production, at a development database, or at anything another process is writing. The test stack uses `tmpfs` for PostgreSQL so a `down -v` leaves nothing behind.

Tests share one database on purpose, and append-only history is retained so the suite can run repeatedly. Each test therefore scopes its assertions to the payments and topics it created rather than to global counts. Fault injection is targeted at a named payment for the same reason: a global switch would fail unrelated events and make one test's observations depend on what another left behind.

CI runs the same `compose.test.yaml` stack rather than workflow service containers, so the documented local command and the remote build exercise identical infrastructure. It then builds the Docker image, starts the container, and runs both demo scripts without publishing the image.

[The remote run](https://github.com/beaprogram/Decision-Rail/actions/runs/34538867915) passed on revision `f01fe41`: the same 149 tests against PostgreSQL 16 and a real broker, the image build, container startup, and both demos (12 and 27 checks). CI configuration in the repository is not itself evidence that a remote run has passed; inspect the workflow result for the revision you care about.

Test reports are written under `target/surefire-reports/`; the JaCoCo report is generated under `target/site/jacoco/`. CI uploads available reports when a verification job finishes, including on failure. Coverage is a diagnostic aid, not a substitute for meaningful assertions.

## Recorded local result

Recorded **2026-09-10 UTC** using Java **21.0.11**, PostgreSQL **16.15**, and Kafka **3.9.1**.

`./mvnw verify` passed **149 tests** with **0 failures, 0 errors, and 0 skipped**:

| Group | Tests | Infrastructure |
| --- | --- | --- |
| Domain and contract units | 86 | None: pure evaluation, breaker state machine, backoff, policy validation, publisher acknowledgement semantics |
| Architecture rules | 4 | None |
| PostgreSQL integration | 35 | Real PostgreSQL, including the migration upgrade check |
| PostgreSQL and Kafka integration | 24 | Real PostgreSQL and a real single-node broker |

The previous milestone's 84 tests are all still present and passing; the 65 added tests did not replace or weaken any of them.

Both demo scripts passed against the packaged application in the local Compose stack: `scripts/demo.sh` (**12 HTTP checks**) and `scripts/async-demo.sh` (**27 checks**). The asynchronous demo observed a payment authorized with the broker container stopped, the breaker OPEN with `/actuator/health/async` DEGRADED while readiness stayed UP, delivery resuming after restart with the original event id and the breaker closing again, a projection applied count that stayed at 1 after the same event was delivered twice more, a replay job whose membership stayed at 4 inputs when a later payment committed, a 409 when a policy version id was rebound to different content, and a shadow divergence (live APPROVE, candidate DECLINE at score 60) after which the balance was unchanged and held funds moved only by the new authorization's own hold.

## Failure cases and rationale

| Case | Evidence to inspect | Failure prevented |
| --- | --- | --- |
| Repeated authorization/capture/void | Original result is replayed and only one state mutation exists. | Double reservation, capture, or release after a network retry. |
| Conflicting key reuse | `409` and unchanged financial state. | A retry key accidentally identifying a different command. |
| Concurrent authorization | Sum of successful holds stays within available funds. | Multiple callers passing a stale balance check. |
| Concurrent capture and void | One valid terminal outcome and matching account/ledger state. | Capturing funds while simultaneously releasing them. |
| Unbalanced journal insertion | Database transaction fails at commit. | A partially written or inconsistent accounting record. |
| Ledger mutation | Database rejects update/delete attempts. | Rewriting accounting history to hide corrections. |
| Merchant access boundary | A different merchant cannot see or mutate the target state. | Cross-tenant disclosure or unauthorized payment action. |
| Rule thresholds and combined signals | Known inputs produce reproducible score, reasons, flags, and outcome. | Boundary errors and explanations that disagree with a score. |
| Short-circuit and terminal rules | Terminal rule behavior is preserved and later contributions do not leak in. | Incorrect evaluation after a terminal decline. |
| Failed commands and rollback | Payment, balances, ledger, and event intent agree after rejection. | Half-completed writes masquerading as success. |
| Payment during a broker outage | Command succeeds; event intent is retained and undelivered. | Coupling payment availability to broker availability. |
| Delivery after broker recovery | Backlog drains with the original event ids; breaker closes via its probe. | Losing committed events, or declaring recovery from one lucky connection. |
| Acknowledged send, then worker crash | Row stays claimed, is reclaimed after lease expiry, resent; one projection effect. | Marking an event delivered that was never recorded, or double-applying the resend. |
| Consumer commit, then lost offset commit | Group re-reads from an earlier offset; one projection effect. | A redelivery producing a second effect. |
| Concurrent dispatchers | No event lost; per-payment sequence order never goes backwards. | Sequence 2 overtaking an unfinished sequence 1. |
| Expired worker versus new owner | Stale lease token completes nothing; current owner completes normally. | A slow worker overwriting the state of the worker that took over. |
| Terminal failure and redrive | One payment's stream blocks; others drain; redrive preserves identity and order. | Silently dropping a failed lifecycle event, or skipping ahead of it. |
| Duplicate, malformed, unsupported, conflicting, unknown-tenant records | Explicit quarantine reasons; the projection is unchanged. | A bad record either crashing the consumer or being silently ignored. |
| Replay determinism | The same pinned inputs and policy produce identical results. | A comparison that depends on when it was run. |
| Replay membership under later commits | Membership and totals stay fixed. | A late commit changing a job's population or denominator. |
| Interrupted replay job | Resumes with one result per input and no inflated totals. | Double-counted results from an incremented counter. |
| Insufficient-funds classification | The baseline is the stored APPROVE risk decision. | Counting a funding decline as a policy decline. |
| Policy version rebinding | Identical resubmission returns the existing version; changed content is 409. | Silently redefining a version that stored decisions name. |
| Score overflow | Raw total recorded, score capped at 100, cap flagged, reasons still reconcile. | An outcome that disagrees with its explanation. |
| Shadow divergence and isolation | Comparison recorded; balances, holds, journals, decisions, and events unchanged. | A comparison feature with a financial side effect. |
| Slow or throwing candidate | Authorization unaffected; failure recorded visibly. | A candidate policy becoming a dependency of taking payments. |
| Send deadline | A buffered send that never acknowledges fails at the deadline. | Treating "scheduled" as "delivered". |
| Breaker transitions | Opens at its threshold, admits one probe, reopens on a failed probe, closes on success. | Declaring recovery early, or a stuck probe slot. |
| Bounded work | A cycle claims at most its batch size with a full backlog. | Unbounded claiming or queueing under load. |
| Worker restart | Work is recovered after lease expiry, and a live claim cannot be stolen. | Losing in-flight work, or two workers owning one row. |
| Storage failure | `503` with nothing reserved, recorded, or queued. | A successful financial response the database never stored. |
| Migration upgrade with existing records | Sequence backfill, delivery status, and payload identity are correct; money and seals intact. | An upgrade that strands committed history or weakens an existing guarantee. |
| Cross-tenant replay and shadow access | Another merchant's job, results, and comparisons read as absent. | Cross-tenant disclosure through new endpoints. |
| Privileged route matching | Merchants and the metrics account are refused admin routes; admin is refused merchant routes. | A privileged path falling through to the broad merchant rule. |

These are the design targets for the relevant checks. The source tests and their actual results are the authority on which cases are currently exercised; do not infer a passing result from this table.

## Why real PostgreSQL and a real broker

Mocks can test orchestration and pure rules, but they cannot validate PostgreSQL row-lock behavior, `SKIP LOCKED` claim semantics, deferred constraint triggers, transaction rollback, or a duplicate-key race. Nor can they validate that a producer reports an acknowledgement with a real offset, that a consumer group re-reads after an offset is not committed, or that records for one key arrive in the order they were sent. Those properties are the product here, not incidental.

Pure policy evaluation, the breaker state machine, backoff bounds, and acknowledgement handling are tested without infrastructure, because they are deterministic logic and deserve fast, exact tests.

## Determinism over sleeping

Timing-dependent checks use injected clocks, explicit failpoints, and bounded polling rather than sleeps:

- The breaker's transitions are verified by advancing a mutable clock to the boundary and one millisecond past it, so "not early" is asserted as well as "eventually".
- Lease expiry is driven by passing an explicit instant, not by waiting for a real lease to lapse.
- Failpoints reproduce the two windows a sleep cannot reach reliably: a broker acknowledgement followed by a worker crash before the outbox update, and a consumer commit followed by a lost offset commit. The second is exercised by stopping the listener, rewinding the consumer group's offsets with an admin client, and restarting it, so redelivery is real rather than simulated by calling a method twice.
- Where a real asynchronous pipeline must be waited on, the helper polls with a stated budget and fails with the condition it was waiting for.

The same rule applies to the word "restart". Tests named for recovery leave behind exactly the state a killed process leaves: a row still claimed, or a job still running, holding a lease that has since expired. Recovery then has to happen through durable state and lease expiry.

## Operator smoke check

With the backend running:

```bash
./scripts/demo.sh
```

This validates an externally observable sequence and checks the final synthetic balance. Run it against an idle demo account; other writers can legitimately change the balance while the script is checking it. The script is complementary to the integration suite and is not a concurrency or performance benchmark.

## Claims deliberately deferred

No throughput, tail latency, recovery-time, availability, or production-readiness claims are published. A future performance milestone must record hardware, configuration, workload, duration, concurrency, error rate, and latency distribution alongside its results.

Specific to this phase:

- **Not exactly-once.** The verified guarantee is at-least-once delivery with idempotent consumer effects. The tests demonstrate duplicate deliveries producing one effect; they do not demonstrate, and the design does not provide, exactly-once processing across PostgreSQL and Kafka.
- **Not highly available.** The broker is a single node with replication factor 1. Broker durability under replica failure is untested because the configuration cannot provide it.
- **No fraud accuracy metrics.** No labelled outcome data exists for synthetic traffic, so precision, recall, and false-positive rates are not computed anywhere.
- **Replay timings are observations, not benchmarks.** `timingMethod` in every report states exactly what was measured: in-process evaluation only, single JVM, no warmup control or repetition.
- **Breaker and backoff defaults are not tuned from measurement.** They are reasonable values for a development stack. The retry budget is documented so it can be reasoned about, not because it was derived from observed production behaviour.
