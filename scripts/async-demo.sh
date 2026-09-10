#!/usr/bin/env bash
# Demonstrates the asynchronous capabilities added in checkpoints 4 to 6 against a running
# local stack: event delivery during and after a broker outage, one consumer effect despite
# duplicate delivery, historical replay against a candidate policy, a shadow divergence with
# no financial effect, and an operator-visible failure and recovery.
#
# The broker outage is a real one: this script stops the broker container. It never calls an
# endpoint that executes commands, and it only ever targets the Compose services named below.
set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -f "$project_dir/.env" ]]; then
  set -a
  source "$project_dir/.env"
  set +a
fi
: "${MERCHANT_DEMO_PASSWORD:?Generate .env with scripts/prepare-local-env.sh or export MERCHANT_DEMO_PASSWORD}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD is required for policy, shadow and redrive operations}"
for dependency in curl jq openssl docker; do
  command -v "$dependency" >/dev/null || { printf 'Missing dependency: %s\n' "$dependency" >&2; exit 1; }
done

base_url=${BASE_URL:-http://localhost:8080}
merchant=${MERCHANT_USERNAME:-demo-merchant}
admin_user=${ADMIN_USERNAME:-admin}
account_id=${ACCOUNT_ID:-11111111-1111-1111-1111-111111111111}
compose_file=${COMPOSE_FILE_PATH:-$project_dir/compose.yaml}
broker_service=${BROKER_SERVICE:-broker}
database_service=${DATABASE_SERVICE:-database}
topic=${EVENTS_TOPIC:-decisionrail.payments.v1}
database_name=${DATABASE_NAME:-decisionrail}
database_user=${JDBC_USERNAME:-decisionrail}
recovery_budget=${RECOVERY_BUDGET_SECONDS:-120}

run_id="async-$(openssl rand -hex 8)"
work_dir=$(mktemp -d)
broker_stopped=0
checks=0

cleanup() {
  if (( broker_stopped == 1 )); then
    printf 'Restoring broker before exit.\n' >&2
    docker compose --file "$compose_file" start "$broker_service" >/dev/null 2>&1 || true
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

fail() { printf '\nDemo failed: %s\n' "$*" >&2; exit 1; }
pass() { checks=$((checks + 1)); printf 'PASS  %s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }

call() {
  local user=$1 method=$2 path=$3 expected=$4 key=${5:-} body=${6:-}
  local args=(--silent --show-error --connect-timeout 5 --max-time 30 --user "$user"
    --request "$method" --output "$work_dir/body" --write-out '%{http_code}')
  [[ -z "$key" ]] || args+=(--header "Idempotency-Key: $key")
  [[ -z "$body" ]] || args+=(--header 'Content-Type: application/json' --data "$body")
  local actual
  actual=$(curl "${args[@]}" "$base_url$path")
  [[ "$actual" == "$expected" ]] || { cat "$work_dir/body" >&2; fail "$method $path expected HTTP $expected, received $actual"; }
}

merchant_call() { call "$merchant:$MERCHANT_DEMO_PASSWORD" "$@"; }
admin_call() { call "$admin_user:$ADMIN_PASSWORD" "$@"; }

psql_value() {
  docker compose --file "$compose_file" exec -T "$database_service" \
    psql --username "$database_user" --dbname "$database_name" --tuples-only --no-align --command "$1" | tr -d '[:space:]'
}

psql_raw() {
  docker compose --file "$compose_file" exec -T "$database_service" \
    psql --username "$database_user" --dbname "$database_name" --tuples-only --no-align --command "$1"
}

# Polls until the command succeeds, with an explicit budget instead of a fixed sleep.
await() {
  local description=$1 budget=$2
  shift 2
  local deadline=$((SECONDS + budget))
  until "$@"; do
    (( SECONDS < deadline )) || fail "timed out after ${budget}s waiting for: $description"
    sleep 1
  done
}

authorize() {
  local amount=$1 key=$2
  local payload
  payload=$(jq -cn --arg id "$account_id" --argjson amount "$amount" \
    '{accountId:$id,amountMinor:$amount,currency:"CAD",country:"CA"}')
  merchant_call POST /v1/payments/authorizations 201 "$key" "$payload"
  jq -er '.id' "$work_dir/body"
}

event_status() { psql_value "SELECT status FROM outbox_events WHERE aggregate_id = '$1'"; }

printf 'DecisionRail asynchronous delivery demo: %s\n' "$base_url"
merchant_call GET /actuator/health 200
pass 'application is healthy'
merchant_call GET "/v1/accounts/$account_id" 200
opening_available=$(jq -er '.availableMinor' "$work_dir/body")
(( opening_available >= 20000 )) || fail "demo needs at least 20000 minor units available, found $opening_available"

# ---------------------------------------------------------------------------
step '1. A payment is authorized while the broker is unavailable'
docker compose --file "$compose_file" stop "$broker_service" >/dev/null
broker_stopped=1
pass 'broker container stopped'

outage_payment=$(authorize 2500 "$run_id-outage")
pass "authorization succeeded during the outage: $outage_payment"
[[ "$(psql_value "SELECT status FROM payments WHERE id = '$outage_payment'")" == AUTHORIZED ]] \
  || fail 'payment was not authorized during the broker outage'
pass 'payment state is AUTHORIZED with the broker down'

# ---------------------------------------------------------------------------
step '2. Committed event intent is retained and the backlog is visible'
[[ "$(event_status "$outage_payment")" != PUBLISHED ]] || fail 'event was published with no broker'
outage_event=$(psql_value "SELECT id FROM outbox_events WHERE aggregate_id = '$outage_payment'")
pass "event intent retained and undelivered: $outage_event"

await 'asynchronous delivery reports itself degraded' "$recovery_budget" \
  bash -c "curl --silent --max-time 5 '$base_url/actuator/health/async' | jq -e '.status == \"DEGRADED\"' >/dev/null"
curl --silent --max-time 5 "$base_url/actuator/health/async" | jq '.components.asyncDelivery.details
  | {brokerBreaker, undeliveredEvents, oldestUndeliveredAgeSeconds, paymentApiAffected}'
pass 'async delivery is DEGRADED while the payment API stays healthy'

curl --silent --max-time 5 "$base_url/actuator/health/readiness" | jq -e '.status == "UP"' >/dev/null \
  || fail 'readiness must stay UP during a broker outage'
pass 'readiness is still UP: a broker outage does not remove the payment API from rotation'

admin_call GET /v1/ops/outbox/backlog 200
jq '{breakerState, countsByStatus, oldestPendingAgeSeconds, blockedPaymentCount}' "$work_dir/body"
jq -e '.breakerState == "OPEN" or .breakerState == "HALF_OPEN"' "$work_dir/body" >/dev/null \
  || fail 'breaker did not open during the outage'
pass 'operator backlog view shows the open breaker and the undelivered backlog'

# ---------------------------------------------------------------------------
step '3. Delivery resumes after the broker returns, with the same event identity'
docker compose --file "$compose_file" start "$broker_service" >/dev/null
broker_stopped=0
await 'broker accepts connections again' "$recovery_budget" \
  docker compose --file "$compose_file" exec -T "$broker_service" \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$broker_service:9092" --list
pass 'broker is accepting connections again'

await 'the backlog drains' "$recovery_budget" \
  bash -c "[[ \"\$(docker compose --file '$compose_file' exec -T '$database_service' psql --username '$database_user' --dbname '$database_name' -tAc \"SELECT status FROM outbox_events WHERE aggregate_id = '$outage_payment'\" | tr -d '[:space:]')\" == PUBLISHED ]]"
delivered_event=$(psql_value "SELECT id FROM outbox_events WHERE aggregate_id = '$outage_payment'")
[[ "$delivered_event" == "$outage_event" ]] || fail 'event identity changed across the outage'
pass "delivery recovered and kept the original event id: $delivered_event"
psql_raw "SELECT status, attempts, broker_partition, broker_offset FROM outbox_events WHERE id = '$outage_event'"

await 'the breaker closes again' "$recovery_budget" \
  bash -c "curl --silent --max-time 5 --user '$admin_user:$ADMIN_PASSWORD' '$base_url/v1/ops/outbox/backlog' | jq -e '.breakerState == \"CLOSED\"' >/dev/null"
pass 'circuit breaker closed again through its half-open probe'

await 'the projection reflects the delivered event' "$recovery_budget" \
  bash -c "curl --silent --max-time 5 --user '$merchant:$MERCHANT_DEMO_PASSWORD' '$base_url/v1/payments/$outage_payment/activity' | jq -e '.lastStatus == \"AUTHORIZED\"' >/dev/null"
merchant_call GET "/v1/payments/$outage_payment/activity" 200
jq '{paymentId, lastStatus, lastEventType, lastSequence, riskOutcome, appliedEventCount}' "$work_dir/body"
pass 'consumer built the merchant-scoped activity projection'

# ---------------------------------------------------------------------------
step '4. Duplicate delivery produces exactly one consumer effect'
applied_before=$(jq -er '.appliedEventCount' "$work_dir/body")
payload=$(psql_raw "SELECT payload::text FROM outbox_events WHERE id = '$outage_event'" | tr -d '\n')
[[ -n "$payload" ]] || fail 'could not read the delivered event payload'
for attempt in 1 2; do
  printf '%s\n' "$payload" | docker compose --file "$compose_file" exec -T "$broker_service" \
    /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server "$broker_service:9092" --topic "$topic" >/dev/null
done
pass 'republished the same committed event twice, byte for byte'

await 'the consumer records the duplicates' 60 \
  bash -c "[[ \"\$(docker compose --file '$compose_file' exec -T '$database_service' psql --username '$database_user' --dbname '$database_name' -tAc \"SELECT count(*) FROM consumed_events WHERE event_id = '$outage_event'\" | tr -d '[:space:]')\" != 0 ]]"
sleep 3
merchant_call GET "/v1/payments/$outage_payment/activity" 200
applied_after=$(jq -er '.appliedEventCount' "$work_dir/body")
[[ "$applied_after" == "$applied_before" ]] \
  || fail "duplicate delivery changed the projection from $applied_before to $applied_after"
pass "projection applied count unchanged at $applied_after despite two extra deliveries"
duplicate_rows=$(psql_value "SELECT count(*) FROM consumed_events WHERE event_id = '$outage_event'")
printf 'Deduplication records for this event id (one per consumer group): %s\n' "$duplicate_rows"

# ---------------------------------------------------------------------------
step '5. Historical replay against an immutable candidate policy'
candidate="demo-candidate-$(openssl rand -hex 4)"
candidate_definition='{"rules":[{"code":"STRICT_AMOUNT","description":"Candidate declines at or above 1000 minor units.","scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,"expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}]}'
admin_call POST /v1/policies 201 '' "$(jq -cn --arg id "$candidate" --argjson definition "$candidate_definition" \
  '{versionId:$id,definition:$definition}')"
candidate_hash=$(jq -er '.definitionHash' "$work_dir/body")
pass "registered immutable candidate $candidate with hash ${candidate_hash:0:16}..."

# Resubmitting the same definition is an idempotent retry; different content is a conflict.
admin_call POST /v1/policies 200 '' "$(jq -cn --arg id "$candidate" --argjson definition "$candidate_definition" \
  '{versionId:$id,definition:$definition}')"
pass 'identical resubmission returned the existing version rather than creating a second one'
conflicting=$(printf '%s' "$candidate_definition" | sed 's/"scoreContribution":60/"scoreContribution":30/')
admin_call POST /v1/policies 409 '' "$(jq -cn --arg id "$candidate" --argjson definition "$conflicting" \
  '{versionId:$id,definition:$definition}')"
pass 'rebinding the same version id to a different definition was rejected with 409'

merchant_call POST /v1/replay-jobs 201 "$run_id-replay" \
  "$(jq -cn --arg version "$candidate" '{candidateVersion:$version,limit:200}')"
job_id=$(jq -er '.id' "$work_dir/body")
input_count=$(jq -er '.inputCount' "$work_dir/body")
pass "created replay job $job_id with $input_count pinned inputs"

# A payment created now must not join a job whose membership was already materialised.
late_payment=$(authorize 1500 "$run_id-late")
members_now=$(psql_value "SELECT count(*) FROM replay_job_items WHERE job_id = '$job_id'")
[[ "$members_now" == "$input_count" ]] || fail "membership changed from $input_count to $members_now"
late_member=$(psql_value "SELECT count(*) FROM replay_job_items WHERE job_id = '$job_id' AND payment_id = '$late_payment'")
[[ "$late_member" == 0 ]] || fail 'a payment committed after job creation joined the job'
pass "membership stayed fixed at $input_count and excluded the later payment"

await 'the replay job completes' "$recovery_budget" \
  bash -c "curl --silent --max-time 10 --user '$merchant:$MERCHANT_DEMO_PASSWORD' '$base_url/v1/replay-jobs/$job_id' | jq -e '.status == \"COMPLETED\"' >/dev/null"
merchant_call GET "/v1/replay-jobs/$job_id/report" 200
jq '{status, inputCount, completedCount, failedCount, pendingCount, candidateOutcomeCounts,
     baselineOutcomeCounts, divergenceCount, divergenceRate, divergenceDenominator,
     labelledOutcomeDataAvailable, evaluationTimings}' "$work_dir/body"
jq -e '.completedCount > 0 and .divergenceCount > 0 and .labelledOutcomeDataAvailable == false' "$work_dir/body" >/dev/null \
  || fail 'replay report did not show completed evaluations and divergence'
pass 'replay report shows divergence with a stated denominator and labelled timings'

merchant_call GET "/v1/replay-jobs/$job_id/results?divergedOnly=true&limit=3" 200
jq '[.[] | {paymentId, baselineOutcome, candidateOutcome, diverged,
            baselineReasons: [.baselineReasons[].code], candidateReasons: [.candidateReasons[].code]}]' "$work_dir/body"
