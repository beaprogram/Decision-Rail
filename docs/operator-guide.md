# Local operator guide

## Credentials and configuration

Run `./scripts/prepare-local-env.sh` once from the repository. It generates an ignored `.env` and never prints the passwords. `.env.example` documents the variables without usable default credentials.

| Variable | Purpose |
| --- | --- |
| `POSTGRES_PASSWORD` | PostgreSQL initialization password used by Compose. |
| `JDBC_URL` | Native application's database connection URL. Compose sets an internal service URL. |
| `JDBC_USERNAME` | Database username. Local setup uses `decisionrail`. |
| `JDBC_PASSWORD` | Native application's database password. |
| `MERCHANT_DEMO_PASSWORD` | Password for `demo-merchant`. Required. |
| `MERCHANT_OTHER_PASSWORD` | Password for `other-merchant`. Required. |
| `OPERATIONS_PASSWORD` | Password for the `operations` metrics user. Required. |
| `ADMIN_PASSWORD` | Password for the `admin` user: policy creation, shadow configuration, outbox redrive. Required. |
| `KAFKA_BOOTSTRAP_SERVERS` | Broker list. Compose sets the internal `broker:9092`; a native run uses the published loopback port. |
| `DEMO_ENABLED` | Explicitly enable synthetic account seed data; defaults to `false` in the application. |
| `PORT` | HTTP port; defaults to `8080`. |
| `DB_PORT`, `KAFKA_PORT`, `BACKEND_PORT` | Optional Compose host-port overrides so this stack can coexist with another local PostgreSQL or broker. |
| `SESSION_COOKIE_SECURE` | Marks the browser session and CSRF cookies `Secure`. Defaults to `false` because the documented local stack is loopback HTTP, where a `Secure` cookie would never be sent and sign-in would be impossible. **Any deployment over HTTPS must set this to `true`.** |
| `SESSION_TIMEOUT` | Browser session idle timeout; defaults to `30m`. |
| `EVENTS_DISPATCHER_ENABLED` | Set `false` to stop this instance publishing events. Committed intent still accumulates. |
| `REPLAY_ENABLED`, `SHADOW_WORKER_ENABLED` | Set `false` to stop this instance running the replay or shadow worker. |
| `EVENTS_FAULT_INJECTION_ENABLED` | Local and test only. Leave `false`; see "Controlled failure injection". |

All four application passwords must be distinct and contain 16–72 characters; startup rejects missing, out-of-range, or placeholder values. The generated values exceed this minimum.

The four identities have non-overlapping authority, and privileged routes are matched before the broad merchant rule so they cannot fall through to it:

| Identity | Can do | Cannot do |
| --- | --- | --- |
| `demo-merchant`, `other-merchant` | `/v1/**` for their own data, including replay jobs and projection reads | Read protected metrics; reach `/v1/ops/**`; create a policy; see another merchant's data |
| `operations` | Read `/actuator/prometheus` | Everything else, including `/v1/ops/**`. It is deliberately not an administrator. |
| `admin` | `/v1/ops/**` and `POST /v1/policies` | Call merchant payment or replay APIs |

If you already have an `.env` from an earlier milestone it will not contain `ADMIN_PASSWORD`. Append one without touching the existing credentials:

```bash
umask 077
printf 'ADMIN_PASSWORD=%s\n' "$(openssl rand -hex 24)" >> .env
```

Public health responses contain no internal detail.

Local demo accounts:

| Account ID | Owner | Currency |
| --- | --- | --- |
| `11111111-1111-1111-1111-111111111111` | `demo-merchant` | CAD |
| `22222222-2222-2222-2222-222222222222` | `other-merchant` | CAD |
| `33333333-3333-3333-3333-333333333333` | `demo-merchant` | USD |

The first demo account starts with 1,000,000 minor units (CAD 10,000.00). Seeding must not top it up on application restart: a persisted balance is financial state, even in a demo.

## Docker startup

```bash
./scripts/prepare-local-env.sh
docker compose up --build -d
docker compose logs -f backend
```

Compose starts PostgreSQL and a single-node Kafka broker before the application. A healthy service means it is accepting connections; the application still needs to complete migration and startup. Check the API separately:

```bash
curl --fail http://localhost:8080/actuator/health
```

