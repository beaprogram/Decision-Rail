#!/usr/bin/env bash
# Restores the database from a backup taken by deploy/bin/backup.sh.
#
# The application is stopped for the duration so nothing writes while the schema is replaced. What
# comes back is exactly the backup - payments, ledger, returns, idempotency records, outbox rows and
# their delivery state as they were - and the application then starts against it. If the image now
# running is newer than the backup's schema, its migrations apply on start, subject to the same
# validation any upgrade gets; if it is older, it will refuse to start against migrations it does not
# know, which is the compatibility limit stated in deploy/README.md.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

restore_usage() {
  cat <<'USAGE'
Usage: deploy/bin/restore.sh <backup.dump>

Stops the application, replaces the public demo's database with the backup, and starts the
application again. Requires typing the project name. The existing database is dropped: take a
backup of it first if there is any chance it matters.
USAGE
}
if (( $# != 1 )) || [[ "$1" == "-h" || "$1" == "--help" ]]; then restore_usage >&2; exit 2; fi
backup=$1
[[ -f "$backup" ]] || { printf 'No such backup: %s\n' "$backup" >&2; exit 1; }
require_dependencies docker
require_env_file
assert_public_project
confirm_destruction "Restore"

compose stop app
compose exec -T database psql -U decisionrail -d postgres -v ON_ERROR_STOP=1 \
  -c "DROP DATABASE IF EXISTS decisionrail WITH (FORCE)" -c "CREATE DATABASE decisionrail OWNER decisionrail"
compose exec -T database pg_restore -U decisionrail -d decisionrail --no-owner --exit-on-error < "$backup"
restored=$(compose exec -T database psql -U decisionrail -d decisionrail -tAc \
  "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')
printf 'Restored schema V%s from %s. Starting the application.\n' "$restored" "$backup"
compose start app
