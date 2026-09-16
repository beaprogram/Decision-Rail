#!/usr/bin/env bash
# Demonstrates that a committed refund survives the process that committed it, and that the
# application starts and serves payments while the broker's name does not resolve.
#
# Everything runs on its own disposable stack (compose.recovery.yaml): its own database, its own
# broker, its own application container, its own ports. It is created here and destroyed here. The
# development stack and the shared test stack are never touched.
#
# The staging is deterministic rather than a race. With the broker container stopped its Compose name
# stops resolving, so the dispatcher cannot publish and the refund's event stays pending for exactly
# as long as this script wants it to - no waiting for a 250ms poll to lose.
set -euo pipefail

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compose_file=${RECOVERY_COMPOSE_FILE:-$project_dir/compose.recovery.yaml}
app_port=${RECOVERY_APP_PORT:-8084}
base_url="http://localhost:$app_port"
merchant=demo-merchant
merchant_password=recovery-demo-merchant-ephemeral
account_id=11111111-1111-1111-1111-111111111111
startup_budget=${RECOVERY_STARTUP_BUDGET:-180}
delivery_budget=${RECOVERY_DELIVERY_BUDGET:-180}

for dependency in curl jq openssl docker; do
  command -v "$dependency" >/dev/null || { printf 'Missing dependency: %s\n' "$dependency" >&2; exit 1; }
done

run_id="recovery-$(openssl rand -hex 6)"
work_dir=$(mktemp -d)
checks=0

compose() { RECOVERY_APP_PORT="$app_port" docker compose --file "$compose_file" "$@"; }

cleanup() {
  printf '\nTearing down the disposable recovery stack.\n' >&2
  compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$work_dir"
}
trap cleanup EXIT

