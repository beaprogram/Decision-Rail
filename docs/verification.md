# Verification strategy

## Render/Aiven adaptation — local evidence, 2026-09-19 UTC

These local checks were performed before publication in worktree `codex/render-aiven`, based on
`01c49f6`. The rehearsal image identified itself as `01c49f6-render-worktree`, not as an immutable
release. This record does not establish a public deployment. No existing applied migration was edited. All infrastructure below is disposable;
the original development stack and the owner's actual Aiven services were not modified.

- Java 21.0.11 wrapper `verify`: **408 backend tests**, zero failures/errors/skips; **42 frontend unit
  tests**, typecheck, lint and bundle passed. The six added tests use actual Boot environment binding
  and factory maps to verify TLS/SASL reaches producer, consumer and admin without weakening required
  acknowledgement, idempotence, deadline or manual-offset settings.
- Ordinary packaged image: **54 Chromium browser tests**, retries zero, first attempt; demos
  **12 / 26 / 27**; independent restart-recovery demo **16**. Dedicated PG16/Kafka3.9 infrastructure
  used ports 55435/19095, application 8086; restart recovery used its own project and ports 55437/8087.
- **Eight supervisor checks** and **four real Caddy 2.11.4 checks** passed. The latter cover trusted
  and untrusted peers, forwarding/Host sanitation, route/status preservation, response headers and
  the existing 64,000-byte body limit. CI now has a separate job using the exact pinned Caddy image.
- Provider-version fixture: **PostgreSQL 18.6** over `verify-full`, V1–V15 applied and validated,
  including a fresh database owned by a non-superuser role. Flyway 11.7.2 emits its newer-than-tested
  warning (tested through PG17); this successful rehearsal is not a vendor-support claim.
  **Kafka 4.2.1** used SCRAM-SHA-256/SASL_SSL and a PEM CA. The actual changed factories produced an
  acknowledged record and consumed/committed the same partition/offset. Admin created two-partition
  topics. Wrong hostnames and a wrong Kafka password were refused, not worked around.
- Final local Render image, **arm64 only**, Java **21.0.12**, hard **512 MiB memory / 0.1 CPU** quota,
  with those real TLS fixtures: readiness first observed at **134.5 seconds** after container start.
  **35 HTTP checks** passed through its real Caddy and application: dashboard/info, blocked metrics,
  anonymous async-health protection, oversized-body refusal, CSRF/session cookie properties,
  login/logout, separation of session and Basic chains, authorize/idempotent repeat/capture/refund,
  clean reconciliation, replay completion and per-client authentication limiting despite changing
  caller forwarding headers. This was a local HTTP proxy simulation; real HTTPS browser/cookie and
  Cloudflare header overwrite remain live checks.
- That payment had **three published events, six consumer receipts and one shadow comparison**.
  Observed cgroup peak after these checks was **264,282,112 bytes (252.0 MiB)**, with zero OOM events,
  zero OOM kills and zero container restarts. It is one short sequential functional rehearsal, not a
  load/capacity result or evidence of actual Render startup speed. Startup TLS handshakes initially
  timed out under the CPU quota and recovered; both consumers then caught up.

Corrections and failed first attempts are retained here: inherited `LOG_FORMAT=json` prevented Spring
contexts from starting on the first full run; explicitly using supported `ecs` resolved that harness
setting. The first Docker build used `COPY --chmod`, unsupported by the installed legacy builder;
it now uses owned COPY plus chmod. The Docker daemon could not bind-mount the SSD's fixture CA, so
only the disposable CA was copied into the test container. The provider probe initially matched an
older PostgreSQL hostname-error wording; the driver had correctly refused the connection. The first
HTTP harness expected logout 200; the actual documented 204 was correct, and the harness was fixed.
The Kafka binding test initially misnamed its simulated system-environment source. No application
assertion was relaxed to hide these setup errors.

One actual supervisor defect was found before delivery: a TERM arriving between spawning a child and
recording its PID could orphan it until container exit killed it. Deterministic signal injection
reproduced the Java and Caddy windows. Startup now defers termination until both PIDs are recorded;
both cases fail with the old script and pass with the corrected script.

A pre-existing metrics warning was observed during replay creation: `decisionrail.replay.jobs.created`
counter conflicts with the exported `decisionrail.replay.jobs` gauge in the Prometheus registry.
Both registrations exist at the base revision. This adaptation leaves them unchanged; the creation
counter must not be relied on until that separate instrumentation issue is fixed. Payment/replay
execution and the reconciliation checks passed.

Publication was authorized by the owner on 2026-09-19. Remaining delivery work at this checkpoint:
commit/push and Render image publication/anonymous pull;
actual Aiven credentials/CA installation in Render; actual platform forwarding, HTTPS, idle wake-up,
workspace free-hour/resource checks; and an Aiven-specific backup/recovery procedure and rehearsal.
The existing Compose backup/restore scripts do not establish managed-service recovery. See
[the Render guide](../deploy/render/README.md). No public URL or CI success is claimed for this worktree.
All three isolated Compose projects and the two separately labelled application containers were
removed after verification, including their test volumes and fixture image tags. Ignored local
evidence remains in `.local/`; shared base-image caches were retained. The original checkout is
still clean at `01c49f6`.

### Publication of the Render image, 2026-09-20 UTC