pass 'per-payment baseline and candidate explanations are available'

# Replay changed nothing about the payments it replayed.
replayed_decision=$(psql_value "SELECT decision ->> 'ruleSetVersion' FROM payments WHERE id = '$outage_payment'")
[[ "$replayed_decision" == demo-v1 ]] || fail 'replay altered a stored decision'
pass 'original stored decisions still name demo-v1 after the replay'

# ---------------------------------------------------------------------------
step '6. Shadow evaluation diverges with no financial effect'
admin_call PUT /v1/ops/shadow 200 '' "$(jq -cn --arg version "$candidate" '{enabled:true,candidateVersion:$version}')"
jq '{enabled, candidateVersion, pendingTasks, comparisons, divergences}' "$work_dir/body"
pass "shadow evaluation enabled for $candidate"

merchant_call GET "/v1/accounts/$account_id" 200
balance_before=$(jq -er '.balanceMinor' "$work_dir/body")
held_before=$(jq -er '.heldMinor' "$work_dir/body")
ledger_before=$(psql_value 'SELECT count(*) FROM ledger_entries')
journals_before=$(psql_value 'SELECT count(*) FROM ledger_journals')

shadow_payment=$(authorize 3000 "$run_id-shadow")
pass "authorized $shadow_payment with shadow evaluation enabled"