If ports `5432`, `19092`, or `8080` are already in use locally, override the host ports instead of editing the file:

```bash
DB_PORT=55434 KAFKA_PORT=19093 BACKEND_PORT=8080 docker compose up --build -d
```

The broker is a **single-node KRaft development broker**: one controller, one broker, replication factor 1. It is not a highly available deployment. A broker restart is a delivery outage and a lost volume is lost events. What makes that survivable is the outbox: committed intent stays in PostgreSQL until a broker acknowledges it. A real deployment would need at least three brokers, replication factor 3, and `min.insync.replicas=2` before any durability claim could be made.

## Native Java development

Run only the database container, export the generated environment, and start Spring Boot:

```bash
docker compose up -d database
set -a
source .env
set +a
./mvnw spring-boot:run
```

Do not run the Compose backend and native backend on the same port simultaneously. If you already run PostgreSQL on port `5432`, choose a separate database port and update both the Compose port mapping and native `JDBC_URL`.

## Open the operator console

Once the stack is running, the dashboard is at **<http://localhost:8080/dashboard/>**. It is built into
the application jar and served from the same origin as the API it calls, so there is nothing else to
start and no CORS to configure.

Sign in with a username below and its password from the generated `.env`:

| Identity | What it can do in the console |
| --- | --- |
| `demo-merchant`, `other-merchant` | Payment search and detail, authorize, capture, void, accounts, policy replay, shadow comparisons, policy reads |
| `admin` | Policy versions and candidate registration, event delivery health, failed events and redrive, shadow configuration |
| `operations` | Nothing. The console tells it plainly that it has no workspace, because it remains the metrics-only identity. |

A short walkthrough from a payment to its explanation and back to delivery health:

1. **Payments → New authorization.** Pick an account, type `25.00`, and note the field showing the
   exact minor-unit value that will be sent. Review and authorize.
2. **Open payment.** The stored decision panel shows the outcome, score, policy version, flags, and
   every reason contribution recorded when the payment was authorized. Nothing is recomputed here.
3. **Lifecycle.** The command, the event's delivery state, and what each consumer group has recorded
   appear as three separate facts. An unpublished event says so rather than showing an invented time.
4. **Capture.** The confirmation restates the concrete payment and amount. After capturing, the
   balanced journal appears.
5. **Policy versions** (as `admin`). Register a candidate; a bad definition comes back with the JSON
   path that was wrong.
6. **Policy replay** (as a merchant). Create a job against that candidate and watch it complete, then
   compare baseline and candidate explanations side by side.
7. **Event delivery** (as `admin`). Liveness, readiness, and asynchronous capability are three
   separate signals, with backlog counts and any stalled payment streams.

### When something is uncertain

Three screens deliberately refuse to guess, because guessing wrong about money is worse than saying so.

- **A command whose outcome is unknown.** If a reply never arrives, the console does not report success
  or failure. It shows the exact command it submitted — account, amount in minor units, request, and
  idempotency key — and offers **Retry safely**, which resends precisely those bytes under the original
  key. Editing the form afterwards does not change what a retry sends, and a new command cannot be
  started until this one resolves. If the original did commit, the retry returns its result rather than
  creating a second payment. Either way the payment is then re-read from the server, so the status,
  funding result, journal, timeline, account balances, and the actions offered are what the server now
  holds — not what the command's own response said, which under a replayed key is the result as it stood
  when that command first ran.
- **A sign-out in progress.** Clicking Sign out removes the workspace immediately, before the request is
  even sent, so nothing of the session stays on screen while the server is being asked. The screen that
  replaces it says the workspace has been cleared and does not claim the session is closed, because that
  is not known yet.
- **A sign-out the server did not confirm.** Everything on screen is cleared immediately either way, but
  the console says the session may still be open and offers a retry. It never claims a session was
  destroyed on the strength of a request that failed.
- **A funding result.** It is read from the payment's lifecycle state, not from the absence of a failure
  code. A policy decline reads "No funds reserved", a capture reads "Funds captured" rather than
  implying the hold is still standing, and an insufficient-funds decline names the failure while keeping
  the separate APPROVE risk decision visible next to it.

Two things the console will not do, deliberately: it has no control that stops the broker, edits
database rows, or arms a failure hook, and it never offers to redrive without an explicit selection.
Use `scripts/async-demo.sh` to see an outage.

