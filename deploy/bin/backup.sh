#!/usr/bin/env bash
# A consistent logical backup of the database, which is the system of record.
#
# pg_dump takes a transactionally consistent snapshot, so payments, ledger, returns, idempotency
# records and the outbox are captured as of one moment. The broker is deliberately not backed up:
# everything it holds was published from the outbox, and deploy/README.md says plainly what a lost
# broker volume does and does not lose.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

backup_usage() {
  cat <<'USAGE'
Usage: deploy/bin/backup.sh

Writes deploy/backups/decisionrail-<UTC timestamp>-<schema version>.dump, a pg_dump custom-format
archive of the public demo's database, and records the running revision beside it. Takes no
arguments. Read-only for the application; the stack keeps serving.
USAGE
}
reject_arguments backup "$@"
require_dependencies docker jq
require_env_file
assert_public_project

backup_dir="$deploy_dir/backups"
mkdir -p "$backup_dir"
chmod 700 "$backup_dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
schema=$(compose exec -T database psql -U decisionrail -d decisionrail -tAc \
  "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')
target="$backup_dir/decisionrail-$stamp-v$schema.dump"

umask 077
compose exec -T database pg_dump -U decisionrail -d decisionrail --format=custom --no-owner > "$target"
compose exec -T app curl --silent --max-time 3 http://127.0.0.1:8080/actuator/info 2>/dev/null \
  | jq '.decisionrail' > "$target.revision.json" || printf '{"commit":"unknown"}\n' > "$target.revision.json"
printf 'Wrote %s (%s bytes), schema V%s.\n' "$target" "$(wc -c < "$target" | tr -d ' ')" "$schema"
printf 'Copy it off this host; a backup that lives on the disk it protects is not one.\n'
