# DecisionRail engineering guide

DecisionRail is an independent, synthetic payment decisioning portfolio project. This repository lives on the user's PortableSSD; do not create another checkout on Desktop.

## Current delivery boundary

Read docs/PROGRESS.md and docs/roadmap.md before continuing. Nine of ten checkpoints are complete. The tenth - the public demo - is closed as packaged, unit-tested, rehearsed and published, **without a live deployment**: two deployment targets are built and their images published, the acceptance checks are executable (deploy/render/live-check.py), and no instance serves them at any URL. Redis features and candidate policy promotion remain later milestones. Do not represent planned capabilities as shipped, do not claim a public URL that does not exist, and do not describe the live acceptance checks as having run against a managed deployment.

## Invariants

- Authorizations reserve integer currency minor units without exceeding available funds.
- All mutations use a merchant-scoped idempotency key and a canonical operation fingerprint.
- State, balances, journal, audit, outbox intent and successful idempotency result commit together.
- Capture journals are balanced, tied to captured payment amount/currency, and sealed after commit. A payment keeps exactly one; every return adds its own compensating journal that reverses it and names the operation it records. Corrections are new entries, never edits.
- Refunds and post-capture reversal share one capped return budget. A payment can never credit back more than it captured, enforced on every write by a row-level CHECK. The equality between a payment's returned total and the sum of its return operations is enforced by the database at all three points a transaction can break it: inserting the payment, updating its returned or captured total, and inserting a return operation. All three are deferred constraint triggers over one shared function, so the rule is judged on the transaction's final state and a legitimate intermediate state inside it is still allowed. A return does not change the payment's status; the totals do.
- A return credits the funding balance only. Holds belong to other authorizations and are never touched by one.
- A payment's currency must equal its funding account's, enforced structurally by a composite foreign key with no ON UPDATE cascade.
- A payment's id is immutable. Journals, returns, events, audit records and stored idempotency responses all name it, and a deferred check that captured one id must still find that row at commit; changing it was how the returned-total check was walked away from.
- Reconciliation is read-only. It derives expectations from the ledger and the operations that wrote it, never repairs what it finds, and is never reported as clean for a partially examined population.
- Merchant identity comes from authentication; every resource query enforces ownership.
- Rules remain independent of Spring, HTTP and persistence; replay and shadow reuse the same pure evaluator.
- Each event gets a durable per-payment sequence assigned inside the payment transaction, and only the lowest unpublished sequence for a payment is claimable. Timestamps, random ids, partition keys and SKIP LOCKED do not establish order.
- Event identity and payload are immutable across retries and redrive; a published row cannot be rewritten.
- A broker send is complete only on acknowledgement, outside any database transaction, and completions are fenced by lease token.
- A consumer commits its deduplication record and its effect together, then acknowledges the offset. Delivery is at-least-once with idempotent effects, never described as exactly-once.
- A policy version identifier cannot be rebound to a different definition, and a candidate is never authoritative.
- Replay pins its membership and inputs at job creation, compares against the stored risk decision rather than payment status, and derives totals from recorded results.
- Replay and shadow must not depend on PaymentService, PaymentStore or OutboxStore. ArchUnit enforces this; do not relax it for convenience.
- A broker or worker failure must never cause a fail-open decision, and a database failure must never return a successful financial response. The application must start and serve payments when the broker is unreachable, including when its name does not resolve; listeners start off the startup path and retry, and never fail the context.
- Real funds, card numbers and invented performance results do not belong in this project. No fraud accuracy metrics without labelled data.
- The browser API lives on /ui with a session cookie and CSRF protection; /v1 stays stateless Basic with CSRF disabled. They are separate chains on purpose: a session cookie must buy nothing on /v1, and neither chain may borrow the other's protections. Do not merge them.
- Capabilities in the identity response are presentation only. Hiding a control is not authorization; every endpoint enforces its own rule. OPERATIONS stays metrics-only however operator-shaped the product becomes.
- The single-page fallback is scoped to /dashboard/**. An unknown or denied API path must keep its status code and never become HTML with 200.
- Money stays integer minor units end to end. Never convert a typed amount by multiplying a parsed float; the browser converts on the digit string and rejects excess precision rather than rounding it.
- One idempotency key per logical command, reused across retries. A timeout is an unknown outcome, not a failure, and must never mint a new key.
- A benchmark reports the measured phase only, with rates computed over the declared window; database reconciliation covers the whole run because warmup moves the same synthetic money.
- Correlation is durable, not thread-local: an event carries the trace of the command that committed it, written in the same transaction and never in the payload a consumer fingerprints. Telemetry is always optional — an absent collector changes no payment outcome, and no export happens while a financial lock is held.
- The public-demo mode (`app.public-demo.enabled`) is off everywhere but the public instance. On, it adds one shared visitor merchant whose password is public by design, budgets that identity on the server on both API chains (commands, replay jobs, reconciliation reports, payments per account) with a `429` issued before anything is claimed or written, limits failed authentication per client address, makes `/actuator/health/async` an operator read, and refuses to start with fault injection enabled or without Secure cookies. `/v1` is never a way around a public-demo restriction. Private identities' credentials exist only on the host. The deployment under `deploy/` uses the Compose project `decisionrail-public`, and every destructive script there requires the project name typed; none can reach the development stack.
- Metric labels stay bounded. Never label a metric with a payment, account, merchant, trace or request id, a policy version, an exception message or a raw URL; those belong on spans and in the operator APIs.

## Validation and workflow

Use Java 21 and ./mvnw verify against the dedicated disposable stack in compose.test.yaml (PostgreSQL on 55433, Kafka on 19092); the test profile defaults to it. Integration tests inject database failures, publish synthetic events and create a throwaway database; never point them at a development or production target. Infrastructure checks must fail loudly when a dependency is missing, never skip.

Prefer injected clocks, explicit failpoints and bounded polling over sleeps. A test named for a restart must leave the state a killed process leaves and recover through durable state, not call the same method twice. Scope assertions and fault injection to the payments a test created, because the suite shares one database.

Every demo script answers `--help` and refuses unknown arguments before sourcing configuration or running any command; DemoScriptArgumentSafetyTest proves it with failing stubs, so keep any new script to the same shape. Run all three demos for changes affecting runtime or API behavior: scripts/demo.sh, scripts/lifecycle-demo.sh and scripts/async-demo.sh. Run the asynchronous one last: it stops the broker, so anything after it runs against an outage it caused. scripts/recovery-demo.sh is separate and self-contained: it builds and destroys its own stack from compose.recovery.yaml and touches nothing else, so run it for changes to delivery, startup or the outbox. Changes touching the dashboard or the browser API also need the Playwright suite in frontend/, run against a running application with real infrastructure; ./mvnw verify already typechecks, lints, unit-tests and builds the frontend. Keep generated assets, node_modules, browser traces and screenshots untracked. Keep schema changes in new Flyway migrations after V15, never edit an applied one, and extend the migration upgrade check when a migration backfills anything. A migration that declares an invariant over existing rows validates them first and refuses with actionable detail rather than repairing financial data, and takes SHARE ROW EXCLUSIVE on the tables involved so no incompatible write can commit between the validation and the protection. Maintain docs/openapi.yaml and document meaningful tradeoffs in an ADR.

Commit only reviewed source and docs. Keep .env, .local, database files and build output untracked. An existing .env from an earlier milestone lacks ADMIN_PASSWORD; append one rather than regenerating the file, and never rotate existing credentials. Fault injection (app.events.fault-injection-enabled) stays false outside local and test configuration, and must never gain an HTTP surface.

Preserve unrelated user changes and do not force-push. Push only when instructed; the user cancelled daily automation and will request work manually. Hosting budget is zero; no paid services or resources without explicit authorization.
