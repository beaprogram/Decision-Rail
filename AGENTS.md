# DecisionRail engineering guide

DecisionRail is an independent, synthetic payment decisioning portfolio project. This repository lives on the user's PortableSSD; do not create another checkout on Desktop.

## Current delivery boundary

Read docs/PROGRESS.md and docs/roadmap.md before continuing. The first delivery covers foundation, financial correctness, and explainable versioned rules. Replay, Kafka delivery, policy authoring/promotion, Redis features, refunds, UI, and public deployment require later milestones. Do not represent planned capabilities as shipped.

## Invariants

- Authorizations reserve integer currency minor units without exceeding available funds.
- All mutations use a merchant-scoped idempotency key and a canonical operation fingerprint.
- State, balances, journal, audit, outbox intent and successful idempotency result commit together.
- Capture journals are balanced, tied to captured payment amount/currency, and sealed after commit.
- Merchant identity comes from authentication; every resource query enforces ownership.
- Rules remain independent of Spring, HTTP and persistence so replay can reuse the same pure evaluator.
- Real funds, card numbers and invented performance results do not belong in this project.

## Validation and workflow

Use Java 21 and ./mvnw verify against a dedicated PostgreSQL database (JDBC_URL/JDBC_USERNAME/JDBC_PASSWORD). Integration tests deliberately inject database failures; never point them at production. Run the local operator demo for changes affecting runtime/API behavior. Keep schema changes in new Flyway migrations, maintain docs/openapi.yaml, and document meaningful tradeoffs.

Commit only reviewed source and docs. Keep .env, .local, database files and build output untracked. Preserve unrelated user changes and do not force-push. Push only when instructed; the user cancelled daily automation and will request work manually. Hosting budget is zero; no paid services or resources without explicit authorization.
