# Delivery plan and progress ledger

The requested initial delivery is defined as **3 of 10 equally weighted scope checkpoints**. Calling this “approximately 30%” is a planning shorthand, not a measurement of elapsed time, engineering effort, production readiness, or a guarantee that the remaining checkpoints are equally difficult.

A checkpoint is complete only when its implementation and relevant verification are present. Future milestones below are a delivery plan, not current capabilities.

| # | Checkpoint | Scope | Initial delivery |
| --- | --- | --- | --- |
| 1 | Foundation and secured API | Java/Spring/PostgreSQL project, migrations, merchant authentication and ownership boundaries, repeatable local startup and CI. | Included |
| 2 | Payment correctness and ledger | Authorize/capture/void lifecycle, durable idempotency, concurrency-safe reservations, balanced append-only capture journal, transactional outbox records. | Included |
| 3 | Explainable versioned decisions | Deterministic policy evaluation, stable policy version, stored decision reasons, coverage of approval and decline paths. | Included |
| 4 | Event delivery | Outbox dispatcher, broker integration, bounded retries, delivery status, and idempotent consumers. | Planned |
| 5 | Replay and shadow evaluation | Historical replay against a chosen ruleset, comparison reports, and a shadow path that cannot alter live state. | Planned |
| 6 | Resilience controls | Timeouts, bounded retries, dependency fault behavior, circuit-breaker experiments, and explicit degradation policies. | Planned |
| 7 | Operator experience | Searchable payments and decisions, policy comparison views, lifecycle timelines, and an accessible operator UI. | Planned |
| 8 | Telemetry and measured performance | Correlated traces and structured logs, operational metrics, load tests, published methodology, and measured limits. | Planned |
| 9 | Extended lifecycle and recovery | Refunds/reversals, reconciliation, recovery procedures, and financial correction evidence. | Planned |
| 10 | Public demo and release | Free-budget hosting assessment, secure configuration, synthetic demo data, deployment validation, and a recorded walkthrough. | Planned |

## Definition of done for the initial delivery

- [x] Java 21 build and PostgreSQL integration checks pass.
- [x] Duplicate command retries return the original result without repeating the mutation.
- [x] Reuse of an idempotency key with a different request is rejected.
- [x] Concurrent authorizations cannot over-reserve an account.
- [x] Capture and void enforce valid transitions under competing requests.
- [x] Capture creates balanced, immutable accounting evidence.
- [x] Stored decisions expose their policy version and reasons.
- [x] Merchant isolation and rejected access paths are tested.
- [x] The operator demo succeeds against the running service.
- [x] Repository documentation clearly distinguishes delivered and planned capabilities.

The checked items are supported by the local 84-test wrapper verification (Java 21.0.11 and PostgreSQL 14.19), the 12-check live packaged-application demo, and documentation review, recorded 2026-09-09 UTC. PostgreSQL 16 and Docker runtime verification also passed on the initial pushed revision; see [PROGRESS.md](PROGRESS.md) for the linked CI record. The checkboxes are verification records, not estimates. They should be updated only after the corresponding checks have been run.

## Engineering value

This first milestone supports a concrete portfolio discussion about monetary modeling, locking, state machines, transactional consistency, tenancy, and audit evidence. Later milestones will add event delivery, fault handling, and measured system behavior.

Resume statements should identify this as a synthetic payment platform and mention only shipped features. Throughput, latency, availability, and recovery claims should be added after repeatable measurements exist. Do not imply affiliation with Mastercard, a bank, or a payment network.
