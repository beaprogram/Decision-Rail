# ADR 0007: Refunds, post-capture reversal, compensating journals, and reconciliation

Status: accepted, checkpoint 9.

Context: checkpoints 1 to 8 delivered a lifecycle that only ever moved money one way. An
authorization reserved funds, a capture consumed them and wrote a sealed journal, and a void released
the hold without moving anything. Nothing could put money back. This checkpoint adds that, and the
awkward parts are not the refund itself but what a second money movement does to a schema built for
exactly one.

## The state machine

A payment's status is unchanged by this checkpoint. It is still one of AUTHORIZED, CAPTURED, VOIDED,
DECLINED, REVIEW.

| From | Command | To | Money | Ledger |
| --- | --- | --- | --- | --- |
| — | authorize (APPROVE, funds available) | AUTHORIZED | hold placed | none |
| — | authorize (APPROVE, funds short) | DECLINED | none | none |
| — | authorize (REVIEW / DECLINE) | REVIEW / DECLINED | none | none |
| AUTHORIZED | capture | CAPTURED | hold consumed, balance debited | one CAPTURE journal |
| AUTHORIZED | void | VOIDED | hold released | **none** |
| CAPTURED | refund (amount ≤ remaining) | **CAPTURED** | balance credited | one RETURN journal |
| CAPTURED | reversal (nothing returned yet) | **CAPTURED** | balance credited in full | one RETURN journal |

Every other combination is refused with a structured rejection:

| Attempt | Code | Status |
| --- | --- | --- |
| Refund or reverse a payment that is not CAPTURED | `INVALID_PAYMENT_STATE` | 409 |
| Refund more than remains | `RETURN_EXCEEDS_REFUNDABLE` | 422 |
| Reverse a capture that has already been returned in part or in full | `PAYMENT_NOT_REVERSIBLE` | 409 |
| Refund with no amount, a non-positive amount, or one above the bound | `INVALID_RETURN_INPUT` | 400 |
| Reverse while stating an amount | `INVALID_RETURN_INPUT` | 400 |
| Reuse a key for a different payment, type, amount or reason | `IDEMPOTENCY_CONFLICT` | 409 |
| Any of the above against another merchant's payment | `PAYMENT_NOT_FOUND` | 404 |

### Why a return does not change the status

A fully refunded payment stays CAPTURED with `returnedAmountMinor == capturedAmountMinor`. Three
reasons, in order of weight:

1. **The capture happened.** It is a historical fact with a sealed journal. A status that said
   otherwise would make the payment's own record disagree with its ledger.
2. **V2's capture-journal rule requires the payment to be CAPTURED.** Moving the status would make an
   existing sealed journal retroactively invalid under its own constraint.
3. **Partial refunds have no status to move to anyway.** Two of three refunds leave a payment in a
   state that is neither "captured" nor "refunded", and inventing one would be a worse lie than
   keeping the accurate one. What changed is a total, so a total is what records it.

Amounts, not status, are the representation: `capturedAmountMinor`, `returnedAmountMinor`, and
`remainingRefundableMinor`, with `refundable` and `reversible` decided by the server.

### Authorization reversal is void, and is unchanged

Releasing a hold before capture is `POST /v1/payments/{id}/void`. It moves no money, so it writes no
journal — there is nothing to record. That behaviour is untouched by this checkpoint, and the
benchmark's correctness check still asserts a voided payment has no journal of any kind.

"Reversal" here means a **post-capture** reversal, which is money movement and does write a journal.
The two words are kept apart deliberately: they are different operations on different states with
different evidence.

## One capped budget, shared

Refunds and post-capture reversal draw on one budget: `captured − Σ returned`, floored at zero and
enforced in three independent places.

1. **The service** locks the payment row, then its account — the same order capture and void use, so a
   refund racing a capture cannot deadlock — sums the return operations, and refuses an amount above
   what remains.
2. **A row-level CHECK** on `payments` refuses `returned_amount_minor` above `captured_amount_minor`,
   whatever wrote it.
3. **A deferred constraint trigger** on `payment_returns` refuses a commit where the sum of return
   operations exceeds the capture, *or* where the payment's recorded total disagrees with that sum.

### What the third one actually covers, precisely

It fires **when a return is written**, because that is the event it is attached to. So a return whose
amount would leave the payment's total disagreeing with the sum of its operations is refused, and the
two cannot be driven apart by adding a return.

It does **not** fire on an update that touches only the payment. Within the row-level cap — which does
apply to every write — `returned_amount_minor` can be moved to a value the return rows do not sum to.
The guarantee is therefore "checked whenever a return is recorded", not "true of every row at every
moment", and this document previously claimed the latter.

