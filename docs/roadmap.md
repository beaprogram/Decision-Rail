# Delivery plan and progress ledger

Delivery is defined as **10 equally weighted scope checkpoints**; **8 are complete**. Calling that “approximately 80%” is planning shorthand, not a measurement of elapsed time, engineering effort, production readiness, or a guarantee that the remaining checkpoints are equally difficult.

A checkpoint is complete only when its implementation and relevant verification are present. Future milestones below are a delivery plan, not current capabilities.

| # | Checkpoint | Scope | Delivered |
| --- | --- | --- | --- |
| 1 | Foundation and secured API | Java/Spring/PostgreSQL project, migrations, merchant authentication and ownership boundaries, repeatable local startup and CI. | Included |
| 2 | Payment correctness and ledger | Authorize/capture/void lifecycle, durable idempotency, concurrency-safe reservations, balanced append-only capture journal, transactional outbox records. | Included |
| 3 | Explainable versioned decisions | Deterministic policy evaluation, stable policy version, stored decision reasons, coverage of approval and decline paths. | Included |
| 4 | Event delivery | Outbox dispatcher, broker integration, bounded retries, delivery status, and idempotent consumers. | Included |
| 5 | Replay and shadow evaluation | Historical replay against a chosen ruleset, comparison reports, and a shadow path that cannot alter live state. | Included |
| 6 | Resilience controls | Timeouts, bounded retries, dependency fault behavior, circuit-breaker behaviour, and explicit degradation policies. | Included |
| 7 | Operator experience | Searchable payments and decisions, policy comparison views, lifecycle timelines, and an accessible operator UI. | Included |
| 8 | Telemetry and measured performance | Correlated traces and structured logs, operational metrics, load tests, published methodology, and measured limits. | Included |
| 9 | Extended lifecycle and recovery | Refunds/reversals, reconciliation, recovery procedures, and financial correction evidence. | Planned |
| 10 | Public demo and release | Free-budget hosting assessment, secure configuration, synthetic demo data, deployment validation, and a recorded walkthrough. | Planned |

## Definition of done for checkpoints 1 to 3

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

## Definition of done for checkpoints 4 to 6

- [x] A payment committed during a broker outage retains its event intent and stays correct.
- [x] Delivery resumes after recovery with the original event identity.
- [x] A broker acknowledgement followed by a worker crash produces a safe resend, not a lost event.
- [x] A consumer database commit followed by a lost offset commit produces one projection effect.
- [x] Concurrent workers lose no events and never reorder a payment's lifecycle.
- [x] An expired worker cannot complete another worker's claim.
- [x] A terminally failed event blocks only its own payment's stream, and redrive restores order.
- [x] Duplicate, malformed, unsupported-schema, conflicting-identity, and unknown-tenant records are handled explicitly.
- [x] A policy version identifier cannot be rebound to a different definition.
- [x] The same pinned inputs and policy produce the same replay results.
- [x] Replay membership does not change when later payments commit.
- [x] An interrupted replay job resumes without duplicate results or inflated totals.
- [x] Historical decisions are unchanged by replay.
- [x] Insufficient-funds declines are compared as the APPROVE risk decisions they recorded.
- [x] Candidate divergence is demonstrated, with balances, holds, journals, decisions, and events unchanged.
- [x] A slow or throwing candidate cannot delay or fail an authorization.
- [x] Send deadlines, retry budgets, bounded batches, and breaker transitions are enforced and observable.
- [x] Pending work survives a worker restart, and a storage failure never returns a successful financial response.
- [x] An upgrade over existing payment, idempotency, ledger, and outbox records backfills correctly.
- [x] Cross-merchant access to replay jobs, results, and comparisons is denied.
- [x] Privileged routes do not fall through to the broad merchant rule.

The checked items are verification records, not estimates. They are supported by the local 149-test
wrapper verification (Java 21.0.11, PostgreSQL 16.15, Kafka 3.9.1), the 12-check transactional demo,
and the 27-check asynchronous demo, recorded 2026-09-10 UTC. See [PROGRESS.md](PROGRESS.md) for the
full record and the material limitations. Update a checkbox only after the corresponding check has
actually been run.

