#!/usr/bin/env bash
# The guided demo history, created through the public API so every row is genuine application
# behaviour: real decisions, real journals, real return operations, real events. Nothing is inserted
# behind the service and no outcome is fabricated.
#
# Every command uses a fixed idempotency key, so running this twice creates nothing new: the second
# run replays the first run's receipts. That is the durable idempotency layer doing its job, and it
# is what makes "seed the sandbox" a repeatable operator action rather than a one-shot.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

seed_demo_usage() {
  cat <<'USAGE'
Usage: deploy/bin/seed-demo.sh

Creates the guided walkthrough's synthetic history on the public demo: approvals, a review, a
policy decline, an insufficient-funds decline, a capture, a void, a partial refund, a reversal, a
registered candidate policy, a shadow divergence and a replay job. Takes no arguments.

  SEED_BASE_URL   where to send requests   (default https://$DEMO_HOST from deploy/public.env)
  SEED_CURL_OPTS  extra curl options       (e.g. --insecure for a "tls internal" rehearsal)

Uses the visitor credential for merchant work and the private ADMIN_PASSWORD for the candidate
policy and shadow configuration. Idempotent: a second run replays, it does not duplicate.
USAGE
}
reject_arguments seed_demo "$@"
require_dependencies curl jq
require_env_file

base_url=${SEED_BASE_URL:-https://$DEMO_HOST}
curl_opts=${SEED_CURL_OPTS:-}
visitor="visitor:$VISITOR_PASSWORD"
admin="admin:$ADMIN_PASSWORD"
cad_primary=aaaa0001-0000-4000-8000-000000000001
usd=aaaa0001-0000-4000-8000-000000000003
candidate=strict-amount-v1
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT
checks=0

# shellcheck disable=SC2086
call() {
  local user=$1 method=$2 path=$3 expected=$4 key=${5:-} body=${6:-}
  local args=(--silent --show-error --max-time 30 --user "$user" -X "$method" -o "$work_dir/body" -w '%{http_code}' \
    -H 'Accept: application/json' $curl_opts)
  [[ -n "$key" ]] && args+=(-H "Idempotency-Key: $key")
  [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data "$body")
  local status
  status=$(curl "${args[@]}" "$base_url$path")
  if [[ " $expected " != *" $status "* ]]; then
    printf 'FAIL %s %s -> %s (expected %s)\n%s\n' "$method" "$path" "$status" "$expected" "$(cat "$work_dir/body")" >&2
    exit 1
  fi
  checks=$((checks + 1))
}
payment_id() { jq -r '.id' "$work_dir/body"; }
authorize() { # account amount country key
  call "$visitor" POST /v1/payments/authorizations "201" "seed-$4" \
    "{\"accountId\":\"$1\",\"amountMinor\":$2,\"currency\":\"$5\",\"country\":\"$3\"}"
  payment_id
}

printf 'Seeding the guided demo at %s\n' "$base_url"

# 1. An approved authorization, captured: the ordinary path, with its capture journal.
captured=$(authorize "$cad_primary" 2500 CA capture-1 CAD)
call "$visitor" POST "/v1/payments/$captured/capture" "200" seed-capture-1-capture
printf 'captured payment      %s\n' "$captured"

# 2. Captured, then partly refunded with a reason: the return evidence.
refunded=$(authorize "$cad_primary" 4000 CA refund-1 CAD)
call "$visitor" POST "/v1/payments/$refunded/capture" "200" seed-refund-1-capture
call "$visitor" POST "/v1/payments/$refunded/refunds" "201" seed-refund-1-refund '{"amountMinor":1500,"reason":"customer returned one item"}'
printf 'partly refunded       %s\n' "$refunded"

# 3. Authorized and voided: the hold released, no journal.
voided=$(authorize "$cad_primary" 1250 CA void-1 CAD)
call "$visitor" POST "/v1/payments/$voided/void" "200" seed-void-1-void
printf 'voided                %s\n' "$voided"

# 4. Captured and reversed in full: a compensating journal for the whole capture.
reversed=$(authorize "$cad_primary" 3000 CA reversal-1 CAD)
call "$visitor" POST "/v1/payments/$reversed/capture" "200" seed-reversal-1-capture
call "$visitor" POST "/v1/payments/$reversed/reversal" "201" seed-reversal-1-reverse
printf 'reversed              %s\n' "$reversed"

# 5. A REVIEW outcome (elevated amount) and a policy DECLINE (the synthetic blocked country), so the
#    explanation panel has reasons to show.
review=$(authorize "$cad_primary" 150000 CA review-1 CAD)
declined=$(authorize "$cad_primary" 2000 ZZ decline-1 CAD)
printf 'review / declined     %s / %s\n' "$review" "$declined"

# 6. An insufficient-funds decline: the policy approved (900.00 is below the elevated-amount rule),
#    but the USD account holds 600.00. Distinct from a policy decline on screen and in the record.
insufficient=$(authorize "$usd" 90000 US insufficient-1 USD)
printf 'insufficient funds    %s\n' "$insufficient"

# 7. A candidate policy, registered by the administrator. Identical resubmission is accepted.
call "$admin" POST /v1/policies "201 200" "" "{\"versionId\":\"$candidate\",\"definition\":{\"rules\":[{\"code\":\"STRICT_AMOUNT\",\"description\":\"Candidate declines at or above 20.00.\",\"scoreContribution\":60,\"flag\":\"HIGH_AMOUNT\",\"terminal\":false,\"expression\":{\"operator\":\"AMOUNT_AT_LEAST\",\"amountMinor\":2000}}]}}"
printf 'candidate policy      %s\n' "$candidate"

# 8. Shadow evaluation of that candidate, then one more authorization so a divergence exists.
call "$admin" PUT /v1/ops/shadow "200" "" "{\"enabled\":true,\"candidateVersion\":\"$candidate\"}"
diverging=$(authorize "$cad_primary" 3500 CA shadow-1 CAD)
printf 'shadow divergence on  %s\n' "$diverging"

# 9. A replay of the visitor's history against the candidate.
call "$visitor" POST /v1/replay-jobs "201 200" seed-replay-1 "{\"candidateVersion\":\"$candidate\",\"limit\":100}"
printf 'replay job            %s\n' "$(payment_id)"

printf 'Seeded. Re-running replays these receipts rather than creating more.\n'
