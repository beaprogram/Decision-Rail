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

`up.sh` and `rollback.sh` accept an image only as `<repository>:sha-<full 40-hex commit>`,
`<repository>@sha256:<digest>`, or both, and refuse every other tag - `latest`, `main`, `stable`, and
the release's own `v0.10.x` name, which is a convenience label rather than a pin. The form is what the
guard enforces; that a `sha-` tag really is that commit's build is the release workflow's guarantee,
and `GET /actuator/info` on the running instance is how it is checked against the release notes and
the CI run. Dependency images (`postgres:16-alpine`, `apache/kafka:3.9.1`, `caddy:2-alpine`) are
mutable tags: they name a major or minor line, not a byte-identical image, and are not claimed to.

To verify from anywhere:

```bash
curl -s https://decisionrail.duckdns.org/actuator/info | jq .decisionrail
curl -s https://decisionrail.duckdns.org/actuator/health/readiness
```

## Operate

| Script | What it does |
| --- | --- |
| `bin/status.sh` | liveness, readiness, async delivery details (with the operations credential), revision, containers |
| `bin/backup.sh` | a **coherent** backup: stops the app, waits for zero consumer lag, dumps the database, writes a manifest, starts the app. Refuses if lag cannot reach zero or the broker cannot be asked |
| `bin/restore.sh <dump>` | stops the app, replaces the database from the dump (into a fresh database, swapped in only on success), resets the broker to empty, and **leaves the app stopped**; requires typing the project name |
| `bin/rollback.sh <image> [--restore <dump>]` | switches to an older image only if it knows every migration the database has applied; with `--restore`, restores the pre-upgrade backup first and starts the older image in one workflow, without the newer image ever running against the restored data |
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

### Backup and restore - what "coherent" means here

PostgreSQL is the system of record, but the consumers' committed positions live in the broker, so an
online database snapshot and the broker's state describe different moments. The failure that leaves
open is concrete: an event PUBLISHED before the snapshot whose projection effect and offset commit
land after it. Restoring that snapshot removes the effect and its receipt while the broker still
holds the advanced offset; the outbox never resends a PUBLISHED event, so the effect is simply gone.

The procedure closes that window rather than hoping it is narrow:

- **`backup.sh` quiesces.** It stops the application (nothing publishes, nothing consumes), confirms
  both consumer groups have **zero lag**, and only then dumps. At that instant every published event's
  effects are in the dump, and anything still PENDING in the outbox is in the dump too. That instant is
  the **recovery point**, recorded in the manifest beside the dump. If lag does not reach zero within
  `BACKUP_QUIESCE_SECONDS` (default 60), or the broker cannot be asked, it refuses and starts the
  application again - a dump taken with lag would not be restorable coherently. The pause is a few
  seconds of visitor-facing downtime.
- **`restore.sh` resets the broker.** After the database is replaced, the broker's volume is removed
  and the broker recreated empty. Its copy of the pre-recovery-point records is not needed - their
  effects are in the dump - and everything it received afterwards belongs to activity the restore
  discards, whose offsets would otherwise point past events the restored database has no receipt for.
  PENDING events at the recovery point are re-dispatched from the outbox when the application
  starts; deduplication by event id means nothing is applied twice.
- **What is discarded, plainly:** every payment, return, replay job, shadow comparison and event
  after the recovery point. This is a synthetic demo; that is acceptable, and it is stated.
- **`restore.sh` leaves the application stopped** and says what to run next: `up.sh` for the current
  image, or `rollback.sh <image> --restore <dump>` when the backup predates a migration.
- **A failed restore changes nothing.** The dump is restored into a fresh database and swapped in
  only when that succeeds; a bad dump leaves the previous database, the broker and a stopped
  application exactly as they were.

The rehearsal in [docs/verification.md](../docs/verification.md) covers the refusal with lag, the
refusal with an unreachable broker, a coherent backup, activity published and consumed after it, the
restore back to identical financial evidence, projection rows and receipt identities, delivery of new
events exactly once afterwards, and the failed-restore path.

### Rollback - the migration boundary