## Engineering value

Checkpoints 1 to 3 support a concrete discussion about monetary modeling, locking, state machines,
transactional consistency, tenancy, and audit evidence.

Checkpoints 4 to 6 add the harder conversation: why a database commit and a broker publish cannot be
made atomic, what actually establishes event order when timestamps and partition keys do not, which
failure windows remain and why they are acceptable, how a read model stays consistent under
redelivery, how a policy change can be evaluated against real history without touching it, and how a
dependency failure is contained so it degrades one capability instead of the service.

Resume statements should identify this as a synthetic payment platform and mention only shipped
features. Describe delivery as at-least-once with idempotent consumers, never as exactly-once.
Throughput, latency, availability, and recovery claims should be added after repeatable measurements
exist, and fraud accuracy claims only if labelled data ever exists. Do not imply affiliation with
Mastercard, a bank, or a payment network.

## Definition of done for checkpoint 8

- [x] Trace context that survives a restart: written durably beside the event in the transaction that commits it, never in the payload a consumer fingerprints.
- [x] One trace from HTTP command through outbox row, each publication attempt, the broker record, and the committed projection effect.
- [x] Each retry a distinct attempt; an idempotent replay points at the original operation without overwriting its provenance; events without trace context still deliver.
- [x] Structured JSON logs with consistent correlation fields, distinguishing an attempt from a committed effect, an acknowledgement from a fenced completion, a duplicate from a newly applied effect, and a business decline from a technical failure.
- [x] A documented metric catalogue with bounded labels, stated units, stated freshness, and no payment, account, merchant, trace or policy identifier used as a label.
- [x] Database-backed gauges that do not cost more as retained history grows, and that report no data rather than a false zero.
- [x] Telemetry operationally optional: an absent collector changes no payment outcome and no readiness signal, exports are bounded, and nothing exports while a financial lock is held.
- [x] A free, local, provisioned observability stack answering the operator questions without anyone building a dashboard by hand.
- [x] A repeatable harness on isolated infrastructure, with an open load model, warmup excluded, and at least three repetitions for the headline figure.
- [x] Published measurements with environment, workload, sample counts, percentiles per run, the sustained rate, and the first failing level.
- [x] Post-run correctness checks scoped to each run's own accounts, covering funds, journals, idempotency, event intent, ordering and duplicate effects.
- [x] Backend, frontend, browser, demo and CI verification unchanged and passing.

## Definition of done for checkpoint 7

- [x] Browser sign-in, sign-out, session expiry, and an identity response carrying server-resolved capabilities.
- [x] CSRF protection on browser login, logout, and every state-changing request, on a security chain separate from the stateless Basic API.
- [x] Session fixation protection, a real server-side logout, and `HttpOnly` session cookies with an appropriate `SameSite` policy.
- [x] Merchant, administrator, and operations boundaries unchanged; the operations identity gains no dashboard capability.
- [x] An authoritative, merchant-scoped payment search with bounded keyset pagination, deterministic ordering, and validated filters.
- [x] A payment detail screen showing the stored decision, its reason contributions, funding failure separately from risk outcome, the capture journal, and a lifecycle that distinguishes the payment transaction, broker publication, and each consumer group.
- [x] Synthetic authorization, capture, and void from the browser, with exact minor-unit conversion and one idempotency key per logical command reused across retries.
- [x] Policy browsing and candidate registration with structured, path-level validation errors.
- [x] Replay and shadow screens using the server's own report semantics, including the divergence denominator and unavailable rather than zero for a missing measurement.
- [x] An administrative workspace with liveness, readiness, and asynchronous capability as distinct signals, a paginated failed-event list, and redrive from an explicit selection.
- [x] The dashboard is built into the application artifact and served from the same origin, with the single-page fallback scoped so an unknown or denied API path is never rewritten.
- [x] Backend, frontend, and real-browser verification, plus both existing demos, all passing against real PostgreSQL and Kafka.