That narrower guarantee is deliberate rather than an omission. Widening it to a trigger on `payments`
was considered and rejected: reconciliation exists precisely to catch separately maintained records
disagreeing with each other, and a schema that made this particular disagreement impossible would also
make that detection impossible to exercise — leaving a check nothing could ever demonstrate. The
constraint that prevents *loss* (never more returned than captured) is enforced on every write; the
constraint that detects *drift* is left to the layer built to report it. Both halves are covered by
tests that assert what fires and what does not.

Concurrency is handled by the payment row lock, not by the trigger: two refunds on one payment
serialise, and eight concurrent 300-unit refunds against a 1000-unit capture commit exactly three.

### Reversal after a partial refund: refused

The choice was between refusing it and quietly returning only the remainder. **It is refused**,
`PAYMENT_NOT_REVERSIBLE`.

"Reverse the capture" means the capture is undone. Once part of it has already come back through a
different operation with its own evidence, that sentence has no unambiguous meaning: it cannot return
the full captured amount without over-crediting, and if it returns the remainder then it *is* a refund
of the remainder and the distinct operation type adds nothing but a chance to misread it. Refusing
keeps one word meaning one thing.

Nothing is lost. Returning everything that is left is always available as a refund, and the dashboard
offers exactly that with the remaining amount filled in.

### A refund names its amount, always

There is no "refund whatever is left" request. The remainder changes as other refunds commit, so one
idempotency key could mean two different amounts at two different moments — and a key whose meaning
drifts is worse than no key. The dashboard reads the remaining amount and sends that number; the
amount is part of the request fingerprint.

## The ledger

V1 declared `ledger_journals.payment_id` UNIQUE and V2 required every journal to be a full capture
journal. Both are replaced, and neither is replaced with "any balanced pair is acceptable" — a balanced
journal can still record the wrong amount, the wrong direction, or an operation that does not exist,
and each of those loses money while every debit still equals a credit.

What replaces them is a rule per journal kind:

- **Exactly one CAPTURE journal per payment**, enforced by a partial unique index.
- **Exactly one journal per return operation**, enforced by a unique index on `source_return_id`.
- A **CAPTURE** journal must be two balanced entries for `payments.captured_amount_minor`, debiting
  `wallet:<account>` and crediting `merchant-clearing:<merchant>`, on a CAPTURED payment.
- A **RETURN** journal must be two balanced entries for its return operation's amount, debiting
  `merchant-clearing:<merchant>` and crediting `wallet:<account>` — the exact reverse — and must agree
  with that operation on payment, merchant, currency and account.

Ledger accounts are checked because a balanced pair naming the wrong account passes every arithmetic
test while moving real value to the wrong place. V2's sealing is untouched: entries can still only be
added in the transaction that created the journal, so a capture journal from 2026 stays exactly as it
was after any number of refunds.

Correction is by compensation. There is no endpoint that sets a balance, edits a journal, or deletes a
return; the database rejects UPDATE and DELETE on all three.

## Idempotency across two response shapes

Every stored idempotent response used to be a `PaymentView`, and the decoder assumed it. A refund
receipt is a different shape, and decoding one as the other would either throw or — against a lenient
reader — succeed and hand back a plausible-looking object that was never what the caller was told.

`idempotency_records.response_kind` records the shape, backfilled to `PAYMENT` for every existing row,
and a replay that finds a kind other than the one the command produces fails loudly. **Historical
authorize, capture and void responses are byte-identical to what they were**: no response shape
changed, because `PaymentView` gained no fields.

A receipt states the totals as they were when it committed, not as they are now. Replaying the first of
three refunds returns that first refund's numbers. Rebuilding a receipt from the payment's current
state would turn a historical record into a live view, which is the one thing a durable idempotent
response must never do.

## Events

Two new types, `payment.refunded.v1` and `payment.reversed.v1`, carrying a `returnOperation` block —
required on a return event, forbidden on a lifecycle one. Without it, two partial refunds of the same
payment produce snapshots differing only in a total, which reads as one event delivered twice.

- They share the payment's aggregate sequence, so a refund cannot be delivered before the capture it
  compensates.
- They do **not** enqueue shadow work. Shadow evaluation compares a candidate policy against an
  authorization decision; a refund carries the decision the authorization already recorded, so
  enqueuing one would duplicate an evaluation and make a candidate's divergence rate depend on how
  often merchants issue refunds.
- Historical events are **not** rewritten. `capturedAmountMinor` and `returnedAmountMinor` are simply
  absent from every event written before this checkpoint, and the contract reads absent as "not
  stated" rather than as zero. Backfilling them would change bytes consumers have already
  fingerprinted, turning a redelivered historical event into apparent identity reuse. V3 could
  backfill payloads because those rows had never been delivered; these have.