The schema only moves forward. An older image cannot run against a database that has applied a
migration it does not know, and `rollback.sh` reads the newest migration the image carries and
compares it with `flyway_schema_history` before doing anything. Without `--restore` it refuses across
that boundary and leaves the application **stopped**; with `--restore <pre-upgrade dump>` it restores
first and then starts the older image, so the newer image never gets a chance to re-apply its
migrations to the restored data. Rehearsed end to end: an image at V14, an upgrade to V15, a refused
direct rollback, then a restore-and-rollback that ended at V14 with no V15 row ever re-applied, and a
forward upgrade again afterwards.

Restoring a backup taken at an older schema into a *newer* image is fine: the migrations apply on
start, subject to the same validation any upgrade gets, and may refuse an inconsistent database
rather than repair it. No claim is made that arbitrary versions are compatible in the other direction.

### Broker storage

Records and KRaft metadata live on the `kafka-data` volume, at `/var/lib/kafka/data/logs` and
`/var/lib/kafka/data/metadata`, set explicitly by `KAFKA_LOG_DIRS` and `KAFKA_METADATA_LOG_DIR`. That
matters because the Apache image builds the broker's configuration from environment variables alone
and does not merge its bundled `server.properties`: the v0.10.0 Compose file mounted the volume but
set neither variable, so the broker wrote to its compiled-in default, `/tmp/kafka-logs`, inside the
container's writable layer. A `stop`/`start` kept that; removing and recreating the container lost it.
Both were observed on a disposable stack, and with the variables set the cluster id, topic id,
log-end offsets and committed consumer offsets were identical across a container removal and
recreation.

**An installation that ran the v0.10.0 file** holds its broker data in the old container's layer, not
on the volume. Applying this change and recreating the broker starts it **empty** - the new path does
not inherit the old layer's contents. Before recreating: take a coherent backup with `backup.sh`
(which needs zero lag, i.e. everything published has been consumed), then let `restore.sh`'s broker
reset be the migration - the outbox re-dispatches what was pending, and the projection already holds
what was consumed. That is the same procedure as any restore, and it is the only one offered; no
attempt is made to copy files out of a container layer.

The broker is one node with replication factor 1. Events **acknowledged** by the broker and then lost
with its volume are not reconstructed by the outbox, which only resends what was never acknowledged;
the projection and audit tables in PostgreSQL still hold what those events produced. A backup on the
host's own disk protects against a mistake, not against losing the host: copy it elsewhere.

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
- Session and CSRF cookies are `Secure`. Forwarding information is authored by Caddy and only Caddy:
  it strips any `Forwarded`, `X-Forwarded-Port/Prefix/Ssl` a client sends and sets `X-Forwarded-For`,
  `-Proto`, `-Host` and `-Port` from what it observed; the application, with Tomcat's native remote-ip
  handling, believes those only from a private-network peer and never reads `Forwarded`. Verified
  through the packaged edge: twelve attempts each spoofing a different client address were counted
  as one address and refused at the eleventh, and spoofed `X-Forwarded-Proto/Host` changed neither
  cookie attributes nor `Location`. The v0.10.0 edge did let a client-supplied `Forwarded: for=` choose
  the address; that was reproduced end to end before the fix.
- The authentication limiter counts real authentication outcomes - the events Spring Security
  publishes for both chains - not response codes, and a success clears only that username's failures
  at that address. An anonymous request carrying a stray `Authorization` header is not a success,
  and signing in as the public visitor does not launder guesses against private accounts.
- `/actuator/health/async` **and every path under it** need an operator credential, and nothing under
  `/actuator/health/` other than `liveness` and `readiness` is public.
- Reconciliation is budgeted for `HEAD` as well as `GET`, because `HEAD` runs the same report.
- The replay limits - one in flight, six an hour - are decided inside the job-creation transaction
  under a lock on the visitor's merchant row, so concurrent requests cannot both count zero. Eight
  simultaneous requests against a limit of one created three jobs on the v0.10.0 code and one after
  the fix; a refusal claims no idempotency key.
- Fault injection is off and the application refuses to start if it is not.

## Remove

`deploy/bin/teardown.sh`, then terminate the instance in the console. Nothing remains that costs
anything at any point, before or after.
