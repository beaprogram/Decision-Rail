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

# An image reference is accepted only in a form that names a revision: a `sha-<full commit>` tag, a
# `@sha256:<digest>` digest, or both. Every other tag - `latest`, `main`, `stable`, a version name -
# is mutable by construction, and a deployment pinned to one cannot be matched to a commit later.
# The form is what this enforces; that a `sha-` tag really is that commit's build is the release
# workflow's guarantee, and `GET /actuator/info` on the running instance is how it is checked.
require_pinned_image() {
  local image=$1
  local name_part=${image%%@*}
  local digest_part=""
  [[ "$image" == *@* ]] && digest_part=${image#*@}
  local tag_ok=0 digest_ok=0
  if [[ "$name_part" =~ ^[a-z0-9._/-]+:sha-[0-9a-f]{40}$ ]]; then tag_ok=1; fi
  if [[ -n "$digest_part" ]]; then
    [[ "$digest_part" =~ ^sha256:[0-9a-f]{64}$ ]] && digest_ok=1
    # With a digest the name may carry no tag at all, or a sha- tag; never a mutable one.
    if [[ "$name_part" == *:* && $tag_ok -eq 0 ]]; then digest_ok=0; fi
  fi
  if (( tag_ok == 0 && digest_ok == 0 )); then
    printf 'Refusing: image "%s" is not pinned to a revision. Use <repository>:sha-<40-hex commit>, <repository>@sha256:<digest>, or both.\n' "$image" >&2
    return 1
  fi
  return 0
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
