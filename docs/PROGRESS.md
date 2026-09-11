# Current delivery and continuation

The plan is ten equally weighted scope checkpoints. Six are now complete: **approximately 60% of
planned scope**, not 60% of effort or production readiness. Calling it that is planning shorthand, and
the checkpoints are not equally difficult. The detailed scope and completion criteria are in
[roadmap.md](roadmap.md).

## Completed checkpoints

1. Java 21/Spring Boot/PostgreSQL foundation, merchant isolation, required credentials, API contract,
   and repeatable delivery configuration.
2. Authorize/capture/void, durable idempotency, database concurrency protection, balanced append-only
   capture journal, and transactional outbox records.
3. Deterministic `demo-v1` rules with immutable stored decision explanations and policy version.
4. Reliable outbox delivery through Kafka: a durable per-payment sequence, a leased dispatcher with
   bounded batches and fencing, retry scheduling and terminal failure state, controlled redrive, and
   an idempotent merchant-scoped projection consumer.
5. Immutable candidate policy versions with a canonical content hash, durable historical replay
   against a materialised input snapshot, and asynchronous shadow evaluation isolated from all
   financial state.
6. Resilience controls: explicit send deadlines, a documented retry budget across both layers, a
   circuit breaker at the broker boundary, worker limits and backpressure, durable backlog and failure
   visibility, and liveness, readiness, and degraded asynchronous capability as three separate signals.

## Verification record

Local evidence recorded **2026-09-11 UTC** using Java **21.0.11**, PostgreSQL **16.15**, and Kafka
**3.9.1**.

- Pinned-wrapper build and suite: **186 tests passed**, with **0 failures, 0 errors, and 0 skipped**
  (107 domain and contract units, 4 architecture rules, 48 PostgreSQL integration tests, 27 tests
  against both PostgreSQL and a real single-node broker). Every test from the earlier milestones is
  still present and passing.
- A review-driven correction pass fixed five correctness defects and one inaccurate claim in this
  ledger. Each correction has a regression test that failed before it and passes after; the failure
  counts and the evidence are recorded in [verification.md](verification.md). The completed-checkpoint
  count is unchanged: this was corrective work inside checkpoints 4 to 6, not new scope.
  - Unhealthy health statuses answered HTTP 200, because a custom status mapping replaces Spring
    Boot's defaults rather than adding to them. A readiness probe reported DOWN in its body while
    returning a success code.
  - A shadow worker whose claim had been taken over still committed its comparison, so a task could be
    marked successful while its only stored comparison recorded a failure.
  - An obsolete replay owner could commit results and item transitions, and completion was decided in
    separate transactions from the totals it published.
  - Event validation accepted values the consumer's own tables reject, so an unprocessable record was
    retried as a transient storage outage and blocked its partition indefinitely.
  - Invalid policy definitions reached the generic handler as HTTP 500 instead of the structured 400
    contract.
- Packaged application in the local Compose stack, rebuilt after the corrections: `scripts/demo.sh`
  passed all **12 HTTP checks** and `scripts/async-demo.sh` passed all **27 checks**.
- The asynchronous demo observed: a payment authorized with the broker container stopped; retained
  event intent with the breaker OPEN and `/actuator/health/async` DEGRADED while readiness stayed UP;
  delivery resuming after restart with the original event id and the breaker closing through its
  half-open probe; a projection applied count that stayed at 1 after the same event was delivered
  twice more; a replay job whose membership stayed at 4 pinned inputs when a later payment committed;
  a 409 when a policy version id was rebound to different content; and a shadow divergence (live
  APPROVE, candidate DECLINE at score 60) after which the balance was unchanged and held funds moved
  only by the new authorization's own hold.
- A migration upgrade check applies V1 and V2 to a throwaway database, seeds payment, idempotency,
  ledger, and outbox records in their original shape, then applies V3 to V5 and asserts the sequence
  backfill, delivery status, payload routing identity, preserved financial records, and that the
  sealed-journal guarantee still holds.
