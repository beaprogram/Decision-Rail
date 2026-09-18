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

# ---------------------------------------------------------------------------
# Snapshot coherence, from the system of record
# ---------------------------------------------------------------------------
#
# What a restore needs to be true of a dump is this: every outbox event that was PUBLISHED has, for
# each consumer group, the durable record that group's contract writes - a consumed_events receipt
# (whose transaction also carried the group's effect, if any) or a consumer_quarantine row. Events
# still PENDING or CLAIMED are re-dispatched from the outbox after a restore and need nothing else;
# FAILED events stay FAILED and can be redriven. That property is asked of the database directly.
#
# It is deliberately not asked of the broker. Consumer lag as printed by kafka-consumer-groups.sh is
# an indirect proxy for the same thing, and its output cannot be taken as evidence: the tool prints
# errors and exits 0, renders unavailable positions as "-", and prints nothing for a group that has
# not committed - and an earlier version of backup.sh turned each of those into "zero lag". Receipts
# in PostgreSQL are the fact itself, complete or not, with no parsing of a tool's prose.
#
# Consumer group names as the application configures them; overridable to match a deployment that
# renames them.
projection_group=${EVENTS_PROJECTION_GROUP:-decisionrail-projection}
shadow_group=${EVENTS_SHADOW_GROUP:-decisionrail-shadow}

# One row, six integer columns: published|missing_projection|missing_shadow|pending|claimed|failed.
coherence_sql() {
  cat <<SQL
SELECT (SELECT count(*) FROM outbox_events WHERE status = 'PUBLISHED')
    || '|' || (SELECT count(*) FROM outbox_events e WHERE e.status = 'PUBLISHED'
                 AND NOT EXISTS (SELECT 1 FROM consumed_events c WHERE c.consumer_group = '$projection_group' AND c.event_id = e.id)
                 AND NOT EXISTS (SELECT 1 FROM consumer_quarantine q WHERE q.consumer_group = '$projection_group' AND q.event_id = e.id))
    || '|' || (SELECT count(*) FROM outbox_events e WHERE e.status = 'PUBLISHED'
                 AND NOT EXISTS (SELECT 1 FROM consumed_events c WHERE c.consumer_group = '$shadow_group' AND c.event_id = e.id)
                 AND NOT EXISTS (SELECT 1 FROM consumer_quarantine q WHERE q.consumer_group = '$shadow_group' AND q.event_id = e.id))
    || '|' || (SELECT count(*) FROM outbox_events WHERE status = 'PENDING')
    || '|' || (SELECT count(*) FROM outbox_events WHERE status = 'CLAIMED')
    || '|' || (SELECT count(*) FROM outbox_events WHERE status = 'FAILED')
SQL
}

# Runs the coherence query against a database in the stack and prints psql's raw answer.
coherence_observe() {
  local database=$1
  compose exec -T database psql -U decisionrail -d "$database" -v ON_ERROR_STOP=1 -tA -c "$(coherence_sql)" 2>&1
}

# Decides on a raw observation. Prints one line of the form
#   COHERENT published=N pending=N claimed=N failed=N        (exit 0)
#   INCOHERENT published=N missing_projection=N missing_shadow=N ...   (exit 1)
#   UNVERIFIED <reason>                                        (exit 2)
# and nothing else. Anything that is not exactly one row of six non-negative integers is UNVERIFIED:
# an error message, an empty answer, a header, a "-", a second row, a negative or non-numeric value.
# Unverified is never coherent.
assess_coherence() {
  local raw=$1
  local line count
  count=$(printf '%s' "$raw" | grep -c . || true)
  if (( count != 1 )); then
    printf 'UNVERIFIED expected one row from the database, got %s line(s): %s\n' "$count" "$(printf '%s' "$raw" | tr '\n' ' ' | cut -c1-160)"
    return 2
  fi
  line=$(printf '%s' "$raw" | tr -d '[:space:]')
  if ! [[ "$line" =~ ^[0-9]+\|[0-9]+\|[0-9]+\|[0-9]+\|[0-9]+\|[0-9]+$ ]]; then
    printf 'UNVERIFIED the database answer is not six non-negative integers: %s\n' "$(printf '%s' "$line" | cut -c1-160)"
    return 2
  fi
  local published missing_projection missing_shadow pending claimed failed
  IFS='|' read -r published missing_projection missing_shadow pending claimed failed <<< "$line"
  if (( missing_projection > published || missing_shadow > published )); then
    printf 'UNVERIFIED more events lack receipts than were published (%s): the answer is not self-consistent\n' "$line"
    return 2
  fi
  if (( missing_projection > 0 || missing_shadow > 0 )); then
    printf 'INCOHERENT published=%s missing_projection=%s missing_shadow=%s pending=%s claimed=%s failed=%s\n' \
      "$published" "$missing_projection" "$missing_shadow" "$pending" "$claimed" "$failed"
    return 1
  fi
  printf 'COHERENT published=%s pending=%s claimed=%s failed=%s\n' "$published" "$pending" "$claimed" "$failed"
  return 0
}

