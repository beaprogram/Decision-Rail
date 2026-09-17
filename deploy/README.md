# Deploying the public demo

Everything needed to build, deploy, operate and remove the public portfolio instance, on the host
chosen in [docs/hosting.md](../docs/hosting.md): one Oracle Cloud Always Free Ampere VM running four
containers under Docker Compose. Nothing here costs money, and nothing here touches a development
stack: the Compose project is `decisionrail-public`, and every script checks that name before doing
anything destructive.

## What runs

| Container | Image | Published port | Purpose |
| --- | --- | --- | --- |
| `caddy` | built from `deploy/caddy/` on `caddy:2-alpine` | 80, 443 | HTTPS, security headers, the only way in |
| `app` | `ghcr.io/beaprogram/decision-rail:sha-<commit>` | none | the application: dashboard, `/ui`, `/v1` |
| `database` | `postgres:16-alpine` | none | the system of record, persistent volume |
| `broker` | `apache/kafka:3.9.1` | none | single-node KRaft broker, persistent volume |

Caddy forwards only the dashboard, its two APIs, the two public health probes and `/actuator/info`.
Metrics, tracing and anything else answer 404 at the edge and are reachable only from the host.

## Versions this release was built and verified with

Java 21 (Temurin 21.0.11 JRE in the image), Spring Boot 3.5.16, PostgreSQL 16.15, Apache Kafka 3.9.1,
Caddy 2, Docker Engine 27 with the Compose plugin, Ubuntu 24.04 LTS on arm64. The application image is
built by [.github/workflows/release.yml](../.github/workflows/release.yml) for `linux/arm64` and
`linux/amd64` from a tagged commit, and its `sha-<commit>` tag is what a deployment pins.

## Owner actions, once

These need an account and cannot be scripted from a repository. No secret is pasted anywhere but
the host.

1. **Oracle Cloud Free Tier account** - <https://www.oracle.com/cloud/free/>. Requires a mobile
   number and a payment card for verification; the card is not charged unless the account is
   upgraded, which nothing here needs. Do not upgrade it.
2. **A hostname** - a subdomain at <https://www.duckdns.org> (sign in with GitHub), e.g.
   `decisionrail.duckdns.org`. Note the token.
3. **The instance** - Compute › Create instance: image Ubuntu 24.04, shape `VM.Standard.A1.Flex`
   with 2 OCPU / 12 GB (1 / 6 also works), a public IPv4 address, and under *Advanced options ›
   Management › cloud-init script* the contents of [cloud-init.yaml](cloud-init.yaml) with
   `DEPLOY_REVISION` replaced by the commit being deployed. If creation fails with "out of host
   capacity", retry later or pick another availability domain; it costs nothing either way.
4. **Ingress** - Networking › Virtual cloud networks › your VCN › the default security list: add
   ingress rules for TCP 80 and TCP 443 from `0.0.0.0/0`. (The host firewall is opened by cloud-init;
   the VCN is separate and is the usual reason a new instance "does not respond".)
5. Point the DuckDNS subdomain at the instance's public IP, or let
   [bin/duckdns-update.sh](bin/duckdns-update.sh) do it from cron with the token in
   `deploy/duckdns.token` (mode 600).

## Deploy

On the host, as the `ubuntu` user, after cloud-init has finished:

```bash
cd /opt/decisionrail
DEMO_HOST=decisionrail.duckdns.org \
APP_IMAGE=ghcr.io/beaprogram/decision-rail:sha-<commit> \
  deploy/bin/prepare-env.sh      # generates deploy/public.env; prints the visitor password only
deploy/bin/up.sh                 # pulls, starts, waits for readiness, prints the running revision
deploy/bin/seed-demo.sh          # the guided history, through the API; idempotent
```

`up.sh` refuses an `APP_IMAGE` that is not pinned to a revision, and `GET /actuator/info` on the
running instance reports the commit, image, build time and newest migration, so what is deployed can
always be matched to the release notes and the CI run.

To verify from anywhere:

```bash
curl -s https://decisionrail.duckdns.org/actuator/info | jq .decisionrail
curl -s https://decisionrail.duckdns.org/actuator/health/readiness
```

## Operate

