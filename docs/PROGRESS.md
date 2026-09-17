# Current delivery and continuation

The plan is ten equally weighted scope checkpoints. Nine are complete, and the tenth is implemented
and verified in its deployed shape with one deliverable - the public URL itself - waiting on an owner
action that a repository cannot perform. "Ten of ten implemented" is planning shorthand, not a claim
of effort or production readiness, and the checkpoints are not equally difficult. The detailed scope
and completion criteria are in [roadmap.md](roadmap.md).

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

9. The extended payment lifecycle and recovery: partial and full refunds and a post-capture reversal
   sharing one capped return budget, each recorded as its own operation with its own balanced
   compensating journal that the database checks against the operation it records, an idempotency layer
   that carries two response shapes without breaking historical ones, return events ordered behind the
   capture they compensate, a read-only reconciliation report that derives expected state from the
   ledger and never repairs what it finds, and an operator recovery procedure for establishing what
   committed, what is merely awaiting delivery, and which action is appropriate. The lifecycle and
   accounting decisions are in [ADR 0007](adr/0007-returns-reconciliation-and-recovery.md).

## Verification record

Local evidence recorded **2026-09-17 UTC** using Java **21.0.11**, PostgreSQL **16.15**, and Kafka
**3.9.1**, after the checkpoint 10 corrections (v0.10.1).

- Pinned-wrapper build and suite: **380 backend tests passed**, with **0 failures, 0 errors, and
  0 skipped**, up from 213 at checkpoint 8. The 310 recorded for `fdc6d96` was correct; a 307 that
  appeared briefly in [verification.md](verification.md) was a counting mistake of mine, explained
  there.
- Dashboard: **42 frontend unit tests** and **54 browser end-to-end tests** with retries disabled, up
  from 44.
- Demos: **12 checks** (transactional), **26 checks** (lifecycle and reconciliation), **27 checks**
  (asynchronous) and **16 checks** (restart recovery, on its own disposable stack), all against a
  running application with real PostgreSQL and Kafka.
- Benchmark collector check, k6 attribution check and the harness smoke run all passed, the last
  exercising the correctness queries updated for the new operation types.
- V10 and V11 were applied to the local development database, which carried **346 payments, 65
  accounts, 55 journals and 439 stored idempotent responses** accumulated across checkpoints 1 to 8.
  All 55 captured payments received a return budget, no uncaptured payment received one, all 439
  responses were typed, and no historical event payload was rewritten.

Three first-attempt failures are recorded rather than absorbed, all of them mine and none an
application defect:

- The browser suite failed **every** test on its first run because I did not load `.env`, so the
  credential lookup threw. Loading it gave 50 of 53.
- Of those three, two were **pre-existing** tests that my work genuinely broke, in the same way twice:
  Playwright matches an accessible name as a substring, so `{ name: 'Capture' }` began matching the new
  "Reverse the capture" button, and a page-wide `getByText('CREDIT')` began matching the returns
  panel's confirmation copy. Both assertions were made precise — `exact: true`, and scoped to the
  journal card — rather than relaxed.
- The third was my own new test racing navigation against a same-named field on the page it was
  leaving.

Two further first-attempt failures happened during development and are worth recording because of what
they revealed:

- Adding a refund test to `BrokerOutageIntegrationTest` broke a neighbouring test that had been
  passing. The dispatcher claims the oldest due events across the whole outbox, which that class shares
  with its own other tests, so the breaker's failure budget was being spent on an unrelated backlog
  before the test's own event was attempted. Rather than weaken the assertion, the new coverage moved
  to its own context and topic and the original class was restored untouched.
- Two existing tests used `payment.refunded.v1` as their example of an *unsupported* event type. It is
  supported now, so both were updated to use a type this consumer genuinely does not know. That is a
  real signal from the suite about a real contract change, not a test that needed loosening.