### Working on the dashboard

`./mvnw verify` builds the dashboard with a Node toolchain it downloads and pins, so no local Node is
needed to build the application. Working on the frontend directly needs Node 20.19 or later:

```bash
DB_PORT=55434 KAFKA_PORT=19093 BACKEND_PORT=8080 docker compose up -d database broker backend
cd frontend
npm ci
npm run dev        # http://localhost:5173, proxying /ui and /actuator to the backend
```

The dev server proxies the API so the browser still sees one origin; without that the session cookie
would not be sent. Other useful commands: `npm run typecheck`, `npm run lint`, `npm test`, and
`npm run e2e` for the browser suite (which needs the application running and `../.env` loaded).

## Walk through the transaction lifecycle

```bash
./scripts/demo.sh
```

The script fails on a mismatched HTTP status, changed replayed result, absent replay marker, invalid capture journal, or incorrect final balance. It uses a new key prefix for each run, so it can be repeated against the same seeded database while funds remain available.

To run against another local port:

```bash
BASE_URL=http://localhost:8081 ./scripts/demo.sh
```

The script's `.env` is local configuration. Review the file before sourcing an `.env` supplied by anyone else.

For a manual request, load `.env` and use the generated password:

```bash
set -a
source .env
set +a
curl --fail-with-body --silent --show-error \
  --user "demo-merchant:$MERCHANT_DEMO_PASSWORD" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: manual-example-001' \
  --data '{"accountId":"11111111-1111-1111-1111-111111111111","amountMinor":2500,"currency":"CAD","country":"CA"}' \
  http://localhost:8080/v1/payments/authorizations
```

Retry with the same body and key to observe replay. Change the body while retaining that key to observe `409`. Capture or void the returned payment using a separate key for that operation.

## Walk through refunds, reversal, and reconciliation

```bash
./scripts/lifecycle-demo.sh
```

Twenty-six checks: partial refunds drawing down one budget, a refund above the remainder refused, a
post-capture reversal, that reversal closed off once anything has been returned, an idempotent replay
returning its own historical totals, the compensating journals and their link to the operations they
record, the database refusing to edit the original capture journal, a reconciliation finding with its
evidence, and the administrative boundary.

It creates its own synthetic account and works only on that account, so it never consumes the seeded
demo balances. It deliberately skews that account's balance to show a discrepancy being detected, then
puts it back before exiting, so the database is left as it was found. Reconciliation itself never
writes; the undo is the script undoing its own fixture.

### Returning money by hand

```bash
set -a; source .env; set +a
payment=<a captured payment id>

# What may still be returned, and what already was.
curl --fail-with-body --silent --user "demo-merchant:$MERCHANT_DEMO_PASSWORD" \
  "http://localhost:8080/v1/payments/$payment/returns" | jq .

# A partial refund. The amount is required and exact.
curl --fail-with-body --silent --user "demo-merchant:$MERCHANT_DEMO_PASSWORD" \
  --header 'Content-Type: application/json' \
  --header "Idempotency-Key: manual-refund-001" \
  --data '{"amountMinor":1500,"reason":"customer returned one item"}' \
  "http://localhost:8080/v1/payments/$payment/refunds" | jq .
```

Reuse the same key and body to see the original receipt returned with `Idempotency-Replayed: true`.
Change the amount under that key to see `409 IDEMPOTENCY_CONFLICT`.

Three things are worth knowing before using these by hand:

- **A refund names its amount.** There is no "refund whatever is left". The remainder changes as other
  refunds commit, so one key could mean two amounts at two moments. Read `remainingRefundableMinor`
  and send that number.
- **A reversal states no amount** and is refused once anything has been returned. Return the remainder
  as a refund instead.
- **A void is not a return.** Releasing an authorization hold before capture moves no money and writes
  no journal. Refund and reversal both apply only after a capture.

### Reconciling

```bash
# Your own accounts.
curl --fail-with-body --silent --user "demo-merchant:$MERCHANT_DEMO_PASSWORD" \
  "http://localhost:8080/v1/reconciliation" | jq '{status, scope, findings}'

# Any merchant, administrator only.
curl --fail-with-body --silent --user "admin:$ADMIN_PASSWORD" \
  "http://localhost:8080/v1/ops/reconciliation?merchantId=demo-merchant" | jq '{status, findings}'
```