- PostgreSQL 16 and Docker runtime verification: [the remote run](https://github.com/beaprogram/Decision-Rail/actions/runs/34538867915) passed on revision
  `f01fe41`, running the same `compose.test.yaml` stack, the full 149-test suite, the image build,
  container startup, and both demos (12 and 27 checks). Do not equate a checked-in CI workflow with a
  passing remote build; inspect the workflow result for the revision you care about.
- Public deployment: not performed.

Record actual commands, test counts, failures, and meaningful limitations here after verification.

## Remaining checkpoints

- [ ] 7. Operator interface for payment state, explanations, and comparisons.
- [ ] 8. Correlated telemetry, reproducible load tests, and measured performance limits.
- [ ] 9. Refunds/reversals, reconciliation, financial corrections, and recovery procedures.
- [ ] 10. Free-budget hosting assessment, secure public demo deployment, and release walkthrough.

## Material limitations to carry forward

These are known and deliberate, not oversights:

- **Delivery is at-least-once with idempotent consumer effects.** Not exactly-once across PostgreSQL
  and Kafka. The failure windows are enumerated in [architecture.md](architecture.md).
- **The broker is a single node with replication factor 1.** Not highly available. A restart is a
  delivery outage and a lost volume is lost events; the outbox is what makes that survivable.
- **A terminally failed event blocks its own payment's stream** until an operator redrives it. Other
  payments keep draining. There is no automatic quarantine path.
- **A candidate policy is never authoritative.** There is no promotion workflow and no code path that
  could make one decide a real payment. That remains future work.
- **Candidates cannot define new decision flags or move the outcome thresholds.** They vary rules only.
- **A candidate score above 100 is capped for the outcome decision, and the raw total is kept.**
  Replay results and shadow comparisons store and return `candidateScore` (capped at 100),
  `candidateRawScore` (the uncapped total), `candidateScoreCapped`, and every contributing reason, so
  the excess is recoverable and the explanation reconciles against the raw total. Only the outcome
  thresholds see the capped value.
- **Shadow evaluation does not backfill.** It applies to authorizations observed while enabled; use a
  replay job for history.
- **Replay membership omits a payment that was still uncommitted** when the job was created. This is a
  documented property of the snapshot.
- **No fraud accuracy metrics anywhere.** No labelled outcome data exists for synthetic traffic, so
  precision, recall, and false-positive rates would be invented.
- **Replay timings are observations for one run**, with no warmup control or repetition. Not a benchmark.
- **Breaker and backoff defaults are not derived from measurement.** They are reasonable development
  values; the retry budget is documented so it can be reasoned about.
- **No outbox or event retention policy yet.** Published rows accumulate.
- **Still absent:** refunds, reconciliation, an operator UI, distributed tracing, measured performance
  limits, and any public deployment.

## Guidance for the next implementation session

Read [architecture.md](architecture.md), the [ADRs](adr/), and the [API contract](openapi.yaml) before
extending this. Checkpoint 7 is the operator interface, and the data it needs mostly exists: the
activity projection, replay reports and results, shadow comparisons, and the outbox backlog view.

Start by confirming the current suite and both demos, with the test stack from `compose.test.yaml`.
Integration tests install failure-injection triggers and create a throwaway database, so they must not
run against a development or concurrently used database.

Preserve the payment transaction boundary, merchant-scoped request identity, the immutable ledger, and
stored decision evidence. Preserve the newer guarantees too: the per-payment event sequence and its
claim predicate, lease fencing, consumer deduplication committing with its effect, policy version
immutability, the materialised replay snapshot, and the structural isolation of replay and shadow from
financial mutation. The architecture tests enforce the last of those; do not relax them to make a new
dependency convenient.

Schema changes go in new Flyway migrations after V5. Do not edit an applied migration, and extend the
migration upgrade check when a new one backfills anything.

Keep generated credentials, `.env`, local database and runtime files under `.local/`, and build output
out of Git and the Docker build context. Compose ports listen only on loopback. Publish only features
and results that have actually been implemented and verified.
