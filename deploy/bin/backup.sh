#!/usr/bin/env bash
# A coherent backup of the public demo: the database at a moment when it holds the durable consumer
# state of every event that has been published.
#
# What "coherent" is, precisely. PostgreSQL is the system of record, but the consumers' committed
# positions live in the broker, so an online snapshot and the broker describe different moments. The
# failure that leaves open: an event PUBLISHED before the snapshot whose consumer receipt and effect
# commit after it. Restoring that snapshot would remove the receipt and effect while the outbox never
# resends a PUBLISHED event - the effect would simply be gone. So this script stops the application
# (nothing publishes, nothing consumes) and then asks the database the question restore actually
# needs answered: does every PUBLISHED outbox event have, for each consumer group, the receipt or
# quarantine record that group's contract writes? Only when that is exactly true does it dump. That
# instant is the recovery point. PENDING and CLAIMED events are in the dump and are re-dispatched
# after a restore; FAILED ones stay FAILED and can be redriven.
#
# The broker is not consulted for this, on purpose. Consumer lag as printed by the Kafka CLI is an
# indirect proxy for the same property, and the tool's output is not evidence: it prints errors and
# exits 0, renders unavailable positions as "-", and prints nothing for a group with no commits. The
# previous version of this script turned every one of those into "zero lag" and dumped. Receipts in
# PostgreSQL are the fact itself. If the answer cannot be read, or is anything other than a complete,
# self-consistent, all-receipts-present result, the backup is refused - never assumed.
#
# The broker itself is not backed up: deploy/bin/restore.sh resets it to empty, which is correct
# precisely because the dump holds every consumer effect of everything the broker had delivered.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

backup_usage() {
  cat <<'USAGE'
Usage: deploy/bin/backup.sh

Stops the application, establishes from the database that every published event's consumer state is
durable, writes deploy/backups/decisionrail-<UTC timestamp>-v<schema>.dump (pg_dump custom format)
with a manifest beside it, and starts the application again. Takes no arguments.

Refuses, and starts the application again, if any published event lacks a consumer group's receipt
after BACKUP_QUIESCE_SECONDS (default 60) of letting the consumers catch up, or if the database's
answer cannot be read or is not self-consistent. No dump or manifest is written on refusal.
An empty environment - nothing published yet - is coherent by construction and is reported as such.
The pause is a few seconds of visitor-facing downtime; that is the price of a restorable backup.
USAGE
}
reject_arguments backup "$@"
require_dependencies docker jq
require_env_file
assert_public_project

backup_dir="$deploy_dir/backups"
mkdir -p "$backup_dir"; chmod 700 "$backup_dir"
quiesce_seconds=${BACKUP_QUIESCE_SECONDS:-60}

refuse() {
  printf 'Refusing: %s\n' "$1" >&2
  printf 'No dump or manifest was written. Starting the application again.\n' >&2
  compose start app >/dev/null
  exit 1
}

printf 'Quiescing: stopping the application so nothing publishes or consumes.\n'
compose stop app >/dev/null
deadline=$((SECONDS + quiesce_seconds))
verdict=""; code=0
while :; do
  set +e; verdict=$(assess_coherence "$(coherence_observe decisionrail)"); code=$?; set -e
  case $code in
    0) break ;;
    2) refuse "coherence could not be established - $verdict" ;;
    1)
      if (( SECONDS >= deadline )); then
        refuse "$verdict after ${quiesce_seconds}s: published events exist whose consumer state is not in the database. A dump now would not restore coherently. Let delivery settle (deploy/bin/status.sh) and retry."
      fi
      # Receipts can only appear with the consumers running; give them a moment, then stop again.
      compose start app >/dev/null; sleep 5; compose stop app >/dev/null ;;
  esac
done
printf '%s\n' "$verdict"
published=${verdict#*published=}; published=${published%% *}
pending=${verdict#*pending=}; pending=${pending%% *}
claimed=${verdict#*claimed=}; claimed=${claimed%% *}
failed=${verdict#*failed=}; failed=${failed%% *}
if (( published == 0 )); then
  printf 'Nothing has been published yet: coherence holds vacuously. This is an empty environment, not an unread one.\n'
fi

stamp=$(date -u +%Y%m%dT%H%M%SZ)
schema=$(compose exec -T database psql -U decisionrail -d decisionrail -tAc \
  "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')
[[ "$schema" =~ ^[0-9]+$ ]] || refuse "the schema version could not be read from the database"
target="$backup_dir/decisionrail-$stamp-v$schema.dump"
umask 077
if ! compose exec -T database pg_dump -U decisionrail -d decisionrail --format=custom --no-owner > "$target" || [[ ! -s "$target" ]]; then
  rm -f "$target"; refuse "pg_dump did not produce a dump"
fi
jq -n --argjson version "$manifest_version" --arg stamp "$stamp" --arg schema "$schema" --arg image "$APP_IMAGE" \
  --arg sha "$(sha256_of "$target")" --argjson published "$published" --argjson pending "$((pending + claimed))" \
  --argjson failed "$failed" --arg projection "$projection_group" --arg shadow "$shadow_group" \
  '{version: $version, recoveryPoint: $stamp, schema: ("V" + $schema), image: $image, dumpSha256: $sha,
    coherence: {method: "durable-receipts", verified: true, publishedEvents: $published,
                consumerGroups: [$projection, $shadow],
                property: "every PUBLISHED outbox event has a consumed_events receipt or consumer_quarantine row for each consumer group, checked with the application stopped"},
    pendingOutboxEventsAtSnapshot: $pending, failedOutboxEventsAtSnapshot: $failed,
    restore: "deploy/bin/restore.sh re-establishes this property on the restored data before replacing the live database, then resets the broker to empty; pending events are re-dispatched, published ones were already applied, and everything after the recovery point is discarded."}' \
  > "$target.manifest.json"
compose start app >/dev/null
printf 'Wrote %s (%s bytes), schema V%s, %s published / %s pending / %s failed event(s) at the recovery point. Application started.\n' \
  "$target" "$(wc -c < "$target" | tr -d ' ')" "$schema" "$published" "$((pending + claimed))" "$failed"
printf 'Copy it off this host; a backup that lives on the disk it protects is not one.\n'
