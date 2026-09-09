# Verification strategy

The central question is whether payment and ledger state remain consistent under retries, rejected requests, and overlapping mutations. A green happy-path HTTP response alone cannot establish that.

## Run the suite

JDK 21 and PostgreSQL are required. The checked-in wrapper downloads pinned Maven 3.9.12 on its first run. For the documented local setup:

```bash
./scripts/prepare-local-env.sh
docker compose up -d database
# Create the dedicated database once; skip this line if it already exists.
docker compose exec database createdb -U decisionrail decisionrail_test
set -a
source .env
set +a
JDBC_URL=jdbc:postgresql://localhost:5432/decisionrail_test \
  ./mvnw --batch-mode --no-transfer-progress verify
```

The test profile uses its own fixture accounts, but rollback tests temporarily install database triggers. Use a dedicated test database with no application or other test suite writing concurrently; never point the suite at production or a database containing important data. The CI job provisions an ephemeral PostgreSQL 16 service and exports the same connection variable names, then builds and starts the Docker image, waits for health, and runs the operator demo without publishing the image.

Test reports are written under `target/surefire-reports/`; the JaCoCo report is generated under `target/site/jacoco/`. CI uploads available reports when a verification job finishes, including on failure. Coverage is a diagnostic aid, not a substitute for meaningful assertions.

## Recorded local result

The initial wrapper verification passed **83 tests**: 56 domain tests, 25 PostgreSQL integration tests, and 2 architecture tests. There were no failures, errors, or skipped tests. This run used Java 21.0.11 and PostgreSQL 14.19 and was recorded on 2026-09-09 UTC. The packaged application passed all 12 operator-demo HTTP checks; final demo funds were 997,500 minor units with no holds after a CAD 25.00 capture. Protected metrics and active rule operators were also inspected successfully. PostgreSQL 16 and actual container startup are a separate CI verification, pending the first remote run.

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

These are the design targets for the relevant checks. The source tests and their actual results are the authority on which cases are currently exercised; do not infer a passing result from this table.

## Why PostgreSQL-backed checks

Mocks can test orchestration and pure rules, but they cannot validate PostgreSQL row-lock behavior, deferred constraint triggers, transaction rollback, or a duplicate-key race. Those properties are part of this implementation and need tests against PostgreSQL.

Pure policy evaluation can be tested separately without the web server or database. This keeps rule semantics easy to understand while the integration suite focuses on state and transaction boundaries.

## Operator smoke check

With the backend running:

```bash
./scripts/demo.sh
```

This validates an externally observable sequence and checks the final synthetic balance. Run it against an idle demo account; other writers can legitimately change the balance while the script is checking it. The script is complementary to the integration suite and is not a concurrency or performance benchmark.

## Claims deliberately deferred

No throughput, tail latency, recovery-time, availability, or production-readiness claims are published for this milestone. A future performance milestone must record hardware, configuration, workload, duration, concurrency, error rate, and latency distribution alongside its results.