A broker outage does not stop a refund committing. Its event intent is durable and delivered in order
when the broker returns.

## Reconciliation

A bounded, synchronous, read-only report. No background-job subsystem: nothing here needs one, and
adding one would be infrastructure with no question to answer.

It runs in a single REPEATABLE READ transaction, so every number comes from one snapshot. Without
that, a refund committing between two queries would make a correct system look broken and the report
would manufacture findings under ordinary concurrent load.

Expected state is derived one link at a time, never from the field being checked:

| Check | Expectation derived from |
| --- | --- |
| Account balance | opening balance, less capture debits against the wallet, plus return credits to it — from **ledger entries** |
| Held funds | the payments still AUTHORIZED on the account |
| Entry direction | wallet entries sitting on the wrong side for their journal's kind |
| Capture journal | exactly one per captured payment, for the amount the capture recorded |
| Return totals | the payment's total vs its **return operations** vs their **journals** — three records, checked against each other |
| Return journals | one balanced journal per return, for that return's amount |
| Ownership | every journal and return carrying the payment's own merchant, currency and account |

Comparing the payment's returned total against the account balance alone would be circular: the same
statement writes both. The chain is walked link by link so any single break is visible.

Findings carry the resource, the expected and actual values, the delta, the currency, and the
operations and journals the expectation came from. Currencies are never combined; each account holds
one and is reconciled in it.

### Currencies that disagree

Every amount above is an integer count of minor units, and minor units of different currencies are not
the same quantity. The account query originally summed capture debits and return credits without
checking that the account, its payments and their journals were denominated alike, so an account whose
currency disagreed with its payments reconciled **clean**: the arithmetic worked perfectly on numbers
that should never have been added together.

Three things now happen instead, in this order:

1. **The disagreement is reported.** `ACCOUNT_CURRENCY_MISMATCH` names the account's currency, the
   currencies found on its payments, and how many payments, journals and returns disagree.
   `PAYMENT_CURRENCY_MISMATCH` names a payment denominated differently from its account.
2. **The arithmetic is refused.** Balance and held funds are not reconciled for that account at all,
   and `ACCOUNT_TOTALS_NOT_DERIVABLE` says so with no expected or actual value, because there is no
   honest number to put there.
3. **Nothing is converted, discarded, or repaired.** Summing across currencies would invent a figure;
   filtering the disagreeing records away would produce a clean result for evidence that is not; and
   converting would require a rate this system has no business holding.

So the report can still conclude "these records disagree about what currency this account holds". It
explicitly **cannot** conclude anything about that account's monetary totals until they agree, and it
says which of the two it is doing.

The application already prevents this at authorization time — a payment's currency must match its
account's. The schema does not, which is exactly why reconciliation checks it: a constraint was
considered and deliberately not added, for the same reason as the returned-total equality above. A
foreign key tying `payments(account_id, currency)` to `accounts(id, currency)` would make the
corruption impossible and the detection undemonstrable, and the layer whose job is noticing that
records disagree should not be the layer that can never be tested.

### Return history is paged, not truncated

History was capped at the 200 oldest returns with no pagination and no metadata. Past that cap the
newest operation and its journal were unreachable, while the payment's totals still counted them — so
a payment with 201 returns reported 201 in its amounts and showed 200 in its list.

It is now keyset paged on the per-payment sequence number, newest first, with `returnCount` stating the
payment's total separately from the page. Keyset rather than offset for the reason payment search
already uses one: a return committing mid-paging would shift an offset and make the next page skip an
operation. Because the ordering key is assigned inside the financial transaction and only ever
increases, a new return lands ahead of the pages already read rather than inside them.

Two things that are deliberately not the fix: the cap was not removed, because one payment's history
must never produce an unbounded response; and it was not merely raised, because any cap without
pagination has the same defect one order of magnitude further out.

Eligibility does not consult the page. `reversible` comes from the payment's return count, not from
whether the current page is empty — a caller who pages past the end must not be offered a reversal on
a capture that has already been returned.

### What it will not do

- **It never writes.** No repair, no journal, no refund. An automatic "correction" would destroy the
  only evidence that something went wrong, using the code whose output is in doubt.
- **It never returns a reassuring result for a partial population.** A bounded run that found nothing
  is `INCOMPLETE`, not `CLEAN`, and says which limit stopped it.
- **It never reads the activity projection.** That read model is built from delivered events and is
  allowed to lag; comparing it against authoritative tables would report delivery latency as missing
  money.
- **It cannot detect a consistently wrong write.** All this evidence lives in one database. A single
  mistaken transaction that wrote the same wrong amount to the payment, its journal and the balance
  would reconcile perfectly. The report says so in its own `limitations`.

