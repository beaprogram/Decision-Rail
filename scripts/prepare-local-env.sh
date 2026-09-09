#!/usr/bin/env bash
set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
env_path="$project_dir/.env"
if [[ -e "$env_path" ]]; then
  printf 'Existing .env kept. Remove it yourself only if you intend to rotate local credentials.\n'
  exit 0
fi
command -v openssl >/dev/null || { printf 'OpenSSL is required to generate local credentials.\n' >&2; exit 1; }
umask 077
postgres_password=$(openssl rand -hex 24)
demo_password=$(openssl rand -hex 24)
other_password=$(openssl rand -hex 24)
operations_password=$(openssl rand -hex 24)
# noclobber prevents overwriting a file created by another invocation.
set -o noclobber
cat > "$env_path" <<ENV
# Local-only credentials. Never commit this file.
POSTGRES_PASSWORD=$postgres_password
JDBC_URL=jdbc:postgresql://localhost:5432/decisionrail
JDBC_USERNAME=decisionrail
JDBC_PASSWORD=$postgres_password
MERCHANT_DEMO_PASSWORD=$demo_password
MERCHANT_OTHER_PASSWORD=$other_password
OPERATIONS_PASSWORD=$operations_password
DEMO_ENABLED=true
ENV
printf 'Created .env with generated local credentials and owner-only permissions.\n'