await 'a shadow comparison is recorded' "$recovery_budget" \
  bash -c "curl --silent --max-time 5 --user '$merchant:$MERCHANT_DEMO_PASSWORD' '$base_url/v1/payments/$shadow_payment/shadow' | jq -e 'length == 1' >/dev/null"
merchant_call GET "/v1/payments/$shadow_payment/shadow" 200
jq '[.[] | {paymentId, candidateVersion, baselineOutcome, candidateScore, candidateOutcome, diverged,
            candidateReasons: [.candidateReasons[].code]}]' "$work_dir/body"
jq -e 'length == 1 and .[0].diverged == true and .[0].baselineOutcome == "APPROVE" and .[0].candidateOutcome == "DECLINE"' \
  "$work_dir/body" >/dev/null || fail 'shadow comparison did not show the expected divergence'
pass 'shadow recorded a divergence: live decision APPROVE, candidate DECLINE'

# The live decision and all money state are exactly what authorization produced.
[[ "$(psql_value "SELECT decision ->> 'outcome' FROM payments WHERE id = '$shadow_payment'")" == APPROVE ]] \
  || fail 'shadow evaluation changed the stored decision'
[[ "$(psql_value "SELECT status FROM payments WHERE id = '$shadow_payment'")" == AUTHORIZED ]] \
  || fail 'shadow evaluation changed the payment status'
