# Render Free with Aiven Free

This is an additional deployment target for a synthetic portfolio demo. The Oracle/Compose release
and its backup/restore scripts remain separate. This target is not a live deployment and must pass
the live checks below before it is described as ready.

## What changes

Build the root Dockerfile with `--target render`. It packages the same application plus Caddy 2.11.4.
Caddy listens on Render's `PORT` (default 10000); Java binds only to loopback port 8080. Render
terminates public HTTPS. Caddy retains the route allowlist, 64 KB body limit, security headers and
30-second upstream response-header deadline. It rewrites forwarding headers from a configured trusted
peer and the platform's `CF-Connecting-IP`, never the caller's `X-Forwarded-For`.

The supervisor starts both processes and terminates the sibling when either exits, including a clean
unexpected exit. Render can then restart the failed service. Public-demo mode, secure cookies, no
local demo fixtures, and no fault injection are enforced by command-line properties. The normal
Dockerfile target and local plaintext Kafka configuration are unchanged.

The `render` Spring profile forwards TLS/SASL configuration to the real Kafka factories, uses six
PostgreSQL connections (minimum two), and disables Kafka topic administration. Managed-service
credentials and CA certificates are supplied at runtime; no credential belongs in this repository.

## Free-tier boundaries

As checked on 2026-09-18, Aiven offers free PostgreSQL and Kafka without a payment card. Aiven's trial
credit banner is separate: verify each service's plan actually says **Free**, not a trial-funded paid
plan. Free Kafka allows five topics with up to two partitions each and limited retention. Free
services can be powered off for inactivity. See [Aiven Free](https://aiven.io/free-tier) and
[Kafka Free limits](https://aiven.io/docs/products/kafka/free-tier/kafka-free-tier).

Render Free supplies 512 MiB memory and 0.1 CPU, sleeps after 15 minutes without inbound traffic, and
shares 750 free instance hours per workspace each month. Existing free services share those hours.
There is no persistent disk. Do not add a paid plan, paid disk, or an external keep-alive workload to
hide the sleep behavior. Account verification may still be requested by Render; if the account asks
for a card, stop rather than promising this account can deploy without one. See
[Render Free](https://render.com/docs/free) and [compute plans](https://render.com/docs/compute-plans).

The image budgets 224 MiB heap plus bounded direct memory, code cache and thread pools. Caddy's Go
memory target is a soft GC target. These settings do not establish real Render capacity or an uptime
guarantee. In-memory sessions and limiters reset on restart. Only one instance is supported.

## Prepare Aiven

1. Confirm both services are on the **Free** plan and Running. The owner's screenshots show
   PostgreSQL 18.6 (20 connections) and Kafka 4.2.1; screenshots establish provisioning, not successful
   application connectivity.
2. In Kafka, select **SASL** connection information. Create topic `decisionrail.payments.v1` with
   **2 partitions**, using the service's permitted replication setting. Do not ask the local three-
   partition `NewTopic` bean to manage it; this profile disables admin auto-creation.
3. Download each service's CA certificate. Keep PostgreSQL and Kafka trust files distinct even if the
   project currently uses the same certificate. Copy passwords directly into Render secret settings;
   do not paste them into chat, source files, screenshots, or terminal commands.
4. Preserve the exact service hostname for certificate validation. Do not use `sslmode=require`, a
   trust-all manager, or an empty hostname-verification algorithm as a connection workaround.

## Prepare the Render service

Use a newly built Render image, **not** the existing app-only `decision-rail:v0.10.2` image. After the reviewed workflow has reached the default branch, the
manual `Release Render image` workflow publishes the separately tagged image
`ghcr.io/beaprogram/decision-rail:render-sha-<full-commit>`. Select **Existing Image**, pin that
immutable revision tag (or verified digest), name the service `decisionrail-demo`, and select **Free**.
Do not deploy an uncommitted local rehearsal image or relabel it as a release. A public image must be
pulled anonymously and its reported commit checked before use. Confirm package visibility explicitly.

Set health-check path `/actuator/health/readiness`. Add these **Secret Files**:

| Filename | Contents |
| --- | --- |
| `postgres-ca.pem` | PostgreSQL CA PEM, including BEGIN/END lines |
| `kafka-ca.pem` | Kafka CA PEM, including BEGIN/END lines |

Render mounts them under `/etc/secrets/`. Set the following environment values in the dashboard:

| Key | Value |
| --- | --- |
| `JDBC_URL` | `jdbc:postgresql://<postgres-host>:<port>/defaultdb?sslmode=verify-full&sslrootcert=/etc/secrets/postgres-ca.pem` |
| `JDBC_USERNAME` | Aiven PostgreSQL username |
| `JDBC_PASSWORD` | Aiven PostgreSQL password (secret) |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka **SASL** service URI, `host:port`, with no URL scheme |
| `KAFKA_SASL_JAAS_CONFIG` | `org.apache.kafka.common.security.scram.ScramLoginModule required username="<user>" password="<password>";` (secret) |
| `KAFKA_CA_PATH` | `/etc/secrets/kafka-ca.pem` |
| `MERCHANT_DEMO_PASSWORD` | Unique random private credential, at least 16 characters |
| `MERCHANT_OTHER_PASSWORD` | Different private credential, at least 16 characters |
| `OPERATIONS_PASSWORD` | Different private credential, at least 16 characters |
| `ADMIN_PASSWORD` | Different private credential, at least 16 characters |
| `VISITOR_PASSWORD` | Separate 16–72 character demo-only password, intentionally public to visitors |
| `RENDER_TRUSTED_PROXIES` | Explicit platform immediate-peer CIDRs, or `private_ranges` only after confirming that trust boundary for the service |
| `LOG_FORMAT` | `ecs` |
| `OTLP_EXPORT_ENABLED` | `false` |

Render supplies `PORT` and `RENDER_EXTERNAL_HOSTNAME`. The hostname must be the canonical public DNS
name, without scheme/path/port. Leave `JAVA_TOOL_OPTIONS` at the image default for the initial check.
JAAS quoted values require escaping a literal backslash as `\\` and a literal quote as `\"`; never
shell-evaluate the resulting string. Do not publish any of the four private credentials.

Render documents its Cloudflare edge as overwriting `CF-Connecting-IP`. Trust applies to the
**immediate peer**, not arbitrary header contents. The local simulation cannot prove the actual
platform's network topology; the live spoofing checks below are mandatory. If Render changes this
contract, stop and revisit the edge configuration rather than enabling broad `0.0.0.0/0` trust.
[Render forwarding guidance](https://render.com/articles/host-pocketbase-on-render),
[Caddy trusted proxies](https://caddyserver.com/docs/caddyfile/options#trusted_proxies).

## Live acceptance checks

Use only synthetic demo data and this service's credentials. Do not point local fault-injection,
operator-demo, recovery or benchmark scripts at managed infrastructure: some stop a broker, alter
rows deliberately, or destroy an isolated Compose project.

- Confirm the selected plan remains Free, shared workspace hours are sufficient, the image revision
  matches `/actuator/info`, schema reaches V15, and the service becomes ready within Render's startup
  allowance. Record actual startup duration, OOM/restart events and memory; local results do not
  establish performance on 0.1 CPU.
- Open the real HTTPS dashboard. Verify the synthetic-data banner, Secure/HttpOnly/SameSite session
  cookie, login/logout, CSRF rejection and session invalidation. A session must not authorize `/v1`.
- Verify anonymous async health and its descendants require authentication, `/actuator/prometheus`
  is 404, security headers exist, oversized bodies are rejected, and unknown API paths remain errors.
- From one client, vary `Forwarded`, `X-Forwarded-For`, `X-Real-IP` and `CF-Connecting-IP` across failed
  logins. They must not evade the per-client limiter. A genuinely different client must not inherit
  that lockout. Do this before publishing the demo password; wait for or explicitly account for the
  test's limiter window. A single shared proxy address is a failed check, not acceptable evidence.
- As visitor, authorize/capture/refund synthetic funds, verify reconciliation, and confirm both
  projection and shadow consumer receipts. Exercise replay within its budget and confirm the budget
  applies through both `/ui` and `/v1`. Record broker topic partitions and secure connection mode.
- Restart only this Render service: sessions end, committed payments remain, outbox delivery recovers,
  and consumers do not repeat effects. Verify wake-up after idle; disclose observed cold-start delay.
- Review Aiven storage/retention and free-plan inactivity status. A provider outage or sleep can leave
  delivery degraded; readiness intentionally does not require a working broker.

## Persistence and recovery

Render's filesystem is ephemeral. Durable state is in Aiven PostgreSQL; do not store backups inside
the Render container. Kafka is a separate managed service. The existing `deploy/bin/backup.sh`,
`restore.sh`, `reset-sandbox.sh` and `rollback.sh` control a Compose host and **are not Aiven procedures**.
Do not run them against these services or claim their local rehearsal proves cloud recovery.

A managed recovery procedure must retain the same stopped-app coherence check, external encrypted
backup/manifest, schema compatibility check, and deliberate handling of broker offsets and receipts.
That procedure and a real-host recovery rehearsal remain pending; do not reset a managed topic to
imitate a local broker-volume reset. This demo has no proven managed-service recovery guarantee.

## Local checks

`python3 deploy/render/test_entrypoint.py` checks process lifecycle and inert help.
`CADDY_BINARY=/path/to/caddy-2.11.4 python3 deploy/render/test_edge.py` exercises the real edge config
against a disposable echo upstream, including trusted/untrusted forwarding and body size. Neither
contacts the development application. Full application checks use the dedicated test stack described
in `AGENTS.md`; provider-version/TLS and 512 MiB rehearsals are recorded in `docs/verification.md`.
