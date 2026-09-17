#!/usr/bin/env bash
# Restores the public demo to a backup's recovery point: the database from the dump, and the broker
# reset to empty - and leaves the application STOPPED for the operator to start with the image they
# intend, which is deploy/bin/up.sh for the current one or deploy/bin/rollback.sh for an older one.
#
# Why the broker is reset rather than kept. The dump was taken with zero consumer lag, so every event
# the broker had published by then has its effects in the dump; the broker's copy of those records is
# not needed. Everything the broker received after the recovery point belongs to activity the restore
# discards, and its committed offsets would point past events the restored database has no receipt
# for. Keeping either would let the stream re-introduce state the database does not agree with. An
# empty broker with no offsets, plus the outbox re-dispatching whatever was PENDING at the recovery
# point, is the state the database expects.
#
# Why the application stays stopped. Starting it here with whatever image happens to be selected
# would, for a cross-migration rollback, immediately re-apply the newer migrations to the older data
# and defeat the point of restoring it. One workflow, one step at a time.
#
# Failure handling: the dump is restored into a fresh database first and swapped in only when that
# succeeds, so a bad dump leaves the previous database in place, the broker untouched, and the
# application stopped.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

restore_usage() {
  cat <<'USAGE'
Usage: deploy/bin/restore.sh <backup.dump>

Stops the application, replaces the database with the backup and resets the broker to empty. Does
NOT start the application: run deploy/bin/up.sh to start the current image against it, or
deploy/bin/rollback.sh <older image> if the backup predates a migration the current image applies.
Requires typing the project name. Everything after the backup's recovery point is discarded.
USAGE
}
if (( $# != 1 )) || [[ "$1" == "-h" || "$1" == "--help" ]]; then restore_usage >&2; exit 2; fi
backup=$1
[[ -f "$backup" ]] || { printf 'No such backup: %s\n' "$backup" >&2; exit 1; }
require_dependencies docker jq
require_env_file
assert_public_project
if [[ -f "$backup.manifest.json" ]]; then
  printf 'Recovery point %s, schema %s, %s pending outbox event(s) at the snapshot.\n' \
    "$(jq -r .recoveryPoint "$backup.manifest.json")" "$(jq -r .schema "$backup.manifest.json")" \
    "$(jq -r .pendingOutboxEventsAtSnapshot "$backup.manifest.json")"
else
  printf 'Warning: no manifest beside %s; it may predate the coherent backup procedure.\n' "$backup" >&2
fi
confirm_destruction "Restore"

restore_database() {
  # Into a fresh database, swapped in on success. The previous database survives a failed restore.
  compose exec -T database psql -U decisionrail -d postgres -v ON_ERROR_STOP=1 -q \
    -c "DROP DATABASE IF EXISTS decisionrail_restore WITH (FORCE)" \
    -c "CREATE DATABASE decisionrail_restore OWNER decisionrail"
  if ! compose exec -T database pg_restore -U decisionrail -d decisionrail_restore --no-owner --exit-on-error < "$backup"; then
    compose exec -T database psql -U decisionrail -d postgres -q -c "DROP DATABASE IF EXISTS decisionrail_restore WITH (FORCE)" || true
    return 1
  fi
  compose exec -T database psql -U decisionrail -d postgres -v ON_ERROR_STOP=1 -q \
    -c "DROP DATABASE IF EXISTS decisionrail_previous WITH (FORCE)" \
    -c "ALTER DATABASE decisionrail RENAME TO decisionrail_previous" \
    -c "ALTER DATABASE decisionrail_restore RENAME TO decisionrail" \
    -c "DROP DATABASE decisionrail_previous WITH (FORCE)"
}

compose stop app >/dev/null
printf 'Application stopped. Restoring the database from %s.\n' "$backup"
if ! restore_database; then
  printf 'Restore failed. The previous database is still in place, the broker is untouched, and the\n' >&2
  printf 'application is STOPPED. Nothing was started. Inspect the dump and retry, or deploy/bin/up.sh\n' >&2
  printf 'to resume on the previous database.\n' >&2
  exit 1
fi
restored=$(compose exec -T database psql -U decisionrail -d decisionrail -tAc \
  "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')
printf 'Database restored at schema V%s.\n' "$restored"

printf 'Resetting the broker to empty: its records and offsets belong to the discarded timeline.\n'
compose rm --stop --force broker >/dev/null
docker volume rm "${project_name}_kafka-data" >/dev/null
compose up --detach --wait broker >/dev/null
printf 'Broker recreated empty.\n'

pending=$(compose exec -T database psql -U decisionrail -d decisionrail -tAc \
  "SELECT count(*) FROM outbox_events WHERE status = 'PENDING'" | tr -d '[:space:]')
cat <<DONE
Restored. The application is STOPPED and was not started.
  Schema: V$restored. Pending outbox events that will be re-dispatched on start: $pending.
  Next: deploy/bin/up.sh              (start the current image, $APP_IMAGE)
    or: deploy/bin/rollback.sh <image> (an older image, checked against schema V$restored first)
DONE
