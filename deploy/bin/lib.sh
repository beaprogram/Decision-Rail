#!/usr/bin/env bash
# Shared by every script under deploy/bin. Sourced, not executed.
#
# Two things live here because every script needs them the same way: where the deployment's files
# are, and the check that the Compose project a script is about to touch really is the public demo
# and not the development stack, the test stack or the recovery demo.

deploy_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
project_root=$(cd "$deploy_dir/.." && pwd)
compose_file="$deploy_dir/compose.public.yaml"
env_file="${DEPLOY_ENV_FILE:-$deploy_dir/public.env}"
project_name=decisionrail-public

# Every Compose invocation names the file and the project explicitly. A script that fell back to
# ./compose.yaml would be operating the development stack.
compose() {
  docker compose --file "$compose_file" --project-name "$project_name" --env-file "$env_file" "$@"
}

require_env_file() {
  if [[ ! -f "$env_file" ]]; then
    printf 'No environment file at %s. Run deploy/bin/prepare-env.sh first.\n' "$env_file" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
}

require_dependencies() {
  for dependency in "$@"; do
    command -v "$dependency" >/dev/null || { printf 'Missing dependency: %s\n' "$dependency" >&2; exit 1; }
  done
}

# Refuses to continue unless the Compose project that exists on this host under our name is the one
# this file describes. Guards the destructive scripts against a mistaken host or a renamed file.
assert_public_project() {
  local declared
  declared=$(compose config --format json 2>/dev/null | jq -r '.name // empty')
  if [[ "$declared" != "$project_name" ]]; then
    printf 'Refusing: the Compose project resolved from %s is "%s", not "%s".\n' "$compose_file" "$declared" "$project_name" >&2
    exit 1
  fi
}

# For anything that destroys data: the operator types the project name. A script argument or an
# environment variable would make it possible to run this by accident from a shell history.
confirm_destruction() {
  local action=$1
  printf '%s will destroy the "%s" project and its volumes on this host.\n' "$action" "$project_name" >&2
  printf 'This is the public demo sandbox only; the development stack is a different project and is not touched.\n' >&2
  printf 'Type the project name to continue: ' >&2
  local typed
  read -r typed
  if [[ "$typed" != "$project_name" ]]; then
    printf 'Not confirmed; nothing was changed.\n' >&2
    exit 1
  fi
}

# Common argument handling: these scripts take no arguments beyond what each documents.
reject_arguments() {
  local script=$1; shift
  if (( $# > 0 )); then
    case "$1" in
      -h|--help) "${script}_usage"; exit 0 ;;
      *) printf 'Unrecognised argument: %s\n\n' "$1" >&2; "${script}_usage" >&2; exit 2 ;;
    esac
  fi
}
