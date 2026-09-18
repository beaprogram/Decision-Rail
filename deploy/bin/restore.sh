#!/usr/bin/env bash
# Restores the public demo to a backup's recovery point - and refuses, before touching anything that
# matters, unless the dump can be shown to be one that a restore leaves coherent.
#
# Order of operations, which is the whole point:
#   1. The manifest is validated: format, version, required fields, the coherence claim, and the
#      dump's SHA-256 against the manifest's, so the two cannot be paired wrongly. A manifest is a
#      description; it is checked, not believed.
#   2. The application is stopped and the dump is restored into a STAGING database.
#   3. Coherence is established on the staging database itself - every PUBLISHED event has each
#      consumer group's receipt or quarantine row - and the published count is compared with the
#      manifest's. This is the property recovery actually requires, asked of the data being restored.
#   4. Only then: the live database is swapped for the staging one, and the broker is reset to empty.
#   5. The application stays STOPPED; the operator starts the intended image with up.sh or rollback.sh.
#
# A failure at 1-3 leaves the live database, the broker and the stopped application exactly as found;
# nothing destructive has happened yet. The broker is reset because the dump holds every consumer
# effect of everything the broker had delivered by the recovery point, so its copy of those records
# is not needed, and everything it received afterwards - and its offsets past the recovery point -
# belong to activity this restore discards; keeping them would let the stream reintroduce state the
# database does not agree with.
#
# Legacy dumps. A dump without a manifest - an online dump from before coherent backups existed - is
# refused by default: nothing establishes that it was taken at a coherent moment, and a warning would
# be a way of pretending otherwise. --legacy-dump lets one through the same staging check, which is
# what decides: a legacy dump that happens to hold every receipt restores exactly like a verified
# one, and one that does not is refused with the counts.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

restore_usage() {
  cat <<'USAGE'
Usage: deploy/bin/restore.sh <backup.dump> [--legacy-dump]

Validates the backup's manifest against the dump, stops the application, restores the dump into a
staging database, establishes coherence there (every published event's consumer state present),
and only then replaces the live database and resets the broker to empty. Does NOT start the
application: run deploy/bin/up.sh to start the current image, or deploy/bin/rollback.sh <older
image> if the backup predates a migration the current image applies. Requires typing the project
name. Everything after the backup's recovery point is discarded.

  --legacy-dump   accept a dump with no manifest (an online dump from before v0.10.1). The staging
                  coherence check still decides; an incoherent legacy dump is refused.
USAGE
}
if (( $# < 1 )) || [[ "$1" == "-h" || "$1" == "--help" ]]; then restore_usage >&2; exit 2; fi
backup=$1; shift
legacy=0
while (( $# > 0 )); do
  case "$1" in
    --legacy-dump) legacy=1; shift ;;
    *) printf 'Unrecognised argument: %s\n\n' "$1" >&2; restore_usage >&2; exit 2 ;;
  esac
done
[[ -f "$backup" ]] || { printf 'No such backup: %s\n' "$backup" >&2; exit 1; }
require_dependencies docker jq
require_env_file
assert_public_project

# ----- 1. the manifest, before anything else -----
manifest="$backup.manifest.json"
manifest_published=""
if (( legacy == 0 )); then
  set +e; verdict=$(validate_manifest "$backup" "$manifest"); ok=$?; set -e
  if (( ok != 0 )); then
    printf 'Refusing: %s.\n' "$verdict" >&2
    if [[ ! -f "$manifest" ]]; then
      printf 'A dump with no manifest cannot be shown to have been taken at a coherent moment. If it is an online\n' >&2
      printf 'dump from before v0.10.1, pass --legacy-dump; the restored data will then be checked for coherence\n' >&2
      printf 'before anything is replaced, and refused if it is not.\n' >&2
    fi
    printf 'Nothing was changed; the application was not stopped.\n' >&2
    exit 1
  fi
  printf 'Manifest %s\n' "$verdict"
  manifest_published=$(jq -r .coherence.publishedEvents "$manifest")
else
  printf 'Legacy dump accepted for staging without a manifest; coherence will be decided on the restored data.\n'
fi
confirm_destruction "Restore"

staging=decisionrail_restore
drop_staging() { compose exec -T database psql -U decisionrail -d postgres -q -c "DROP DATABASE IF EXISTS $staging WITH (FORCE)" >/dev/null 2>&1 || true; }
stopped_and_intact() {
  printf 'The live database is untouched, the broker is untouched, and the application is STOPPED.\n' >&2
  printf 'Nothing was started; deploy/bin/up.sh resumes on the previous database.\n' >&2
}

# ----- 2. stage -----
compose stop app >/dev/null
printf 'Application stopped. Restoring %s into the staging database.\n' "$backup"
drop_staging
compose exec -T database psql -U decisionrail -d postgres -v ON_ERROR_STOP=1 -q -c "CREATE DATABASE $staging OWNER decisionrail"
if ! compose exec -T database pg_restore -U decisionrail -d "$staging" --no-owner --exit-on-error < "$backup"; then
  drop_staging
  printf 'Refusing: the dump could not be restored into the staging database.\n' >&2
  stopped_and_intact; exit 1
fi

# ----- 3. coherence, on the restored data -----
set +e; verdict=$(assess_coherence "$(coherence_observe "$staging")"); code=$?; set -e
if (( code != 0 )); then
  drop_staging
  printf 'Refusing: the restored data is not coherent - %s.\n' "$verdict" >&2
  printf 'It holds published events whose consumer state is missing; after a broker reset those effects could\n' >&2
  printf 'never be recovered. This dump was not taken at a coherent moment and cannot be restored by this procedure.\n' >&2
  stopped_and_intact; exit 1
fi
staged_published=${verdict#*published=}; staged_published=${staged_published%% *}
if [[ -n "$manifest_published" && "$manifest_published" != "$staged_published" ]]; then
  drop_staging
  printf 'Refusing: the manifest says %s published events, the restored data has %s. They do not describe the same snapshot.\n' \
    "$manifest_published" "$staged_published" >&2
  stopped_and_intact; exit 1
fi
restored=$(compose exec -T database psql -U decisionrail -d "$staging" -tAc \
  "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')
printf 'Staged data is %s (schema V%s).\n' "$verdict" "$restored"

# ----- 4. replace, then reset the broker: the first destructive step -----
compose exec -T database psql -U decisionrail -d postgres -v ON_ERROR_STOP=1 -q \
  -c "DROP DATABASE IF EXISTS decisionrail_previous WITH (FORCE)" \
  -c "ALTER DATABASE decisionrail RENAME TO decisionrail_previous" \
  -c "ALTER DATABASE $staging RENAME TO decisionrail" \
  -c "DROP DATABASE decisionrail_previous WITH (FORCE)"
printf 'Live database replaced.\n'
printf 'Resetting the broker to empty: its records and offsets belong to the discarded timeline.\n'
compose rm --stop --force broker >/dev/null
docker volume rm "${project_name}_kafka-data" >/dev/null
compose up --detach --wait broker >/dev/null
printf 'Broker recreated empty.\n'

pending=$(compose exec -T database psql -U decisionrail -d decisionrail -tAc \
  "SELECT count(*) FROM outbox_events WHERE status IN ('PENDING', 'CLAIMED')" | tr -d '[:space:]')
cat <<DONE
Restored. The application is STOPPED and was not started.
  Schema: V$restored. Pending outbox events that will be re-dispatched on start: $pending.
  Next: deploy/bin/up.sh              (start the current image, $APP_IMAGE)
    or: deploy/bin/rollback.sh <image> (an older image, checked against schema V$restored first)
DONE
