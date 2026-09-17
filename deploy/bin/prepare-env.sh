#!/usr/bin/env bash
# Generates the public deployment's environment file with fresh secrets. Run once per host.
#
# The file is created with owner-only permissions and is never committed: deploy/.gitignore excludes
# it. Every private credential is random; the visitor's is random too, because it does not need to
# be memorable - the sign-in page shows it. The only value the operator supplies is the hostname.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

prepare_env_usage() {
  cat <<'USAGE'
Usage: deploy/bin/prepare-env.sh

Creates deploy/public.env with generated credentials for the public demo. Takes no arguments.
Set DEMO_HOST and APP_IMAGE in the environment, or edit them into the file afterwards.

  DEMO_HOST   the public hostname Caddy will obtain a certificate for (e.g. decisionrail.duckdns.org)
  APP_IMAGE   the image to run, pinned to a revision (e.g. ghcr.io/beaprogram/decision-rail:sha-<commit>)

Prints the visitor password, which is public by design, and nothing else.
USAGE
}
reject_arguments prepare_env "$@"
require_dependencies openssl

if [[ -e "$env_file" ]]; then
  printf 'Existing %s kept. Remove it yourself only if you intend to rotate this deployment'"'"'s credentials.\n' "$env_file"
  exit 0
fi

umask 077
visitor_password=$(openssl rand -hex 12)
set -o noclobber
cat > "$env_file" <<ENV
# Public demo deployment. Generated $(date -u +%Y-%m-%dT%H:%M:%SZ). Never commit this file.
#
# The hostname Caddy serves and obtains a certificate for.
DEMO_HOST=${DEMO_HOST:-REPLACE.duckdns.org}
# The application image, pinned to a revision. See deploy/README.md for how it is published.
APP_IMAGE=${APP_IMAGE:-ghcr.io/beaprogram/decision-rail:REPLACE-WITH-sha-TAG}

# Storage credentials, internal to the Compose network.
POSTGRES_PASSWORD=$(openssl rand -hex 24)
KAFKA_CLUSTER_ID=$(openssl rand -base64 16 | tr '+/' '-_' | tr -d '=')

# The shared public visitor. Public by design; shown on the sign-in page.
VISITOR_PASSWORD=$visitor_password

# Private identities. Used by the operator walkthrough and never published.
MERCHANT_DEMO_PASSWORD=$(openssl rand -hex 24)
MERCHANT_OTHER_PASSWORD=$(openssl rand -hex 24)
OPERATIONS_PASSWORD=$(openssl rand -hex 24)
ADMIN_PASSWORD=$(openssl rand -hex 24)

# Visitor budgets. The defaults are documented in deploy/README.md.
DEMO_COMMANDS_PER_MINUTE=30
DEMO_REPLAY_JOBS_PER_HOUR=6
DEMO_MAX_PAYMENTS_PER_ACCOUNT=300

# Leave empty for the real deployment. Setting it to "tls internal" (quoted, it contains a space)
# makes Caddy issue a locally trusted certificate instead, for rehearsing on a host without a
# public name. CADDY_HTTP_PORT and CADDY_HTTPS_PORT may be set for a rehearsal on a workstation.
CADDY_TLS_DIRECTIVE=
ENV
printf 'Created %s with owner-only permissions.\n' "$env_file"
printf 'Visitor password (public by design): %s\n' "$visitor_password"
if [[ -z "${DEMO_HOST:-}" || -z "${APP_IMAGE:-}" ]]; then
  printf 'Edit DEMO_HOST and APP_IMAGE in that file before deploy/bin/up.sh.\n'
fi