Merchants see their own accounts, with the merchant taken from authentication and carried into every
query. A broader view is a separate method reachable only from `/v1/ops` and `/ui/ops`, both restricted
to ADMIN. OPERATIONS remains metrics-only.

## Recovery

The operator procedure is in the operator guide; what belongs here is the decision behind it.

**The idempotency record is the authority on what committed**, not a log line and not the dashboard.
Three states, and the middle one is the one that matters:

| State | Means | Action |
| --- | --- | --- |
| No row | Nothing was claimed under this key | Safe to send |
| A row with no response body | A transaction claimed the key and did not complete; it rolled back with everything it wrote | Resend the same key and body |
| A row with a response body | It committed; that body is the answer | Resend the same key to receive it |

An unknown outcome is not a failure, and a fresh key for work that may already exist is how a payment
is made twice. Resending an identical key and body is always safe: it either performs the work once or
returns what it already did.

One thing this deliberately does not claim: an absent idempotency record proves no durable result was
committed under that key, not that the request never arrived. A request that reached the application
and failed before commit leaves the same absence. For deciding what to do next the two are the same,
which is why the procedure is written on the state rather than on a diagnosis.

**A committed financial effect and an undelivered event are different situations.** Money that moved
has moved. A projection that has not caught up is delivery lag, and neither a PENDING outbox row nor a
terminally failed one is a reason to reissue a financial command — a redrive delivers the original
event, with the identity it was committed with.

### What recovery this project actually demonstrates

- A refund commits while the broker is unreachable, keeps its money, and its event is delivered in
  order once the broker returns.
- That event is deliverable from its outbox row alone, with every piece of the dispatcher's in-process
  state — lease, claim, breaker — discarded first.
- A committed command whose response was lost is recovered by resending its key.
- A failure after the financial writes rolls back all of them, and frees the key.

- A committed refund surviving the process that committed it: the application is killed with the event
  still undelivered, a new process starts against the same database **with the broker still
  unavailable**, serves payments, and delivers that event in order with its original identity once the
  broker returns. `scripts/recovery-demo.sh`, on its own disposable stack.

### Starting without a broker

The application used to refuse to start whenever the broker's *name* did not resolve. A listener
container builds its consumer as it starts, the client resolves `bootstrap.servers` there and then, and
an unresolvable name throws `ConfigException: No resolvable bootstrap urls given in bootstrap.servers`
out of Spring's lifecycle processor, failing the whole context. A restart during a broker outage was
therefore a payment outage too — the precise thing that keeping the broker out of readiness, and
putting event intent in an outbox, exist to prevent.

Two distinctions matter and were previously blurred:

- **A resolvable address with a closed port was never affected.** The consumer constructs fine and
  discovers the problem later. Describing every connection failure as equivalent to this one was wrong.
- **A stopped container's name stops resolving.** Under Compose that is the ordinary case, which is why
  this surfaced there rather than against a closed port.

Listener containers no longer auto-start. `ListenerStarter` starts them after the context is up, off
the startup path, and retries on a fixed interval while the broker is unreachable — so delivery resumes
when the broker returns, without another restart. One daemon thread, one attempt per interval, and it
stops scheduling once every container is running.

Three things it deliberately does not do. It does not leave listeners permanently disabled: it retries
until they run. It does not report delivery healthy while consumers are stopped: the asynchronous
health indicator reports `consumersRunning` and goes DEGRADED, while readiness — which is about the
payment path — stays UP. And it does not swallow configuration errors: the cause chain is unwrapped,
and a `ConfigException` that is *not* the unresolvable-bootstrap one is logged and abandoned rather than
retried forever, because waiting will not fix a value that is simply wrong.

One implementation note worth recording, because it was wrong first. A container whose start throws can
still answer `isRunning()` with true — a concurrent container marks itself running while bringing its
children up, and a failure part way through leaves that flag set with no consumer behind it. Trusting
it reported healthy consumers for a process that had none, and made the retry a no-op. The starter
therefore keeps its own record of what it actually started, and stops a container before retrying it.

### What it does not
- **No backup or restore.** There is no tested recovery from a lost PostgreSQL volume and no
  recovery-point or recovery-time objective anywhere. Losing the database loses payments, ledger,
  idempotency records and outbox together.
- **A single-node broker.** Replication factor 1. Events already published and then lost from the
  broker are not recoverable by this system; the outbox only covers what has not been acknowledged.

## What is deliberately absent

No settlement rails, merchant liquidity accounts, chargebacks, foreign exchange, or payment-network
integration. The account model is the same synthetic one: a wallet balance, a hold, and a clearing
account per merchant. Returns move money between the two accounts that already exist.
