#!/usr/bin/env bash
# Runs a previous image, safely: either directly against the current database when that image knows
# every migration the database has applied, or - with --restore - after restoring the backup taken
# before the upgrade, so the older schema is what the older image starts against.
#
# One workflow. The application is stopped first and stays stopped until the compatible image is
# selected, so the newer image never gets a chance to re-apply its migrations to restored data.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

rollback_usage() {
  cat <<'USAGE'
Usage: deploy/bin/rollback.sh <image> [--restore <backup.dump>]

Switches the public demo to <image>, pinned to a revision (sha- tag or digest).

Without --restore: refused unless the image knows every migration the database has applied.
With --restore:    stops the application, restores the backup (database replaced, broker reset,
                   everything after the backup's recovery point discarded), then checks the image
                   against the restored schema and starts it. This is how a rollback across a
                   migration boundary is done; the newer image is never started in between.
Requires typing the project name when --restore is given.
USAGE
}
if (( $# < 1 )) || [[ "$1" == "-h" || "$1" == "--help" ]]; then rollback_usage >&2; exit 2; fi
target_image=$1; shift
backup=""
while (( $# > 0 )); do
  case "$1" in
    --restore) backup=${2:-}; shift 2 ;;
    *) printf 'Unrecognised argument: %s\n\n' "$1" >&2; rollback_usage >&2; exit 2 ;;
  esac
done
require_pinned_image "$target_image" || exit 1
require_dependencies docker jq
require_env_file
assert_public_project

if ! docker pull --quiet "$target_image" >/dev/null 2>&1 && ! docker image inspect "$target_image" >/dev/null 2>&1; then
  printf 'Could not pull %s and it is not present locally.\n' "$target_image" >&2
  exit 1
fi
known=$(docker run --rm --entrypoint cat "$target_image" /app/latest-migration | tr -d '[:space:]')
[[ "$known" =~ ^[0-9]+$ ]] || { printf 'Could not read the newest migration from %s.\n' "$target_image" >&2; exit 1; }

applied_schema() {
  compose exec -T database psql -U decisionrail -d decisionrail -tAc \
    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]'
}

# The application is stopped before anything else, whichever branch follows.
compose stop app >/dev/null
if [[ -n "$backup" ]]; then
  [[ -f "$backup" ]] || { printf 'No such backup: %s\n' "$backup" >&2; exit 1; }
  # restore.sh stops the app (already stopped), restores, resets the broker, and leaves it stopped.
  "$deploy_dir/bin/restore.sh" "$backup"
fi

applied=$(applied_schema)
if (( known < applied )); then
  printf 'Refusing: the database is at V%s but %s only knows migrations up to V%s.\n' "$applied" "$target_image" "$known" >&2
  if [[ -z "$backup" ]]; then
    printf 'Use --restore <backup taken before the upgrade>; the application stays STOPPED until then.\n' >&2
  else
    printf 'The restored backup is newer than that image knows. The application stays STOPPED.\n' >&2
  fi
  exit 1
fi

printf 'Database at V%s; %s knows up to V%s. Switching the application image.\n' "$applied" "$target_image" "$known"
tmp=$(mktemp)
sed "s|^APP_IMAGE=.*|APP_IMAGE=$target_image|" "$env_file" > "$tmp" && cat "$tmp" > "$env_file" && rm -f "$tmp"
exec "$deploy_dir/bin/up.sh"