merchant_call GET "/v1/accounts/$account_id" 200
balance_after=$(jq -er '.balanceMinor' "$work_dir/body")
held_after=$(jq -er '.heldMinor' "$work_dir/body")
[[ "$balance_after" == "$balance_before" ]] || fail "shadow changed the balance: $balance_before -> $balance_after"
[[ "$held_after" == $((held_before + 3000)) ]] \
  || fail "held funds are not exactly the new authorization's own hold: $held_before -> $held_after"
[[ "$(psql_value 'SELECT count(*) FROM ledger_entries')" == "$ledger_before" ]] || fail 'shadow wrote ledger entries'
[[ "$(psql_value 'SELECT count(*) FROM ledger_journals')" == "$journals_before" ]] || fail 'shadow wrote a journal'
shadow_events=$(psql_value "SELECT count(*) FROM outbox_events WHERE aggregate_id = '$shadow_payment'")
[[ "$shadow_events" == 1 ]] || fail "shadow emitted extra events: $shadow_events"
pass 'balances, holds, journals, stored decision and events unchanged by shadow evaluation'
printf 'Balance %s unchanged; held moved %s -> %s, which is exactly the new authorization hold of 3000.\n' \
  "$balance_after" "$held_before" "$held_after"

admin_call PUT /v1/ops/shadow 200 '' '{"enabled":false}'
pass 'shadow evaluation disabled again; recorded comparisons retained'

# ---------------------------------------------------------------------------
step '7. Summary'
admin_call GET /v1/ops/outbox/backlog 200
jq '{breakerState, countsByStatus, oldestPendingAgeSeconds, blockedPaymentCount}' "$work_dir/body"
curl --silent --max-time 5 "$base_url/actuator/health/async" | jq '{status, details: .components.asyncDelivery.details}'

printf '\nDemo passed: %s checks.\n' "$checks"
printf 'Shown: authorization during a broker outage, retained event intent, recovery with the same\n'
printf 'event identity, one consumer effect despite duplicate delivery, historical replay against an\n'
printf 'immutable candidate, a shadow divergence with no financial effect, and an observable\n'
printf 'breaker open and close.\n'
printf 'Guarantee demonstrated: at-least-once delivery with idempotent consumer effects. Not\n'
printf 'end-to-end exactly-once processing across PostgreSQL and Kafka.\n'
