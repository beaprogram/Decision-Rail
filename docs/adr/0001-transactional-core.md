# ADR 0001: Keep payment state, ledger, idempotency, and outbox in one transactional core

- Status: accepted for the initial milestone
- Date: 2026-09-08

## Context

Payment commands can arrive more than once. They can also overlap, time out after committing, or compete for the same available funds. A successful HTTP response is useful only if the persisted payment state and its accounting evidence agree.

Starting with independently deployed services would require distributed consistency decisions before the core payment invariants and failure tests exist. This release has one developer-facing demo workload and synthetic funds; it has no measured scale requirement that calls for distributed writes.

## Decision

Use a Java 21 Spring Boot application with explicit domain modules, Spring JDBC, and PostgreSQL. Persist payment transitions, decision evidence, idempotency results, capture journal entries, and outbox rows inside the same database transaction.

Use database concurrency controls for competing financial mutations. Application-level checks alone are insufficient because two requests can both observe the same pre-mutation state.

Treat capture journal entries as append-only accounting evidence. Financial corrections should be modeled as new operations in a later milestone, rather than rewriting historical entries.

Create durable outbox records now. Defer event publishing, delivery retries, broker provisioning, and downstream consumption until their own milestone. Do not claim that writing an outbox row is equivalent to delivering an event.

## Alternatives considered

| Alternative | Reason not chosen now |
| --- | --- |
| Separate payment, account, ledger, and decision services | Introduces cross-service consistency, compensation, and deployment concerns before the core invariant tests are established. |
| Publish directly to Kafka during the HTTP request | A database commit and broker publish can disagree; neither side shares the other's transaction boundary. |
| Keep idempotency in process memory | Retries after restart or across instances could repeat a mutation. |
| Use floating-point monetary values | Binary rounding can make balances and journals inconsistent. The domain uses integer minor units. |
| Use only a mock/in-memory database in tests | PostgreSQL locks, constraints, and isolation behavior are part of the product's correctness. |

## Consequences

**Benefits:** payment outcomes are atomic, failure cases are easier to reproduce, and operational setup stays small enough to inspect. PostgreSQL integration tests exercise the same database family used by the local deployment.

**Tradeoffs:** modules share a process and database availability boundary. Transactions can contend on hot accounts. The outbox will accumulate until a dispatcher and retention policy are implemented. This design is not a claim of high availability or production readiness.

**Revisit when:** measured contention requires a new partitioning strategy, a separate team owns a domain, broker consumers need independent scaling, or a workload justifies service extraction.
