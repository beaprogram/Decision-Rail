# Local operator guide

## Credentials and configuration

Run `./scripts/prepare-local-env.sh` once from the repository. It generates an ignored `.env` and never prints the passwords. `.env.example` documents the variables without usable default credentials.

| Variable | Purpose |
| --- | --- |
| `POSTGRES_PASSWORD` | PostgreSQL initialization password used by Compose. |
| `JDBC_URL` | Native application's database connection URL. Compose sets an internal service URL. |
| `JDBC_USERNAME` | Database username. Local setup uses `decisionrail`. |
| `JDBC_PASSWORD` | Native application's database password. |
| `MERCHANT_DEMO_PASSWORD` | Password for `demo-merchant`. Required. |
| `MERCHANT_OTHER_PASSWORD` | Password for `other-merchant`. Required. |
| `OPERATIONS_PASSWORD` | Password for the `operations` metrics user. Required. |
| `DEMO_ENABLED` | Explicitly enable synthetic account seed data; defaults to `false` in the application. |
| `PORT` | HTTP port; defaults to `8080`. |

All three application passwords must be distinct and contain 16–72 characters; startup rejects missing, out-of-range, or placeholder values. The generated values exceed this minimum. Merchant users cannot read the protected Prometheus endpoint; the operations user cannot access `/v1/**`. Public health responses contain no internal detail.

Local demo accounts:

| Account ID | Owner | Currency |
| --- | --- | --- |
| `11111111-1111-1111-1111-111111111111` | `demo-merchant` | CAD |
| `22222222-2222-2222-2222-222222222222` | `other-merchant` | CAD |
| `33333333-3333-3333-3333-333333333333` | `demo-merchant` | USD |

The first demo account starts with 1,000,000 minor units (CAD 10,000.00). Seeding must not top it up on application restart: a persisted balance is financial state, even in a demo.

## Docker startup

```bash
./scripts/prepare-local-env.sh
docker compose up --build -d
docker compose logs -f backend
```

Compose starts PostgreSQL before the application. A healthy PostgreSQL service means it is accepting connections; the application still needs to complete migration and startup. Check the API separately:

```bash
curl --fail http://localhost:8080/actuator/health
```

## Native Java development

Run only the database container, export the generated environment, and start Spring Boot:

```bash
docker compose up -d database
set -a
source .env
set +a
./mvnw spring-boot:run
```

Do not run the Compose backend and native backend on the same port simultaneously. If you already run PostgreSQL on port `5432`, choose a separate database port and update both the Compose port mapping and native `JDBC_URL`.

## Walk through the transaction lifecycle

```bash
./scripts/demo.sh
```

The script fails on a mismatched HTTP status, changed replayed result, absent replay marker, invalid capture journal, or incorrect final balance. It uses a new key prefix for each run, so it can be repeated against the same seeded database while funds remain available.

To run against another local port:

```bash
BASE_URL=http://localhost:8081 ./scripts/demo.sh
```

The script's `.env` is local configuration. Review the file before sourcing an `.env` supplied by anyone else.

For a manual request, load `.env` and use the generated password:

```bash
set -a
source .env
set +a
curl --fail-with-body --silent --show-error \
  --user "demo-merchant:$MERCHANT_DEMO_PASSWORD" \
  --header 'Content-Type: application/json' \
  --header 'Idempotency-Key: manual-example-001' \
  --data '{"accountId":"11111111-1111-1111-1111-111111111111","amountMinor":2500,"currency":"CAD","country":"CA"}' \
  http://localhost:8080/v1/payments/authorizations
```

Retry with the same body and key to observe replay. Change the body while retaining that key to observe `409`. Capture or void the returned payment using a separate key for that operation.

## Interpreting results

A payment contains `id`, `accountId`, `amountMinor`, `currency`, `country`, `status`, `decision`, `failureCode`, `createdAt`, and `updatedAt`. The nested decision records `outcome`, `score`, `ruleSetVersion`, `reasons`, and `flags`.

- `AUTHORIZED`: synthetic funds are held and can be captured or released.
- `CAPTURED`: funds have been consumed and a balanced journal exists.
- `VOIDED`: the authorization hold has been released.
- `REVIEW`: policy requests review; funds are not reserved and a review workflow is not implemented yet.
- `DECLINED`: authorization was refused by policy or funds availability. Inspect both `decision` and `failureCode`.

A problem response uses JSON fields `type`, `title`, `status`, `detail`, `code`, and `requestId`. Authentication errors may occur before the payment controller; use the HTTP status and response body instead of assuming every response has a payment shape.

The ledger endpoint returns an array of entries with `id`, `journalId`, `ledgerAccount`, `side`, `amountMinor`, and `currency`.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Compose refuses to start because a variable is empty | Run `prepare-local-env.sh`; keep `.env` in the repository root. |
| Database authentication fails after changing `.env` | An existing volume still has its original initialized database password. Restore the matching configuration or deliberately rotate the database password. |
| Backend cannot connect to PostgreSQL | Check the database health and the connection host/port. Inside Compose the host is `database`; native Java uses `localhost`. |
| Demo returns `401` | Ensure the app and script use the same generated merchant password. |
| Demo reports missing account | Start the app with `DEMO_ENABLED=true` and verify it uses the intended local database. |
| An identical authorization repeats but does not spend again | Expected idempotency behavior; use a new key only for a new command. |
| `409` on key reuse | The key was already used with a different request or operation. |
| `REVIEW` or policy decline | Inspect the stored score, reasons, and `demo-v1` rules. |
| Outbox records stay pending | Expected in this milestone; delivery has not been implemented. |

## Operating boundary

This release is intended for local evaluation with synthetic data. Its Compose ports listen only on loopback. A public release still needs a verified hosting plan, TLS, managed credentials, persistence and recovery choices, and an explicit demo access policy. That work belongs to the deployment milestone and has not been silently assumed complete.
