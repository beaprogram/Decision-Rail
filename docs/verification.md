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

`verify` also typechecks, lints, unit-tests and builds the dashboard, using a Node toolchain the build
downloads and pins, and copies the bundle into the jar. Use `-Dskip.frontend=true` for fast Java-only
iteration; the resulting jar then has no dashboard, which is why it is not the default.

### Browser tests

These need the application actually running, because they drive a real browser against it:

```bash
# A running stack with the dashboard in it.
DB_PORT=55434 KAFKA_PORT=19093 BACKEND_PORT=8080 docker compose up --build -d

cd frontend
npm ci
npx playwright install chromium
set -a; source ../.env; set +a          # the tests use the real identities
COMPOSE_FILE_PATH=../compose.yaml npx playwright test
```

The suite stops and restarts the broker container to produce a real outage, which is why it needs the
Compose file. The application deliberately exposes no endpoint that could do that. Set
`SCREENSHOT_DIR` to collect screenshots of each completed screen.

**Retries are zero, locally and in CI.** `playwright.config.ts` sets `retries: 0` unconditionally. A
browser test that only passes on a second attempt is reporting a real defect in the application or in
itself, and retrying would hide exactly the first-attempt failures this suite exists to catch. CI runs
the same command with the same setting, so a green CI run means the suite was green on first attempt.
Some tests create fixtures of their own — an account, a candidate policy — rather than asserting against
the seeded demo balances, so that a test which moves money never depends on, or disturbs, the state the
walkthrough uses.

Those are also the test profile's defaults, so `./mvnw verify` alone works once the stack is up.

To reset the stack between runs:

```bash
docker compose -f compose.test.yaml down -v && docker compose -f compose.test.yaml up -d --wait
```

**Use a dedicated database and broker.** Integration tests deliberately install database triggers that inject storage failures, publish synthetic events, and create a throwaway database for the migration upgrade check. Never point the suite at production, at a development database, or at anything another process is writing. The test stack uses `tmpfs` for PostgreSQL so a `down -v` leaves nothing behind.

Tests share one database on purpose, and append-only history is retained so the suite can run repeatedly. Each test therefore scopes its assertions to the payments and topics it created rather than to global counts. Fault injection is targeted at a named payment for the same reason: a global switch would fail unrelated events and make one test's observations depend on what another left behind.

CI runs the same `compose.test.yaml` stack rather than workflow service containers, so the documented local command and the remote build exercise identical infrastructure. It then builds the Docker image, starts the container, and runs both demo scripts without publishing the image.

