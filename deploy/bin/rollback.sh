#!/usr/bin/env bash
# Runs a previous image against the current database, if the database allows it.
#
# The schema only moves forward. An image can only run against a database whose applied migrations
# it knows about, so a rollback across a migration boundary is not a rollback of the image alone: it
# is a restore of the pre-upgrade backup followed by the older image. This script checks that
# boundary before touching anything and says which of the two situations this is.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

rollback_usage() {
  cat <<'USAGE'
Usage: deploy/bin/rollback.sh <image>

Switches the public demo's application to <image> (a ghcr.io tag pinned to a revision) without
touching the database, after confirming that image knows every migration the database has applied.
If it does not, the script stops and tells you to restore the pre-upgrade backup first.
USAGE
}
if (( $# != 1 )) || [[ "$1" == "-h" || "$1" == "--help" ]]; then rollback_usage >&2; exit 2; fi
target_image=$1
case "$target_image" in *:latest|*:main|*REPLACE*) printf 'Refusing: %s does not name a revision.\n' "$target_image" >&2; exit 1 ;; esac
require_dependencies docker
require_env_file
assert_public_project

applied=$(compose exec -T database psql -U decisionrail -d decisionrail -tAc \
  "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1" | tr -d '[:space:]')
if ! docker pull --quiet "$target_image" >/dev/null 2>&1 && ! docker image inspect "$target_image" >/dev/null 2>&1; then
  printf 'Could not pull %s and it is not present locally.\n' "$target_image" >&2
  exit 1
fi
known=$(docker run --rm --entrypoint cat "$target_image" /app/latest-migration | tr -d '[:space:]')
[[ "$known" =~ ^[0-9]+$ ]] || { printf 'Could not read the newest migration from %s.\n' "$target_image" >&2; exit 1; }
if (( known < applied )); then
  printf 'Refusing: the database is at V%s but %s only knows migrations up to V%s.\n' "$applied" "$target_image" "$known" >&2
  printf 'Roll back the data first: deploy/bin/restore.sh <backup taken before the upgrade>, then run this again.\n' >&2
  exit 1
fi

printf 'Database at V%s; %s knows up to V%s. Switching the application image.\n' "$applied" "$target_image" "$known"
tmp=$(mktemp)
sed "s|^APP_IMAGE=.*|APP_IMAGE=$target_image|" "$env_file" > "$tmp" && cat "$tmp" > "$env_file" && rm -f "$tmp"
exec "$deploy_dir/bin/up.sh"
