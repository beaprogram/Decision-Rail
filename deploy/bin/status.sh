#!/usr/bin/env bash
# The three health questions, asked separately: is the process alive, can it take payment traffic,
# and is asynchronous delivery impaired. Plus which revision is running and what the containers say.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

status_usage() {
  cat <<'USAGE'
Usage: deploy/bin/status.sh

Prints liveness, readiness, async delivery state, the running revision and container status for the
decisionrail-public stack. Read-only. Takes no arguments.
USAGE
}
reject_arguments status "$@"
require_dependencies docker curl jq
require_env_file
assert_public_project

probe() { compose exec -T app curl --silent --max-time 3 -o /dev/null -w '%{http_code}' "$@" 2>/dev/null || printf '000'; }
printf 'liveness:  %s\n' "$(probe http://127.0.0.1:8080/actuator/health/liveness)"
printf 'readiness: %s\n' "$(probe http://127.0.0.1:8080/actuator/health/readiness)"
printf 'async:     '
compose exec -T app curl --silent --max-time 3 --user "operations:$OPERATIONS_PASSWORD" \
  http://127.0.0.1:8080/actuator/health/async 2>/dev/null | jq -c '{status, details: .components.asyncDelivery.details}' || printf 'unavailable\n'
printf 'revision:  '
compose exec -T app curl --silent --max-time 3 http://127.0.0.1:8080/actuator/info 2>/dev/null | jq -c '.decisionrail' || printf 'unavailable\n'
compose ps