Read `status` first, and read it precisely:

| Status | What it means |
| --- | --- |
| `CLEAN` | Everything in scope was examined and agreed. |
| `DISCREPANCIES_FOUND` | Everything in scope was examined; some of it disagreed. |
| `INCOMPLETE` | A limit stopped the examination. **Nothing found is not nothing wrong.** |
| `INCOMPLETE_WITH_DISCREPANCIES` | A limit stopped it, and what was examined already disagreed. |

`scope.incompleteReason` names the limit; narrow with `accountId` or raise `paymentLimit`.
`scope.checks` lists what was compared, so a check that is absent is visible rather than assumed to
have passed, and `limitations` states what the report cannot establish at all.

This report never writes. It will not repair a balance, rewrite a journal, or create a refund, and
there is no endpoint that does. A discrepancy is corrected by a new compensating operation with its
own evidence, or it is investigated; it is never edited away.

## Recovery: what committed, what is only awaiting delivery, and what to do

Three questions, in order. Answering them out of order is how a committed refund gets issued twice.

### 1. Did the command commit?

The authority is the idempotency record, not a log line and not the dashboard.

```bash
set -a; source .env; set +a
key=<the idempotency key the caller used>
docker compose exec -T database psql -U decisionrail -d decisionrail -c \
  "SELECT response_kind, http_status, response_body FROM idempotency_records
   WHERE merchant_id = 'demo-merchant' AND idempotency_key = '$key'"
```

| What you see | What it means | What to do |
| --- | --- | --- |
| No row at all | Nothing was ever claimed under this key. | Safe to send the command. |
| A row with `response_body` **null** | A transaction claimed the key and did not complete. It rolled back with everything it had written. | **Resend the same key and the same body.** Never mint a new one. |
| A row with a `response_body` | The command committed. That body is the answer the caller should have had. | **Resend the same key** to receive it, or read it here. Do not send a second command. |

The middle row is the case that matters. An unknown outcome is not a failure, and a fresh key for work
that may already exist is how a payment is made twice. Resending the identical key and body is always
safe: it either performs the work once or returns what it already did.

One caveat this project states rather than hides: an absent idempotency record proves no **durable
result was committed** under that key. It does not by itself prove the request never arrived — a
request that reached the application and failed before commit leaves exactly the same absence. For the
purpose of deciding what to do next the two are the same: nothing committed, so resending the same key
is correct either way.

### 2. Did the money move?

The ledger, not the payment row, is the record of money.

```bash
payment=<payment id>
docker compose exec -T database psql -U decisionrail -d decisionrail -c \
  "SELECT j.journal_kind, j.source_return_id, e.ledger_account, e.side, e.amount_minor
     FROM ledger_journals j JOIN ledger_entries e ON e.journal_id = j.id
    WHERE j.payment_id = '$payment' ORDER BY j.created_at, e.side"
```

A capture debits `wallet:<account>` and credits `merchant-clearing:<merchant>`. A return does the
exact reverse and names the return operation it records. Then reconcile that account: if the balance
agrees with these entries, the money is where the evidence says it should be.

### 3. Is the event only awaiting delivery?

A committed financial effect and an undelivered event are **different situations with different
actions**. Money that moved has moved; a projection that has not caught up is delivery lag.

```bash
docker compose exec -T database psql -U decisionrail -d decisionrail -c \
  "SELECT event_type, aggregate_sequence, status, attempts, published_at, last_error
     FROM outbox_events WHERE aggregate_id = '$payment' ORDER BY aggregate_sequence"
```

| `status` | Meaning | Action |
| --- | --- | --- |
| `PENDING` / `CLAIMED` | Committed intent, not yet acknowledged by the broker. | Wait. Check `/actuator/health/async` and the broker. |
| `PUBLISHED` | The broker acknowledged it. Consumers may still be behind. | Check `consumed_events` per group. |
| `FAILED` | The retry budget is exhausted. It blocks **only this payment's** stream. | Redrive it; see "Handling a stalled delivery stream". |

Nothing here is a reason to re-issue a financial command. The money is committed either way, and the
event carries the identity it was committed with: a redrive delivers the original event, not a new one.