The worktree branch was pushed as `codex/render-aiven` (`945b8fa`), verified by
[CI run 35481316461](https://github.com/beaprogram/Decision-Rail/actions/runs/35481316461), merged
into `main` as `ca61492`, verified again by
[CI run 35481913404](https://github.com/beaprogram/Decision-Rail/actions/runs/35481913404), and the
owner dispatched the manual `Release Render image` workflow on that commit:
[run 35481914159](https://github.com/beaprogram/Decision-Rail/actions/runs/35481914159), concluded
`success`. Every conclusion was read from the run itself, not from a watcher's exit code.

It published `ghcr.io/beaprogram/decision-rail:render-sha-ca61492a80c6e3da835376c88a5325425ad2012d`,
a separate tag that does not replace `v0.10.0`, `v0.10.1` or `v0.10.2`, which still resolve to
`1977084`, `d9cea82` and `a4d94d6`. Read back from the registry without credentials on 2026-09-19 and
again on 2026-09-20, the tag is publicly visible and resolves to the multi-platform **index**
`sha256:2d76386200d8c6a695ddbffcfd3b9d1f3b5229ab6805c2acdc13109be22b3243`, whose children are
`linux/amd64` `sha256:59ef4954ae46ea9514cea040c5c3aeb9154c820c5207728a06a35af09b2dc85f` and
`linux/arm64` `sha256:e5efcee06223c352a2b574a02a09c01b7673fd72a46a70b656adcce7c6640d06`. Both
children carry `org.opencontainers.image.revision` and `APP_COMMIT` `ca61492…` and the entrypoint
`/app/render-entrypoint.sh`, so `/actuator/info` on a running instance will report that commit.

This is a published, inspectable image, not a deployment. Nothing has been created on Render, the
Aiven services have not been connected, and every live acceptance check in
[the Render guide](../deploy/render/README.md) is still owed.

### The acceptance harness, rehearsed before the live run

`deploy/render/live-check.py` encodes the guide's live checks so the live run is executed rather than
described. It was rehearsed on 2026-09-20 against a disposable `decisionrail-public` Compose project
running the published `v0.10.2` application image behind the packaged Caddy edge, with its own
generated credentials and high ports - not the development stack, and not a managed service:
**45 checks passed, 0 failed**, covering the reported revision and migration, the five security
headers and the removed `Server` header, `/actuator/prometheus` 404, anonymous 401 on
`/actuator/health/async` and its descendants, the 64,001-byte body refusal, unknown `/v1` and `/ui`
paths staying errors while `/dashboard/**` falls back to the page, CSRF refusal without a token, a
`Secure; HttpOnly; SameSite` session cookie, a session buying nothing on `/v1`, logout invalidating
it, an authorize/idempotent-repeat/capture/refund sequence with `CLEAN` reconciliation, replay
completing within budget through both chains, three published events with six receipts across both
consumer groups and one shadow comparison with no duplicate receipts, and ten failed logins with
rotating `X-Forwarded-For`, `Forwarded`, `X-Real-IP` and `CF-Connecting-IP` values still reaching a
429 on the eleventh, with correct credentials from that address also refused and non-attempt requests
passing through. One harness defect was found and fixed during the rehearsal: it read `commit` at the
top level of `/actuator/info` instead of under `decisionrail`, so its first run failed on a healthy
instance.

A rehearsal against a Compose stack is not the live run. It does not establish the platform's
forwarding behaviour, real HTTPS and browser cookie handling, managed PostgreSQL and Kafka over TLS
and SASL, free-instance startup time, or idle wake-up. Those remain owed, and the harness exists so
they are measured rather than asserted.

### Checkpoint 10's final state, and why it is closed here

Recorded **2026-09-23 UTC**. The deployment was carried as far as this repository can carry it, and
then stopped rather than left open:

| Deliverable | State |
| --- | --- |
| Free-budget hosting assessment, with sources | Done, [docs/hosting.md](hosting.md) |
| Secure public-demo configuration (shared visitor, server-side budgets on both chains, per-address authentication limiting, operator-only async health, refusal to start without Secure cookies or with fault injection) | Done, exercised by the backend suite and by the harness below |
| Compose deployment bundle with HTTPS at the edge, pinned images, seeding, health, backup, restore, rollback, teardown | Done, [deploy/](../deploy/) |
| Second target packaged for managed services: supervised edge plus application in one image, TLS and SASL to managed PostgreSQL and Kafka, 512 MiB budget | Done, [deploy/render/](../deploy/render/), with 8 supervisor checks and 4 real-Caddy checks in CI |
| Published, inspectable images for both targets, digests recorded | Done, `sha-a4d94d6…` and `render-sha-ca61492…` |
| Acceptance checks as an executable harness | Done, `deploy/render/live-check.py`, 45 checks, rehearsed 45/45 |
| Recorded walkthrough | Done, [walkthrough.md](walkthrough.md), labelled with the revision it shows |
| **A live public instance at a URL** | **Not done.** No Render service is serving the packaged image; the hostname tried answers with Render's own `x-render-routing: no-server`. The managed PostgreSQL and Kafka services exist on free plans and power off when idle, and were never connected to a running application. |

So checkpoint 10 is **packaged, unit-tested, rehearsed and published, without a live deployment**. The
live acceptance checks in [the Render guide](../deploy/render/README.md) have never run against a
managed deployment, and nothing in this repository should be read as saying they have. What that would
still take is owner work in two dashboards - a topic with two partitions, two CA files, fourteen
environment values, one service created from the published image - followed by
`deploy/render/live-check.py --phase readonly|session|flow|limiter` and one manual check from a second
network. It is not blocked on code.

### A sharp edge in the browser suite, found and blunted

The browser tests reach the application over HTTP through `DASHBOARD_BASE_URL` and reach its database
through Compose. Nothing tied those two together. Pointing the browser at a second stack while leaving
`COMPOSE_PROJECT_NAME` alone sent every inserted row to whichever project Compose resolved to by
directory name, and the only symptom was a fifteen-second `selectOption` timeout on an account picker
that was missing the account. In this session that happened, and the suite wrote 35 empty accounts -
no payments, no journals, no audit rows, no events - into the development database before it was
noticed. `createIsolatedAccount` now reads the row back through the application's own API and fails
immediately, naming the two variables to set. Confirmed both ways on 2026-09-23: mis-targeted, the
first test fails with that sentence; targeted correctly, all 54 pass.


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

[The remote run](https://github.com/beaprogram/Decision-Rail/actions/runs/35938805212) passed on revision `ddd3c73`, the closing revision: 408 backend tests, 42 frontend unit tests and 54 browser end-to-end tests against PostgreSQL 16 and a real broker, plus the image build, container startup, the operator demos, restart recovery on its own disposable stack, both collector checks and the performance harness smoke run. The browser step ran with retries at 0, so every one of those 54 passed on its first attempt. Both of its jobs concluded `success`, the backend suite and the separate real-Caddy edge job. The [preceding runs](https://github.com/beaprogram/Decision-Rail/actions/runs/35295428689) passed on `a4d94d6` with 402 backend tests, on `d9cea82` with 380, on `1977084` with 359, on `aec16d7` with 329, on `995ba63` with 318 and on `fdc6d96` with 310, and an [earlier one](https://github.com/beaprogram/Decision-Rail/actions/runs/34719181973) on revision `4754fab` with 213 backend, 41 frontend unit and the 41 browser tests that existed then; it is kept because the checkpoint 8 group breakdown was counted against it. CI configuration in the repository is not itself evidence that a remote run has passed; inspect the workflow result for the revision you care about.

Test reports are written under `target/surefire-reports/`; the JaCoCo report is generated under `target/site/jacoco/`. CI uploads available reports when a verification job finishes, including on failure. Coverage is a diagnostic aid, not a substitute for meaningful assertions.

## Recorded local result

Recorded **2026-09-23 UTC** on revision `e000c1a`, using PostgreSQL **16.15** and Kafka **3.9.1**
(`apache/kafka:3.9.1`, single-node KRaft). `./mvnw clean verify` passed **408 backend tests** with
**0 failures, 0 errors, and 0 skipped**, in 5 minutes 37 seconds, alongside **42 frontend unit tests**
in four files. The **54 browser end-to-end tests** passed with retries disabled, on the first attempt,
against an application built from this tree on its own disposable Compose project. The four demo
scripts passed against that same deployment: **12** transactional checks, **26** lifecycle and
reconciliation checks, **27** asynchronous checks, and **16** restart-recovery checks on the separate
stack that `recovery-demo.sh` builds and destroys itself.

Two honest notes about that run. The local JVM was **25.0.1**, not the Java 21.0.11 these records
name, because 21 is not installed on this machine; the build targets release 21 (`java.version`), and
CI is what exercises the documented toolchain on every pushed revision. And the browser suite ran with
`COMPOSE_PROJECT_NAME` naming the stack under test: see "A sharp edge in the browser suite" below for
what happens without it, which is a thing this session got wrong before getting it right.

The figures above supersede the **402** recorded for `a4d94d6` (v0.10.2), which superseded 380 for
`d9cea82`, 359 for `1977084`, 329 for `50a2761`, 318 for `fc6fa5c`, 310 for `fdc6d96` and 302/41 for
`aa6de43`. The table below is the checkpoint 8 record, kept because it is what the group breakdown was
counted against; the checkpoint 9 additions are listed in the section that follows it. The **408**
figure is the current total and the **213** figure is a historical record of an earlier revision - they
are not two counts of the same thing.

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
| Database-level over-return | A direct UPDATE above the capture is refused by the row-level cap by name; an inserted payment, a payment-only update and a return row that disagree with the recorded total are each refused by the equality rule | A cap that only exists in application code, and an equality checked at only some of the three points that can break it |
| Other payments on the same account | A refund credits the balance and leaves an unrelated hold exactly as it was; that authorization still captures | Refunded money silently reserved against work nobody requested |
| Ordered return events | Four events in sequence, the two refunds distinguished by their return block, each naming its operation | Two partial refunds indistinguishable because the status did not move |
| Refund events and shadow | No shadow task is enqueued during a bounded window after two refunds are delivered | A candidate's divergence rate depending on how often merchants issue refunds |
| A refund during a broker outage | Commits with the broker unreachable; intent durable and unpublished; delivered in broker-offset order after recovery | A financial command made to depend on a broker |
| Recovery with no in-process state | The dispatcher's lease, claim and breaker state are all discarded; the committed refund is then delivered from the outbox row alone, with its original identity. Deliberately **not** described as a restart: the JVM does not restart, and a genuine process boundary for an undelivered refund event is not demonstrated anywhere here | A "restart" test that only calls the same method twice, and a restart claim nothing actually crossed |
| Reconciliation of valid state | A captured, partly refunded account reports CLEAN with its scope, snapshot, checks and limitations | A report whose silence cannot be distinguished from a report that checked nothing |
| An inconsistent fixture | A skewed balance is detected with expected, actual, delta, currency and references | A reconciliation that only agrees with itself |
| Evidence the schema now refuses | Currency disagreement and a drifted returned total are rejected by the named constraint against the real schema, and separately detected by the real report against a throwaway database migrated only to V11 | A constraint dropped to keep a detection test, or a detector silently untested once the corruption became unwritable |
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

- **Editing a migration after the suite has applied it breaks the next run.** Flyway records a
  checksum per migration, so even a comment-only change to a file already applied to the shared test
  database fails validation on the next start. The fix is to recreate the disposable stack
  (`docker compose --file compose.test.yaml down && up -d --wait`) so it migrates from empty, never to
  edit the recorded checksum. This is also why a migration should be finished before it is run, and
  why the suite is re-run after any late edit to one.

- **An application container left attached to the test broker will eat the suite's events.** The
  browser run uses a packaged container pointed at the disposable stack; leaving it running while
  `./mvnw verify` starts gives the topics a second set of consumers in the same group. Eleven
  delivery, breaker and shadow tests then fail in ways that read as a delivery regression rather than
  as contention. `docker ps` before believing such a failure, and stop the container.

- **Killing a run mid-test can leave fault injection installed.** The tests that inject storage failures
  create a trigger and drop it in a `finally` block, which a terminated JVM never reaches. A leftover
  trigger on `consumer_quarantine` blocks every quarantine insert, and since holding the partition is the
  correct response to a storage failure, the symptom is four unrelated-looking consumer timeouts rather
  than an error naming the cause. Check `SELECT tgname FROM pg_trigger WHERE NOT tgisinternal` for a
  `test_` prefix, or reset the stack with `down -v`, before believing such a failure.

Both demo scripts passed against the packaged application in the local Compose stack: `scripts/demo.sh` (**12 HTTP checks**) and `scripts/async-demo.sh` (**27 checks**). The browser suite passed **44 of 44** against that same packaged application, on first attempt with retries disabled. The asynchronous demo observed a payment authorized with the broker container stopped, the breaker OPEN with `/actuator/health/async` DEGRADED while readiness stayed UP, delivery resuming after restart with the original event id and the breaker closing again, a projection applied count that stayed at 1 after the same event was delivered twice more, a replay job whose membership stayed at 4 inputs when a later payment committed, a 409 when a policy version id was rebound to different content, and a shadow divergence (live APPROVE, candidate DECLINE at score 60) after which the balance was unchanged and held funds moved only by the new authorization's own hold.

## The pre-checkpoint-10 hardening pass

Baseline `aa6de43`, whose CI run passed 302 backend, 41 frontend unit and 54 browser tests. Four
things changed: a remaining cross-generation idempotency collision, two database constraints the
project had chosen to leave unenforced, and two browser tests that did not test what they said.

### The cross-encoding idempotency collision

The length-prefixed reason encoding fixed the `null`/absent ambiguity, but the receipt check that
made it safe ran only in the **legacy fallback** branch. A request whose fingerprint hashed to the
stored value directly was replayed without the receipt ever being consulted — so wherever an old
unversioned encoding and the new one could produce the same string, the key answered for a command
nobody sent.

Reproduced through the real HTTP and database path before anything was changed, on both REFUND and
REVERSAL, in both directions:

| Stored under the legacy encoding | Submitted now | Before |
| --- | --- | --- |
| reason `"-"` | reason absent | **201**, replaying the wrong receipt |
| reason `"4:null"` | reason `"null"` | **201**, replaying the wrong receipt |
| reason `"1:a"` | reason `"a"` | **201**, replaying the wrong receipt |

This is a changed-request identity and provenance defect, not an observed duplicate credit: the
replay returns an existing receipt and moves no money.

A version prefix on new fingerprints would not have resolved it, because the ambiguity lives in rows
already written without one. The correction instead makes the **stored receipt the authority on every
completed replay**, not just on the fallback path: the decoded response must describe the payment,
the operation type, the normalised reason and — for a refund — the explicit amount that is being
asked for now. That covers all three persisted generations at once and needed no migration or version
column, because the discriminator is data the rows already carry.

Each collision test carries a **control**: re-issuing the genuine legacy request must still replay its
own receipt. Without it a fixture error would look identical to the fix working. Regression coverage
asserts that every generation still replays its own legitimate retry, that the opposite direction of
each collision also conflicts, and that a replayed return leaves exactly one operation, one journal,
one credit and one event.

### Two constraints, added as a deliberate hardening decision

`V12` ties a payment's currency to its funding account (`payments(account_id, currency) →
accounts(id, currency)`, no `ON UPDATE`), and `V13` extends returned-total equality to payment-only
updates with a second deferred constraint trigger. Neither is a response to a demonstrated
money-losing path: the review found no normal service path producing either inconsistency. ADR-0007
records the reasoning, including the argument this withdraws.

Both migrations detect incompatible rows **before** validating, and refuse with a count, an example
payment, the reconciliation finding types that describe it, and an explicit statement that they will
not repair financial data. Nothing is silently corrected, deleted or skipped.

| Evidence | Where |
| --- | --- |
| An account cannot be redenominated out from under its payments; a payment cannot be created in a currency its account does not hold | `ReconciliationIntegrationTest`, asserting the named constraint rather than any database error |
| CAD and USD stay independent — a USD payment, capture and refund on a USD account commit and reconcile | same test |
| A payment-only returned total cannot drift downwards or upwards; an overshoot is still refused by the row-level cap, asserted by its own constraint name | `RefundIntegrationTest` |
| A refusal rolls back the whole financial operation: the credit, the return operation and the total go back together, and the payment is still usable afterwards | `RefundIntegrationTest` |
| Valid captures, partial refunds, full refunds, reversals and concurrent refunds still commit | the rest of `RefundIntegrationTest`, unchanged |
| A populated database upgrades through V13 with its journals, events and stored idempotency responses intact | `MigrationUpgradeTest` |
| A snapshot that disagrees refuses the upgrade, names the count and an example, and is left exactly as it was | `ReconciliationLegacyEvidenceTest`, one throwaway database per pre-flight |

### Keeping the detector under test without weakening production

The constraints make two of the reconciliation corruption fixtures unwritable. Rather than dropping a
constraint to keep a test, the evidence is split by what each test can honestly establish:

| Claim | Boundary exercised |
| --- | --- |
| Production refuses the invalid write | The real schema. The write is attempted through `JdbcTemplate` and the named constraint refuses it |
| Valid data reconciles | The real schema and the real service: payments, captures, refunds and reversals |
| The detector still identifies damaged evidence | A **private throwaway database migrated only to V11**, seeded there, read by the real `ReconciliationStore` and `ReconciliationService`, and dropped afterwards |

The third is the case that matters for honesty. A database restored from a partial backup, or not yet
upgraded, can hold rows nothing would write today. The fixture's returned-total drift is seeded the
way it could actually have happened — the return and the total were written together and agreed, and a
later update moved the total alone — with correct journals throughout, which is what made it invisible
to everything except a check comparing the column against the rows it summarises. The report finds
**exactly** the four seeded findings and nothing else, so the detector is shown to distinguish rather
than to condemn everything it sees, and re-running it changes no balance, no return and no currency.

The constraint's absence exists only inside a database created for one test. No migration is edited,
nothing shared is weakened, and reconciliation stays read-only.

### The delayed-identity browser scenario, rebuilt

The previous version held an outgoing **request** before `route.continue()` — so nothing was ever
delayed on the response side — and drove the identity change with `signIn`'s `page.goto`, which
remounts the whole SPA and rebuilds its state from scratch. That remount is the one thing that makes
the scenario safe by accident. It also waited 1.5 seconds and called that evidence.

It is now a deterministic interleaving inside one document: the route handler fetches the server's real
answer for the first merchant and holds **the response**, signals that it has it, and the test signs
out and signs the second merchant in without navigating, then releases. Every step waits for a named
signal.

What it establishes is that the held response never reaches the next identity's screen — and it says
which mechanism did that. The dashboard cancels the outstanding request at the transition, so the
response is abandoned in the browser rather than received and rejected; the test asserts the
cancellation, because that is what actually happens. The generation guard — the code that refuses a
response arriving whole after the identity moved on — cannot be reached through that path, so it has
its own focused test in `src/api/client.test.ts` where the response is delivered late and in full.

Ownership is asserted by resource identity, not by an amount another merchant could legitimately
share: every rendered row is read back from its own link, the first merchant's payment id is absent,
and the ids on screen are confirmed to belong to the signed-in merchant **by querying the database**,
not by comparing a rendered row count against a number taken from the same response that drew the rows.

**The earlier CI failure remains unexplained.** Nothing in this rebuild establishes a cause for it, and
none is claimed; the section above that records it is unchanged.

### Browser pagination that actually pages

`returns.spec.ts` created four returns against an API page size of 50, asserted on the single page it
got, and never pressed the Older or Newest buttons its comment described. It now builds a payment with
**52 return operations** — the first through the form, the rest through the API, because fifty trips
through the form would take minutes and prove nothing the form's own tests do not — and traverses:

- page one holds 50 rows, sequences 52 down to 3, with the payment's total stated separately from the
  page length and no "Newest" control offered;
- **Older returns** reaches sequences 2 and 1, with no "Older" control and a "Newest" one;
- the two pages neither overlap nor leave a gap: their union is exactly 1–52;
- **Newest** returns to the first page with the same 50 rows and the controls the other way round.

A return committing between two page reads stays covered where it can be asserted without depending on
the dashboard's five-second cache window: against the API, in `RefundIntegrationTest`, alongside the
201-operation history that proves nothing is unreachable past the old cap. Both were kept.

### First-attempt failures in this pass

Recorded because a suite that only ever passes on the second run is not evidence.

| What failed | Why | What it means |
| --- | --- | --- |
| Three idempotency collision pairs returned 201 instead of 409 | The defect, reproduced before being fixed | Intended |
| The shared test database refused to migrate under V12, naming 4 payments denominated differently from their account | Corruption fixtures from earlier reconciliation tests were still in it | The pre-flight working exactly as designed. The **disposable** stack was recreated; the development database was not touched, and was separately confirmed to have no offending rows |
| `ReconciliationIntegrationTest.aReturnedTotalThatDisagreesWithItsOperationsIsReported` and `RefundIntegrationTest.theReturnedTotalEqualityIsCheckedWhenAReturnIsWrittenAndNotOnEveryUpdate` | V13 contradicts both: they existed to assert the gap it closes | Removed and rewritten. The second's replacement asserts the rule from both sides |
| A compile error: `ReturnCommand(UUID, ReturnType, int, null)` undefined | The amount is a boxed `Long` | Fixture only |
| `isolation.spec.ts` timed out waiting for the second merchant's search response | My rendezvous, not the application: that response had already arrived before the wait began | The test now waits on rendered state instead |
| `returns.spec.ts` showed "50 of 52" after returning to the newest page | React Query serves a page younger than its five-second `staleTime` from cache, so a return created moments earlier was not yet visible | The application is behaving as designed. The between-page assertion was dropped from the browser test; that coverage is the backend's |
| `./mvnw clean verify` failed 11 Kafka-path tests | The disposable application container used for the browser run was still attached to the shared test broker, so its consumers were taking the suite's events out of the same topics under the same group | Environmental, and mine. Removing the container and re-running gave 310 of 310 with no other change. Recorded because the failure list — delivery ordering, breaker recovery, shadow isolation — looks exactly like a real delivery regression |

### Recorded result for this pass

Recorded **2026-09-17 UTC** using Java **21.0.11**, PostgreSQL **16.15**, Kafka **3.9.1** and
Playwright **1.63**, against a disposable application container on the disposable test stack — the
development stack was left running and untouched throughout.

- `./mvnw clean verify`: **310 backend tests**, 0 failures, 0 errors, 0 skipped
- **42 frontend unit tests**
- **54 browser end-to-end tests**, first attempt, retries disabled
- `scripts/demo.sh` passed; `scripts/lifecycle-demo.sh` **26 checks**; `scripts/async-demo.sh`
  **27 checks**; `scripts/recovery-demo.sh` **16 checks**

Remotely, [CI run 35230495695](https://github.com/beaprogram/Decision-Rail/actions/runs/35230495695)
passed every step on the delivered revision `fdc6d96` — the same commit, not an earlier one: compile and
test, image build, container startup with the operator demos, restart recovery, the browser suite, both
benchmark collector checks and the harness smoke run.

This pass adds **eight test methods** net (272 to 280 `@Test` declarations; five parameterised classes
expand to more executions than declarations): six in `RefundIntegrationTest` for the collision
regressions, the two-sided returned-total rule and the constraint rollback, three in the new
`ReconciliationLegacyEvidenceTest`, less the one reconciliation fixture the new constraints made
unwritable — plus one in `client.test.ts` on the frontend.

**Correction.** This section first recorded **307**, and said the difference from `aa6de43`'s 302 could
not be reconciled. It can: 307 was a miscount of mine, not a measurement. I had totalled the
`Tests run:` lines in `target/surefire-reports/*.txt`, and `TraceSamplingTest` uses `@Nested` classes —
its `.txt` summary reads `Tests run: 0` while its XML report and Maven's own total both count 3. The
real figure is **310**, exactly 302 plus the eight tests this pass added, and it is what CI reported for
`fdc6d96` all along. Read totals from `*.xml` or from Maven's summary line; the per-class `.txt` files
undercount any class with nested tests.

## The returned-total INSERT gap

Baseline `a81a5db`, whose CI run passed 310 backend, 42 frontend unit and 54 browser tests. One
correction, deliberately bounded to the missing guard and the documentation that overstated it.

### The failure window, reproduced

V10 attached the returned-total equality to inserts on `payment_returns`; V13 added the payment-update
side, after which ADR-0007 and this document described the rule as holding "whichever side is written".
It did not. A transaction can break the equality three ways — insert the payment already inconsistent,
update the payment's totals, insert a return operation — and only two were covered.

Reproduced on the project's own PostgreSQL **16.15**, against V1–V13 applied unchanged to an isolated
database, with a fixture valid in every respect except the one under test:

| Step | Result |
| --- | --- |
| A valid account: real merchant, CAD, funded | committed |
| A CAPTURED payment, amount 1000, captured 1000, **recording 100 returned** | committed |
| A valid balanced 1000-unit capture journal debiting the wallet and crediting merchant clearing | committed |
| Zero return operations | — |
| `COMMIT` | **accepted** |

After commit the payment recorded `returned_amount_minor = 100` against an operation sum of `0`. An
`UPDATE` of that same total to 101 was then refused by V13:

```
ERROR:  Payment bbbbbbbb-… records 101 returned but its return operations total 0
CONTEXT:  PL/pgSQL function enforce_returned_total(uuid) line 24 at RAISE
          SQL statement "SELECT enforce_returned_total(NEW.id)"
```

That contrast is the finding: the guard was live and blind to how the row arrived. The review's
reproduction used PostgreSQL 14.19; this one is on the supported 16.15, so the gap is not a
version-specific behaviour.

As with V12 and V13 this is a schema-invariant gap. **No API path that produces it was demonstrated,
and no money loss was demonstrated.** `PaymentService` writes returns and totals together under the
payment row lock, and an authorization inserts the column at its default of zero.

### What V14 adds

One deferred constraint trigger, `returned_total_on_payment_insert`, on `AFTER INSERT ON payments`,
executing the existing `enforce_returned_total_on_payment()` — so insert, update and return insertion
are one rule with one message and one `FOR UPDATE` on the payment, rather than three implementations
free to drift apart. Nothing in V13 or any earlier migration is edited.

It carries `WHEN (NEW.returned_amount_minor IS DISTINCT FROM 0)`, so the ordinary authorization path
queues no deferred work at all. A payment inserted at zero cannot be inconsistent at that instant — a
return operation references its payment, so none can exist before the row does — and one added later in
the same transaction is caught by the return-side trigger. That last sentence is a test, not an
assumption: `aPaymentInsertedAtZeroStillCannotGainAReturnWithoutItsTotal`. What the clause avoids is a
per-authorization aggregate at every commit on the hottest path in the system, which would also have
invalidated the published throughput figures for no additional protection.

The migration takes `LOCK TABLE payments, payment_returns IN SHARE ROW EXCLUSIVE MODE` **before** its
pre-flight, so a transaction already in flight cannot commit the very row the validation just declared
absent. It conflicts with the `ROW EXCLUSIVE` that writes take, leaves readers alone, and is the mode
`CREATE TRIGGER` acquires anyway.

**Correction.** An earlier revision of this section grouped V12 and V13 together as having that
window. They do not behave the same way, and only one of them does:

| Migration | How it installs | Window |
| --- | --- | --- |
| V12 | An ordinary validated `FOREIGN KEY`. PostgreSQL checks the existing rows and takes the locks that constraint needs as part of adding it | None of this kind — the check and the constraint are one operation the server performs |
| V13 | A custom `DO` pre-flight, then `CREATE TRIGGER`, with no lock held across the two | Yes: a conflicting row could commit between them |
| V14, V15 | The same shape as V13, but holding `SHARE ROW EXCLUSIVE` from before the pre-flight | Closed |

V13 is applied and is not edited. What would catch anything that slipped through its window is the
pre-flight in V14 or V15 — each re-checks returned-total equality against the rows as they stand.
Note what those pre-flights do **not** cover: they check returned-total equality only, not
payment/account currency agreement, which is V12's concern and is enforced by V12's foreign key
rather than re-validated later.

### Evidence

Transaction behaviour, against the real schema, written through `JdbcTemplate` because no API path
produces any of it (`ReturnedTotalInsertGuardTest`):

| Case | Result |
| --- | --- |
| Inconsistent payment INSERT, valid capture journal, no returns, **and no follow-up UPDATE** that could hand the work to V13's guard | refused at COMMIT, naming `records 100 returned … total 0` |
| The same, one minor unit inconsistent and far below the cap | refused by the equality rule, asserted by message so the cap cannot be what answered |
| A returned total above the capture | refused by `payments_returned_within_capture`, by name — the separate rule still answers for its own case |
| A valid captured payment, nothing returned, no returns | commits — the control that proves the fixtures reach the rule under test rather than dying on an unrelated constraint |
| A payment inserted claiming 250 returned **together with** the 250-unit return that justifies it | commits, which is what proves the check is genuinely deferred rather than immediate |
| A payment inserted at zero, then a return added in the same transaction with no update | refused by the return-side trigger |
| The V13 update side and the V10 return-insertion side | still refuse, and writing both sides together still commits |
| A refused transaction | leaves no payment, no journal and no ledger entries |

Upgrade behaviour, one throwaway database per case, created and dropped by the test
(`ReturnedTotalInsertUpgradeTest`):

| Case | Result |
| --- | --- |
| A V13 database whose rows agree | upgrades to 14; totals, returns and journals unchanged; the new trigger installed; and the reproduced insert is then refused on that upgraded database |
| A V13 database holding the reproduced inconsistency | refused, naming the count, the example payment and `RETURN_TOTAL_MISMATCH`; schema stays at 13; the total is still 100, no return operation was invented, nothing deleted, both ledger entries intact, and the trigger is **not** half-installed |

The legacy-evidence reconciliation tests are untouched and still run against their own V11 snapshot.

### First-attempt failures in this pass

Two, neither of them the migration:

| What failed | Why | What it means |
| --- | --- | --- |
| Applying V14 by hand through `psql` failed with `LOCK TABLE can only be used in transaction blocks` | My ad-hoc reproduction script ran statements in autocommit; Flyway runs each migration in a transaction | The migration is correct and now says so in a comment. Failing loudly beats running unprotected, so the behaviour is kept rather than worked around |
| A late comment-only edit to V14, after the suite had already applied it, would have failed the next run's Flyway validation on a changed checksum | Mine, caught before it failed | The disposable stack was recreated from empty and the full suite re-run against the exact committed file, which is the 318 recorded below |

Everything else passed on its first attempt: the eight new tests, the full suite, the browser suite and
all four demos.

One sequencing note, stated rather than glossed: locally, the demos and the browser suite ran against a
container built before that comment-only edit. The executable content of the migration did not change
and the backend suite was re-run afterwards from an empty database — and
[CI run 35238244685](https://github.com/beaprogram/Decision-Rail/actions/runs/35238244685) then re-ran
every step against the final revision `995ba63`, so the delivered claim rests on that rather than on
the local ordering.

### Recorded result for this pass

Recorded **2026-09-17 UTC** using Java **21.0.11**, PostgreSQL **16.15**, Kafka **3.9.1** and
Playwright **1.63**, against a disposable application container on the disposable test stack.

**Correction.** This paragraph first said the development stack was not started, stopped or written
to. That was wrong, and it contradicted an incident disclosed in the same delivery. During that pass I
ran `./scripts/async-demo.sh --help`; the script ignored arguments, so it executed the demo against
its defaults — `compose.yaml` and the `broker` service — rather than printing usage. It therefore:

- stopped the **development** broker for roughly 21 seconds, which the script's own cleanup restarted;
- created **three synthetic payments** in the development database, taking it from 619 to 622.

Recovery was reported at the time as consumers running, breaker CLOSED and zero undelivered events;
that is what the health endpoint showed then and it has not been re-verified since. Three terminally
failed events dated **2026-09-12** predate all of this and were deliberately left untouched, so the
development stack's DEGRADED async health is older than the incident rather than a consequence of it.
Nothing has been deleted or edited to make the environment look untouched: the three payments are
still there and the older failed events are unchanged.

The script's argument handling is fixed in the pass that follows this one, with a test that proves the
help and invalid-argument paths reach neither `.env` nor any operational command.

- `./mvnw clean verify`: **318 backend tests**, 0 failures, 0 errors, 0 skipped — 310 plus the eight
  added here, with Maven's own summary and the XML reports agreeing
- **42 frontend unit tests** and **54 browser end-to-end tests**, first attempt, retries disabled
- `scripts/demo.sh` passed; `scripts/lifecycle-demo.sh` **26 checks**; `scripts/async-demo.sh`
  **27 checks**; `scripts/recovery-demo.sh` **16 checks**, the last on its own stack built from empty,
  which is also where V14 is exercised as part of a first-time migration rather than an upgrade

Remotely, [CI run 35238244685](https://github.com/beaprogram/Decision-Rail/actions/runs/35238244685)
passed every step on the delivered revision `995ba63` — the same commit, not an earlier one: compile
and test, image build, container startup with the operator demos, restart recovery, the browser suite,
both benchmark collector checks and the harness smoke run.

No benchmark campaign was run and no throughput claim is changed; `performance.md` and the artifacts
under `benchmark/results/` are untouched.

## The payment-identity bypass

Baseline `fc6fa5c`, whose CI run passed 318 backend, 42 frontend unit and 54 browser tests. That green
run did not resolve these findings; they were identified against it.

### Reproduction and control, on PostgreSQL 16.15

V14 refuses a payment inserted with a returned total its operations do not sum to, but a deferred
trigger captures its `NEW` row when the statement runs and validates at COMMIT. Moving the row out from
under it defeated the check.

Run against V1–V14 applied unchanged to a database created for this purpose and dropped afterwards:

| Transaction | PostgreSQL 16.15 | Review's 14.19 |
| --- | --- | --- |
| Insert CAPTURED payment `A` (1000/1000, recording 100 returned), `UPDATE payments SET id = B`, insert a valid balanced 1000-unit CAPTURE journal for `B`, COMMIT | **committed** — final row `recorded=100`, `derived=0`, `return_count=0` | committed, same figures |
| The identical transaction **without** the identity change | **refused at COMMIT**, `records 100 returned … total 0`, leaving `payments=0`, `journals=0` | refused, same message |

The two versions agree, so this is not version-specific. The mechanism is as the review described: the
deferred trigger holds `NEW.id = A`, `enforce_returned_total(A)` finds no row and returns early, and
V13's update trigger has `WHEN (returned or captured changed)` so a change to `id` queues nothing.

**This is a direct-SQL invariant defect. No API path reaches it and no money loss was demonstrated.**
Every UPDATE the application issues against `payments` sets status, `captured_amount_minor`,
`returned_amount_minor` or `updated_at`; none of them names `id`. That was confirmed against the source
before choosing the fix, and `theUpdatesTheApplicationActuallyMakesAreUnaffected` keeps it honest.

### The invariant, and why it rather than a wider trigger

`V15` makes a payment's id immutable, with an immediate `BEFORE UPDATE OF id` trigger carrying
`WHEN (OLD.id IS DISTINCT FROM NEW.id)` — so an UPDATE that merely mentions `id` while leaving it alone
is still allowed, and the ones the application actually issues never reach the trigger at all.

Widening the returned-total triggers to watch `id` would have fixed the instance and left the class:
every future deferred constraint on `payments` would inherit the same unstated assumption that the row
it captured is still findable at COMMIT. Immutability removes the assumption. It is also correct on its
own terms — journals, returns, events, audit records and stored idempotency responses all name that id,
and so does the merchant. `DELETE` is deliberately not covered; that is a separate decision and not the
defect found.

Immediate rather than deferred, unlike the three returned-total triggers: a total legitimately changes
during a transaction and is only meaningful at the end, while an identity change has no legitimate
intermediate form, so the statement attempting it is what to refuse.

### Evidence

Against the real schema (`PaymentIdentityImmutabilityTest`):

| Case | Result |
| --- | --- |
| The reproduced bypass | refused, naming both ids; no payment, journal or ledger entry survives under **either** identity |
| Renaming a payment that has already committed with its journal | refused; row and journal intact |
| The three UPDATEs the application issues — capture, status transition, returned total | all still work |
| An UPDATE that sets `id` to its own value | allowed |
| A payment inserted claiming 250 returned **with** its 250-unit return | commits — V15 did not turn the deferred check into an immediate one |
| V14's insert-side refusal | still fires |
| Authorize, capture, void, refund and reversal through `PaymentService` | unchanged, including a reversal leaving the payment CAPTURED |

Upgrade behaviour, one throwaway database per case (`PaymentIdentityUpgradeTest`), with the invalid
fixture seeded **by running the bypass at V14** rather than by disabling anything:

| Case | Result |
| --- | --- |
| A V14 database whose rows agree | upgrades; totals, returns and journal entries unchanged; trigger installed; the rename is then refused on that upgraded database |
| A V14 database holding the bypassed row | refused, naming the count, the payment and `RETURN_TOTAL_MISMATCH`; schema stays at 14; total still 100; no return operation invented; both ledger entries intact; neither the trigger nor its function partially installed |

V15 cannot detect that an identity was changed in the past — the old value is gone and nothing recorded
it. What it detects is the inconsistency such a change was used to introduce.

## Asking a demo a question must not run it

`scripts/async-demo.sh` took no arguments and ignored the ones it was given, so `--help` executed the
demo: it stopped the configured broker and created synthetic payments against whatever stack the
environment pointed at. That is not hypothetical — it happened to this project's own development stack,
and is recorded below.

The script now parses arguments in its first executable block, **before** `.env` is sourced, before
credentials are required, before the dependency check, and before any `docker`, `curl`, `psql`,
`openssl` or `mktemp` call. `--help`/`-h` print usage and exit 0; an unknown argument or `--help`
combined with anything else prints the reason and usage on stderr and exits 2. The usage text states
plainly that a real run stops the selected broker and creates synthetic activity, and shows the
disposable-stack invocation.

`AsyncDemoArgumentSafetyTest` proves the side-effect freedom rather than asserting the message. It
copies the script into a temporary directory laid out like the repository, beside a fake `.env` whose
content creates a marker file when sourced, and puts stubs for `docker`, `curl`, `jq`, `openssl`,
`psql`, `mktemp` and `sleep` first on `PATH` that record the call and fail. For `--help`, `-h`, an
unknown argument and an unsupported combination, it asserts the marker is absent and the recorded
command list is empty. A fourth case is the positive control: with no arguments the script still
reaches `.env`, so the other three cannot pass by the script simply exiting at the top for everything.

Only this script was changed. `demo.sh`, `lifecycle-demo.sh` and `recovery-demo.sh` still ignore
arguments and remain exposed to the same mistake — out of scope here, and stated rather than left to be
discovered.

### First-attempt failures in this pass

| What failed | Why | What it means |
| --- | --- | --- |
| `AsyncDemoArgumentSafetyTest` — all four, exit 127 | I cleared `PATH` entirely, so the usage heredoc could not find `cat` | Fixture. The stubs now *shadow* the dangerous commands with the system paths following, which is what the check actually needs |
| `helpPrintsUsageAndTouchesNothing` — a phrase assertion | "takes no arguments" wrapped across a newline in the usage text | The usage was reworded so the phrase reads on one line |
| A boxed-type compile error on `ReturnCommand(…, 1_500, …)` | The amount is a `Long` | Fixture only |
| `ReturnedTotalInsertUpgradeTest` asserted the upgraded schema was at "14" | Adding V15 made that hard-coded number wrong | A real consequence of the change. Both upgrade tests now read the latest version from the migrations on the classpath, so the next migration does not break a check that is about the upgrade succeeding |

The reproduction, both migrations' behaviour, the identity regressions and the full suite passed on
their first attempt.

### Recorded result for this pass

Recorded **2026-09-17 UTC** using Java **21.0.11**, PostgreSQL **16.15**, Kafka **3.9.1** and
Playwright **1.63**.

- `./mvnw clean verify`: **329 backend tests**, 0 failures, 0 errors, 0 skipped — 318 plus eleven added
  here, with Maven's summary and the XML reports agreeing, against a database migrated from empty
- **42 frontend unit tests** and **54 browser end-to-end tests**, first attempt, retries disabled
- `scripts/demo.sh` passed; `scripts/lifecycle-demo.sh` **26 checks**; `scripts/async-demo.sh`
  **27 checks**; `scripts/recovery-demo.sh` **16 checks**

Infrastructure for this pass was uniquely named and confirmed by name before removal: a
`dr-pk-probe-fc6fa5c` PostgreSQL 16.15 container on 127.0.0.1:55499 for the reproduction, a
`dr-verify-v15` application container on 127.0.0.1:8082, and an image tagged `decisionrail:verify-v15`.
**The development stack was not started, stopped, migrated or written to by this pass** — its
containers' start times and its payment count were checked before and after.

Remotely, [CI run 35246574566](https://github.com/beaprogram/Decision-Rail/actions/runs/35246574566)
passed every step on the delivered revision `aec16d7`: compile and test, image build, container startup
with the operator demos, restart recovery, the browser suite, both collector checks and the harness
smoke run.

No benchmark campaign was run and no throughput claim is changed.

## Checkpoint 10: the public demo, verified in its deployed shape

Baseline `50a2761`, whose CI run passed 329 backend, 42 frontend unit and 54 browser tests. What
this checkpoint adds is a deployment shape, so the verification has two halves: the ordinary suite
with the new behaviour under test, and a rehearsal of the actual deployment bundle on this machine -
the same Compose file, the same edge, the same image build - with every claim in `deploy/README.md`
exercised rather than asserted.

### What the suite covers

`PublicDemoIntegrationTest` (12) runs a context with the public mode on and the budgets set very low,
through both API chains, with an injected clock advanced between tests so the in-memory windows are
empty each time:

| Claim | Evidence |
| --- | --- |
| The visitor is an ordinary merchant to every ownership rule | its own payment readable; another merchant's payment and account 404, in both directions |
| It has no administrative reach on either chain | `POST /v1/policies`, `/v1/ops/**`, `PUT shadow`, redrive, `/actuator/prometheus`, `/ui/ops/**`, `POST /ui/policies` all 403; capabilities agree but are not the rule |
| The bootstrap introduces the demo and nothing private | `publicDemo` names the visitor and the limits; no private credential in the body |
| The command budget refuses before anything is written, on both chains | 2 browser + 2 API commands spend a budget of 4; the 5th is `429 DEMO_CAPACITY_EXHAUSTED` with `Retry-After`, no payment and **no idempotency key claimed**; reads still work; the private merchant is not budgeted |
| The history cap consumes no key and leaves retries replayable | the 3rd authorization on a 2-payment account is `429 DEMO_ACCOUNT_FULL`; its key is absent; a retry of the first authorization still replays `201`; another account is unaffected |
| Replay is bounded to one in flight and an hourly allowance | second job while one is PENDING: 429; after completion: allowed; third within the hour: 429; reading jobs is not budgeted |
| Reconciliation is bounded per minute for the visitor only | 2 allowed, 3rd 429; the private merchant's 4 succeed |
| Failed sign-ins lock an address out on both chains | 3 bad Basic attempts; then the right password is 429 and so is a browser sign-in; another address and an unauthenticated probe are unaffected |
| A successful sign-in clears an address's failures | two mistakes, success, two more mistakes, success |
| Probes public, async details an operator read | liveness/readiness 200 with no components; `/actuator/health/async` 401 anonymous, 403 visitor, 200 operations and admin |
| The running revision is public and carries no configuration | commit, image, `latestMigration`, `synthetic: true`; no password, JDBC or broker string |
| Fault injection has no HTTP surface for anyone | four plausible paths as three identities: 403/404/405 |

`PublicDemoGuardsTest` (6) checks what a public instance refuses to start with - fault injection on,
cookies not `Secure`, a placeholder password - and the budget window's edges with a frozen clock.
`DemoScriptArgumentSafetyTest` (16) proves, for all four demo scripts, that `--help`, `-h`, an unknown
argument and `--help` combined with anything reach neither `.env` nor `docker`, `curl`, `psql`,
`jq`, `openssl` or `mktemp` - the fake `.env` leaves a marker if sourced and every command is a stub
that records and fails - with a no-argument positive control per script so the negative cases cannot
pass vacuously.

### The rehearsal: `deploy/` in the deployed shape

On this machine, on the Compose project `decisionrail-public` with a locally issued certificate
(`tls internal`) and high ports, from an image built with the commit stamped in. Every step below is
a script from `deploy/bin` or a request through Caddy over HTTPS; nothing was done to the containers
by hand except where the check is about doing exactly that.

| Check | Result |
| --- | --- |
| `up.sh` refuses `APP_IMAGE=…:latest` | refused, naming the tag |
| `up.sh` with a pinned image | four containers up; readiness reached; `/actuator/info` reports the commit, image, build time and `V15` |
| Transport | HTTP/2 over TLS; HSTS, CSP, `X-Frame-Options: DENY`, `nosniff`, `no-referrer`; no `Server` header; HTTP redirects to HTTPS |
| Edge allow-list | `/actuator/prometheus`, `/env`, `/metrics` answer 404 at Caddy; liveness, readiness and info 200; async health 401 anonymous, 200 as operations; readiness body is `{"status":"UP"}` only |
| Cookies through the proxy | `XSRF-TOKEN … Secure; SameSite=Lax`; `JSESSIONID … Secure; HttpOnly; SameSite=Lax` |
| Sign-in | without the CSRF token 403; with it 200 as `visitor`, `ROLE_MERCHANT` only; a mutation without the token 403; `DELETE /ui/session` 204 and the identity is anonymous afterwards |
| Visitor through `/v1` | `POST /v1/policies` 403, `/v1/ops/outbox/backlog` 403, `PUT /v1/ops/shadow` 403, another merchant's account 404 |
| Seed | `seed-demo.sh` created, through the API: CAPTURED; CAPTURED with 15.00 refunded; VOIDED; CAPTURED and fully reversed; REVIEW; DECLINED by policy (`TEST_COUNTRY_BLOCKED`); **DECLINED with `INSUFFICIENT_FUNDS` and an APPROVE decision**; a candidate policy; shadow enabled and a diverging authorization; a replay job that ran to COMPLETED. 2 returns, 5 journals, 3 shadow comparisons, all events published and consumed |
| Seed is idempotent | a second run exits 0 and the payment count is unchanged: every command replayed its receipt |
| Command budget through the proxy | 30 authorizations 201, the 31st and 32nd 429 with `Retry-After: 57` and the documented detail |
| Authentication limiter through the proxy | ten wrong admin passwords; then the right one is 429, and so is a visitor sign-in from the same address |
| Application restart | payments 38, published 44, consumed 88 before and after; a signed-in session is anonymous afterwards |
| Broker outage | authorization during the outage 201; readiness 200; async details show 1 undelivered with the breaker still CLOSED (the DEGRADED threshold is age-based and had not elapsed); after `start broker` the event is PUBLISHED and undelivered is 0 |
| Backup and restore | `backup.sh` wrote a V15 custom-format dump; one more payment was made; `restore.sh` (project name typed) dropped and restored; the fingerprint of every payment's id, status and returned total matches the backup exactly and the later payment is gone |
| Rollback | `…:latest` refused; an image whose `/app/latest-migration` says 14 against a V15 database refused with the restore instruction; an image that knows V15 accepted, `APP_IMAGE` rewritten, stack redeployed |
| Reset | a wrong project name refused; the right one removed the volumes, started empty and re-seeded |
| Teardown | a wrong project name refused; the right one removed containers, network and volumes; the development stack's containers, start times, schema (V11) and payment count (622) unchanged throughout |
| Recording | two WebM videos produced by Playwright against this stack: the visitor path (56 s) and the operator segment (19 s) |

Not rehearsed, because it cannot be here: the Oracle host itself, the public certificate, and the
live smoke test. Those are what the owner actions in `deploy/README.md` unlock, and the checkpoint is
recorded as pending on exactly that.

### First-attempt failures in this pass

| What failed | Why | What it means |
| --- | --- | --- |
| `PublicDemoIntegrationTest` — 12 errors, FK violation on `accounts.merchant_id` | the visitor had no `merchants` row | A real gap: the visitor needs to exist as a tenant. `PublicDemoFixtures` writes the merchant row and three accounts idempotently when the mode is on |
| the same — 3 failures, `DEMO_CAPACITY_EXHAUSTED` in unrelated tests | the in-memory budgets are per process and the tests share one context | The budgets working as designed; the tests now advance an injected clock two hours apart |
| the same — 1 failure after advancing the clock one minute | `SlidingWindowBudget` pruned only events strictly older than the window, so with a frozen clock an event exactly one window old never left | A real boundary defect in new code, found by the frozen clock. The boundary is inclusive now, and `secondsUntilRelief` agrees with it |
| the same — 2 failures | my two very low test limits (4 commands, 2 payments per account) reached each other | Test design; the flows were separated |
| a compile error and a duplicate `clock` bean name | harness | — |
| `DemoScriptArgumentSafetyTest` — all cases exit 127 | I cleared `PATH` entirely, so the usage heredoc could not find `cat` | The stubs now shadow the dangerous commands with the system paths behind them |
| the same — one phrase assertion | "takes no arguments" wrapped across a line in the usage text | reworded |
| Rehearsal: `internal: command not found` | `CADDY_TLS_DIRECTIVE=tls internal` unquoted in the env file | the template now says to quote it |
| Rehearsal: `compose pull` failed | a locally built image is in no registry | `up.sh` and `rollback.sh` accept a local image only when it is actually present, and still fail otherwise |
| Rehearsal: bind-mounting the Caddyfile failed under Colima | a single-file mount from the external volume | the edge is now a tiny built image with the file inside it, which is also the better deployment |
| Seed: the "insufficient funds" case was a policy decline | 90,000.00 tripped `HIGH_AMOUNT` before the balance mattered | the USD account is now 600.00 so a policy-approved 900.00 is refused for funds — the genuine `INSUFFICIENT_FUNDS` with an `APPROVE` decision |
| Walkthrough: four re-recordings | an ambiguous `visitor` match once the banner existed; seeded rows pushed off page one by the budget test's 30 payments; a strict-mode duplicate; reconciliation wording | the recording uses the page's own filters, which is a better walkthrough, and scoped assertions |

None of the application's existing tests changed.

### Recorded result for this pass

Recorded **2026-09-17 UTC** using Java **21.0.11**, PostgreSQL **16.15**, Kafka **3.9.1**, Caddy 2,
Playwright **1.63**, Docker via Colima.

- `./mvnw clean verify`: **359 backend tests**, 0 failures, 0 errors, 0 skipped, from an empty
  database — 329 plus 34 added (12 public demo, 6 guards, 16 script safety), less the 4 the
  generalised script test replaced
- **42 frontend unit tests**, lint clean; **54 browser end-to-end tests** first attempt, retries
  disabled, against a standard-mode container
- `scripts/demo.sh` passed; `scripts/lifecycle-demo.sh` **26 checks**; `scripts/async-demo.sh`
  **27 checks**; `scripts/recovery-demo.sh` **16 checks**; every script's `--help` verified by hand
  to leave the development broker's start time unchanged
- the rehearsal above, and two walkthrough recordings

Remotely, [CI run 35275604071](https://github.com/beaprogram/Decision-Rail/actions/runs/35275604071)
passed every step on the delivered revision `1977084`: compile and test, image build, container
startup with the operator demos, restart recovery, the browser suite, both collector checks and the
harness smoke run. The tag `v0.10.0` on that commit ran
[the release workflow](https://github.com/beaprogram/Decision-Rail/actions/runs/35278164285), which
published `ghcr.io/beaprogram/decision-rail:sha-19770842ab47837fbf0e035c0ae1964b840c4583` (also
`:v0.10.0`, the same digest `sha256:ea621b6c…`) for `linux/amd64` and `linux/arm64`. The published
image was then pulled without credentials, its revision label and `APP_COMMIT` read back as
`1977084…`, its `/app/latest-migration` as 15, and it was started in public mode against the
disposable stack: `GET /actuator/info` reported that same commit and image, the bootstrap introduced
the visitor, and the async health details answered 401 anonymously. The two recordings are attached
to [the v0.10.0 release](https://github.com/beaprogram/Decision-Rail/releases/tag/v0.10.0) and were
fetched back from it as real WebM streams.

The development stack was not started, stopped, migrated, reset or written to by this work. The
machine had rebooted before the pass began, which took the container runtime down; starting the
runtime brought the development containers back exactly as the reboot had left them (image of
2026-09-16, schema V11, "No migration necessary", 622 payments), and nothing here touched them
afterwards.

## Checkpoint 10 corrections (v0.10.1)

Baseline `6d3ef63` (released code `1977084`, `v0.10.0`), whose CI passed. A review of the release
configuration found eight findings; none concerns the financial core, and each is listed here with
what was **observed** before the fix, what was only established from source, the correction, and the
regression that now holds it.

| # | Finding | Before the fix | Correction | Regression |
| --- | --- | --- | --- | --- |
| R1 | The authentication limiter cleared an address on any sub-400 response to a Basic-bearing request; the browser chain ignores Basic, so anonymous `GET /ui/identity` with a bogus header reset it | **Observed** in the real chains: two wrong administrator passwords, an anonymous reset, repeated - eight guesses, no refusal | Failures and successes come from Spring Security's authentication events on both chains; a success clears only that username's failures at that address | `PublicDemoIntegrationTest`: anonymous stray-header reset, visitor success not clearing administrator guesses, recovery after the window, both chains |
| R2 | Caddy passed a client's `Forwarded` header through and the `framework` strategy honoured its `for=`, so the limiter's key was client-selectable | **Observed end to end** through the packaged v0.10.0 edge: twelve wrong passwords, each with a different `Forwarded: for=`, never refused; the right password then accepted | Caddy strips `Forwarded`, `X-Forwarded-Port/Prefix/Ssl` and authors `X-Forwarded-For/Proto/Host/Port` itself; the application uses Tomcat's native remote-ip valve (private-network peers only, `Forwarded` never read) | Rehearsed through the corrected edge: the same twelve, plus spoofed `X-Forwarded-*`, refused at the eleventh; cookies still `Secure`; HTTP→HTTPS redirect and `Location` unaffected |
| R3 | Only the exact `/actuator/health/async` was protected; `/actuator/health/async/asyncDelivery` fell through to the public wildcard | **Observed** (review, isolated chains): anonymous 401 for the group, 200 with backlog and breaker details for the descendant | The group and its descendants need an operator; only `/actuator/health`, `/liveness` and `/readiness` are public; any other health path is an operator read | `PublicDemoIntegrationTest` (anonymous/visitor/operations/admin on both paths) and through the edge |
| R4 | `HEAD` bypassed the reconciliation budget; Spring runs the `GET` handler and drops the body | **Observed** (review, real interceptor): GET 200, 200, 429, then four HEADs each running the handler | `HEAD` is charged like `GET` | `PublicDemoIntegrationTest`: after two reports, every further `GET` and `HEAD` on both chains is 429 and **the service is not invoked** (spy) |
| R5 | The one-in-flight replay limit was a count in an interceptor before the controller created the job | **Observed** here, through HTTP, on the pre-fix code: eight concurrent requests against a limit of one created **three** PENDING jobs | Admission moved into the job-creation transaction under `SELECT … FOR UPDATE` on the visitor's merchant row; the hourly allowance is counted from the rows there too; a refusal rolls back with no job, membership or key | `VisitorReplayConcurrencyTest`: eight concurrent HTTP requests create exactly one; refused keys claim nothing; a same-key retry replays; capacity returns on COMPLETED or FAILED; the hourly control answers separately; the private merchant is unlimited |
| R6 | The broker mounted a volume but set no `log.dirs`; the Apache image builds its configuration from the environment alone | **Observed**: effective configuration without `log.dirs`; `__cluster_metadata-0` and checkpoints in `/tmp/kafka-logs`; the mounted volume empty | `KAFKA_LOG_DIRS` and `KAFKA_METADATA_LOG_DIR` on the volume | Rehearsed: seeded activity, then the container **removed and recreated** with the volume kept - cluster id, topic id, log-end and committed offsets byte-identical; a new event afterwards published and consumed by both groups |
| R7 | An online database snapshot uncoordinated with the broker's committed offsets; restore replaced the database and restarted the application with the broker's newer state | Source-derived; not reproduced as a loss. What was rehearsed instead is the corrected procedure's every branch | `backup.sh` stops the application and requires zero consumer lag (unknown lag refuses); `restore.sh` restores into a fresh database swapped in on success, resets the broker to empty, leaves the application stopped | Rehearsed: refusal with lag 4 (application restarted), refusal with the broker unreachable, a coherent backup, three payments published and consumed after it, restore back to identical financial fingerprint, projection count and receipt identities, broker empty, a new event delivered once with no duplicate consumer effect afterwards, and a corrupt dump leaving the previous database, the broker and a stopped application intact. This row records the `v0.10.1` procedure, which `v0.10.2` replaced: see "R7, closed" below |
| R8 | `restore.sh` started the current image, so a cross-migration rollback could re-apply the newer migrations before the older image was selected | **Observed** (review, stub execution): restore of V14 ended in `start app` without selecting an image | `restore.sh` starts nothing; `rollback.sh <image> --restore <dump>` is the one workflow | Rehearsed with real images: V14 image deployed and backed up; V15 image upgraded; direct rollback refused with the application stopped; `--restore` rollback ended at V14 on the V14 image with **no V15 row ever re-applied**; forward again to V15 |
| — | The image guard rejected three strings and accepted `stable` | **Observed** (review, stub execution) | Only `sha-<40-hex>` tags and `@sha256:` digests are accepted | `ImagePinGuardTest`: four accepted forms, ten refused including `stable`, `v0.10.0` and a short sha |
| — | Release notes called the amd64 child digest the image's digest | Fact of the record | Index and per-architecture digests labelled separately, read fresh for the corrective image | — |

### First-attempt failures in this pass

| What failed | Why | What it means |
| --- | --- | --- |
| The five new regressions in `PublicDemoIntegrationTest` | Against the unfixed code | Intended: R1, R3 and R4 reproduced in the real context |
| The R1 stray-header test after the fix, at its fourth guess | I had encoded the pre-fix observation (eight 401s); the fix refuses at the fourth | The assertion now states the intended behaviour |
| The window-recovery test, `403` not `200` | My final check used an administrator credential on a merchant route | Test fixture |
| The first replay "reproduction" | It called the service directly, bypassing the interceptor where the old limit lived - it showed no limit at that layer, not the race | Discarded; the reproduction was redone through HTTP, where three jobs were created |
| The hourly replay test | It measured the hour on the application clock while `created_at` is stamped by the database | The policy counts on the database clock, and the test ages rows rather than the clock |
| `replayIsBoundedToOneInFlightAndAnHourlyAllowance` | Another class leaves a PENDING visitor job in the shared database; then the command budget (4/min in that context) fired before the hourly check | The test starts from no in-flight jobs and advances the clock past the command window |
| `recovery-demo.sh`, port `55436` in use | I started it a second time while my first, output-filtered, invocation was still tearing down | A clean run followed: 16 checks. My mistake, not the script's |

### Recorded result for this pass

Recorded **2026-09-17 UTC** using Java **21.0.11**, PostgreSQL **16.15**, Kafka **3.9.1**, Caddy 2,
Playwright **1.63**.

- `./mvnw clean verify`: **380 backend tests**, 0 failures, 0 errors, 0 skipped, from an empty
  database - 359 plus 21 added (3 limiter regressions, 1 HEAD-budget regression with a service spy,
  3 replay concurrency, 14 image-pin guard)
- **42 frontend unit tests**, lint clean; **54 browser end-to-end tests** first attempt, retries
  disabled, against a standard-mode container built from this tree
- `scripts/demo.sh` passed; `scripts/lifecycle-demo.sh` **26 checks**; `scripts/async-demo.sh`
  **27 checks**; `scripts/recovery-demo.sh` **16 checks**
- the rehearsal table above, on the `decisionrail-public` project on this machine with the corrected
  image and a synthetic V14 image built from this tree without `V15`, all confirmed by name before
  removal

Remotely, [CI run 35287845550](https://github.com/beaprogram/Decision-Rail/actions/runs/35287845550)
passed every step on the delivered revision `d9cea82`. The tag `v0.10.1` on that commit ran
[the release workflow](https://github.com/beaprogram/Decision-Rail/actions/runs/35288730001), which
published `ghcr.io/beaprogram/decision-rail:sha-d9cea820d158e66f3013a5c2311ab29a028a2dcc` (also
`:v0.10.1`). Read fresh from the registry afterwards, both tags resolve to the multi-platform
**index** `sha256:9712ec586d09b0e80de3e78b08ea2b53b045ec97827c7f5b1004698c8c803129`, with children
`linux/arm64` `sha256:613ba5444bd8086a5f796822d92e3eb88d4bc3ce208a104ffcbc486b288ed702` and
`linux/amd64` `sha256:7666355f2529443e181b5153cda6ac567db9d8e12c4bb3959ae6c7791f33e4c6`. The image
was pulled without credentials, its `APP_COMMIT` and newest migration read back as `d9cea82…` and 15,
and it was started in public mode against the disposable stack: `GET /actuator/info` reported that
commit, `/actuator/health/async/asyncDelivery` answered 401 anonymously, and eleven `HEAD` requests
for reconciliation were counted and refused at the eleventh. The release is at
[v0.10.1](https://github.com/beaprogram/Decision-Rail/releases/tag/v0.10.1); the v0.10.0 release
carries a note pointing to it and correcting its digest labelling.

The development stack was not started, stopped, migrated or written to; its schema (V11), payment
count (622) and broker start time were unchanged throughout. The public instance remains **not ready
for exposure** until the live checks in `deploy/README.md` run on the actual host.

## R7, closed: backup and restore coherence (v0.10.2)

Baseline `994354b` (`v0.10.1`). A focused review found R7 still open in two places. Both were
reproduced here with the v0.10.1 scripts unchanged, a fake `docker` on `PATH` and real `jq`, before
anything was changed.

### What was observed before the fix

`backup.sh` computed "consumer lag" with `awk 'NR>1 && $1==g {s+=($6=="-"?0:$6)} END {print s+0}'`
over the Kafka CLI's output. Five inputs, each returned by the fake with exit 0:

| Input to the lag computation | Exit | Said "lag is zero" | Ran `pg_dump` | Manifest |
| --- | --- | --- | --- | --- |
| `Error: Consumer group 'decisionrail-projection' does not exist.` | 0 | yes | yes | `consumerLagAtSnapshot: 0` |
| `Error: Executing consumer group command failed due to ...TimeoutException` | 0 | yes | yes | `0` |
| a partition row with current offset `-`, log-end `7`, lag `-` | 0 | yes | yes | `0` |
| nothing at all | 0 | yes | yes | `0` |
| the header line only | 0 | yes | yes | `0` |

The mechanism: no matching row sums to zero, `-` was mapped to zero by hand, and `awk` coerces any
other non-number to zero. Kafka 3.9.1's `ConsumerGroupCommand` really does print errors and return
normally, and really does render unavailable positions as `-`, so these are the shapes a real
observation can take.

`restore.sh`, given a dump with no manifest, printed a warning and then - in order - restored,
renamed the live database, removed the broker container and deleted its volume. A v0.10.0 online dump
holding a PUBLISHED event whose receipt was never captured would have entered exactly that path.

### The correction, and why it establishes the property

The property a restore needs is not "the broker reports no lag"; it is **"the database holds the
durable consumer state of every event that was published"**, because after the restore the broker is
reset and the outbox never resends a PUBLISHED event. That property is asked of the database
directly, with the application stopped, as one row of six integers: published events; how many of
them lack a `consumed_events` receipt or `consumer_quarantine` row for the projection group; the same
for the shadow group; and the PENDING, CLAIMED and FAILED counts. It follows the consumers' actual
contracts, read from their source: both claim a receipt for every event they parse *before* deciding
whether the type is one they act on, and both commit any effect in the same transaction as that
receipt - so a receipt is the fact for every event type, including the ones the shadow consumer
deliberately ignores and the ones it ignores because shadow is off, and no projection or shadow effect
is required beyond it. PENDING and CLAIMED events need nothing: they are re-dispatched. FAILED events
stay FAILED.

`assess_coherence` accepts exactly that row and nothing else. An error message, an empty answer, a
header, a `-`, a missing column, a malformed or negative number, a second row, or a count of missing
receipts larger than the published count is **UNVERIFIED** (exit 2); one or more missing receipts is
**INCOHERENT** (exit 1); only the complete, self-consistent, all-present answer is **COHERENT**
(exit 0). An empty environment - `0|0|0|0|0|0` - is coherent by construction and `backup.sh` says so
in words, which is a different thing from an answer that could not be read.

`restore.sh` validates the manifest - JSON, version 1, every required field present and typed, the
`durable-receipts` method, `verified: true`, and the dump's SHA-256 equal to the manifest's - **before
stopping the application**; then restores into a staging database, runs the same coherence check on
the staged data, compares its published count with the manifest's, and only then swaps the live
database and resets the broker. Legacy manifestless dumps are refused unless `--legacy-dump` is
passed, and then the staging check decides.

### Regression evidence

`RecoveryCoherenceGuardTest` (22) runs the real shell functions and the real scripts:

- `assess_coherence` on sixteen input shapes - empty, header, database error, timeout text, dashes,
  incomplete columns, malformed, negative, more-missing-than-published, two rows, three incoherent
  answers, three coherent ones including pending/claimed/failed counts and the empty environment -
  each yielding exactly one verdict line with the expected exit.
- `validate_manifest` on a missing file, non-JSON, unsupported version, missing field, wrong type,
  unknown method, unverified claim, checksum mismatch, and a valid manifest.
- `backup.sh` against a fake `docker` answering an error, nothing, and a missing receipt: exit 1,
  "No dump or manifest was written", no `pg_dump` in the recorded calls, an empty backups directory,
  and `start app` as the last recorded action. Against a coherent answer: the dump, a manifest with
  `verified: true`, the published and pending counts, and the dump's SHA-256.
- `restore.sh` against a fake `docker`: a manifestless dump refused with no `stop app`; a manifest
  with the wrong checksum refused with no `stop app`; a valid manifest whose staged data lacks a
  receipt refused after `pg_restore` with no `ALTER DATABASE` and no broker step, the staging database
  dropped; the same under `--legacy-dump`; a published-count mismatch refused likewise; and a verified
  dump proceeding in the order `pg_restore` → `ALTER DATABASE` → broker removed → volume deleted, with
  nothing starting the application.

### Rehearsal on disposable PostgreSQL and Kafka

On the `decisionrail-public` project on this machine, first from an image built from this tree (the table below), then again from the published artifact (the section after the recorded result):

| Step | Result |
| --- | --- |
| Backup of an empty environment | `COHERENT published=0`, "Nothing has been published yet: coherence holds vacuously. This is an empty environment, not an unread one."; dump and manifest written |
| Seed, then backup at T | `COHERENT published=14 pending=0`; manifest `version 1`, `durable-receipts`, `verified: true`, `publishedEvents 14`, SHA-256 equal to the dump's |
| A payment made with the broker stopped, then backup | `COHERENT published=14 pending=1`: a PENDING event needs no receipt and is captured for re-dispatch |
| An incoherent legacy-style dump (a published event's projection receipt deleted in a scratch copy, dumped without a manifest) without `--legacy-dump` | refused before the application was stopped; it kept running |
| The same with `--legacy-dump` | staged; `INCOHERENT published=14 missing_projection=1`; refused; live fingerprint unchanged, broker cluster id and topic id unchanged, staging database dropped, application stopped |
| A valid dump paired with a manifest carrying the wrong checksum | refused before the application was stopped |
| Three payments published and consumed after T2 (12 payments, 18 published, 36 receipts) | — |
| Restore to T2 | manifest VALID; staged `COHERENT published=14 pending=1`; live database replaced; broker recreated empty; application stopped. Restored: 9 payments, 14 published, 1 pending, 28 receipts, **14 of 14** published events holding both receipts |
| Start, then one new payment | the pending event re-dispatched and the new one delivered: 16 published, 32 receipts (16 × 2 groups), 0 duplicate receipts, 0 projection rows without a payment |
| `rollback.sh <image> --restore <dump>` | chains through the validated restore and starts the image; the cross-migration semantics verified for v0.10.1 are unchanged |

Teardown confirmed by project name; the development stack's schema (V11), payment count (622) and
broker start time were unchanged throughout.

### First-attempt failures in this pass

| What failed | Why | What it means |
| --- | --- | --- |
| `RecoveryCoherenceGuardTest` - two compile errors | a record component named `log` collided with an accessor I also called `log()`, and a two-variable declaration mixed types | Test-fixture typing |
| The first seeded backup in the rehearsal reported `published=0` | the seed ran while the application, restarted by the previous backup, was not yet ready; its calls failed | Rehearsal sequencing; re-seeded after readiness. The empty-environment backup it produced instead was itself a valid observation and is kept above |

### Remaining limits

Coherence is decided from the database's own records of what its consumers did. It cannot detect a
consumer that acknowledged a broker offset without writing its receipt, because the consumers commit
the receipt before acknowledging and the application is stopped when the question is asked; that is
the contract, and it is what this procedure relies on. The broker is not backed up at all, by design.
Restore discards everything after the recovery point. And none of this has run on the actual public
host, which does not yet exist.

### Recorded result for this pass

- `./mvnw clean verify`: **402 backend tests**, 0 failures, 0 errors, 0 skipped - 380 plus the 22
  guard tests - from an empty database. No application code changed in this pass; the browser suite,
  demos, collector checks and harness smoke are exercised by CI on the pushed revision.
- Remotely, [CI run 35295428689](https://github.com/beaprogram/Decision-Rail/actions/runs/35295428689)
  concluded `success` on every step for the delivered revision `a4d94d6`, including the 54 browser
  tests with retries disabled, the demos, restart recovery and both collector checks; the conclusion
  was read from the run itself, not from a watcher's exit code.
- The tag `v0.10.2` on that commit ran
  [the release workflow](https://github.com/beaprogram/Decision-Rail/actions/runs/35401797703)
  (`success`), publishing `ghcr.io/beaprogram/decision-rail:sha-a4d94d6aa8cb69a0935111d7791580750d5dd435`
  (also `:v0.10.2`). Read fresh from the registry afterwards, both tags resolve to the multi-platform
  **index** `sha256:5c851bb410f7823f0e5c000f93489c0ca0cf2040e47e86ef08b4d03c4fcf2c4e`, whose children are
  `linux/arm64` `sha256:35fa0daf44d3661f2732ac38dfcd3c73b10618cf1dcaffa4de0c7ea3eb8e7436` and
  `linux/amd64` `sha256:4e775322964a44fa2d8b60e8aa0f36cee8ca7b1ff46eaf251b982adc4237425f`; both children
  carry revision label and `APP_COMMIT` `a4d94d6…`. The release is at
  [v0.10.2](https://github.com/beaprogram/Decision-Rail/releases/tag/v0.10.2); the v0.10.1 release
  carries a note pointing to it. `v0.10.0` and `v0.10.1` still resolve to `1977084` and `d9cea82`.

### The published artifact, pulled anonymously and exercised

The image was pulled with an isolated Docker configuration holding no credentials (the user's own
configuration and login were not touched), and started in public mode on the disposable
`decisionrail-public` project with its own generated credentials, `tls internal` and ports 8480/8443.
`GET /actuator/info` reported commit `a4d94d6…`, image `sha-a4d94d6…` and migration `V15`; readiness
answered 200; the public-demo banner was present; `/actuator/health/async/asyncDelivery` answered 401
anonymously; the eleventh `HEAD` reconciliation request was refused with 429. Only `linux/arm64` was
executed here; `linux/amd64` is verified by digest and labels, not by running it.

The R7 procedure was then run against that image, with the application's real consumers rather than a
fake `docker`:

| Step | Result |
| --- | --- |
| Seed, then `backup.sh` at T | `COHERENT published=14 pending=0`; manifest `version 1`, `durable-receipts`, `verified: true`, SHA-256 equal to the dump's. T: 8 payments, 14 published, 28 receipts |
| `backup.sh` with the observation made unreadable (`consumer_quarantine` renamed for the duration) | refused: `UNVERIFIED expected one row from the database, got 3 line(s): ERROR: relation "consumer_quarantine" does not exist…`; no dump or manifest written; application started again |
| `backup.sh` with one published event's shadow receipt removed (parked, then put back) | refused: `INCOHERENT published=14 missing_projection=0 missing_shadow=1`; no dump or manifest written; application started again |
| Two payments after T (T2: 10 payments, 16 published, 32 receipts, 0 duplicates, 2 broker records) | — |
| The incoherent legacy-style dump (built by restoring the verified dump into a scratch database, deleting one projection receipt, dumping without a manifest) without `--legacy-dump` | refused before the application was stopped: `INVALID no manifest…`; it kept running |
| The same with `--legacy-dump` | staged; `INCOHERENT published=14 missing_projection=1`; refused; live fingerprint unchanged, broker volume unchanged, staging database dropped, application stopped |
| The verified dump paired with a manifest carrying a tampered checksum | refused before the application was stopped: `INVALID dump checksum … does not match the manifest's …` |
| `restore.sh` of the verified snapshot over T2 | manifest `VALID`; staged `COHERENT published=14`; live database replaced; broker volume recreated (0 records); application `STOPPED and was not started`. Back to T's fingerprint: 8 payments, 14 published, 28 receipts, **14 of 14** holding both receipts |
| `up.sh`, then one payment | 9 payments, 15 published, 30 receipts, 0 duplicate receipts, 0 quarantine, **1** broker record: the restored PUBLISHED events were not resent |

Harness mistakes on the way, none in the scripts: my first post-backup payments hit a wrong path (404)
and then a wrong credential variable (401) and a non-UUID account (400), so those steps were repeated
until real activity existed; and a scratch copy made with `CREATE DATABASE … TEMPLATE` was refused by
PostgreSQL while the application held sessions, so its empty dump was refused by `restore.sh` at the
staging step ("the dump could not be restored into the staging database", application stopped, live
untouched) rather than at the coherence step - itself a correct refusal, and the intended incoherent
dump was then built from the verified dump instead.

Teardown was confirmed by project name; the development stack's schema (V11), payment count (622) and
broker start time were unchanged throughout.

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
