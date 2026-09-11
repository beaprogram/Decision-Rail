# ADR 0002: Deliver outbox events through a leased dispatcher with a per-payment sequence

- Status: accepted for checkpoint 4
- Date: 2026-09-10

## Context

V1 created `outbox_events` as durable intent: a committed row saying an event should exist. Nothing
delivered it, and the table had no delivery state, no attempt bookkeeping, and no reliable order.

Order turned out to be the hard part. The obvious candidates are all inadequate:

- **`occurred_at` timestamps** tie lifecycle order to clock resolution and to whatever the system
  clock does. Two events for one payment can share a microsecond.
- **Random event ids** have no order at all.
- **A Kafka partition key** keeps one payment's records on one partition, which is necessary for a
  consumer to read them in order, but it says nothing about the order in which a dispatcher offers
  them. Two workers can send sequence 2 before sequence 1 and the broker will faithfully preserve
  that mistake.
- **`FOR UPDATE SKIP LOCKED`** prevents two workers claiming the same row. It does not prevent one
  worker claiming sequence 2 while another is still sending sequence 1.

A payment's lifecycle order is not cosmetic. A consumer that applies `captured` before `authorized`
builds a read model that never saw the authorization.

## Decision

**Assign a durable per-payment sequence inside the committing payment transaction.** The transaction
already owns the payment row: newly inserted for an authorization, locked `FOR UPDATE` for capture and
void. `max(aggregate_sequence) + 1` under that lock is therefore uncontended, and a unique constraint
on `(aggregate_id, aggregate_sequence)` is the backstop if that assumption is ever broken.

**Make an event claimable only when it is the lowest unpublished sequence for its payment.** Since a
claimed row is not published, this single predicate delivers two properties at once: lifecycle order,
and at most one in-flight event per payment. It also explains the failure mode honestly, because a
terminally failed event is unpublished too and therefore blocks exactly one payment's stream while
every other payment keeps draining.

**Split the cycle into three transactions:** claim and commit the lease, send with no transaction
open, then record the outcome under the lease token. Sending inside the claim transaction would hold
row locks across a broker round trip, which is how an asynchronous dependency becomes synchronous
payment latency.

**Fence completions with a lease token.** A worker that stalls past its lease and whose row is
reclaimed updates zero rows rather than overwriting the new owner's state.

**Backfill pre-existing rows from `(occurred_at, id)`.** Nothing had been delivered, so reconstructing
order from the write timestamp with a stable id tie-break is deterministic and auditable. It applies
only to rows written before the migration; the live path never depends on timestamp resolution. The
same migration adds routing identity to old payloads so committed history stays deliverable instead of
arriving at a consumer as malformed.

**Validate events against the bounds the consumer's own tables accept.** Validation originally
checked a normalised copy of a value and then stored the original: a currency of "cad" passed because
its uppercase form is supported, and failed a column CHECK on insert. That arrived as a database
exception, which the retry policy correctly treats as a transient storage outage and retries
indefinitely, so one unprocessable record blocked its partition forever. Status, policy version, and
failure code had the same shape of gap with no validation at all. The contract now requires canonical,
in-bounds values and refuses anything else, which routes it to quarantine in bounded time. Rejecting
rather than normalising is deliberate: rewriting a payload would change the bytes a consumer
deduplicates and fingerprints on, and a consumer may not alter an immutable event it received. Genuine
storage failures still propagate and still retry without acknowledging, because turning every database
exception into a quarantine would convert a recoverable outage into lost work.

**Fence every write by ownership, not just the final state transition.** Both workers originally wrote
their result before the lease-fenced row update, and discarded that update's false result. A worker
whose claim had been taken over therefore committed a comparison or a batch of replay results anyway.
Each path now takes a row lock on its own task or job first and writes nothing unless it still owns it,
with a consistent lock order of claim row first, then results. For replay, recomputing totals, counting
remaining work, and completing the job also moved into that same locked transaction, so completion can
no longer be decided against totals that an outstanding batch is about to change.

**Do not charge a short-circuited send against the retry budget.** This was a real defect found in
testing: because the breaker's rejection looked like any other failure, a few seconds of protection
terminally failed a whole backlog of events that had never been offered to the broker. A rejected send
now refunds the attempt, and an open breaker skips claiming entirely.

## Alternatives considered

| Alternative | Reason not chosen |
| --- | --- |
| Publish to Kafka inside the payment transaction | A database commit and a broker publish cannot agree; one will succeed while the other fails. |
| A single global sequence across all payments | Forces a single hot counter and serialises unrelated payments behind each other for no benefit. |
| Kafka transactions for exactly-once delivery | Exactly-once within Kafka still does not span the PostgreSQL commit. It would add broker coupling without removing the window this design documents. |
| Order by `occurred_at` at claim time | Correct only while the clock is monotonic, the resolution is sufficient, and one worker runs. |
| No ordering guarantee, consumers sort | Pushes an unbounded reordering buffer and a completeness problem onto every consumer. |
| A dead-letter topic instead of blocking | Hides a failed lifecycle event and lets later events for the same payment apply against a gap. Blocking one payment is louder and more correct. |

## Consequences

**Benefits.** Payment commands keep working while the broker is down. Ordering is a committed fact
rather than an inference. Delivery state, attempts, failures, and blocked streams are queryable, and
recovery after a worker death needs no manual step.

**Tradeoffs.** The window between a broker acknowledgement and the outbox update is real: a crash
inside it resends. That is why the guarantee is at-least-once delivery with idempotent consumer
effects, and why the consumer commits its deduplication record and its effect in one transaction. One
terminally failed event stalls its payment's stream until an operator redrives it, which is deliberate
but does require an operator. The sequence assignment adds one indexed query per event to the payment
transaction.

**Not claimed.** End-to-end exactly-once processing across PostgreSQL and Kafka. Ordering across
different payments. Any throughput or latency figure. The local broker is a single node with
replication factor 1 and is not a highly available deployment.

**Revisit when:** consumers need ordering across aggregates, a terminal failure needs automatic
quarantine rather than stream blocking, or measured backlog drain time justifies partitioned workers.