### Supported actions, and what is not one

| Situation | Supported action |
| --- | --- |
| Outcome unknown, nothing committed | Resend the same key and body. |
| Outcome unknown, already committed | Resend the same key; you get the original response. |
| Money returned in error | Nothing. A return is immutable. There is no un-return; the payment can be authorized again as new work. |
| Wrong amount refunded (too little) | Refund the difference, as a further refund. |
| Wrong amount refunded (too much) | Not possible: the budget is capped, and the database refuses it. |
| Balance disagrees with the ledger | Investigate. Do not adjust the balance; there is no endpoint that does, and doing it by hand destroys the evidence. |
| Event terminally failed | Redrive. |

Not supported, deliberately: editing a journal, deleting a return, setting a balance, or minting a new
idempotency key for work that may exist. The first three are rejected by the database; the fourth is
rejected by arithmetic, eventually.

### An upgrade that refuses to apply

Two migrations validate a relationship across rows that already exist, and both check for incompatible
records **before** touching anything:

- **`V12`** — every payment must be denominated in the currency its funding account holds.
- **`V13`** — every payment's `returned_amount_minor` must equal the sum of its return operations.

If either finds rows that disagree, the migration fails and the startup stops there, with a message
naming how many rows, one example payment, and the reconciliation finding types that describe them:

```
Cannot enforce payment/account currency agreement: 4 payment(s) are denominated
differently from their funding account.
DETAIL:  For example payment 0f2c…. Reconciliation reports these as PAYMENT_CURRENCY_MISMATCH
         and ACCOUNT_CURRENCY_MISMATCH, with the account and currencies involved.
HINT:    Investigate those records and correct them with compensating operations before
         upgrading. This migration deliberately does not alter, delete or repair financial data.
```

The database is left exactly as the migration found it: the failed migration rolls back, and the
schema stays at the last version that applied. Nothing is repaired, adjusted or deleted on your behalf
— an upgrade that "fixed" such rows would destroy the only evidence that something went wrong.

What to do:

```bash
# Which payments, and what the disagreement is.
psql ... --command "SELECT p.id, p.currency AS payment, a.currency AS account, p.account_id
                      FROM payments p JOIN accounts a ON a.id = p.account_id
                     WHERE p.currency <> a.currency"

psql ... --command "SELECT p.id, p.returned_amount_minor AS recorded,
                           coalesce(sum(r.amount_minor), 0) AS operations
                      FROM payments p LEFT JOIN payment_returns r ON r.payment_id = p.id
                     GROUP BY p.id, p.returned_amount_minor
                    HAVING p.returned_amount_minor <> coalesce(sum(r.amount_minor), 0)"
```

Then reconcile (`GET /v1/reconciliation`) to see the same records with expected, actual, delta and the
references they were derived from, and correct them with compensating operations — the same rule as
every other financial correction here. Only then re-run the upgrade. Do not edit the migration, and do
not adjust the rows to make it pass.

If the rows came from a test fixture rather than from real activity — which is where they came from in
this project's own history — the answer is to recreate that **disposable** database, never the
development one.

### Database and broker loss

Stated plainly, because the alternative is implying something that was never tested.

- **No backup or restore procedure has been demonstrated in this project.** There is no tested
  recovery from a lost PostgreSQL volume, and no recovery-point or recovery-time objective is claimed.
  Losing the database loses the payments, the ledger, the idempotency records and the outbox together.
- **The local broker is a single node with replication factor 1.** A lost Kafka volume loses delivered
  events. What survives is the outbox: committed intent stays in PostgreSQL until a broker
  acknowledges it, so events not yet published are re-delivered after the broker returns. Events
  already published and then lost from the broker are **not** recoverable by this system.
- What *is* demonstrated, across a real process boundary: a refund commits with the broker stopped,
  the application process is killed with that event still undelivered, a new process starts against the
  same database **while the broker is still unavailable**, serves payments, and delivers the pending
  event in order with its original identity once the broker returns — with one consumer effect, one
  return operation, one journal and one credit, and the original receipt still replaying under its key.

  ```bash
  ./scripts/recovery-demo.sh    # 16 checks on its own disposable stack, ~2 minutes
  ```

  It builds and destroys its own database, broker and application container. The development stack and
  the shared test stack are not touched.

