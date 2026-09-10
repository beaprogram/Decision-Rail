# ADR 0004: Protect the broker boundary and report degradation separately from readiness

- Status: accepted for checkpoint 6
- Date: 2026-09-10

## Context

Checkpoint 4 introduced the first dependencies the payment core never had: a broker, and background
workers that talk to it. Without explicit controls, a broker outage turns into dispatcher threads
blocked on socket timeouts, a backlog retried far faster than it can drain, and an instance that looks
unhealthy to an orchestrator even though every payment command is still correct.

## Decision

**One breaker, at the broker send boundary, with a stated purpose.** It stops the dispatcher spending
its whole send deadline on every queued event while the broker is unreachable. Send failures and
missing acknowledgements count toward it. Serialization and contract errors do not: those are defects
in our own data, and counting them would open a breaker over a healthy dependency. While OPEN the
dispatcher claims nothing at all, so an outage stops costing lease churn and attempt bookkeeping. While
HALF_OPEN exactly one probe is admitted; a failed probe returns to OPEN and restarts the wait, so
recovery is never declared from one lucky connection.

**Derive the producer timeouts instead of tuning them by hand.** Kafka requires
`delivery.timeout.ms >= linger.ms + request.timeout.ms`, and this application requires the client to
give up before its own send deadline so a failure is definite rather than abandoned mid-flight. Both
constraints are computed from the configured deadline. An earlier hand-set combination violated the
Kafka rule and made every producer fail at construction, which is why this is derived now.

**Publish the retry budget as the product of both layers**, not either one alone: the client's
delivery timeout per attempt, and the application's bounded attempts separated by exponential backoff
with full jitter. Jitter is not decoration here. A broker outage fails the entire backlog at almost the
same instant, and without jitter every event would retry in synchronised waves.

**Wait for the acknowledgement.** A producer returns as soon as a record is buffered. Treating that as
delivery would mark events published that the broker never received, so the publisher blocks on the
future up to the deadline and requires a partition and offset before recording success.

**Separate liveness, readiness, and degraded asynchronous capability.** Readiness includes the
database, because no payment command can succeed without it, and excludes the broker, because payment
commands succeed perfectly well while it is down. Backlog age, terminal failures, and breaker state
report through a separate health group as DEGRADED, mapped to HTTP 200. Folding that into readiness
would remove a correct payment API from rotation because of a broker outage, which is the exact failure
the outbox exists to avoid.

**Fault injection is typed, local-only, and has no HTTP surface.** The switches name a payment and a
behaviour; none of them accepts a command, a host, or a path. They are inert unless
`app.events.fault-injection-enabled` is true, which only the test profile and the documented local
demo set. The demo's broker outage is a real one: it stops the broker container.

**Keep metric labels bounded.** Every label is an enumeration: a delivery status, a task state, a job
status. No payment id, merchant id, or policy version appears in a metric. Per-payment detail belongs
in the operator APIs, which are queried deliberately rather than scraped continuously.

## Alternatives considered

| Alternative | Reason not chosen |
| --- | --- |
| A resilience library for the breaker | A hand-written breaker with an injected clock makes transitions testable at the boundary by advancing time, and keeps the failure-counting policy explicit rather than configured. |
| A breaker per event or per topic | The thing being protected is one dependency, not one event. Per-event breakers never accumulate enough signal to trip. |
| Claim a batch while OPEN and reject each send | Churns leases and attempt counters for work that will not be attempted. |
| Count every failure against the retry budget | A brief protective window terminally failed a whole backlog that never reached the broker. |
| Broker reachability in readiness | Takes a working payment API out of rotation during an outage the design is built to survive. |
| An HTTP endpoint for fault injection | A remotely triggerable failure surface, with no benefit over a test-scoped bean and a container stop. |
| Unbounded consumer retries with offset skipping | Skipping past an event the database could not record loses a projection update silently. Blocking the partition is louder and recoverable. |

## Consequences

**Benefits.** A broker outage degrades only the asynchronous path, visibly, with bounded cost. Recovery
is automatic and observable in metrics and the operator API. Failure windows are reproducible in tests
rather than described in prose.

**Tradeoffs.** An open breaker delays delivery for healthy events too, because the breaker is per
dependency rather than per event. A database outage blocks the consumer's partition instead of skipping
forward, which is the correct trade but does stall progress. Terminal failures need an operator
redrive.

**Not claimed.** Availability, recovery-time, or throughput figures. Tracing and measured performance
limits remain checkpoint 8. The breaker thresholds are defaults chosen for a development stack, not
values derived from measured production behaviour.

**Revisit when:** measured outage behaviour justifies different thresholds, more than one outbound
dependency needs protecting, or a consumer needs a quarantine path instead of blocking.