fail() { printf '\nRecovery demo failed: %s\n' "$*" >&2; exit 1; }
pass() { checks=$((checks + 1)); printf 'PASS  %s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }

sql() {
  compose exec -T database psql --username decisionrail --dbname decisionrail_recovery \
    --tuples-only --no-align --command "$1" | tr -d '[:space:]'
}

call() {
  local method=$1 path=$2 expected=$3 key=${4:-} body=${5:-}
  local args=(--silent --show-error --connect-timeout 5 --max-time 30
    --user "$merchant:$merchant_password" --request "$method"
    --output "$work_dir/body" --write-out '%{http_code}')
  [[ -z "$key" ]] || args+=(--header "Idempotency-Key: $key")
  [[ -z "$body" ]] || args+=(--header 'Content-Type: application/json' --data "$body")
  local actual
  actual=$(curl "${args[@]}" "$base_url$path")
  [[ "$actual" == "$expected" ]] || { cat "$work_dir/body" >&2; fail "$method $path expected HTTP $expected, got $actual"; }
}

wait_for_api() {
  local deadline=$((SECONDS + startup_budget))
  until curl --fail --silent --max-time 3 "$base_url/actuator/health" >/dev/null 2>&1; do
    (( SECONDS < deadline )) || fail "the application did not answer within ${startup_budget}s"
    sleep 2
  done
}

printf 'DecisionRail restart-recovery demonstration on disposable infrastructure\n'

# ---------------------------------------------------------------------------
step 'Bring up an isolated stack'
compose down --volumes --remove-orphans >/dev/null 2>&1 || true
compose up --detach --build --wait --wait-timeout 300 >/dev/null
wait_for_api
pass 'database, broker and application are up'

# ---------------------------------------------------------------------------
step 'Capture a payment while everything is healthy'
authorization=$(jq -cn --arg id "$account_id" '{accountId:$id,amountMinor:9000,currency:"CAD",country:"CA"}')
call POST /v1/payments/authorizations 201 "$run_id-auth" "$authorization"
payment=$(jq -er '.id' "$work_dir/body")
call POST "/v1/payments/$payment/capture" 200 "$run_id-capture"
balance_after_capture=$(sql "SELECT balance_minor FROM accounts WHERE id = '$account_id'")
pass "captured payment $payment"

# ---------------------------------------------------------------------------
step 'Stop the broker, so its name no longer resolves'
compose stop broker >/dev/null
pass 'broker stopped'

# ---------------------------------------------------------------------------
step 'A refund commits with delivery unavailable'
refund_key="$run_id-refund"
call POST "/v1/payments/$payment/refunds" 201 "$refund_key" \
  '{"amountMinor":3000,"reason":"committed before the restart"}'
cp "$work_dir/body" "$work_dir/receipt"
return_id=$(jq -er '.returnId' "$work_dir/receipt")
journal_id=$(jq -er '.journalId' "$work_dir/receipt")

[[ "$(sql "SELECT returned_amount_minor FROM payments WHERE id = '$payment'")" == 3000 ]] \
  || fail 'the refund did not commit'
[[ "$(sql "SELECT balance_minor FROM accounts WHERE id = '$account_id'")" == "$((balance_after_capture + 3000))" ]] \
  || fail 'the account was not credited'
[[ "$(sql "SELECT count(*) FROM ledger_journals WHERE source_return_id = '$return_id'")" == 1 ]] \
  || fail 'the compensating journal is missing'
pass 'money moved: one return operation, one journal, one credit'

refund_event=$(sql "SELECT id FROM outbox_events WHERE aggregate_id = '$payment' AND event_type = 'payment.refunded.v1'")
[[ -n "$refund_event" ]] || fail 'the refund wrote no event intent'
[[ "$(sql "SELECT status FROM outbox_events WHERE id = '$refund_event'")" != PUBLISHED ]] \
  || fail 'the event was published with the broker stopped'
pass 'its event intent is durable and undelivered'

# ---------------------------------------------------------------------------
step 'Kill the application process, broker still unavailable'
# SIGKILL rather than a graceful stop: this must leave exactly what a crashed process leaves, which
# is a database and nothing else.
compose kill backend >/dev/null
[[ "$(compose ps --status running --services | grep -c '^backend$' || true)" == 0 ]] \
  || fail 'the application is still running'
pass 'application process terminated'

# ---------------------------------------------------------------------------
step 'A new process starts against the same database, with the broker still gone'
compose start backend >/dev/null
wait_for_api
pass 'the application started without a reachable broker'

curl --fail --silent --max-time 5 "$base_url/actuator/health/readiness" --output "$work_dir/body"
jq -e '.status == "UP"' "$work_dir/body" >/dev/null || fail 'readiness is not UP'
curl --fail --silent --max-time 5 "$base_url/actuator/health/async" --output "$work_dir/body"
jq -e '.status == "DEGRADED" and .components.asyncDelivery.details.consumersRunning == false
       and .components.asyncDelivery.details.paymentApiAffected == false' "$work_dir/body" >/dev/null \
  || { jq . "$work_dir/body"; fail 'degraded delivery is not reported honestly'; }
pass 'readiness UP, asynchronous delivery reported DEGRADED with consumers stopped'

# The financial API is genuinely usable, not merely answering health checks.
call GET "/v1/accounts/$account_id" 200
call POST /v1/payments/authorizations 201 "$run_id-after-restart" "$authorization"
after_restart=$(jq -er '.id' "$work_dir/body")
[[ "$(sql "SELECT status FROM payments WHERE id = '$after_restart'")" == AUTHORIZED ]] \
  || fail 'the new process cannot take payments'
pass 'the new process takes payments with no broker'

# ---------------------------------------------------------------------------
step 'Restore the broker'
compose start broker >/dev/null
deadline=$((SECONDS + delivery_budget))
until [[ "$(sql "SELECT status FROM outbox_events WHERE id = '$refund_event'")" == PUBLISHED ]]; do
  (( SECONDS < deadline )) || fail "the refund event was not delivered within ${delivery_budget}s"
  sleep 3
done
pass 'the refund event was delivered by the new process, with no further restart'

# Consumers came back on their own too.
curl --fail --silent --max-time 5 "$base_url/actuator/health/async" --output "$work_dir/body"
jq -e '.components.asyncDelivery.details.consumersRunning == true' "$work_dir/body" >/dev/null \
  || fail 'the consumers did not restart themselves'
pass 'consumers restarted without another application restart'

# ---------------------------------------------------------------------------
step 'What was delivered, and how much of it'
events=$(sql "SELECT string_agg(event_type, ',' ORDER BY aggregate_sequence) FROM outbox_events WHERE aggregate_id = '$payment'")
[[ "$events" == "payment.authorized.v1,payment.captured.v1,payment.refunded.v1" ]] \
  || fail "unexpected event stream: $events"
# One partition, so offsets are assigned in send order: this is the delivered order.
[[ "$(sql "SELECT count(*) FROM (SELECT broker_offset, lag(broker_offset) OVER (ORDER BY aggregate_sequence) AS previous
           FROM outbox_events WHERE aggregate_id = '$payment') ordered
         WHERE previous IS NOT NULL AND broker_offset <= previous")" == 0 ]] \
  || fail 'events were not published in per-payment order'
pass 'published in order: authorized, captured, refunded'

[[ "$(sql "SELECT (payload -> 'returnOperation' ->> 'id') FROM outbox_events WHERE id = '$refund_event'")" == "$return_id" ]] \
  || fail 'the delivered event names a different return operation'
pass 'the event carries the identity committed before the restart'

deadline=$((SECONDS + delivery_budget))
until [[ "$(sql "SELECT coalesce(return_event_count, 0) FROM payment_activity WHERE payment_id = '$payment'")" == 1 ]]; do
  (( SECONDS < deadline )) || fail 'the projection did not apply the refund exactly once'
  sleep 3
done
# One record per consumer group, which is what deduplication guarantees. Two groups consume this
# topic - the activity projection and the shadow enqueue - so the total is per group, not one overall.
[[ "$(sql "SELECT count(*) FROM (SELECT consumer_group FROM consumed_events WHERE event_id = '$refund_event'
           GROUP BY consumer_group HAVING count(*) <> 1) duplicated")" == 0 ]] \
  || fail 'a consumer group recorded the refund event more than once'
[[ "$(sql "SELECT count(*) FROM consumed_events WHERE event_id = '$refund_event'
           AND consumer_group = 'decisionrail-projection'")" == 1 ]] \
  || fail 'the projection did not record the refund event exactly once'
[[ "$(sql "SELECT returned_amount_minor FROM payment_activity WHERE payment_id = '$payment'")" == 3000 ]] \
  || fail 'the projection disagrees with the payment'
pass 'exactly one consumer effect'

# ---------------------------------------------------------------------------
step 'The money is still exactly what it was'
[[ "$(sql "SELECT count(*) FROM payment_returns WHERE payment_id = '$payment'")" == 1 ]] \
  || fail 'more than one return operation exists'
[[ "$(sql "SELECT count(*) FROM ledger_journals WHERE payment_id = '$payment' AND journal_kind = 'RETURN'")" == 1 ]] \
  || fail 'more than one compensating journal exists'
[[ "$(sql "SELECT returned_amount_minor FROM payments WHERE id = '$payment'")" == 3000 ]] \
  || fail 'the returned total changed across the restart'
pass 'one return operation, one journal, one credit'

# ---------------------------------------------------------------------------
step 'Retrying the original command returns the original receipt'
call POST "/v1/payments/$payment/refunds" 201 "$refund_key" \
  '{"amountMinor":3000,"reason":"committed before the restart"}'
diff -q <(jq -S . "$work_dir/receipt") <(jq -S . "$work_dir/body") >/dev/null \
  || fail 'the replayed receipt differs from the original'
[[ "$(sql "SELECT count(*) FROM payment_returns WHERE payment_id = '$payment'")" == 1 ]] \
  || fail 'the retry created a second return'
[[ "$(sql "SELECT id FROM ledger_journals WHERE source_return_id = '$return_id'")" == "$journal_id" ]] \
  || fail 'the journal changed'
pass 'the retry replays the receipt and moves no money'

printf '\nRestart recovery demonstrated: %s checks.\n' "$checks"
printf 'A refund committed before the process was killed, survived it, and was delivered in order\n'
printf 'by a new process that had started with no broker reachable.\n'
