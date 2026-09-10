# ADR 0003: Evaluate immutable policy snapshots for replay and shadow, never for live decisions

- Status: accepted for checkpoint 5
- Date: 2026-09-10

## Context

The decision engine could only evaluate the ruleset compiled into it. Asking "what would a different
policy have decided" was impossible, which is exactly the question a payments team asks before
changing a rule.

Two constraints shaped the design. Stored decisions are evidence: a payment records the policy version
that decided it, and that record must keep meaning the same thing forever. And the authoritative
decision path must not become less trustworthy because a comparison feature exists.

## Decision

**Generalise the pure engine to evaluate any supplied snapshot, and keep the authoritative path on the
built-in policy.** `evaluatePolicy(ruleSet, input)` is pure: no clock, no database, no I/O, no random
source. `demo-v1` still produces the stored decision, unchanged in behaviour and persisted shape.

**Use a separate result type for candidate evaluation.** A stored decision keeps its strict invariant
that reason contributions sum exactly to its score. A candidate needs to express something that
invariant forbids: a raw total above the top of the scale. Adding a field to the stored shape would
have changed how existing rows deserialize, so candidates return their own type carrying `rawScore`,
the capped `score`, and a `scoreCapped` flag.

**Cap an overflowing score at 100 and record the cap.** Rejecting such policies at creation would
require deciding which rule combinations are jointly reachable, which depends on terminal rules and
overlapping expressions; a validator that guessed would reject valid policies, including the built-in
one whose contributions sum to 210 but which can never exceed 80 in practice. Capping keeps the
outcome monotone: a rule that matches can never make a decision less severe.

**Do not let a candidate set the outcome thresholds.** A candidate varies rules. If it could also move
the review and decline boundaries, a divergence would be ambiguous between changed rules and a
relabelled scale, and the report could not tell an operator which.

**Hash the canonical form, not the submission.** The hash covers meaning: reordered keys, whitespace,
code casing, and country-set order all hash identically, while any change to rule order, a
contribution, an operator, or a bound does not. Rule and child order are part of the canonical form
because both change evaluation. The version id is excluded, which is what lets the service distinguish
an idempotent resubmission from an attempt to rebind a name.

**Materialise replay membership at job creation.** A timestamp cutoff with later pagination is not
enough: a transaction that started before the cutoff can commit after the first page, changing
membership and the denominator mid-run. Membership is a fixed row set, and the original inputs and the
baseline risk decision are snapshotted with it, so evaluation never reads today's account balance to
reconstruct a historical feature.

**Derive job aggregates from recorded results rather than incrementing counters.** An incremented
counter double counts whenever a batch is retried or a job resumes. Recomputation is idempotent, so a
resumed job converges on the totals a single uninterrupted run would have produced.

**Compare against the stored risk decision, never the payment status.** A payment declined for
insufficient funds has an APPROVE risk outcome. Treating its status as a policy decline would
systematically overstate agreement with any strict candidate.

**Derive shadow work from committed authorization events, in its own consumer group.** A callback
registered during authorization would be lost with the process, and awaiting it would make a candidate
policy a synchronous dependency of taking payments. A separate group means a shadow failure cannot
roll back or stall the projection consumer.

**Give shadow and replay no access to financial mutation.** Neither package depends on the payment
service, the payment store, or the outbox, and architecture tests assert those dependencies stay
absent. Isolation that rests on reviewer vigilance is not isolation.

## Alternatives considered

| Alternative | Reason not chosen |
| --- | --- |
| Expressions as scripts or a small language | Arbitrary code in a policy means unbounded evaluation cost and an execution surface. The closed operator set maps onto a sealed type. |
| Candidate-settable thresholds | Makes divergence ambiguous between changed rules and a rescaled score. |
| Reject policies that could exceed 100 | Requires reachability analysis over terminal and overlapping rules; would reject the built-in policy. |
| Mutable policy versions | Silently rewrites what every historical decision naming that version means. |
| Timestamp cutoff re-queried per batch | Late commits change membership and the divergence denominator partway through a run. |
| Re-evaluating the baseline during replay | Compares a candidate against a reconstruction rather than against what actually happened. |
| Shadow evaluation inline with authorization | Makes a candidate policy able to slow or fail real payments. |
| One table for replay results and shadow comparisons | A report could no longer say which population it described: a bounded historical job or a live observation stream. |

## Consequences

**Benefits.** A candidate can be compared against real history and against live authorizations with no
risk to money. Reports state their denominator and how timings were measured. Jobs survive restarts.
Historical decisions remain exactly what they were.

**Tradeoffs.** A capped candidate score loses information above 100, recorded but not recoverable.
Candidates select from the existing decision-flag vocabulary rather than defining new flags. Shadow
applies only to authorizations observed while it is enabled; enabling it does not backfill. Replay
membership omits a payment that was still uncommitted when the job was created, which is a documented
property of the snapshot rather than a race.

**Not claimed.** Fraud precision, recall, or false-positive rates: no labelled outcome data exists for
this synthetic data, so computing them would be inventing them. Promotion of a candidate to
authoritative status, which is outside this phase and has no code path. Performance figures: replay
timings are observed values for a single run with no warmup control.

**Revisit when:** a promotion workflow is needed, candidates need their own flag vocabulary, labelled
outcomes exist so accuracy metrics become meaningful, or replay volume outgrows a single worker.