### The checkpoint 8 record

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
- A final correction pass fixed a failpoint ordering race and an inaccurate retry report.
  - `DeliveryFaults.clear()` released gates before disarming injected failures, so a worker parked in a
    failpoint could wake into a fault the caller was in the middle of clearing. Reproduced against the
    real class at attempt 21 of 50; corrected by disarming first, which the `countDown`/`await`
    happens-before edge makes a guarantee rather than a smaller window.
  - The retry report said zero HTTP failures and described all 576 originals as replayed. The artifact
    shows 7 failed originals, 569 successful, and 1707 replays — 569 x 3. All seven failed at TCP
    connection establishment, which is the transport-level evidence that no request was sent; the
    absent idempotency records establish only that nothing was durably committed for those keys, which
    is a weaker and separate fact. Why the connection failed is not established by the retained
    evidence and is left unattributed.
- A further correction pass fixed two remaining benchmark defects. Checkpoint 8 remains complete and the
  checkpoint count is unchanged.
  - Dropped iterations were selected on a custom scenario tag, which k6 does not attach to
    executor-dropped iterations. The submetric existed, matched nothing, and reported zero while the
    aggregate held hundreds; the published claim of a sustained 30/s with zero dropped work was never
    established. Selectors now use the built-in scenario tag, warmup drops are reported separately, and
    the summariser reconciles the partitions against the aggregate. Re-measurement against criteria
    fixed in advance puts the sustained rate at **25 iterations/s**, with 30/s failing one repetition in
    three.
  - The backlog sample counter counted event markers as observations, and the configured sampler delay
    was reported as though it were the observation interval. Observations, markers and failed queries
    are now counted apart, and the observed spacing is derived from the timeline: 2–5 seconds against a
    configured 2.
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

### Open items

- None outstanding. The three review cleanups requested alongside checkpoint 9 are done: the failpoint
  test's wall-clock assertion is replaced by an interrupt-based check that distinguishes an armed delay
  from a disarmed one rather than from a threshold, the benchmark summariser's population prose now
  agrees with whether the scenario actually declared a warmup, and the claim that missing idempotency
  records prove a request never arrived is corrected — those records establish only that nothing was
  durably committed, and it is the dial-timeout evidence that carries the transport-level conclusion.
  The cause of those seven connection failures remains unattributed.
- The `ShadowStaleWorkerTest` flake stays resolved: an ordering race in `DeliveryFaults.clear()`, with
  the earlier shared-database hypothesis withdrawn. See [verification.md](verification.md).

### The pre-checkpoint-10 hardening pass

Four things, none of which extend scope:

- **A cross-encoding idempotency collision, closed.** The direct hash-match branch replayed a stored
  response without ever consulting the receipt, so wherever an old unversioned fingerprint encoding and
  the current one could produce the same string, a key answered for a command nobody sent — reproduced
  through the real HTTP and database path on both refund and reversal before being fixed. The stored
  receipt is now the authority on **every** completed replay, which covers all three persisted
  generations without a migration or a version column. A changed-request identity defect, not an
  observed duplicate credit.
- **Two schema constraints, added as a deliberate decision.** `V12` ties a payment's currency to its
  funding account; `V13` extends returned-total equality to updates that touch only the payment. The
  earlier position — leaving the second unenforced so reconciliation had something to detect — is
  withdrawn in [ADR 0007](adr/0007-returns-reconciliation-and-recovery.md). Prevention and detection
  are now evidenced separately: the production schema refuses the write, and the detector is exercised
  against a private throwaway database migrated only to V11. Neither constraint responds to a
  demonstrated loss; no service path produced either inconsistency.
- **Two browser tests that did not test what they claimed.** The delayed-identity scenario held an
  outgoing request rather than a response and changed identity by navigating, which remounts the SPA;
  it is now a deterministic same-document interleaving with explicit rendezvous, asserting ownership by
  resource identity confirmed against the database. The returns pagination test created four returns
  against a page size of 50 and never pressed the paging controls; it now builds 52 operations and
  traverses both ways.
