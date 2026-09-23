# Release notes

## v0.10.2 - R7 backup and restore corrections

Revision `a4d94d6aa8cb69a0935111d7791580750d5dd435`, verified by
[CI run 35295428689](https://github.com/beaprogram/Decision-Rail/actions/runs/35295428689) and published by
[release run 35401797703](https://github.com/beaprogram/Decision-Rail/actions/runs/35401797703) as
`ghcr.io/beaprogram/decision-rail:sha-a4d94d6aa8cb69a0935111d7791580750d5dd435` (also `:v0.10.2`; pin to
the `sha-` tag or a digest). Multi-platform **index** (what the tags resolve to)
`sha256:5c851bb410f7823f0e5c000f93489c0ca0cf2040e47e86ef08b4d03c4fcf2c4e`; `linux/arm64` child
`sha256:35fa0daf44d3661f2732ac38dfcd3c73b10618cf1dcaffa4de0c7ea3eb8e7436`; `linux/amd64` child
`sha256:4e775322964a44fa2d8b60e8aa0f36cee8ca7b1ff46eaf251b982adc4237425f`. Release:
[v0.10.2](https://github.com/beaprogram/Decision-Rail/releases/tag/v0.10.2); evidence, including the
procedure exercised against the published image, in [verification.md](verification.md).
Supersedes `v0.10.1` for the two remaining R7 defects; `v0.10.0` and `v0.10.1`, their tags, images and
recordings are preserved unchanged. No visible behaviour changed.

- **Backup no longer infers zero lag from missing evidence.** The v0.10.1 script summed a Kafka CLI's
  `LAG` column, turning an error message, an empty answer, a header, or a `-` into zero - reproduced
  with stubs: each case exited 0, announced zero lag, dumped, and wrote `consumerLagAtSnapshot: 0`.
  Coherence is now asked of PostgreSQL directly: every PUBLISHED outbox event must hold, for each
  consumer group, the receipt or quarantine row that group's contract writes. Anything that is not a
  complete, self-consistent answer is unverified, and unverified is never coherent. An empty
  environment is reported as such. On refusal nothing is written and the application is restarted.
- **Restore establishes coherence before any destructive step.** The v0.10.1 script warned about a
  missing manifest and continued to rename the live database and delete the broker volume -
  reproduced with stubs. Now the manifest is validated (format, version, fields, coherence claim,
  SHA-256 against the dump) before the application is stopped; the dump is restored into a staging
  database; coherence is re-established on the staged data and its published count compared with the
  manifest; and only then is the live database swapped and the broker reset. Every refusal leaves
  the live database and broker untouched and the application stopped.
- **Legacy dumps** (manifestless, v0.10.0 online dumps) are refused by default; `--legacy-dump`
  admits one to the same staging check, which decides.

Public deployment remains pending and the instance is **not ready for exposure** until the live
checks in `deploy/README.md` have run on the actual host.

## v0.10.1 - Checkpoint 10 corrections

Revision `d9cea82`, tag `v0.10.1`, image `ghcr.io/beaprogram/decision-rail:sha-d9cea820d158e66f3013a5c2311ab29a028a2dcc`.
The tag resolves to the multi-platform **index** `sha256:9712ec586d09b0e80de3e78b08ea2b53b045ec97827c7f5b1004698c8c803129`;
its children are `linux/arm64` `sha256:613ba5444bd8086a5f796822d92e3eb88d4bc3ce208a104ffcbc486b288ed702`
(the Oracle Ampere host) and `linux/amd64` `sha256:7666355f2529443e181b5153cda6ac567db9d8e12c4bb3959ae6c7791f33e4c6`,
read fresh from the registry after publication. Supersedes `v0.10.0`, whose tag, image and recordings
are preserved unchanged; the recordings remain accurate because no visible behaviour changed.

A review of the release configuration found eight findings, none in the financial core. Each was
reproduced where it could be, corrected, and covered by a regression:

- **Authentication limiter** - failures are now counted from Spring Security's own authentication
  events on both chains, and a success clears only that username's failures at that address. Before,
  any sub-400 response to a request carrying `Authorization` cleared the address, so an anonymous
  `GET /ui/identity` with a bogus Basic header reset the count (observed: eight wrong administrator
  passwords, no refusal), and a visitor sign-in would have laundered administrator guesses.
- **Forwarding headers** - Caddy now strips every client-supplied `Forwarded`/`X-Forwarded-*` and
  authors them itself; the application uses Tomcat's native remote-ip handling, which believes only a
  private-network peer and never reads `Forwarded`. Observed through the packaged edge before the fix:
  twelve wrong passwords with a different spoofed `Forwarded: for=` each, never refused.
- **Async health** - the group *and every path under it* need an operator credential; nothing under
  `/actuator/health/` other than the two probes is public. Before, `/actuator/health/async/asyncDelivery`
  fell through to the public wildcard.
- **HEAD** - reconciliation is budgeted for `HEAD` too; a refused request never runs the report
  (asserted on the service, not the status).
- **Replay concurrency** - the in-flight and hourly limits are decided inside the job-creation
  transaction under a lock on the visitor's merchant row. Observed before the fix: eight concurrent
  requests against a limit of one created three jobs.
- **Broker storage** - `KAFKA_LOG_DIRS` and `KAFKA_METADATA_LOG_DIR` point at the volume. Observed
  before: an empty volume and the cluster metadata in `/tmp/kafka-logs`. After: cluster id, topic id
  and committed offsets identical across a container removal and recreation.
- **Coherent backup and restore** - `backup.sh` stops the application and requires zero consumer lag
  before dumping; `restore.sh` resets the broker to empty and leaves the application stopped. The
  recovery point and what is discarded are stated in the manifest and the runbook.
- **Rollback ordering** - `restore.sh` no longer starts anything; `rollback.sh --restore` is the one
  workflow for a cross-migration rollback, rehearsed with real V14 and V15 images.
- **Image pin guard** - only `sha-<full commit>` tags or `@sha256:` digests are accepted, with negative
  tests; `stable` and `v0.10.x` are refused.
- **Digest labelling** - the multi-platform *index* digest and the per-architecture child digests are
  now recorded separately; v0.10.0's notes had presented the amd64 child as "the" digest.

Public deployment remains pending on owner actions and available free capacity, and remains
**not ready for public exposure** until the live checks in `deploy/README.md` have been run on the
actual host.

## v0.10.0 - Public demo and release (Checkpoint 10)

Revision `1977084`, tag `v0.10.0`, image `ghcr.io/beaprogram/decision-rail:sha-19770842ab47837fbf0e035c0ae1964b840c4583`.
The tag resolves to the multi-platform **index** `sha256:bfc7f868307d9ed7bc227af4908f33a0fbb930e00f46f8a6d63035bfc3d92b52`,
whose children are `linux/amd64` `sha256:ea621b6cbdfd808fbf4aa17afd5ae9703120905e8dd922588effd3fd3eda4962`
and `linux/arm64` `sha256:78648646c42564e2010333281857a4b71db1dd0b76826f7980ff30ac5024bd2c` - the
arm64 child is what the proposed Oracle Ampere host would run. (An earlier version of this note
presented the amd64 child as the image's digest.) `GET /actuator/info` on a running instance reports
the commit. Recordings are attached to [the release](https://github.com/beaprogram/Decision-Rail/releases/tag/v0.10.0).
**Superseded by v0.10.1** for the findings listed above; the artifacts are preserved as released.

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
- Made backup and restore refuse to guess: a snapshot is coherent only when the database itself shows
  every published event's consumer state, and a restore proves that property on a staging copy before
  it may replace the live database or reset the broker - both defects reproduced before being fixed.
- Packaged the same application for a second, managed target - a supervised container edge plus the
  application, TLS and SASL to managed PostgreSQL and Kafka, inside a 512 MiB budget - with the
  deployment's acceptance checks written as an executable harness rather than a checklist.
