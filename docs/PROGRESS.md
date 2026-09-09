# Current delivery and continuation

The initial target is three of ten equally weighted scope checkpoints: **approximately 30% of planned scope**, not 30% of effort or production readiness. The detailed scope and completion criteria are in [roadmap.md](roadmap.md).

## Initial checkpoint scope

1. Java 21/Spring Boot/PostgreSQL foundation, merchant isolation, required credentials, API contract, and repeatable delivery configuration.
2. Authorize/capture/void, durable idempotency, database concurrency protection, balanced append-only capture journal, and transactional outbox records.
3. Deterministic `demo-v1` rules with immutable stored decision explanations and policy version.

## Verification record

Local evidence recorded **2026-09-09 UTC**.

- Static documentation/tooling checks completed: shell syntax, local documentation links, Compose/CI YAML parsing, and the demo's journal predicate.
- Local pinned-wrapper build and suite: **83 tests passed**, with **0 failures, 0 errors, and 0 skipped** (56 domain tests, 25 PostgreSQL integration tests, 2 architecture tests), using Java **21.0.11** and isolated PostgreSQL **14.19**.
- Packaged application startup, migrations, and operator demo: **passed** against the isolated local PostgreSQL demo database. All **12 HTTP checks** passed; balance changed from 1,000,000 to **997,500** minor units with **0 held**, exactly CAD 25.00 captured.
- Additional live inspection: operations metrics returned **200** with two decision evaluations and two replay events; active rules exposed the expected `demo-v1` operators. Sealed-journal behavior is covered by the passing integration suite.
- PostgreSQL 16 and Docker runtime verification: pending the first GitHub Actions run. CI verifies the suite, builds the image, starts it against PostgreSQL 16, waits for health, and executes the operator demo.
- Public deployment: not performed in this milestone.

Record actual commands, test counts, failures, and meaningful limitations here after verification. Do not equate a checked-in CI workflow with a passing remote build.

## Remaining checkpoints

- [ ] 4. Outbox delivery worker, broker integration, bounded retries, and idempotent consumption.
- [ ] 5. Historical replay, shadow evaluation, and policy comparison.
- [ ] 6. Timeouts, retries, failure behavior, circuit breakers, and declared degradation policies.
- [ ] 7. Operator interface for payment state, explanations, and comparisons.
- [ ] 8. Correlated telemetry, reproducible load tests, and measured performance limits.
- [ ] 9. Refunds/reversals, reconciliation, financial corrections, and recovery procedures.
- [ ] 10. Free-budget hosting assessment, secure public demo deployment, and release walkthrough.

## Guidance for the next implementation session

Read the [architecture](architecture.md), [ADR](adr/0001-transactional-core.md), and [API contract](openapi.yaml) before extending the core. Start with checkpoint 4 after confirming the current tests and demo. Use `./mvnw verify` with a dedicated test database; rollback checks temporarily install database triggers and must not run against a concurrently used or production database. Preserve the payment transaction, merchant-scoped request identity, immutable ledger, and stored decision evidence. Outbox rows are durable intent; do not claim event delivery until a verified dispatcher exists.

Keep generated credentials, `.env`, local database/runtime files under `.local/`, and build output out of Git and Docker build context. The local Compose stack deliberately exposes only loopback ports. Publish only features and performance results that have actually been implemented and verified.