# ---------------------------------------------------------------------------
# Backup manifests
# ---------------------------------------------------------------------------
#
# A manifest is written beside every dump backup.sh produces. It records the recovery point, the
# schema, what coherence was established and how, and the dump's SHA-256 so the two cannot be paired
# wrongly. It is a description, not a proof: restore.sh checks the manifest's integrity and its
# association with the dump, and then establishes coherence again on the restored data itself.
manifest_version=1

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

# Validates a manifest against its dump. Prints one line - VALID ... or INVALID <reason> - and returns
# 0 or 1. Every required field must be present and well-typed, the version supported, the coherence
# method the one this procedure knows, coherence declared verified, and the checksum equal to the
# dump's. A manifest that fails any of these is not a manifest for this dump.
validate_manifest() {
  local dump=$1 manifest=$2
  [[ -f "$manifest" ]] || { printf 'INVALID no manifest at %s\n' "$manifest"; return 1; }
  if ! jq -e . "$manifest" >/dev/null 2>&1; then printf 'INVALID manifest is not JSON\n'; return 1; fi
  local version
  version=$(jq -r '.version // empty' "$manifest")
  [[ "$version" == "$manifest_version" ]] || { printf 'INVALID unsupported manifest version "%s" (this procedure knows %s)\n' "$version" "$manifest_version"; return 1; }
  local missing
  missing=$(jq -r '[ "recoveryPoint","schema","dumpSha256","coherence","pendingOutboxEventsAtSnapshot" ] - (keys) | join(",")' "$manifest")
  [[ -z "$missing" ]] || { printf 'INVALID manifest lacks required field(s): %s\n' "$missing"; return 1; }
  if ! jq -e '(.recoveryPoint|type=="string") and (.schema|type=="string") and (.dumpSha256|type=="string")
              and (.pendingOutboxEventsAtSnapshot|type=="number")
              and (.coherence|type=="object") and (.coherence.method|type=="string")
              and (.coherence.verified|type=="boolean") and (.coherence.publishedEvents|type=="number")' "$manifest" >/dev/null 2>&1; then
    printf 'INVALID manifest field(s) have the wrong type\n'; return 1
  fi
  [[ "$(jq -r .coherence.method "$manifest")" == "durable-receipts" ]] || { printf 'INVALID unknown coherence method "%s"\n' "$(jq -r .coherence.method "$manifest")"; return 1; }
  [[ "$(jq -r .coherence.verified "$manifest")" == "true" ]] || { printf 'INVALID manifest does not claim verified coherence\n'; return 1; }
  local expected actual
  expected=$(jq -r .dumpSha256 "$manifest"); actual=$(sha256_of "$dump")
  [[ "$expected" == "$actual" ]] || { printf 'INVALID dump checksum %s does not match the manifest'"'"'s %s: this manifest belongs to a different dump\n' "${actual:0:12}" "${expected:0:12}"; return 1; }
  printf 'VALID recoveryPoint=%s schema=%s publishedEvents=%s pending=%s\n' \
    "$(jq -r .recoveryPoint "$manifest")" "$(jq -r .schema "$manifest")" "$(jq -r .coherence.publishedEvents "$manifest")" "$(jq -r .pendingOutboxEventsAtSnapshot "$manifest")"
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