- **An upgrade that finds pre-existing disagreement refuses and explains**, naming the count, an
  example payment and the reconciliation finding types, and repairs nothing. The runbook is in
  [operator-guide.md](operator-guide.md).

### The returned-total INSERT correction

One bounded fix. The equality between a payment's returned total and its return operations was
enforced when a return was inserted (V10) and when a payment was updated (V13), and both this ledger
and ADR-0007 then described it as holding whichever side was written. A payment *inserted* already
inconsistent was neither event, and committed. Reproduced against V1-V13 on PostgreSQL 16.15: a valid
account, a CAPTURED payment of 1000 captured 1000 recording 100 returned, a valid balanced capture
journal and no return operations at all. An UPDATE of that same total was refused, which is what shows
the guard was live and blind to how the row arrived.

`V14` adds the missing deferred trigger on payment insert, reusing the existing validation function so
all three entry points are one rule. It validates existing rows first and refuses an upgrade that
would declare an invariant they do not satisfy, holding `SHARE ROW EXCLUSIVE` from before the check
until the protection is in place so nothing can slip between them.

This is a schema-invariant gap. **No API path producing it and no money loss were demonstrated** - the
service writes returns and totals together under the payment row lock, and an authorization inserts
the column at its default of zero. What is worth carrying forward is the reasoning error rather than
the trigger: a rule was described by the events someone happened to attach it to instead of by
enumerating every way the state it protects can change, and that mistake was made twice in the same
place before it was caught.

### The payment-identity correction

Two findings and a documentation correction, all narrow.

- **A payment could be renamed out from under a deferred check.** V14 refuses a payment inserted with a
  returned total its operations do not sum to, but a deferred trigger captures its row when the
  statement runs and validates at COMMIT. Inserting payment `A`, changing its id to `B`, and committing
  left the inconsistency behind: the queued check looked for `A`, found nothing and returned, and `B`
  was never examined. Reproduced on PostgreSQL 16.15, with the same transaction minus the rename
  correctly refused as the control. `V15` makes a payment's id immutable, which removes the class
  rather than the instance — any future deferred constraint on `payments` would have inherited the same
  assumption. A direct-SQL defect: **no API path reaches it and no money loss was demonstrated.**
- **`scripts/async-demo.sh --help` ran the demo.** The script ignored arguments, so asking what it does
  stopped the configured broker and created synthetic payments. It now parses arguments before sourcing
  `.env` or touching anything, documents that a real run interrupts its broker, and has a test that
  proves the help and invalid-argument paths reach no operational command. The other three demo scripts
  still ignore arguments; that is out of this pass's scope and recorded in
  [verification.md](verification.md).
- **Two claims corrected.** The previous pass's record said the development stack was not started,
  stopped or written to; that was wrong and contradicted an incident disclosed in the same delivery,
  and it now carries the incident. A statement grouping V12 and V13 as sharing a validate-then-install
  window was also wrong about V12, which installs an ordinary validated foreign key.

## Checkpoint 10: public demo and release

Acceptance criteria, with the state of each. A checkbox is ticked only when the thing exists and was
verified; the record is in [verification.md](verification.md).

- [x] **Hosting assessed against a zero budget** using current official documentation, with the
      date and sources recorded, covering the whole stack's memory, storage, networking, expiry,
      billing requirements and whether zero spend is enforceable - [hosting.md](hosting.md).
- [x] **A hosting option chosen and its trade-offs stated**: Oracle Cloud Always Free (Ampere A1),
      the only option that runs the application, PostgreSQL and Kafka permanently with a zero that
      the account type enforces; idle reclamation, capacity and the single-instance limits stated.
- [x] **A public access model that keeps administration private**: one shared visitor merchant with a
      public credential, budgets enforced on the server on both API chains, private identities for
      the operator, `/v1` not a way around the restrictions, UI visibility never the authorization -
      `PublicDemoIntegrationTest`, and rehearsed through the real proxy.
