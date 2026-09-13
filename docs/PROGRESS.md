# Current delivery and continuation

The plan is ten equally weighted scope checkpoints. Eight are now complete: **approximately 80% of
planned scope**, not 80% of effort or production readiness. Calling it that is planning shorthand, and
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
7. The operator console: browser session authentication with CSRF protection on its own security chain,
   an authoritative merchant payment search with keyset paging, a lifecycle timeline that keeps the
   payment transaction, broker publication, and each consumer group distinct, an administrative
   failed-event list with redrive, and seven screens covering payments, accounts, policy versions,
   replay, shadow, and event delivery. The dashboard is built into the application jar and served from
   the same origin as the API it calls.

8. Correlated telemetry and measured performance: trace context written durably beside each event so a
   payment can be followed from its HTTP command through publication attempts to a committed projection
   effect after a restart, structured JSON logs carrying those identifiers, a documented metric
   catalogue with bounded labels and stated freshness, a free local Prometheus/Tempo/Grafana stack
   provisioned in source control, and a repeatable load harness on isolated infrastructure whose
   results, limits and correctness checks are published in [performance.md](performance.md).

## Verification record

Local evidence recorded **2026-09-12 UTC** using Java **21.0.11**, PostgreSQL **16.15**, and Kafka
**3.9.1**. The build compiles the dashboard with the Node it downloads and pins, **22.14.0**; the
browser suite was driven by the machine's own Node **25.2.1**, since Playwright is run directly rather
than through the build.

- Pinned-wrapper build and suite: **213 backend tests passed**, with **0 failures, 0 errors, and
  0 skipped**. Every test from the earlier milestones is still present and passing; the breakdown by
  group is in [verification.md](verification.md).
- Dashboard: **41 frontend unit tests** and **44 browser end-to-end tests**, the latter
  run by a real Chromium against the packaged application with real PostgreSQL and Kafka, all passing.
  The browser suite runs with **retries disabled**, locally and in CI, so a first-attempt failure cannot
  be hidden by a passing second attempt.
- A second review-driven correction pass fixed seven defects in the checkpoint 7 work. Each has a
  regression test that failed before the fix and passes after; the reproduced failure counts are
  recorded in [verification.md](verification.md). The completed-checkpoint count is unchanged: this was
  corrective work inside checkpoint 7, not new scope.
  - Authentication transitions left the browser with no CSRF token. Rotation on login expired the old
    cookie and deferred writing the replacement, and a successful login short-circuits the filter chain,
    so nothing downstream ever materialised it. The login response's only `XSRF-TOKEN` header was the
    expiry. Signing out then failed with 403, and the earlier logout test only passed because it read
    the payment list in between, which is what issued the token.
  - A failed sign-out was presented as a completed one. The screen was cleared in a `finally` block
    whatever the server said, and the rejected promise was discarded, so a session that still existed
    was reported as destroyed.
  - A retried command was rebuilt from current form state, so editing the amount or the candidate
    policy after submitting changed what the original idempotency key stood for.
  - An incomplete successful response was treated as success with no data. The request deadline ended at
    the response headers and body-parse failures became `null`, so a mutation whose outcome was unknown
    discarded its idempotency key.
  - Identity fencing ran after side effects and not after body processing, so a response belonging to a
    previous identity could broadcast session expiry or write into the new identity's command state.
  - Payment details labelled funds as reserved whenever no failure code was present, including for a
    policy-declined payment that never held anything.
  - This ledger contradicted itself: six checkpoints and 60% alongside a delivered operator console.
- A correction pass on checkpoint 8 fixed five findings, each confirmed against the implementation
  before it was changed. Checkpoint 8 remains complete and the checkpoint count is unchanged.
  - The benchmark reported aggregate metrics under a field saying warmup was excluded. Scenario tags do
    reach custom metrics, so the scripts now declare the measured sub-metrics and the summariser reads
    nothing else, refusing to fall back to an aggregate. Rates are computed over the declared window,
    because k6's own rate on a sub-metric divides by the whole run.
  - Sampling was assumed rather than carried: valid identifiers exist whether or not a span was
    recorded, so storing only identifiers turned every unsampled request into a sampled publication.
    The decision is now stored, propagated in the traceparent flags, and honoured even when false.
  - The committed-command counter incremented before its transaction committed, counting attempts under
    the name of commitments. It is now an after-commit callback.
  - Shadow evaluation had no correlation. The trace travels with the durable task and is read back when
    it is claimed, so an evaluation on a worker thread after a restart is still in the command's trace.
  - The backlog peak was sampled after the load generator had exited, so it could not have observed the
    outage it was published against. Backlog is now sampled throughout and reported as an observed
    maximum, with recovery and load-end drain reported as separate clocks.
