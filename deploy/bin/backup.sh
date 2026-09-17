#!/usr/bin/env bash
# A coherent backup of the public demo: the database at a moment when the event stream has nothing
# the database does not already know about.
#
# Why coherence has to be arranged rather than assumed. PostgreSQL is the system of record, but the
# consumers' committed positions live in the broker. An online snapshot taken while a consumer is
# mid-way leaves a window: an event PUBLISHED before the snapshot whose projection effect and offset
# commit land after it. Restoring that snapshot would remove the effect and its receipt while the
# broker still held the advanced offset - the outbox never resends a PUBLISHED event, so the effect
# would simply be gone. So this script stops the application (nothing publishes, nothing consumes),
# confirms both consumer groups have zero lag, and only then dumps. At that instant every published
# event's effects are in the dump; anything still PENDING in the outbox is in the dump too and is
# re-dispatched after a restore. That instant is the recovery point.
#
# The broker itself is not backed up. deploy/bin/restore.sh replaces its data with an empty broker,
# which is correct precisely because the dump was taken with zero lag: nothing the broker held was
# still needed, and anything it received afterwards belongs to activity the restore discards.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

backup_usage() {
  cat <<'USAGE'
Usage: deploy/bin/backup.sh

Stops the application, waits for both consumer groups to reach zero lag, writes
deploy/backups/decisionrail-<UTC timestamp>-v<schema>.dump (pg_dump custom format) with a manifest
beside it, and starts the application again. Takes no arguments.

Refuses, and leaves the application running, if consumer lag does not reach zero within
BACKUP_QUIESCE_SECONDS (default 60): a dump taken with lag would not be restorable coherently.
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

consumer_lag() {
  # Total lag across both groups, or "unknown" if the broker cannot be asked. Unknown is never
  # treated as zero: a backup taken while the broker is unreachable cannot be shown coherent.
  local total=0 group output lag
  for group in decisionrail-projection decisionrail-shadow; do
    if ! output=$(compose exec -T broker /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server broker:9092 \
        --describe --group "$group" 2>/dev/null); then
      printf 'unknown'; return
    fi
    lag=$(printf '%s\n' "$output" | awk 'NR>1 && $1==g {s+=($6=="-"?0:$6)} END {print s+0}' g="$group")
    total=$((total + lag))
  done
  printf '%s' "$total"
}

printf 'Quiescing: stopping the application so nothing publishes or consumes.\n'
compose stop app >/dev/null
deadline=$((SECONDS + quiesce_seconds))
lag=$(consumer_lag)
while [[ "$lag" == "unknown" ]] || (( lag > 0 )); do
  if [[ "$lag" == "unknown" ]]; then
    printf 'Refusing: the broker cannot be asked for consumer positions, so coherence cannot be established.\n' >&2
    printf 'Starting the application again. Bring the broker back and retry.\n' >&2
    compose start app >/dev/null
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    printf 'Refusing: consumer lag is still %s after %ss with the application stopped, so events were\n' "$lag" "$quiesce_seconds" >&2
    printf 'published that no consumer has applied. A dump now would not restore coherently. Starting the\n' >&2
    printf 'application again; let delivery settle (deploy/bin/status.sh) and retry.\n' >&2
    compose start app >/dev/null
    exit 1
  fi
  # Lag can only fall with the application running; give it a moment, then stop it again.
  compose start app >/dev/null; sleep 5; compose stop app >/dev/null
  lag=$(consumer_lag)
done
printf 'Consumer lag is zero: the database now holds every published event'"'"'s effects.\n'

stamp=$(date -u +%Y%m%dT%H%M%SZ)
schema=$(compose exec -T database psql -U decisionrail -d decisionrail -tAc \
  "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')
target="$backup_dir/decisionrail-$stamp-v$schema.dump"
umask 077
compose exec -T database pg_dump -U decisionrail -d decisionrail --format=custom --no-owner > "$target"
pending=$(compose exec -T database psql -U decisionrail -d decisionrail -tAc \
  "SELECT count(*) FROM outbox_events WHERE status = 'PENDING'" | tr -d '[:space:]')
jq -n --arg stamp "$stamp" --arg schema "$schema" --arg image "$APP_IMAGE" --arg pending "$pending" \
  '{recoveryPoint: $stamp, schema: ("V" + $schema), image: $image, consumerLagAtSnapshot: 0,
    pendingOutboxEventsAtSnapshot: ($pending|tonumber),
    restore: "deploy/bin/restore.sh replaces the database with this dump and resets the broker to empty; pending events are re-dispatched, published ones were already applied."}' \
  > "$target.manifest.json"
compose start app >/dev/null
printf 'Wrote %s (%s bytes), schema V%s, %s pending outbox event(s) captured. Application started.\n' \
  "$target" "$(wc -c < "$target" | tr -d ' ')" "$schema" "$pending"
printf 'Copy it off this host; a backup that lives on the disk it protects is not one.\n'