### Restarting during a broker outage

This is safe, and was not always. The application starts and serves payments with the broker
unreachable, including when its **name does not resolve** — a stopped container under Compose, which is
the ordinary case. Readiness stays UP because it is about the payment path; `/actuator/health/async`
reports DEGRADED with `consumersRunning: false` so the impairment is visible rather than implied.

Consumers start on their own once the broker returns. Nothing needs restarting a second time, and
nothing needs starting by hand:

```bash
curl --silent http://localhost:8080/actuator/health/async | jq '.components.asyncDelivery.details'
# consumersRunning goes true on its own once the broker is reachable
```

A resolvable address whose port is merely closed always worked and is a different case; the two are not
interchangeable.

## Walk through asynchronous delivery, replay, and shadow

```bash
./scripts/async-demo.sh
```

This drives the capabilities added in checkpoints 4 to 6 against a running stack and fails on any
mismatch. It shows, in order: a payment authorized while the broker is stopped; retained event
intent with an open breaker and a DEGRADED asynchronous health signal while readiness stays UP;
delivery resuming after the broker returns with the original event id; one projection effect despite
the same event being delivered twice more; a replay job whose membership does not change when a
later payment commits; a 409 when a policy version id is rebound to different content; and a shadow
divergence that leaves balances, holds, journals, the stored decision, and the event stream
untouched.

The broker outage is real: the script stops the broker container and restarts it. It targets only
the Compose services named by `BROKER_SERVICE` and `DATABASE_SERVICE`, and it never calls an
endpoint that executes commands. If you overrode the Compose host ports, pass the same values:

```bash
DB_PORT=55434 KAFKA_PORT=19093 ./scripts/async-demo.sh
```

## Watching the asynchronous path

```bash
set -a; source .env; set +a

# Delivery backlog, terminal failures, blocked payment streams, breaker state.
curl --silent --user "admin:$ADMIN_PASSWORD" \
  http://localhost:8080/v1/ops/outbox/backlog | jq .

# Is asynchronous delivery degraded? Separate from readiness on purpose.
curl --silent http://localhost:8080/actuator/health/async | jq .
curl --silent http://localhost:8080/actuator/health/readiness | jq .

# Merchant view of the event-derived projection.
curl --silent --user "demo-merchant:$MERCHANT_DEMO_PASSWORD" \
  http://localhost:8080/v1/activity | jq .
```

Metrics worth watching on `/actuator/prometheus` (operations identity):

| Metric | Meaning |
| --- | --- |
| `decisionrail_outbox_backlog{status=...}` | Events by delivery status. A growing `PENDING` means delivery is behind. |
| `decisionrail_outbox_backlog_age_seconds` | Age of the oldest undelivered event. The primary outage signal. |
| `decisionrail_outbox_publish_attempts_total`, `..._acknowledged_total`, `..._failures_total` | Send attempts versus real acknowledgements. |
| `decisionrail_outbox_publish_short_circuited_total` | Sends the breaker refused. These cost no broker round trip and no retry budget. |
| `decisionrail_outbox_retry_scheduled_total`, `decisionrail_outbox_failed_terminal_total` | Retry pressure and events that gave up. |
| `decisionrail_outbox_leases_reclaimed_total`, `decisionrail_outbox_lease_lost_total` | Worker deaths recovered, and completions fenced out. |
| `decisionrail_broker_breaker_state` | 0 closed, 1 half-open, 2 open. |
| `decisionrail_consumer_duplicates_total`, `..._applied_total`, `..._quarantined_total` | Deduplicated redeliveries, applied effects, and refused records by reason. |
| `decisionrail_consumer_quarantine_size` | Records the consumer refused to apply. |
| `decisionrail_shadow_tasks{state=...}`, `decisionrail_shadow_failures_total` | Shadow queue depth and candidate failures. |
| `decisionrail_replay_jobs{status=...}`, `decisionrail_replay_items_remaining` | Replay progress. |

Every label is a bounded enumeration. No payment id, merchant id, or policy version appears in a
metric; per-payment detail belongs in the operator APIs.

## Handling a stalled delivery stream

