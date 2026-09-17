#!/usr/bin/env bash
# Starts or updates the public demo to the image named in the environment file, and waits until the
# application answers its readiness probe. Safe to re-run: Compose only recreates what changed.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

up_usage() {
  cat <<'USAGE'
Usage: deploy/bin/up.sh

Pulls the pinned image, starts the decisionrail-public stack, waits for readiness and prints the
running revision. Takes no arguments; reads deploy/public.env.
USAGE
}
reject_arguments up "$@"
require_dependencies docker curl jq
require_env_file
assert_public_project

case "$APP_IMAGE" in
  *:latest|*REPLACE*|*:main)
    printf 'Refusing: APP_IMAGE=%s does not name a revision. Pin a sha- tag or a digest.\n' "$APP_IMAGE" >&2
    exit 1 ;;
esac
case "$DEMO_HOST" in
  *REPLACE*|"") printf 'Refusing: DEMO_HOST is not set in %s.\n' "$env_file" >&2; exit 1 ;;
esac

printf 'Deploying %s as https://%s\n' "$APP_IMAGE" "$DEMO_HOST"
if ! compose pull --quiet app 2>/dev/null; then
  # A rehearsal runs an image built on this machine, which no registry has. Anything else must pull.
  if docker image inspect "$APP_IMAGE" >/dev/null 2>&1; then
    printf 'Not in a registry; using the local image %s.\n' "$APP_IMAGE"
  else
    printf 'Could not pull %s and it is not present locally.\n' "$APP_IMAGE" >&2
    exit 1
  fi
fi
compose up --detach --remove-orphans

deadline=$((SECONDS + 240))
until compose exec -T app curl --fail --silent --max-time 3 http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; do
  if (( SECONDS >= deadline )); then
    printf 'The application did not become ready within 240 seconds. Recent log lines:\n' >&2
    compose logs --tail 40 app >&2
    exit 1
  fi
  sleep 3
done
printf 'Ready. Running revision:\n'
compose exec -T app curl --silent --max-time 3 http://127.0.0.1:8080/actuator/info | jq '.decisionrail'
printf 'Async delivery (operator read):\n'
compose exec -T app curl --silent --max-time 3 --user "operations:$OPERATIONS_PASSWORD" \
  http://127.0.0.1:8080/actuator/health/async | jq '{status, details: .components.asyncDelivery.details}'