[The remote run](https://github.com/beaprogram/Decision-Rail/actions/runs/34719181973) passed on revision `4754fab`: 213 backend tests, 41 frontend unit tests, and 41 browser end-to-end tests against PostgreSQL 16 and a real broker, plus the image build, container startup, and both demos (12 and 27 checks). The browser step ran with retries at 0, so every one of those 41 passed on its first attempt. CI configuration in the repository is not itself evidence that a remote run has passed; inspect the workflow result for the revision you care about.

Test reports are written under `target/surefire-reports/`; the JaCoCo report is generated under `target/site/jacoco/`. CI uploads available reports when a verification job finishes, including on failure. Coverage is a diagnostic aid, not a substitute for meaningful assertions.

## Recorded local result

Recorded **2026-09-12 UTC** using Java **21.0.11**, PostgreSQL **16.15**, and Kafka **3.9.1**.

`./mvnw clean verify` passed **213 backend tests** with **0 failures, 0 errors, and 0 skipped**:

| Group | Tests | Infrastructure |
| --- | --- | --- |
| Domain and contract units | 107 | None: pure evaluation, breaker state machine, backoff, policy validation and bounds, event contract bounds, publisher acknowledgement semantics |
| Architecture rules | 4 | None |
| PostgreSQL integration | 48 | Real PostgreSQL, including health status codes, worker takeover races, and the migration upgrade check |
| PostgreSQL and Kafka integration | 27 | Real PostgreSQL and a real single-node broker |
| Browser authentication | 15 | Real HTTP and a real cookie jar, not MockMvc: cookie attributes, CSRF round trips across login and logout with no intervening request, and session identity only exist once a real client and container exchange headers |
| Dashboard read APIs | 12 | Real PostgreSQL: search, filters, keyset paging, timeline, failed events, and tenant isolation on every route |

The frontend adds **41 unit tests** covering exact money conversion, response classification at
the transport boundary, idempotent command submission and replay, and funding presentation, and
**41 browser end-to-end tests** run by a real Chromium against the packaged application with
real PostgreSQL and Kafka, with retries disabled.

### Browser verification

These are integration tests, not unit tests with a fake network. Responses are intercepted in five
places, and in each the response being withheld is the server's own: one is held open while the signed-in
identity changes, one sign-out is stopped before it reaches the server, and three commands are allowed to
reach the server and commit before their reply is dropped. Nothing is stubbed; an interception decides
only whether a real reply arrives, because "the server did the work and you never found out" cannot be
produced any other way.

| Case | Evidence | Failure prevented |
| --- | --- | --- |
| Sign in, wrong password, sign out, expiry | The refusal names neither half of the credential; expiry clears the screen and asks again | A failed login reported as an expired session, or tenant rows surviving a sign-out |
| Two-merchant isolation including a late response | A held response for the previous identity never lands on the next one's screen | One tenant's data decorating another's dashboard after a switch |
| Authorization and its stored explanation | Outcome, score, policy version and reason contributions, read from the stored decision | A recomputed explanation that no longer matches what was applied |
| Capture and void on separate eligible payments | Confirmation restates the concrete payment and amount; the journal appears after capture | A confirmation that says only "are you sure" |
| Search, filters, paging, timeline, deep-link refresh | Filters live in the URL and survive a reload; pages do not overlap | A shareable view that resets, or paging that skips rows |
| Candidate registration and validation errors | The server's JSON path is shown for a rejected definition | An input error presented as a server fault |
| Replay to completion and comparison | Baseline and candidate explanations side by side, denominator stated | A completed job whose report was fetched while it was still running |
| Shadow divergence | Candidate DECLINE alongside a live APPROVE, with the payment untouched | A candidate outcome mistaken for a real one |
| Failed-event inspection and redrive | An isolated failed event created during an outage, redriven from an explicit selection | An empty filter becoming "redrive everything" |
| Rejected CSRF mutations | A cookie-authenticated POST with no token, and with a wrong one, are both refused | Another origin driving a payment with the user's session |
| Broker outage presentation | Payments authorize and read normally while delivery reports DEGRADED | A broker outage presented as a payment failure |
| Loading, empty, unavailable, conflict states | Each has its own presentation; a null rate reads "Not available", never zero | An absent measurement rendered as a real one |
| Narrow width, keyboard, labels, dialogs | No horizontal page scroll at 390px, every control labelled, focus visible, Escape closes without acting | A console that cannot be operated without a mouse |
| Sign in, out, and in again on one page, with no reload | Each transition leaves a usable CSRF token, proven without navigating, because a reload obtains one as a side effect | A sign-in that only works after a reload, and a reload concealing it |
| Sign out immediately after signing in, as an identity with no workspace | The sign-out is accepted although no workspace request was ever made | Authentication that depends on an unrelated data request to work |
| A sign-out that never reaches the server | Reported as unconfirmed with a retry, not as a completed sign-out; the retry reconciles | A live session presented as destroyed |
| A sign-out that commits but loses its response | Reconciled against the server and reported as signed out | An unresolved notice for work that actually completed |
| A command whose response is withheld after the server commits | The retry resends the submitted bytes under the original key after the form was edited, and the database shows one payment, one job, one journal | A retry that means something different from the command it is recovering |
| CSRF still required after a transition | A mutation with no token is refused once signed in again | A transition quietly lowering protection |

Every test from the earlier milestones is still present and passing. Two assertions were **strengthened**, not relaxed: two policy checks previously accepted any `IllegalArgumentException` for an invalid document and now require the structured validation failure with its JSON path, because the old expectation encoded the defect that such inputs were reported as server faults.

### Corrections verified in the asynchronous-path pass

Each correction was demonstrated by a test that failed before it and passes after.

| Correction | Regression test | Failures before |
| --- | --- | --- |
| Unhealthy health statuses returned HTTP 200 | `HealthStatusMappingTest` | 3 |
| Stale shadow worker committed a comparison over the new owner's claim | `ShadowStaleWorkerTest` | 2 |
| Obsolete replay owner committed results and item transitions | `ReplayTakeoverTest` | 1 |
| Event validation accepted values the consumer's tables reject | `EventContractBoundsTest` | 6 |
| A refused record blocked its Kafka partition indefinitely | `MalformedEventPartitionTest` | 1 (40s timeout: the valid record behind it was never processed) |
| Invalid policy definitions returned HTTP 500 | `PolicyValidationBoundaryTest` | 8 |

The partition-blocking case was confirmed by temporarily restoring the original currency check: the valid record queued behind the refused one was still unprocessed after 40 seconds, which is the blocked partition. With the correction it is processed.

### Corrections verified in the operator-console pass

A second review pass against the checkpoint 7 work. Each correction has a test that failed before it and
passes after, and the failure counts below are the ones actually observed against the unfixed code.

| Correction | Regression test | Failures reproduced before the fix |
| --- | --- | --- |
| A CSRF token was not available after an authentication transition | `BrowserSessionIntegrationTest` (4 added cases) | 1 failure, 3 errors of 15. The login response's only `XSRF-TOKEN` header was `XSRF-TOKEN=; Max-Age=0`, so the cookie jar held no token at all and the three transition cases threw rather than asserting |
| The same defect through a real browser | `session-transitions.spec.ts` | A network probe against the unfixed build recorded `GET /ui/identity -> 200`, `POST /ui/session -> 200`, `DELETE /ui/session -> 403`, with only `JSESSIONID` present afterwards and the UI still showing signed out |
| A failed sign-out was reported as completed | `session-transitions.spec.ts` (sign-out never reaching the server, and committed-but-response-lost) | The unfixed client set anonymous in a `finally` block and the rejected promise was discarded, so neither case could be distinguished |
| A retry rebuilt the command from current form state | `command.test.ts`, `command-recovery.spec.ts` | `AssertionError: expected 9900 to be 2500` — the retry sent the edited amount under the original key |
| An unreadable successful response was treated as success | `client.test.ts` | 9 of 13 failed, including a genuine hang (`Test timed out in 5000ms`) for a body that never completes, because the deadline ended at the headers |
| Identity fencing ran after side effects | `client.test.ts` | Included in the 9 above: a stale 401 broadcast session expiry, and a stale body failure wrote into the new identity's state |
| Funding labels were derived from the absence of a failure code | `funding.test.ts` | 7 of 7 failed, including a policy-declined payment labelled "Funds reserved" |

The CSRF reproduction was run twice, the second time from a clean build, after an IDE-written class file
in `target/classes` produced a misleading result. Treat a surprising test outcome as suspect until the
build it came from is known to be Maven's own.

### Test infrastructure notes

Two environmental details were corrected while adding these tests, both test-only:

- **The disposable test database now allows 400 connections.** The suite keeps one cached Spring context per test configuration for the whole run, each with its own pool, and the added classes pushed the total past the server's default of 100. The test profile's pool is also reduced to 6, which is ample for its concurrency checks.
- **A freshly enqueued row is not claimed by a cycle run in the same millisecond.** The row takes its due time from the database clock while the worker compares it against the JVM clock, and the two differ by a few milliseconds in a container. Tests that drive a single cycle backdate the due time rather than depending on that agreement. Production is unaffected: the workers poll continuously.

- **A page's own `fetch` cannot replay a cookie.** `Cookie` is a forbidden header name, so a browser
  drops it silently. Two sign-out tests originally checked that a destroyed session's cookie no longer
  authenticates by calling `fetch` from the page with that header, which the browser removed; the request
  then went out with the jar the page already had, which after a sign-out is empty. Both returned 401
  whether or not the server had destroyed anything. They now replay the cookie from an API request
  context outside the browser, where the header is honoured.

- **Killing a run mid-test can leave fault injection installed.** The tests that inject storage failures
  create a trigger and drop it in a `finally` block, which a terminated JVM never reaches. A leftover
  trigger on `consumer_quarantine` blocks every quarantine insert, and since holding the partition is the
  correct response to a storage failure, the symptom is four unrelated-looking consumer timeouts rather
  than an error naming the cause. Check `SELECT tgname FROM pg_trigger WHERE NOT tgisinternal` for a
  `test_` prefix, or reset the stack with `down -v`, before believing such a failure.

Both demo scripts passed against the packaged application in the local Compose stack: `scripts/demo.sh` (**12 HTTP checks**) and `scripts/async-demo.sh` (**27 checks**). The browser suite passed **41 of 41** against that same packaged application, on first attempt with retries disabled. The asynchronous demo observed a payment authorized with the broker container stopped, the breaker OPEN with `/actuator/health/async` DEGRADED while readiness stayed UP, delivery resuming after restart with the original event id and the breaker closing again, a projection applied count that stayed at 1 after the same event was delivered twice more, a replay job whose membership stayed at 4 inputs when a later payment committed, a 409 when a policy version id was rebound to different content, and a shadow divergence (live APPROVE, candidate DECLINE at score 60) after which the balance was unchanged and held funds moved only by the new authorization's own hold.

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
| Unhealthy health status codes | DOWN and OUT_OF_SERVICE answer `503`; DEGRADED answers `200`. | A readiness probe reporting failure in its body while returning a success code. |
| Health indicator with an unreadable dependency | The indicator reports DOWN with a reason instead of throwing. | One failing indicator replacing the whole health document with a generic error. |
| Stale shadow worker after takeover | No comparison, no task change, and the new owner's result is the only one stored. | A task marked successful while its stored comparison records a failure. |
| Obsolete replay owner | No results, no item transitions, and no completion. | Totals and item state describing work nobody was authorised to do. |
| Replay job with a batch in flight | The job is skipped by other workers until the batch resolves. | Completion decided against totals an outstanding batch is about to change. |
| Completed replay job totals | Every published count equals the aggregate of its durable result rows. | A finished job whose report disagrees with its own stored results. |
| Event value outside a consumer column's bounds | Quarantined in bounded time; the next record on the partition is processed. | An unprocessable record retried forever as though it were a transient outage. |
| Quarantine write failure | The offset is not advanced, and the record is quarantined once storage recovers. | Acknowledging work that was never recorded. |
| Invalid policy definition | `400` with the offending JSON path, and no version row persisted. | An input error reported to the caller as a server fault. |
| Cross-tenant replay and shadow access | Another merchant's job, results, and comparisons read as absent. | Cross-tenant disclosure through new endpoints. |
| Privileged route matching | Merchants and the metrics account are refused admin routes; admin is refused merchant routes. | A privileged path falling through to the broad merchant rule. |
| CSRF token across an authentication transition | Login and logout responses each carry a usable token; the next mutation is accepted with nothing in between. | A transition that leaves the page unable to make its next request, or a reload hiding it. |
| A sign-out the server never confirmed | Data is cleared immediately; the outcome is reported as unconfirmed and reconciled when the server answers. | A session that still exists being presented as destroyed. |
| A retried command after the form changed | The original method, path, body and key are resent byte for byte. | A retry that recovers a different command from the one it claims. |
| A successful response whose body cannot be read | Classified indeterminate; the snapshot and key are kept for retry. | An unknown outcome recorded as a success with no data. |
| A response arriving after the identity changed | Rejected before any identity-sensitive side effect, and again after the body is read. | A previous identity's failure expiring or corrupting the new one's state. |
| Funding presentation per lifecycle state | Reserved, captured, released, or none, derived from status; a funding decline keeps the separate risk outcome visible. | Held funds claimed for a payment that never reserved any. |

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
