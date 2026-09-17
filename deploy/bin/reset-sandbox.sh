#!/usr/bin/env bash
# Recreates the public demo from nothing: the database and broker volumes are destroyed and the stack
# is started fresh. This is how the demo is "reset" - there is no reset endpoint, no editing of
# ledger history and no in-place repair; the sandbox is expendable and is simply replaced.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

reset_sandbox_usage() {
  cat <<'USAGE'
Usage: deploy/bin/reset-sandbox.sh

Destroys the decisionrail-public volumes (database, broker, certificates are kept) and starts the
stack again empty, then re-seeds the guided demo history. Requires typing the project name. Takes
no arguments. Never touches any other Compose project.
USAGE
}
reject_arguments reset_sandbox "$@"
require_dependencies docker curl jq
require_env_file
assert_public_project
confirm_destruction "Reset"

compose down --remove-orphans
for volume in postgres-data kafka-data; do
  docker volume rm "${project_name}_${volume}" >/dev/null 2>&1 || true
done
printf 'Volumes removed. Starting the stack empty.\n'
"$deploy_dir/bin/up.sh"
"$deploy_dir/bin/seed-demo.sh"
