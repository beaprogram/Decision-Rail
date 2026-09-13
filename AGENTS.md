# DecisionRail engineering guide

DecisionRail is an independent, synthetic payment decisioning portfolio project. This repository lives on the user's PortableSSD; do not create another checkout on Desktop.

## Current delivery boundary

Read docs/PROGRESS.md and docs/roadmap.md before continuing. Eight of ten checkpoints are complete: foundation, financial correctness, explainable versioned rules, Kafka event delivery, replay and shadow evaluation, resilience controls, the operator console, and correlated telemetry with measured performance. Refunds and reconciliation, Redis features, candidate policy promotion, and public deployment require later milestones. Do not represent planned capabilities as shipped.

## Invariants

- Authorizations reserve integer currency minor units without exceeding available funds.
- All mutations use a merchant-scoped idempotency key and a canonical operation fingerprint.
- State, balances, journal, audit, outbox intent and successful idempotency result commit together.
- Capture journals are balanced, tied to captured payment amount/currency, and sealed after commit.
- Merchant identity comes from authentication; every resource query enforces ownership.
- Rules remain independent of Spring, HTTP and persistence; replay and shadow reuse the same pure evaluator.
- Each event gets a durable per-payment sequence assigned inside the payment transaction, and only the lowest unpublished sequence for a payment is claimable. Timestamps, random ids, partition keys and SKIP LOCKED do not establish order.
- Event identity and payload are immutable across retries and redrive; a published row cannot be rewritten.
- A broker send is complete only on acknowledgement, outside any database transaction, and completions are fenced by lease token.
- A consumer commits its deduplication record and its effect together, then acknowledges the offset. Delivery is at-least-once with idempotent effects, never described as exactly-once.
- A policy version identifier cannot be rebound to a different definition, and a candidate is never authoritative.
- Replay pins its membership and inputs at job creation, compares against the stored risk decision rather than payment status, and derives totals from recorded results.
- Replay and shadow must not depend on PaymentService, PaymentStore or OutboxStore. ArchUnit enforces this; do not relax it for convenience.
- A broker or worker failure must never cause a fail-open decision, and a database failure must never return a successful financial response.
- Real funds, card numbers and invented performance results do not belong in this project. No fraud accuracy metrics without labelled data.
- The browser API lives on /ui with a session cookie and CSRF protection; /v1 stays stateless Basic with CSRF disabled. They are separate chains on purpose: a session cookie must buy nothing on /v1, and neither chain may borrow the other's protections. Do not merge them.
- Capabilities in the identity response are presentation only. Hiding a control is not authorization; every endpoint enforces its own rule. OPERATIONS stays metrics-only however operator-shaped the product becomes.
- The single-page fallback is scoped to /dashboard/**. An unknown or denied API path must keep its status code and never become HTML with 200.
- Money stays integer minor units end to end. Never convert a typed amount by multiplying a parsed float; the browser converts on the digit string and rejects excess precision rather than rounding it.
- One idempotency key per logical command, reused across retries. A timeout is an unknown outcome, not a failure, and must never mint a new key.
- A benchmark reports the measured phase only, with rates computed over the declared window; database reconciliation covers the whole run because warmup moves the same synthetic money.
- Correlation is durable, not thread-local: an event carries the trace of the command that committed it, written in the same transaction and never in the payload a consumer fingerprints. Telemetry is always optional — an absent collector changes no payment outcome, and no export happens while a financial lock is held.
- Metric labels stay bounded. Never label a metric with a payment, account, merchant, trace or request id, a policy version, an exception message or a raw URL; those belong on spans and in the operator APIs.

## Validation and workflow

Use Java 21 and ./mvnw verify against the dedicated disposable stack in compose.test.yaml (PostgreSQL on 55433, Kafka on 19092); the test profile defaults to it. Integration tests inject database failures, publish synthetic events and create a throwaway database; never point them at a development or production target. Infrastructure checks must fail loudly when a dependency is missing, never skip.

Prefer injected clocks, explicit failpoints and bounded polling over sleeps. A test named for a restart must leave the state a killed process leaves and recover through durable state, not call the same method twice. Scope assertions and fault injection to the payments a test created, because the suite shares one database.

Run both demos for changes affecting runtime or API behavior: scripts/demo.sh and scripts/async-demo.sh. Changes touching the dashboard or the browser API also need the Playwright suite in frontend/, run against a running application with real infrastructure; ./mvnw verify already typechecks, lints, unit-tests and builds the frontend. Keep generated assets, node_modules, browser traces and screenshots untracked. Keep schema changes in new Flyway migrations after V9, never edit an applied one, and extend the migration upgrade check when a migration backfills anything. Maintain docs/openapi.yaml and document meaningful tradeoffs in an ADR.

Commit only reviewed source and docs. Keep .env, .local, database files and build output untracked. An existing .env from an earlier milestone lacks ADMIN_PASSWORD; append one rather than regenerating the file, and never rotate existing credentials. Fault injection (app.events.fault-injection-enabled) stays false outside local and test configuration, and must never gain an HTTP surface.

Preserve unrelated user changes and do not force-push. Push only when instructed; the user cancelled daily automation and will request work manually. Hosting budget is zero; no paid services or resources without explicit authorization.
