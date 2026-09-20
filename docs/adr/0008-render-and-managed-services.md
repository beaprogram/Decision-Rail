# ADR 0008: Render edge and managed PostgreSQL/Kafka

Status: accepted for implementation; live deployment acceptance remains pending.

The owner requires a zero-cost, no-card portfolio deployment. The original Compose bundle targets a
VM, while the proposed Render Free service supplies one public container, limited CPU/memory and an
ephemeral filesystem. Aiven Free supplies the durable PostgreSQL and Kafka services separately.

## Decision

Add a named `render` Docker target containing the application and pinned Caddy. Keep the ordinary
application target as the default. Java listens only on loopback; Caddy retains the reviewed route
allowlist, body cap and security headers. A supervisor forwards shutdown and stops the other process
if either exits, so an apparently healthy edge cannot survive a dead application indefinitely.

Render terminates HTTPS. Caddy trusts explicitly configured immediate peers and derives client
identity from the platform-overwritten `CF-Connecting-IP`; caller forwarding headers are discarded.
The actual platform trust boundary and resistance to forged headers must be checked live before
exposure. The entrypoint fixes public-demo mode, secure cookies and disabled fault injection.

Kafka factories inherit Boot's connection properties so managed TLS/SASL settings reach all clients,
then overwrite settings that protect delivery correctness. The render profile requires SASL_SSL,
SCRAM-SHA-256 and PEM trust with hostname verification. PostgreSQL uses verify-full with its CA.
Aiven's two-partition topic is provisioned explicitly; Boot topic administration is disabled only in
this profile. Connection pools and JVM resource limits are bounded for the candidate free plan.

Publish an independently named `render-sha-<commit>` tag in the existing public GHCR package, retaining
all existing application sha/version tags. Publication alone does not deploy the service.

## Consequences

Two processes in one container require explicit lifecycle supervision, but preserve the edge controls
without requiring another paid service. Resource and forwarding assumptions now need provider-specific
acceptance, not just the original Compose rehearsal. Sessions/limiters remain in memory, only one
instance is supported, and idle sleep/cold starts are part of this demo's behavior.

The local 512 MiB/0.1 CPU rehearsal passed, but does not establish throughput, uptime or actual Render
startup speed. Managed infrastructure is not controlled by the Compose backup/restore scripts. An
Aiven-specific coherent backup/recovery procedure and rehearsal remain open. No database migrations,
payment semantics or event delivery guarantees change in this adaptation.

See [Render deployment instructions](../../deploy/render/README.md) and the bounded evidence in
[verification.md](../verification.md).