| Script | What it does |
| --- | --- |
| `bin/status.sh` | liveness, readiness, async delivery details (with the operations credential), revision, containers |
| `bin/backup.sh` | `pg_dump` custom-format archive to `deploy/backups/`, named with the schema version; copy it off the host |
| `bin/restore.sh <dump>` | stops the app, replaces the database from the dump, starts the app; requires typing the project name |
| `bin/rollback.sh <image>` | switches to an older image **only if** it knows every migration the database has applied; otherwise tells you to restore first |
| `bin/reset-sandbox.sh` | destroys the database and broker volumes and re-seeds; requires typing the project name |
| `bin/teardown.sh` | removes the stack and its volumes; requires typing the project name |
| `bin/duckdns-update.sh` | keeps the DNS name pointed at this host (cron) |

Every script takes `--help`, prints what it will do, and does nothing else on that path.

### Health, and what each answer means

- `GET /actuator/health/liveness` - the process answers. This is what the container health check
  uses, so a broker outage never restarts a healthy payment API.
- `GET /actuator/health/readiness` - the database is reachable and a payment command could succeed.
- `GET /actuator/health/async` - **operator credential required on the public instance.** Whether
  asynchronous delivery is impaired: consumers running, breaker state, undelivered count and age,
  terminally failed count. `DEGRADED` here with readiness `UP` means payments are fine and events
  are waiting; that is the designed behaviour during a broker outage, not an incident.

### What a restart does

Sessions are in memory. Restarting the application signs every visitor out; nothing else is lost -
payments, ledger, returns, idempotency records and the outbox are in PostgreSQL, and delivery resumes
from the outbox with the original event identities. Restarting the broker is a delivery outage the
application rides out: payments continue, readiness stays `UP`, undelivered events are counted, and
they are published when the broker returns. The in-memory visitor budgets and the authentication
limiter reset on an application restart; that is acceptable for what they protect.

### Backup, restore and rollback - the honest limits

- The schema only moves forward. An older image cannot run against a database that has applied a
  migration it does not know, and `rollback.sh` checks exactly that before switching. Rolling back
  across a migration boundary means **restore the pre-upgrade backup, then the older image**.
  Restoring a backup taken at an older schema into a newer image is fine: the migrations apply on
  start, subject to the same validation any upgrade gets, and may refuse an inconsistent database
  rather than repair it.
- The broker is one node with replication factor 1. Events that were **acknowledged** by the broker
  and then lost with its volume are not reconstructed by the outbox, which only resends what was never
  acknowledged. The projection and audit tables in PostgreSQL still hold what those events produced.
  Nothing here claims otherwise.
- `backup.sh` does not back up the broker volume, for that reason.
- A backup on the host's own disk protects against a mistake, not against losing the host. Copy it
  somewhere else; the Always Free tenancy's object storage or a laptop both cost nothing.

### Reset

There is no reset endpoint, no editing of ledger history, and no in-place repair. The sandbox is
expendable: `reset-sandbox.sh` destroys its volumes and starts again, then re-seeds. It asks for the
project name to be typed - `decisionrail-public` - and refuses anything else. The development stack is
a different Compose project (`decisionrail`) with a different file, and no script here can reach it.

## Security model, briefly

Detailed in [docs/architecture.md](../docs/architecture.md) and ADR-0005. On the public instance:

- One **visitor** identity, an ordinary merchant whose password is public and shown on the sign-in
  page. Everyone who visits shares it and sees what everyone else has done; the page says so.
- Four **private** identities - `demo-merchant`, `other-merchant`, `operations`, `admin` - with
  generated passwords that exist only in `deploy/public.env` on the host. Policy registration, shadow
  configuration, redrive and the async health details need them.
- The visitor is **budgeted on the server, on both APIs**: 30 state-changing requests a minute, 6
  replay jobs an hour and one in flight, 10 reconciliation reports a minute, and 300 payments per
  account. A refusal is `429` with `Retry-After` and a plain explanation, before anything is written.
  The three accounts are `aaaa0001-0000-4000-8000-000000000001/2` (CAD) and `…3` (USD, deliberately
  small so a funds decline can be shown).
- Ten failed sign-ins from one address in fifteen minutes lock that address out of authenticating,
  on both APIs, until the window passes.
- Session and CSRF cookies are `Secure`; the application trusts forwarded headers only because Caddy
  is the only thing able to reach it.
- Fault injection is off and the application refuses to start if it is not.

## Remove

`deploy/bin/teardown.sh`, then terminate the instance in the console. Nothing remains that costs
anything at any point, before or after.
