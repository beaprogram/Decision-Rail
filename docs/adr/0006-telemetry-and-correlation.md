# ADR 0006: Correlation that survives the asynchronous boundary

## Status

Accepted. Implemented in checkpoint 8.

## Context

An operator investigating one synthetic payment has to follow it from an HTTP command through a
durable outbox row, one or more publication attempts, a broker record, and a committed projection
effect. Those steps happen on different threads, minutes apart, and sometimes in a different process
after a restart.

Everything the system already had for correlation lives in a thread. The server-generated request id
is put into MDC by a filter and removed when the request returns. A trace context is the same: it is
attached to the thread that runs the request. Neither can be present when a dispatcher picks the event
up later, and no amount of care with thread pools changes that, because the thread that knew is gone.

## Decision

**Write the origin down.** `outbox_events` gains `origin_trace_id` and `origin_span_id`, written by the
same transaction that commits the event. `idempotency_records` gains `origin_trace_id`, recording which
request first performed a command. Both are nullable, both are validated as lowercase hex of exactly
the right length, and a span id without a trace id is refused by a constraint.

**Keep it out of the payload.** The published bytes are what a consumer fingerprints for
deduplication. Putting trace context there would change event identity between attempts and invalidate
every fingerprint already recorded. Correlation travels to the broker as a `traceparent` record header,
which is invisible to the fingerprint, and the envelope contract is unchanged.

**Parent, not link.** A publication attempt is a child span of the command that produced the event, and
consumption is a child of the publication. One trace therefore contains the whole life of one event,
which is exactly the question this checkpoint exists to answer, and it works in any backend. Span links
were rejected because support for them is uneven and a trace an operator has to reassemble by hand is
not correlation.

**One span per attempt.** A retry is a new child, not a longer first one, so "it took four attempts" is
visible as four spans rather than inferred from a duration.

**Shadow work carries its trace in the row.** A shadow task is enqueued by a consumer and evaluated
later on a worker thread, possibly in another process, possibly after another worker's lease expired.
Three separate pieces of thread context are gone by then, so the trace is written into `shadow_tasks`
in the same transaction that creates the task and read back when it is claimed. A redelivery leaves the
first task's provenance alone, because the insert is `ON CONFLICT DO NOTHING` and the request that
first caused the work keeps the claim.

**A replay points, it does not overwrite.** An idempotent retry is a new HTTP request with its own
trace. It tags its span with the trace of the operation it is replaying, read from the idempotency
record. The stored provenance is written only by the insert that wins the `ON CONFLICT`, so the
original request keeps its claim and the original event keeps its own.

**Sampling is decided once, and the decision is carried.** At the request, and honoured by every later
hop. The decision is stored beside the trace (`origin_trace_sampled`) and written into the traceparent
flags, because identifiers alone do not carry it: an unsampled span has perfectly valid ids, so
inferring "sampled" from their presence silently reverses a deployment's choice not to record.

An unsampled context is propagated rather than dropped. Dropping it would leave the next hop with no
parent, which makes it a new root that takes its own sampling decision — the same work recorded anyway,
now under a trace id that leads back to nothing. A stored row with no decision (written before the
column existed) is treated as unsampled for the same reason.

**Only `traceparent`.** No baggage. Baggage is attacker-influenced text that would travel into the logs
and metrics of every downstream hop, and nothing here needs it.

**Telemetry cannot fail a payment.** Export is off unless an endpoint is deliberately configured; spans
are still created and their ids still reach every log line, so correlation works with no collector
running at all. Every tracing call is wrapped so a failure degrades to "untraced" rather than to a
failed command, no export happens while a financial lock is held, and the exporter has a bounded queue
and a three-second timeout.

**Committed-outcome metrics are counted after commit.** `decisionrail.payments.commands` increments in
an after-commit callback, so a command whose transaction rolls back is never counted as committed. A
failure in that callback is swallowed and logged: the payment is already durable, and telemetry must not
turn a committed command into an apparent failure for the caller. These remain process-local
operational counters, not an accounting record — they reset on restart, and a crash between commit and
callback loses an increment while the payment stands. The ledger is the authority on money.

## Consequences

A trace stays open for as long as delivery takes. Under a broker outage that is minutes, and a backend
that closes traces on a fixed window will show such a trace in pieces. That is the price of one trace
per event and it is accepted deliberately; the alternative was correlation nobody could follow.

Two columns and a header are now part of the delivery contract. They are optional everywhere: an event
written before this existed has no origin and delivers normally, starting its own trace.

The W3C `traceparent` is assembled and parsed directly rather than through the tracing bridge's
propagator. The propagator was tried first and, on this stack, silently injected nothing and silently
failed to establish a remote parent — which looks exactly like working correlation until someone opens
a trace and finds it empty. The format is 55 fixed characters, it is validated in one place, and it is
covered by tests that fail if it regresses.

## Alternatives considered

| Alternative | Reason not chosen |
| --- | --- |
| MDC only, no durable columns | Cannot survive the request returning, let alone a restart. This is the problem, not a solution to it. |
| Trace context inside the event payload | Changes the bytes a consumer fingerprints, so event identity would differ between attempts and every recorded fingerprint would be invalidated. |
| A new trace per publication, linked to the origin | Backend support for links is uneven, and an operator would have to reassemble the story by hand. |
| Reuse the request id as the correlation key | It is already server-generated and kept, but it has no notion of a parent, a child, or a span, so it cannot express where time went. Both are emitted; they answer different questions. |
| Assume a stored origin was sampled | Identifiers exist whether or not a span was recorded, so this turned every unsampled request into a sampled publication and produced traces whose first span does not exist. |
| Drop unsampled context instead of propagating it | The next hop becomes a new root and re-samples, recording the work anyway under a trace that leads nowhere. |
| Increment the committed-command counter inline | It counted attempts under the name of commitments; a rollback after that point left the count standing with no money moved. Verified: the inline version reported 1.0 after a rollback with no payment row. |
| Instrument the rule evaluator | It is pure, deterministic, and reused by replay and shadow precisely because it depends on nothing. It is timed at its calling boundary instead. |