A terminally failed event blocks exactly one payment's stream. Later events for that payment stay
unclaimable until the failure is resolved, while every other payment keeps draining. That is
deliberate: applying a payment's `captured` event when its `authorized` event was never delivered
would build a read model from a gap.

```bash
set -a; source .env; set +a
curl --silent --user "admin:$ADMIN_PASSWORD" \
  http://localhost:8080/v1/ops/outbox/backlog | jq '{countsByStatus, blockedPaymentCount}'

# Redrive every failed event for one payment, so no earlier event is left behind.
curl --silent --user "admin:$ADMIN_PASSWORD" \
  --header 'Content-Type: application/json' \
  --data '{"paymentId":"<payment-id>"}' \
  http://localhost:8080/v1/ops/outbox/redrive | jq .
```

Redrive resets only the attempt budget and schedule. Event identity and payload are never rewritten,
so a redriven event is deduplicated by consumers like any other redelivery, and repeating the same
redrive request is a no-op because it matches only rows that are still failed. Check
`stillBlockedPaymentCount` in the response: if it is non-zero, some payment still has an earlier
failed event that was not part of your filter.

## Comparing a candidate policy

```bash
set -a; source .env; set +a

# 1. Register an immutable candidate. Admin only.
curl --silent --user "admin:$ADMIN_PASSWORD" --header 'Content-Type: application/json' \
  --data '{"versionId":"candidate-strict-v1","definition":{"rules":[
    {"code":"STRICT_AMOUNT","description":"Candidate declines at or above 1000 minor units.",
     "scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,
     "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}]}}' \
  http://localhost:8080/v1/policies | jq '{versionId, definitionHash, origin}'

# 2. Replay history against it. The merchant owns the job.
curl --silent --user "demo-merchant:$MERCHANT_DEMO_PASSWORD" \
  --header 'Content-Type: application/json' --header 'Idempotency-Key: replay-example-0001' \
  --data '{"candidateVersion":"candidate-strict-v1","limit":500}' \
  http://localhost:8080/v1/replay-jobs | jq '{id, inputCount, status}'

# 3. Read the report, then the diverging payments.
curl --silent --user "demo-merchant:$MERCHANT_DEMO_PASSWORD" \
  http://localhost:8080/v1/replay-jobs/<job-id>/report | jq .
curl --silent --user "demo-merchant:$MERCHANT_DEMO_PASSWORD" \
  "http://localhost:8080/v1/replay-jobs/<job-id>/results?divergedOnly=true" | jq .

# 4. Or evaluate the candidate alongside live authorizations.
curl --silent --user "admin:$ADMIN_PASSWORD" --request PUT \
  --header 'Content-Type: application/json' \
  --data '{"enabled":true,"candidateVersion":"candidate-strict-v1"}' \
  http://localhost:8080/v1/ops/shadow | jq .
```

Reading the report correctly matters:

- `divergenceRate` is `divergenceCount / completedCount`. The response states this in
  `divergenceDenominator` so a partially finished job is not misread.
- Divergence compares **risk decisions**, not payment outcomes. A payment declined for insufficient
  funds has an `APPROVE` risk decision and is compared as one. `paymentStatus` and
  `paymentFailureCode` appear on each result for context only.
- `labelledOutcomeDataAvailable` is always `false`. There are no fraud labels for this synthetic
  data, so no precision, recall, or false-positive rate is reported.
- `timingMethod` describes exactly what was measured. These are observed values for one run, not a
  benchmark.
- A candidate is never authoritative. Replaying it or enabling it for shadow evaluation does not let
  it decide a real payment, and there is no promotion path in this phase.

Shadow evaluation applies to authorizations observed while it is enabled; it does not backfill.
Use a replay job for history. Disabling it stops new work and retains recorded comparisons.

## Controlled failure injection

`EVENTS_FAULT_INJECTION_ENABLED` arms typed, local-only failpoints used by the test suite to
reproduce failure windows deterministically: a broker acknowledgement followed by a worker crash
before the outbox row is updated, a send that never reaches the broker, and a slow or throwing
candidate policy. Each switch names a payment and a behaviour. None of them accepts a command, a
host, or a path, and none is reachable over HTTP.

Leave it `false` outside local development and the test profile. The demo script does not need it:
its broker outage is produced by stopping the broker container.

## Interpreting results