- [x] **Bounded, repeatable synthetic usage**: commands per minute, replay jobs per hour and in
      flight, reconciliation reports per minute, payments per account; a truthful `429` before any
      work; an idempotent seed script producing genuine approvals, declines of both kinds, capture,
      void, refund, reversal, a candidate policy, a shadow divergence and a replay; reset as an
      explicit, confirmed operator action on the expendable sandbox only.
- [x] **A reproducible deployment bundle**: `deploy/` with environment separation and secrets outside
      source control, pinned versions, memory limits, persistent storage without the benchmark's
      tmpfs or `fsync=off`, three-way health, outage-tolerant startup, backup/restore, a
      migration-aware rollback, and `GET /actuator/info` naming the deployed commit and image.
- [x] **Demo scripts safe to ask questions of**: all four answer `--help` and refuse unknown
      arguments before touching `.env` or any command, proven with failing stubs.
- [x] **The packaged deployment verified in its deployed shape**: HTTPS, cookie attributes, login,
      logout, CSRF, visitor permissions and direct-API bypass attempts, cross-merchant and
      administrative denial, budget and authentication limits, the payment lifecycle and
      reconciliation, replay and shadow, restart persistence, broker outage and recovery, backup and
      restore round trip, rollback refusal and acceptance - all on disposable infrastructure.
- [x] **Release materials**: README, `deploy/README.md`, [release-notes.md](release-notes.md) with
      defensible resume bullets, [walkthrough.md](walkthrough.md), and a **playable recording** of the
      visitor path and a labelled operator segment, produced from the real application.
- [ ] **Public deployment working**: a live URL, verified by a smoke test against the actual page,
      running the reported revision. **Pending**: it needs an Oracle Cloud Free Tier account and a
      DuckDNS name, which are owner actions (card verification, a sign-in); the exact steps are in
      `deploy/README.md`. No URL is claimed until then.

The checkpoint's implementation is finished and the release is verified. It is not marked complete,
because its last deliverable does not yet exist.

### Corrections after review (v0.10.1)

A review of `v0.10.0`'s release configuration found eight findings, none in the financial core; the
corrective release fixes each with a regression, and the record is in
[verification.md](verification.md). Observed before the fix, on disposable infrastructure: an
anonymous request with a stray `Authorization` header resetting the authentication limiter; a
client-supplied `Forwarded: for=` choosing the address the limiter keyed on, end to end through the
packaged edge; `/actuator/health/async/asyncDelivery` public; `HEAD` bypassing the reconciliation
budget; eight concurrent replay requests creating three jobs against a limit of one; the broker
writing to `/tmp/kafka-logs` with an empty volume. Established from the scripts and then rehearsed:
an online database snapshot uncoordinated with the broker's committed offsets, and a restore that
started the newer image before an older one could be selected. Also corrected: an image guard that
accepted `stable`, and release notes that called the amd64 child digest the image's digest.

The public instance is **not ready for exposure** until the live checks in `deploy/README.md` have
been run on the actual host; that remains pending on owner access and available free capacity.

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
- **A return does not change a payment's status.** A fully refunded payment is still CAPTURED, because
  the capture happened and its journal is sealed evidence of it. What records the money coming back is
  the returned total, not a status. A reader expecting a REFUNDED state will not find one.
- **A post-capture reversal is refused after a partial refund**, rather than silently becoming a refund
  of the remainder. Returning what is left is always available as a refund; see
  [ADR 0007](adr/0007-returns-reconciliation-and-recovery.md) for why the alternative was rejected.
- **Reconciliation compares records, not reality.** Everything it reads lives in one database, so it
  detects independently maintained records disagreeing with each other and cannot detect a single
  mistaken transaction that wrote the same wrong amount everywhere. The report carries this limitation
  in its own response.
- **Reconciliation's per-payment checks are bounded; its balance checks are not.** An account's expected
  balance is derived over its whole history, because a partial derivation would be wrong rather than
  incomplete. That makes the balance query's cost grow with an account's history.
