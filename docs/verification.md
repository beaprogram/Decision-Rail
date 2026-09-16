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

[The remote run](https://github.com/beaprogram/Decision-Rail/actions/runs/34719181973) passed on revision `4754fab`: 213 backend tests, 41 frontend unit tests, and the 41 browser end-to-end tests that existed at that revision, against PostgreSQL 16 and a real broker, plus the image build, container startup, and both demos (12 and 27 checks). The browser step ran with retries at 0, so every one of those 41 passed on its first attempt. CI configuration in the repository is not itself evidence that a remote run has passed; inspect the workflow result for the revision you care about.

Test reports are written under `target/surefire-reports/`; the JaCoCo report is generated under `target/site/jacoco/`. CI uploads available reports when a verification job finishes, including on failure. Coverage is a diagnostic aid, not a substitute for meaningful assertions.

## Recorded local result

Recorded **2026-09-16 UTC** using Java **21.0.11**, PostgreSQL **16.15**, and Kafka **3.9.1**.

`./mvnw clean verify` passed **302 backend tests** with **0 failures, 0 errors, and 0 skipped**, alongside
**41 frontend unit tests** and **54 browser end-to-end tests** with retries disabled. The table below is
the checkpoint 8 record, kept because it is what the group breakdown was counted against; the checkpoint
9 additions are listed in the section that follows it. The **302** figure is the current total and the
**213** figure is a historical record of an earlier revision — they are not two counts of the same thing.

### The earlier recorded result (checkpoint 8)

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
**44 browser end-to-end tests** run by a real Chromium against the packaged application with
real PostgreSQL and Kafka, with retries disabled.

## What checkpoint 9 added

Backend tests went from 213 (checkpoint 8's recorded figure) to **282**, and browser tests from 44 to
**53**. The new coverage, and the failure each case exists to prevent:

| Case | Evidence | Failure prevented |
| --- | --- | --- |
| A partial refund, then the remainder | Three refunds of one capture; the account returns to its opening balance and nothing further is accepted | A budget that resets, or a remainder that can be refunded twice |
| A refund above what remains | 422 with the remaining amount stated; no return row, no journal, no balance change | An over-credit reported as a rejection after the money had already moved |
| Concurrent returns exceeding the budget | Eight simultaneous 300-unit refunds against a 1000-unit capture: exactly three commit, five are refused, the recorded total and the operation sum both read 900 | A cap that holds only when requests arrive one at a time |
| Concurrent retries of one key | Six simultaneous identical refunds return one return id; one operation, one journal, one credit | An idempotency key that is only idempotent when the retry is late enough |
| A changed fingerprint under a used key | Different amount, different reason, different payment and a different operation type each rejected | A key that silently answers for a command nobody sent |
| The same key text across merchants | Both merchants' refunds commit independently with different ids and amounts | A tenant boundary that a shared string can cross |
| A rollback after the financial writes | The surrounding transaction fails: no return, no journal, no returned total, no extra event, balance unchanged — and the key is free | A partial financial effect surviving a failure |
| A committed refund whose response was lost | The same key returns the original receipt; one return exists | Money returned twice because the caller never heard back |
| A historical receipt after later returns | The first of three refunds still reports its own totals, while the live view reports current ones | A durable receipt quietly becoming a live view |
| Historical payment responses after refunds exist | Authorize and capture keys still replay byte-identical payment responses | A new response shape breaking the decoding of old ones |
| Reversal after a partial refund | Refused with `PAYMENT_NOT_REVERSIBLE`; refunding the remainder still works, and the summary says why | A "reversal" that silently means something different depending on history |
| Cross-merchant refund, reversal and read | 404 for another merchant, 403 for the operations identity; no return written | Ownership enforced only by what the dashboard chooses to show |
| Malformed compensating journals | The database refuses a second journal for one return, a wrong amount, a reversed direction, a second capture journal, and any deletion of entries | "Balanced" accepted as sufficient, when a balanced pair can still name the wrong account |
| Database-level over-return | A direct UPDATE above the capture, and a return row disagreeing with the recorded total, are both refused | A cap that only exists in application code |
| Other payments on the same account | A refund credits the balance and leaves an unrelated hold exactly as it was; that authorization still captures | Refunded money silently reserved against work nobody requested |
| Ordered return events | Four events in sequence, the two refunds distinguished by their return block, each naming its operation | Two partial refunds indistinguishable because the status did not move |
| Refund events and shadow | No shadow task is enqueued during a bounded window after two refunds are delivered | A candidate's divergence rate depending on how often merchants issue refunds |
| A refund during a broker outage | Commits with the broker unreachable; intent durable and unpublished; delivered in broker-offset order after recovery | A financial command made to depend on a broker |
| Recovery with no in-process state | The dispatcher's lease, claim and breaker state are all discarded; the committed refund is then delivered from the outbox row alone, with its original identity. Deliberately **not** described as a restart: the JVM does not restart, and a genuine process boundary for an undelivered refund event is not demonstrated anywhere here | A "restart" test that only calls the same method twice, and a restart claim nothing actually crossed |
| Reconciliation of valid state | A captured, partly refunded account reports CLEAN with its scope, snapshot, checks and limitations | A report whose silence cannot be distinguished from a report that checked nothing |
| An inconsistent fixture | A skewed balance and a skewed returned total are each detected with expected, actual, delta, currency and references | A reconciliation that only agrees with itself |
| Reconciliation under concurrent load | Twelve reports while refunds and captures commit continuously: no findings | A snapshot so loose it invents discrepancies under ordinary traffic |
| A bounded population | A limit below the population reports INCOMPLETE with no findings, and INCOMPLETE_WITH_DISCREPANCIES when something was also wrong | "Nothing found" read as "nothing wrong" |
| Reconciliation as a mutation | Three consecutive reports over a known-broken account change no balance, no return, no journal | A report that repairs what it finds, destroying the evidence |
| The administrative view | ADMIN 200; merchant, other merchant and operations all 403 | A broader view reachable by adding a query parameter |
| Migration over existing records | Captured payments get their budget, uncaptured ones do not, stored responses are typed PAYMENT with unchanged bytes, historical event payloads gain no new fields, and a return works against a V1-era capture whose journal stays sealed | An upgrade that strands history or rewrites delivered events |

## The checkpoint 9 correction pass

Six defects found by review against `42fa608`, which had passed its whole suite. Each was reproduced
first, and the reproduction is kept as the regression.

| Defect | Reproduced as | Correction |
| --- | --- | --- |
| Return fingerprints concatenated a nullable reason, so a null reference rendered as `"null"` and an absent reason shared one identity with the literal text | `refund(payment, 1000, null, key)` then the same key with reason `"null"` returned **201 replaying the original receipt** instead of 409, on both refund and reversal | The reason is length-prefixed: absent is `-`, present is `<length>:<value>`. Only the return fingerprints changed; authorize, capture and void contain no optional free text and were never ambiguous, so rewriting them would break every stored key for nothing |
| A return event's operation type was checked against the set of known types but never against the event carrying it, and the full-capture rule keyed off the nested type — so mislabelling also skipped the check that would have caught it | A `payment.reversed.v1` carrying a 1000-unit `REFUND` against a 2500 capture parsed cleanly | One-to-one correspondence required, and the full-capture rule now keys off the event type |
| Reconciliation summed capture debits and return credits without comparing currencies, so an account whose currency disagreed with its payments reported **CLEAN** | Changing only `accounts.currency` to USD on a CAD account with a valid CAD authorization | The disagreement is reported, and the arithmetic is refused rather than performed across currencies |
| Return history loaded the 200 oldest returns with no pagination and no metadata, while the dashboard showed the list length as the count | 201 refunds: totals counted 201, history contained 200, the newest operation and its journal were unreachable | Keyset paged newest first on the per-payment sequence number, with `returnCount` separate from the page |
| The wrong-amount and wrong-direction journal tests inserted a second journal for a return that already had one, so uniqueness rejected them before the rule they named | Both assertions passed for the wrong reason | Fixtures now build a return with no journal yet, each case asserts the specific constraint or trigger message, and a positive control proves the fixture reaches the validation |
| The application would not start when the broker's name did not resolve | A context pointed at a `.invalid` hostname failed to load | Listeners start off the startup path and retry |

### Idempotency compatibility

Changing a fingerprint changes the hash stored against every key written before it, so a legitimate
retry of a return that already committed would have started failing as a conflict. The old fingerprint
is still computed and compared as a fallback — but only when the **stored receipt** confirms the reason
really was the one being sent now.

That second condition is what stops the fallback carrying the defect forward. The ambiguity was between
an absent reason and the literal `"null"`, and the receipt records which of the two committed, so a
request carrying one can never replay a receipt written for the other whichever encoding produced the
stored hash. A key claimed but never completed has no receipt to confirm anything and nothing committed
under it either, so it is refused like any other mismatch.

No migration was needed: the discriminator is data the rows already carry. Tests cover a retry of a
pre-fix key replaying its receipt, a genuinely different request against that same legacy key being
refused, and the literal `"null"` being refused against a legacy absent-reason receipt.

### What the currency checks can and cannot conclude

They can conclude that records disagree about what currency an account holds, and they name the
account, the currencies, and how many payments, journals and returns disagree. They **cannot** conclude
anything about that account's monetary totals while the disagreement stands, and the report says so
with `ACCOUNT_TOTALS_NOT_DERIVABLE` carrying no expected or actual value. Nothing is summed across
currencies, converted, filtered away, or repaired.

The application already prevents this at authorization time; the schema does not. A foreign key tying
`payments(account_id, currency)` to `accounts(id, currency)` was considered and deliberately not added,
for the same reason as the returned-total equality: it would make the corruption impossible and the
detection impossible to demonstrate, leaving a check nothing could exercise. See
[ADR 0007](adr/0007-returns-reconciliation-and-recovery.md).

### Restart recovery across a real process boundary

`scripts/recovery-demo.sh`, 16 checks on a stack it creates and destroys. A refund commits with the
broker stopped; the application is killed with SIGKILL, leaving what a crash leaves; a new process
starts against the same database **while the broker is still unavailable**, reports readiness UP and
asynchronous delivery DEGRADED with `consumersRunning: false`, and takes payments. The broker returns,
the pending event publishes in per-payment order with the identity committed before the restart, the
consumers restart themselves, and the original receipt still replays under its key with one return
operation, one journal and one credit.

The broker being unavailable is what stages the pending event, which is why this no longer races the
dispatcher's 250ms poll: with the broker's name unresolvable the dispatcher cannot publish at all.

### One CI failure I could not explain, and what I did about it

The correction pass's first CI run failed one browser test, `isolation.spec.ts`'s "a slow response for
the previous identity never lands on the next one". It had passed 54 of 54 locally on both the
development database and a fresh one.

The failing assertion was `expect(page.getByText('23.00')).toHaveCount(0)` — the previous merchant's
payment amount must not appear after switching identity. CI reported one matching element for the full
fifteen-second poll.

**The retained evidence does not corroborate that.** The ARIA snapshot, the trace's DOM snapshots and
the failure screenshot contain no `23.00` anywhere; the page shows the other merchant's three payments
at 22.00, 3.00 and 20.00, and the server reports three matching. The payment-id assertion immediately
above passed, and the trace confirms that assertion is sound rather than vacuous — the rendered
identifier's text content is the full UUID. Running the whole suite against a throwaway database did
not reproduce it.

So I do not know what that element was, and I am not going to invent an explanation. What I changed is
the evidence, not the conclusion: both assertions are now scoped to the results table, the amount is
anchored to a whole cell, and a new assertion requires the number of rendered rows to equal the count
the server reports for the signed-in identity. A page-wide substring match is a poor detector here —
`23.00` also matches a legitimate `123.00`, and this project has now had three separate false positives
from exactly that (`Capture` matching "Reverse the capture", `CREDIT` matching "credited",
`4 returns` matching "Showing 4 of 4 returns"). The property under test is unchanged and better
covered; if a real leak exists, a scoped row assertion will name it instead of leaving a number nobody
can trace.

### The account list, and why this suite found it

Four browser tests failed in CI on a fresh database, and locally on the development one, with
`did not find some options` on the account picker. The cause was not the tests: the account list
returned the 100 *oldest* accounts, so any account a test had just created was off the end once the
shared database held more than a hundred. Both databases had - CI accumulates them within one run,
since `verify`, the demos and the browser suite share it.

Two things changed, and one deliberately did not. The list is now ordered newest first, because under
a bound the ordering decides what disappears and hiding a just-created account is the harmful
direction. The screen now says when the list is at its limit instead of presenting a truncated list as
complete, which is what the payment search already does with `matchedCountCapped`. Paging was not
added; it is the operator console's work and is recorded as still open.

The browser helper also stopped selecting an account by position. It now creates its own funded
account per authorization, which is what the rest of the suite already does and what makes each test
independent of every other test's leftovers. The recorded run is against the development database at
104 accounts - the population that exposed the problem - not a fresh one.

### Migration evidence against a real, populated database

The throwaway-database check above proves the upgrade applies to constructed records. It was also
applied to the local development database, which had accumulated real history across checkpoints 1
to 8 — **346 payments, 65 accounts, 55 journals and 439 stored idempotent responses**:

| After applying V10 and V11 | Result |
| --- | --- |
| CAPTURED payments given a return budget | 55 of 55 |
| Non-captured payments wrongly given one | 0 |
| Stored idempotent responses typed | 439 of 439, all `PAYMENT` |
| Existing journals classified | 55, all `CAPTURE` |
| Historical event payloads rewritten | none |

Both migrations reported success, and the three demos then ran against that same database.

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
| Sign-out while the request is still in flight | The workspace is unmounted before the request is delivered, and the screen does not claim the session is closed | Tenant data left on screen for as long as the server takes to answer, or a sign-out announced before it happened |
| A capture or void retried after a first attempt that never reached the server | The payment is re-read after the retry: status, funding, journal, actions and account balances all reflect the completed command | A screen still offering Capture on a payment that has just been captured, because the command's own response stood in for reading it |
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

### Corrections verified in the workspace-state pass

Two defects the previous pass left, both about what the screen shows after a command rather than about
what the server does. Each was reproduced against the unfixed build before the fix.

| Correction | Regression test | Reproduced before the fix |
| --- | --- | --- |
| The protected workspace stayed on screen while sign-out was in flight | `session-transitions.spec.ts`, holding the DELETE before delivery | The merchant navigation resolved to 1 element on 34 consecutive polls across the full 15 seconds the request was held, so the workspace was mounted for the whole time the session was still alive |
| A retried capture or void never re-read the payment | `command-recovery.spec.ts`, capture and void | `page.waitForResponse` timed out after 15s waiting for a `GET /ui/payments/{id}` that never happened; the screen kept offering Capture on a payment that had just been captured |

Both retry tests block the first request **before** it reaches the server, which is what distinguishes
them from the existing committed-but-response-lost case. There the payment is already CAPTURED when the
first recovery read runs, so that read alone makes the screen correct and a retry that re-reads nothing
still looks right. Here the first attempt never ran, the recovery read legitimately sees AUTHORIZED, and
only a fresh read after the retry can make the screen right.

All of it is integration evidence against the packaged application, real PostgreSQL, and a real broker.
The interceptions decide only whether a real request is delivered or a real response arrives; no
response is fabricated, and every assertion about money is checked against the database as well as the
screen.

### Telemetry verified in the checkpoint 8 pass

| Property | Check | What it would catch |
| --- | --- | --- |
| Trace context survives the request | `TelemetryCorrelationTest` | Correlation held only in a thread, which cannot exist when delivery happens later |
| It reaches the broker as a header, not in the payload | `TelemetryCorrelationTest` | Event identity changing between attempts and invalidating every recorded fingerprint |
| A publication is a distinct span under the originating trace | `TelemetryCorrelationTest` | A retry that looks like a longer first attempt, or a trace that leads nowhere |
| An event written before correlation existed still delivers | `TelemetryCorrelationTest` | An upgrade treating missing telemetry as a defect and stalling a committed event |
| An idempotent replay points at the original without overwriting it | `TelemetryCorrelationTest` | A retry claiming to be the request that first performed the command |
| A collector that is not there changes no payment outcome | `TelemetryCorrelationTest` | Telemetry becoming a dependency of taking payments |
| Only well-formed hex becomes a traceparent | `OriginTraceTest` | Unvalidated text assembled into an outbound header |
| The correlation columns are optional and constrained | `MigrationUpgradeTest` | A backfill that was never written, and malformed values reaching a header later |

**A regression this pass introduced and the existing suite caught.** Moving the consumer's
acknowledgement into a `finally` block, while adding a timer around it, made the consumer acknowledge a
record whose quarantine row had failed to persist — turning a recoverable storage outage into lost
events. `MalformedEventPartitionTest` failed on it immediately. The acknowledgement is back on the
non-throwing path only, with a comment saying why it must never be moved, and the timer stayed in the
`finally` where it belongs. This is the value of keeping infrastructure tests that assert on offsets
rather than on happy paths.

### Corrections verified in the checkpoint 8 correction pass

| Finding | Confirmed how | Regression check |
| --- | --- | --- |
| The collector read aggregate metrics under a field saying warmup was excluded | A probe with warmup samples at 1000 and measured at 10 produced an aggregate average of 406; a live 20s run at 10/s gave 200 measured samples against a 276-sample aggregate whose maximum came from warmup | `benchmark/collector-check.sh`, which fails on every reported figure if the summariser reads aggregates, and refuses a summary whose aggregate is present without its measured sub-metric |
| A sub-metric's rate divides by the whole run | The same probe reported 6 measured hits in a 2.07s run as 2.90/s | Rates are computed as count over the declared window; the check asserts the arithmetic |
| Sampling was assumed, not carried | Inspection: identifiers were stored without the decision, flags were hard-coded to `01`, and delivery rebuilt its parent with `sampled(true)` | `TraceSamplingTest` at probabilities 1 and 0 and with an explicitly unsampled upstream parent, reading real spans from an in-memory exporter |
| The committed-command counter counted attempts | Reproduced: with the inline increment, a rolled-back command left the counter at 1.0 with no payment row | `CommittedCommandMetricTest` |
| Shadow evaluation had no correlation | Inspection: `ShadowTaskConsumer` and `ShadowWorker` had no tracing at all | `ShadowCorrelationTest`, which asserts the evaluation span on a detached thread is parented to the enqueue span in the command's trace |
| The backlog peak was sampled after the load stopped | Inspection: the counter was initialised after the generator exited. The corrected harness observes 513–879 at the sustained rate where the old one reported 0–3 | Backlog is sampled on a fixed interval with timestamps; the per-sample timeline is committed beside each result |

**A regression this pass introduced and its own tests caught.** Three of the new telemetry tests failed
first time because they were scoped to the wrong population against the shared test database: a worker
claimed another test's leftover task, three nested contexts shared one mutable topic field, and a
"legacy row" was cleared without its sampling column and was refused by the new constraint. The last of
those was the constraint working correctly. All three are the same class of mistake as the finding they
were written for.

### The benchmark collector contract

Two checks, both in CI, covering a gap that a single one cannot.

| Check | Establishes |
| --- | --- |
| `benchmark/collector-check.sh` | What the summariser does with a summary it is given: reports the measured population, computes rates over the declared window, and reads a backlog timeline correctly — markers apart from observations, a failed query as unknown rather than zero, observed spacing apart from the configured delay. |
| `benchmark/k6-attribution-check.sh` | What k6 actually puts in that summary. Runs the pinned image against a workload that really drops iterations, with no application behind it. |

The second exists because the first cannot see the defect it was written for. A fixture asserting on
`dropped_iterations{phase:measured}` was asserting on a population k6 never produces: executor-dropped
iterations carry global run tags and the built-in scenario tag, not a scenario's custom tags. The
fixture passed, the harness reported zero drops, and runs that dropped hundreds were published as
dropping none.

Observed against the pinned 0.55.0 image: aggregate 284 drops, `{scenario:measured}` 162,
`{scenario:warmup}` 122, `{phase:measured}` **0**, `{phase:warmup}` **0**. Restoring the old selector
makes the summariser refuse the run rather than report a zero.

### A failpoint ordering race, found and fixed

`ShadowStaleWorkerTest.aStaleWorkerCannotRecordATerminalFailureOverTheNewOwnersClaim` failed once in CI
on revision `3337b00` and passed on a re-run of that same revision. The cause is now confirmed, and it
is **not** the shared-database cycle-count hypothesis recorded earlier — that explanation is withdrawn.

`DeliveryFaults.clear()` released every gate and *then* cleared the injected failure state. A worker
parked inside a failpoint wakes on that release and immediately reads the failure state, so the test's
"disarm everything so the rightful owner can finally succeed" handed the rightful owner a fault set that
had not been cleared yet. It failed terminally, and the cycle evaluated nothing.

**Reproduced against the real class**, no replica involved: a worker parked at a gate and released by
`clear()` observed `Simulated candidate policy evaluation failure` on attempt 21 of 50.

The correction is an ordering guarantee rather than a narrower window. `clear()` disarms first and
releases second, and `CountDownLatch.countDown()` happens-before the return from `await()`, so every
disarming write is guaranteed visible to the released worker.

| Evidence | Old ordering | Corrected |
| --- | --- | --- |
| Real class, 50 parked-worker rendezvous, uncontrolled | failed at attempt 21 | 50/50 pass |
| Real methods composed in the wrong order, interleaving forced | fails every time | n/a |
| `ShadowStaleWorkerTest`, 10 consecutive local runs | 10/10 pass | 10/10 pass |

The integration test passes ten times in a row under **both** orderings on this machine: the window is
too narrow to hit here, which is exactly why it read as flakiness and why the unit-level rendezvous is
what pins it. The one CI failure remains the only observation of it at integration level.

`release()` deliberately still leaves faults armed — releasing one worker into an armed failure is how
the stale-worker path is expressed, and folding disarm into release would make that interleaving
unwritable. Cleanup now also joins outstanding test workers rather than only releasing them, so a failed
assertion cannot leave one running into the next test.

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

Both demo scripts passed against the packaged application in the local Compose stack: `scripts/demo.sh` (**12 HTTP checks**) and `scripts/async-demo.sh` (**27 checks**). The browser suite passed **44 of 44** against that same packaged application, on first attempt with retries disabled. The asynchronous demo observed a payment authorized with the broker container stopped, the breaker OPEN with `/actuator/health/async` DEGRADED while readiness stayed UP, delivery resuming after restart with the original event id and the breaker closing again, a projection applied count that stayed at 1 after the same event was delivered twice more, a replay job whose membership stayed at 4 inputs when a later payment committed, a 409 when a policy version id was rebound to different content, and a shadow divergence (live APPROVE, candidate DECLINE at score 60) after which the balance was unchanged and held funds moved only by the new authorization's own hold.

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
| Migration upgrade with existing records | Sequence backfill, delivery status, and payload identity are correct; money and seals intact. The return budget and response kind are backfilled, and historical event payloads gain no new fields. | An upgrade that strands committed history or weakens an existing guarantee. |
| Return budget under concurrency | Eight simultaneous refunds against one capture commit exactly the three the budget allows. | A cap that holds only for sequential requests. |
| Compensating journal validity | A return journal must match its operation on amount, currency, merchant, payment and both ledger accounts in the correct direction. | A balanced pair that records the wrong amount or moves value the wrong way. |
| Reconciliation as a read | Repeated reports over a broken account change no balance, return, or journal. | A report that repairs what it finds, destroying the evidence. |
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
| Telemetry exporter unavailable | Commands commit and readiness is unchanged with no collector configured. | Observation becoming a dependency of taking payments. |
| Gauge source unavailable | Backlog gauges report no data rather than zero. | A false zero backlog reading identically to a healthy one. |
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
./scripts/demo.sh          # 12 checks: the transactional lifecycle
./scripts/lifecycle-demo.sh # 26 checks: refunds, reversal, compensating journals, reconciliation
./scripts/recovery-demo.sh  # 16 checks: restart recovery, on a stack it creates and destroys
./scripts/async-demo.sh    # 27 checks: delivery, outage, replay, shadow. Run last: it stops the broker.
```

`lifecycle-demo.sh` creates its own synthetic account rather than spending a seeded demo balance, and
it deliberately skews that account's balance to show a discrepancy being detected before putting it
back. Reconciliation itself never writes; the undo is the script undoing its own fixture, and the
script asserts the account reconciles again afterwards.

The first validates an externally observable sequence and checks the final synthetic balance. Run it against an idle demo account; other writers can legitimately change the balance while the script is checking it. The script is complementary to the integration suite and is not a concurrency or performance benchmark.

## Claims deliberately deferred

No throughput, tail latency, recovery-time, availability, or production-readiness claims are published. A future performance milestone must record hardware, configuration, workload, duration, concurrency, error rate, and latency distribution alongside its results.

Specific to this phase:

- **Not exactly-once.** The verified guarantee is at-least-once delivery with idempotent consumer effects. The tests demonstrate duplicate deliveries producing one effect; they do not demonstrate, and the design does not provide, exactly-once processing across PostgreSQL and Kafka.
- **Not highly available.** The broker is a single node with replication factor 1. Broker durability under replica failure is untested because the configuration cannot provide it.
- **No fraud accuracy metrics.** No labelled outcome data exists for synthetic traffic, so precision, recall, and false-positive rates are not computed anywhere.
- **Replay timings are observations, not benchmarks.** `timingMethod` in every report states exactly what was measured: in-process evaluation only, single JVM, no warmup control or repetition.
- **Breaker and backoff defaults are not tuned from measurement.** They are reasonable values for a development stack. The retry budget is documented so it can be reasoned about, not because it was derived from observed production behaviour.
- **No backup or restore has been demonstrated.** There is no tested recovery from a lost PostgreSQL volume, and no recovery-point or recovery-time objective is claimed anywhere. Losing the database loses payments, ledger, idempotency records and outbox together. What *is* demonstrated is recovery across a real process boundary: a refund committed with the broker stopped survives the application being killed, and a new process started while the broker is still unavailable delivers it in order with its original identity. That is `scripts/recovery-demo.sh`, on its own disposable stack. It is still not a backup-and-restore claim: the database itself is never lost in that scenario.
- **Reconciliation compares records, not reality.** Every record it reads lives in one database. It detects independently maintained records disagreeing with each other; it cannot detect a single mistaken transaction that wrote the same wrong amount to the payment, its journal and the balance together. The report states this in its own `limitations` field rather than leaving it to documentation.
- **Return performance is unmeasured.** The published throughput figures were taken before refunds and reconciliation existed and describe authorize, capture and void only. No benchmark exercises a refund or a reconciliation report, so nothing is claimed about either. The historical artifacts remain historical.