A payment contains `id`, `accountId`, `amountMinor`, `currency`, `country`, `status`, `decision`, `failureCode`, `createdAt`, and `updatedAt`. The nested decision records `outcome`, `score`, `ruleSetVersion`, `reasons`, and `flags`.

- `AUTHORIZED`: synthetic funds are held and can be captured or released.
- `CAPTURED`: funds have been consumed and a balanced journal exists.
- `VOIDED`: the authorization hold has been released.
- `REVIEW`: policy requests review; funds are not reserved and a review workflow is not implemented yet.
- `DECLINED`: authorization was refused by policy or funds availability. Inspect both `decision` and `failureCode`.

A problem response uses JSON fields `type`, `title`, `status`, `detail`, `code`, and `requestId`. Authentication errors may occur before the payment controller; use the HTTP status and response body instead of assuming every response has a payment shape.

The ledger endpoint returns an array of entries with `id`, `journalId`, `ledgerAccount`, `side`, `amountMinor`, and `currency`.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Compose refuses to start because a variable is empty | Run `prepare-local-env.sh`; keep `.env` in the repository root. |
| Database authentication fails after changing `.env` | An existing volume still has its original initialized database password. Restore the matching configuration or deliberately rotate the database password. |
| Backend cannot connect to PostgreSQL | Check the database health and the connection host/port. Inside Compose the host is `database`; native Java uses `localhost`. |
| Demo returns `401` | Ensure the app and script use the same generated merchant password. |
| Demo reports missing account | Start the app with `DEMO_ENABLED=true` and verify it uses the intended local database. |
| An identical authorization repeats but does not spend again | Expected idempotency behavior; use a new key only for a new command. |
| `409` on key reuse | The key was already used with a different request or operation. |
| `REVIEW` or policy decline | Inspect the stored score, reasons, and `demo-v1` rules. |
| Outbox records stay pending | Check `/actuator/health/async` and `/v1/ops/outbox/backlog`. An OPEN breaker or an unreachable broker means delivery is waiting, not lost. |
| `countsByStatus.FAILED` is non-zero | Events exhausted their retry budget. Inspect `last_error`, fix the cause, then redrive by payment. |
| One payment's events stop while others flow | Expected: a terminally failed event blocks exactly that payment's stream. Redrive it. |
| Projection read returns `404` | No event for that payment has been delivered and projected yet, or the payment belongs to another merchant. |
| `/actuator/health/async` reports `DEGRADED` | Asynchronous delivery is impaired. Readiness stays `UP` because payment commands are unaffected. |
| Readiness is `DOWN` but the broker is fine | Readiness includes the database. Check PostgreSQL, not Kafka. |
| Replay job stays `PENDING` | The replay worker is disabled (`REPLAY_ENABLED=false`) or not running on this instance. |
| Replay job has fewer inputs than expected | Membership is fixed at creation. Payments committed afterwards, or outside the `from` window, are not members. |
| Shadow comparison never appears | Shadow must be enabled *before* the authorization, the event must be delivered, and the shadow worker must be running. |
| `409 POLICY_VERSION_CONFLICT` | That version id already exists with different content. Policy versions are immutable; use a new id. |
| The dashboard shows a sign-in screen immediately after signing in | The session cookie was rejected. Check `SESSION_COOKIE_SECURE`: if it is `true` over plain HTTP the browser will never send the cookie back. |
| A browser mutation returns `CSRF_TOKEN_INVALID` | The page did not send the token from the `XSRF-TOKEN` cookie. Reload the dashboard; a hard refresh reissues it. |
| Everyone is signed out after a restart | Expected. Sessions are held in memory, which is why more than one instance would need shared session storage. |
| `/dashboard/` returns 404 | The jar was built without the frontend, most likely with `-Dskip.frontend=true`. Rebuild without it. |
| Startup fails naming the built-in policy | The in-code `demo-v1` rules changed. Historical decisions name that version, so its meaning must not change: introduce a new version id instead. |

## Operating boundary

This release is intended for local evaluation with synthetic data. Its Compose ports listen only on loopback. A public release still needs a verified hosting plan, TLS, managed credentials, persistence and recovery choices, and an explicit demo access policy. That work belongs to the deployment milestone and has not been silently assumed complete.