- **No backup or restore procedure has been demonstrated.** There is no tested recovery from a lost
  PostgreSQL volume and no recovery-point or recovery-time objective is claimed. Losing the database
  loses payments, ledger, idempotency records and outbox together.
- ~~The application does not start while the broker is unreachable.~~ **Fixed in the correction pass.**
  Listener containers no longer auto-start; `ListenerStarter` starts them after the context is up and
  retries while the broker is unreachable, so the payment API starts and serves with the broker's name
  unresolvable, and delivery resumes on its own without another restart. Covered by
  `UnresolvableBrokerStartupTest`, which starts a real context against a `.invalid` hostname, and end
  to end by `scripts/recovery-demo.sh`. Note the distinction that was previously blurred: a resolvable
  address with a closed port always constructed a consumer fine and was never affected; the failure was
  specifically name resolution.
- ~~Recovery of an undelivered event across a real process boundary is not demonstrated.~~ **Fixed in
  the correction pass.** `scripts/recovery-demo.sh` kills the application with a refund's event still
  pending and starts a new process against the same database with the broker still stopped. The broker
  being unavailable is what stages the pending event, so nothing races the dispatcher's 250ms poll.
- **The dashboard's account list is still bounded at 100 and still unpaged**, though it no longer hides
  the wrong end. It listed the 100 *oldest* accounts, ascending, with nothing said about truncation, so
  a merchant with more than 100 could not see or authorize against the account they had just created.
  Pre-existing since checkpoint 7 (`87e34cb`); it surfaced here because this checkpoint's fixtures
  pushed both the development database (104 accounts) and a single CI run past that bound, and it
  failed four browser tests. Now ordered newest first, and the screen says when the list is at its
  limit rather than presenting a truncated list as complete. **Paging is still absent**: a merchant
  with more than 100 accounts cannot reach the older ones from the dashboard at all. That remains the
  operator console's work, not the return lifecycle's.
- **Recovery of an undelivered event across a real process boundary is not demonstrated.** What is
  demonstrated is that the outbox row alone suffices: every piece of the dispatcher's in-process state
  is discarded and delivery still happens with the committed identity. A genuine restart was attempted
  for the checkpoint 9 demo and abandoned, because the two ways to stage it both fail - restarting
  during a broker outage does not boot (above), and holding the event by hand loses a race against the
  dispatcher's own 250ms poll on every attempt. The claim is withdrawn rather than approximated.
- **Return and reconciliation performance is unmeasured.** The figures in
  [performance.md](performance.md) predate both and describe authorize, capture and void only. No
  benchmark exercises a refund or a report, so nothing is claimed about either.
- **No settlement rails, merchant liquidity accounts, chargebacks, or foreign exchange.** Returns move
  money between the two synthetic accounts that already exist.
- **Still absent:** candidate policy promotion and any public deployment.

## Guidance for the next implementation session

Read [architecture.md](architecture.md), the [ADRs](adr/), and the [API contract](openapi.yaml) before
extending this. The remaining work on checkpoint 10 is the owner's: create the free-tier account and
the DNS name, run the three commands in `deploy/README.md`, smoke-test the URL, and record it here
and in the README with the commit `GET /actuator/info` reports. Nothing in the repository needs to
change for that.

Two things checkpoint 10 settled are worth keeping in mind. The reconciliation endpoint walks an
account's whole history, which is why the visitor's accounts are capped at 300 payments each and the
report is budgeted per minute for that identity; a longer-lived public instance should keep those
bounds rather than raise them. And the public-demo mode is the only place the visitor identity and
its budgets exist; every other environment runs without it, so a test that needs it enables it
explicitly, as `PublicDemoIntegrationTest` does.

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

Schema changes go in new Flyway migrations after V13. Do not edit an applied migration, and extend the
migration upgrade check when a new one backfills anything.

Keep generated credentials, `.env`, local database and runtime files under `.local/`, and build output
out of Git and the Docker build context. Compose ports listen only on loopback. Publish only features
and results that have actually been implemented and verified.
