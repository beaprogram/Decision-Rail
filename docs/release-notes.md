# Release notes

## v0.10.0 - Public demo and release (Checkpoint 10)

Revision: see the git tag `v0.10.0` and `GET /actuator/info` on any running instance.

### What is new

- **A public-demo mode** (`PUBLIC_DEMO_ENABLED=true`), off everywhere else. It adds one shared
  `visitor` merchant whose password is public and shown on the sign-in page, and applies server-side
  budgets to that identity on both API chains: state-changing commands per minute, replay jobs per
  hour and in flight, reconciliation reports per minute, and payments per account. A refusal is
  `429` with `Retry-After` and a plain explanation, issued before anything is claimed or written.
- **An authentication attempt limiter** per client address, on both chains, for the public instance.
- **Operator-only async health** on the public instance: `/actuator/health/async` needs an operator
  credential; liveness and readiness stay public and say only their status.
- **`GET /actuator/info`** reports the commit, image, build time and newest migration of the running
  revision, and nothing about its configuration.
- **A deployment bundle** under `deploy/`: Compose with persistent volumes and memory limits, Caddy
  for automatic HTTPS and security headers, a cloud-init for the host, and scripts for secrets,
  deploy, seed, status, backup, restore, rollback (migration-aware), sandbox reset and teardown.
  A release workflow publishes the image to GHCR for arm64 and amd64 with the commit stamped in.
- **A hosting assessment** against current official documentation, with the zero-cost conditions,
  the chosen option and its trade-offs stated: [hosting.md](hosting.md).
- **A recorded walkthrough** of the deployed configuration, visitor and operator segments:
  [walkthrough.md](walkthrough.md).
- **Every demo script** (`demo.sh`, `lifecycle-demo.sh`, `async-demo.sh`, `recovery-demo.sh`) now
  answers `--help` without doing anything, and refuses unknown arguments, with a test that proves
  those paths reach neither `.env` nor any operational command.

### What did not change

The payment transaction boundary, idempotency, ledger and return rules, merchant isolation, event
ordering and delivery guarantees, replay and shadow isolation, and the `/ui` session + CSRF chain
kept separate from the stateless `/v1` chain. No schema migration was needed: the visitor's merchant
row and accounts are data written idempotently at startup when the mode is on. No benchmark was
re-run and no performance claim changed.

### Known limits of the public instance

Single instance with in-memory sessions (a restart signs everyone out); single broker with
replication factor 1; a shared sandbox with no per-visitor isolation; a free-tier host that may stop
an idle instance. All stated in the README and `deploy/README.md`.

### Deployment status at release

Implementation finished and release verified locally in the deployed shape. **Public deployment is
pending** two owner actions - an Oracle Cloud Free Tier account and a DuckDNS name - documented in
`deploy/README.md`. No public URL is claimed until it exists.

## Resume bullets, defensible as written

- Built a Java 21 / Spring Boot payment decisioning service with PostgreSQL and Kafka: idempotent
  authorize / capture / void / refund / reversal under a per-merchant request identity, a balanced
  double-entry ledger sealed by database constraints, and a read-only reconciliation that derives
  expected balances from the ledger and never repairs what it finds.
- Implemented at-least-once event delivery with a transactional outbox, per-payment ordering under
  the row lock, lease-fenced dispatch, a circuit breaker at the broker boundary, and idempotent
  consumers - verified with fault injection, broker outages and a real process-restart recovery demo.
- Closed three database-invariant gaps found in review by adding validated migrations that refuse an
  inconsistent upgrade rather than repairing financial history, each reproduced first and covered by
  regression tests against real PostgreSQL.
- Measured 25 business operations/s sustained on one laptop with zero failures through a 25-second
  broker outage, with the method, ceiling and limitations published.
- Shipped a React/TypeScript operator console with session + CSRF protection separate from the
  stateless API, tenant isolation on every route, and a Playwright suite run with retries disabled.
- Prepared a zero-cost public deployment: an assessed and documented free host, a bounded shared
  visitor identity with server-enforced budgets on both APIs, HTTPS at the edge, migration-aware
  rollback and rehearsed backup/restore, plus a recorded walkthrough.