- A third pass fixed two defects the previous one left, both in what the screen shows rather than in
  what the server does. Checkpoint 7 remains complete and the checkpoint count is unchanged.
  - Signing out left the protected workspace on screen until the server answered. Advancing the
    identity generation and dropping the query cache does not unmount anything: the screens stay
    mounted and re-render, so a merchant's payment rows and workspace were still there while the
    logout request was in flight. Sign-out now moves the session into its own `signing-out` state, so
    the application boundary unmounts every protected screen synchronously, before the request is even
    sent. That state says the workspace has been cleared and explicitly does not claim the session was
    destroyed, which is still decided only by the server's answer or by reconciling with it afterwards.
  - A retried capture or void never re-read the payment. The first attempt refreshed the payment,
    payment list, and account queries afterwards, but the retry called the command handle directly from
    the outcome notice and skipped that step, so a capture that succeeded on its second attempt left the
    screen offering Capture on a payment that had just been captured. Both paths now run through one
    post-attempt refresh. The command's own response is not used as a substitute: under a replayed key
    the server returns the result as it stood when the command first ran, which is a historical
    snapshot, and the success notice now says so instead of asserting the payment's current status.
- Packaged application in the local Compose stack, rebuilt after the corrections: `scripts/demo.sh`
  passed all **12 HTTP checks** and `scripts/async-demo.sh` passed all **27 checks**.
- The asynchronous demo observed: a payment authorized with the broker container stopped; retained
  event intent with the breaker OPEN and `/actuator/health/async` DEGRADED while readiness stayed UP;
  delivery resuming after restart with the original event id and the breaker closing through its
  half-open probe; a projection applied count that stayed at 1 after the same event was delivered
  twice more; a replay job whose membership stayed fixed when a later payment committed; a 409 when a
  policy version id was rebound to different content; and a shadow divergence (live APPROVE, candidate
  DECLINE at score 60) after which the balance was unchanged and held funds moved only by the new
  authorization's own hold.
- A migration upgrade check applies V1 and V2 to a throwaway database, seeds payment, idempotency,
  ledger, and outbox records in their original shape, then applies the later migrations and asserts the
  sequence backfill, delivery status, payload routing identity, preserved financial records, and that
  the sealed-journal guarantee still holds.
- PostgreSQL 16 and Docker runtime verification: [the remote run](https://github.com/beaprogram/Decision-Rail/actions/runs/34719181973) passed on revision
  `4754fab`, running the same `compose.test.yaml` stack, the full backend suite, the frontend build
  and unit tests, the image build, container startup, both demos, and the browser suite against the
  packaged container. Do not equate a checked-in CI workflow with a passing remote build; inspect the
  workflow result for the revision you care about.
- Public deployment: not performed.

Record actual commands, test counts, failures, and meaningful limitations here after verification.

## Remaining checkpoints

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
- **Dashboard authentication is a local development arrangement.** Identities and passwords come from
  the generated `.env`; there is no user store, no password rotation, no multi-factor step, and no
  account lockout. It is not hardened for exposure to the public internet.
- **Sessions are in-memory and single-instance.** Restarting the application signs everyone out, and
  running two instances behind a load balancer would need shared session storage that does not exist.
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
- **Measured performance is one laptop, not a capacity figure.** The sustained rate in
  [performance.md](performance.md) was measured with the load generator, application, database and
  broker sharing ten CPUs, and with PostgreSQL on tmpfs with `fsync` off. It describes this machine's
  behaviour, not a deployment's. Host drift between batches is real and uncontrolled: the same offered
  rate measured markedly slower in a later batch than an earlier one.
- **Delivery falls behind before the API does.** Publication is already behind commitment at the
  sustained rate and catches up within seconds; at the failing level the backlog grows several times
  larger and takes four times as long to clear. That delivery is what *causes* API latency to degrade
  is a hypothesis, not a measurement: no per-component CPU accounting was collected.
- **Tracing keeps one trace open for the life of an event.** Under a broker outage that is minutes, and
  a backend that closes traces on a fixed window will show such a trace in pieces.
- **Traces are exported only when a collector is configured.** With none, spans are still created and
  their ids still reach logs, but nothing leaves the process.
- **Still absent:** refunds, reconciliation, and any public deployment.

## Guidance for the next implementation session

Read [architecture.md](architecture.md), the [ADRs](adr/), and the [API contract](openapi.yaml) before
extending this. Checkpoint 9 is refunds, reversals and reconciliation against the append-only ledger.
Nothing in the ledger may be rewritten to support it: a correction is a new entry, and the sealed
journal constraint is there to make that the only option.

Start by confirming the current suite and both demos, with the test stack from `compose.test.yaml`.
Integration tests install failure-injection triggers and create a throwaway database, so they must not
run against a development or concurrently used database. Browser tests need the application actually
running; they drive the Compose stack directly to produce a broker outage, and they run with retries
disabled on purpose.

Preserve the payment transaction boundary, merchant-scoped request identity, the immutable ledger, and
stored decision evidence. Preserve the newer guarantees too: the per-payment event sequence and its
claim predicate, lease fencing, consumer deduplication committing with its effect, policy version
immutability, the materialised replay snapshot, and the structural isolation of replay and shadow from
financial mutation. Preserve the dashboard's own: the `/ui` session chain separate from stateless Basic
`/v1`, CSRF on every browser mutation including login and logout, one immutable submitted command per
idempotency key, and an unknown outcome reported as unknown rather than as success or failure. The
architecture tests enforce the isolation rules; do not relax them to make a new dependency convenient.

Schema changes go in new Flyway migrations after V9. Do not edit an applied migration, and extend the
migration upgrade check when a new one backfills anything.

Keep generated credentials, `.env`, local database and runtime files under `.local/`, and build output
out of Git and the Docker build context. Compose ports listen only on loopback. Publish only features
and results that have actually been implemented and verified.
