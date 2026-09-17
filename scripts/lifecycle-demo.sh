#!/usr/bin/env bash
# Demonstrates checkpoint 9 against a running local stack: partial refunds, a post-capture
# reversal, the shared return budget, the compensating journals that record each one, idempotent
# recovery of a refund whose response was lost, and reconciliation detecting a discrepancy.
#
# It creates its own synthetic account and works only on that account. It never touches the seeded
# demo balances, never edits a journal, and never calls an endpoint that repairs anything. The one
# deliberate inconsistency it introduces is to its own account's balance column, and it is put back
# before the script exits so the database is left exactly as it was found.
set -euo pipefail

# ---------------------------------------------------------------------------
# Arguments, handled before anything happens
# ---------------------------------------------------------------------------
#
# This block runs before .env is sourced, before credentials are required, before dependencies are
# checked, and before any docker, curl, psql or mktemp call. This script takes no arguments; asking it
# a question must not be a way to perform an action against whatever stack the environment points at.
usage() {
  cat <<'USAGE'
Usage: scripts/lifecycle-demo.sh

Walks refunds, reversal and reconciliation against an already-running DecisionRail stack.
It takes no arguments; everything is configured through the environment.

THIS SCRIPT CREATES SYNTHETIC PAYMENTS AND RETURNS on the instance it targets, creates an account of
its own in the configured database, and briefly skews that account's balance by direct SQL to show
reconciliation detecting it (and puts it back). Point it at disposable infrastructure only.

Environment:
  BASE_URL               application base URL      (default http://localhost:8080)
  COMPOSE_FILE_PATH      Compose file for psql     (default <project>/compose.yaml)
  DATABASE_SERVICE       database service          (default database)
  DATABASE_NAME          database to write to      (default decisionrail)
  MERCHANT_USERNAME      merchant identity         (default demo-merchant)
  ADMIN_USERNAME         administrator identity    (default admin)
  MERCHANT_DEMO_PASSWORD, ADMIN_PASSWORD           required; read from .env when present

Example:
  BASE_URL=http://localhost:8081 COMPOSE_FILE_PATH=compose.test.yaml \\
    DATABASE_SERVICE=test-database DATABASE_NAME=decisionrail_test scripts/lifecycle-demo.sh

Exit status: 0 demo passed or help printed, 1 a check failed, 2 the arguments were not usable.
USAGE
}

if (( $# > 0 )); then
  case "$1" in
    -h|--help)
      if (( $# > 1 )); then
        printf '%s takes no arguments, so --help cannot be combined with any.\n\n' "${BASH_SOURCE[0]##*/}" >&2
        usage >&2
        exit 2
      fi
      usage
      exit 0
      ;;
    *)
      printf 'Unrecognised argument: %s\n%s takes no arguments; configure it through the environment.\n\n' \
        "$1" "${BASH_SOURCE[0]##*/}" >&2
      usage >&2
      exit 2
      ;;
  esac
fi

project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [[ -f "$project_dir/.env" ]]; then
  set -a
  source "$project_dir/.env"
  set +a
fi
: "${MERCHANT_DEMO_PASSWORD:?Generate .env with scripts/prepare-local-env.sh or export MERCHANT_DEMO_PASSWORD}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD is required for the administrative reconciliation view}"
for dependency in curl jq openssl docker; do
  command -v "$dependency" >/dev/null || { printf 'Missing dependency: %s\n' "$dependency" >&2; exit 1; }
done

base_url=${BASE_URL:-http://localhost:8080}
merchant=${MERCHANT_USERNAME:-demo-merchant}
admin_user=${ADMIN_USERNAME:-admin}
compose_file=${COMPOSE_FILE_PATH:-$project_dir/compose.yaml}
database_service=${DATABASE_SERVICE:-database}
database_name=${DATABASE_NAME:-decisionrail}
database_user=${JDBC_USERNAME:-decisionrail}
opening_balance=${DEMO_OPENING_BALANCE:-500000}

run_id="lifecycle-$(openssl rand -hex 8)"
# Built from openssl rather than uuidgen, which is not present on every runner. Version 4, variant 1.
account_id=$(openssl rand -hex 16 | sed -E 's/^(.{8})(.{4}).(.{3}).(.{3})(.{12})$/\1-\2-4\3-a\4-\5/')
[[ "$account_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-a[0-9a-f]{3}-[0-9a-f]{12}$ ]] \
  || { printf 'Could not build a UUID for the demo account.\n' >&2; exit 1; }
work_dir=$(mktemp -d)
skewed=0
checks=0

cleanup() {
  if (( skewed == 1 )); then
    # The demo's own fixture, put back. Reconciliation itself never writes; this is the script
    # undoing what the script did, and it is why the discrepancy could be demonstrated at all.
    printf 'Restoring the deliberately skewed balance before exit.\n' >&2
    psql_value "UPDATE accounts SET balance_minor = balance_minor + 250 WHERE id = '$account_id'" >/dev/null 2>&1 || true
  fi
  rm -rf "$work_dir"
}
trap cleanup EXIT

fail() { printf '\nDemo failed: %s\n' "$*" >&2; exit 1; }
pass() { checks=$((checks + 1)); printf 'PASS  %s\n' "$*"; }
step() { printf '\n== %s ==\n' "$*"; }

psql_value() {
  docker compose --file "$compose_file" exec -T "$database_service" \
    psql --username "$database_user" --dbname "$database_name" --tuples-only --no-align --command "$1" | tr -d '[:space:]'
}

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

authorize() {
  local amount=$1 key=$2
  merchant_call POST /v1/payments/authorizations 201 "$key" \
    "$(jq -cn --arg id "$account_id" --argjson amount "$amount" \
        '{accountId:$id,amountMinor:$amount,currency:"CAD",country:"CA"}')"
  jq -er '.id' "$work_dir/body"
}

captured_payment() {
  local amount=$1 label=$2 payment
  payment=$(authorize "$amount" "$run_id-$label-auth")
  merchant_call POST "/v1/payments/$payment/capture" 200 "$run_id-$label-capture"
  printf '%s' "$payment"
}

balance() { psql_value "SELECT balance_minor FROM accounts WHERE id = '$account_id'"; }


printf 'DecisionRail lifecycle and reconciliation demo: %s\n' "$base_url"
merchant_call GET /actuator/health 200
pass 'application is healthy'

# ---------------------------------------------------------------------------
step 'A synthetic account of this run''s own'
# Created here rather than borrowing a seeded demo account, so every balance assertion below is about
# money this run moved and nothing else.
psql_value "INSERT INTO accounts (id, merchant_id, currency, opening_balance_minor, balance_minor)
            VALUES ('$account_id', '$merchant', 'CAD', $opening_balance, $opening_balance)" >/dev/null
[[ "$(balance)" == "$opening_balance" ]] || fail 'the demo account was not created'
pass "account $account_id opened with $opening_balance minor units"

# ---------------------------------------------------------------------------
step 'Partial refunds draw down one shared budget'
partial=$(captured_payment 5000 partial)
[[ "$(balance)" == "$((opening_balance - 5000))" ]] || fail 'capture did not debit the balance'
pass 'captured CAD 50.00'

merchant_call GET "/v1/payments/$partial/returns" 200
jq -e '.capturedAmountMinor == 5000 and .returnedAmountMinor == 0 and .remainingRefundableMinor == 5000
       and .refundable == true and .reversible == true' "$work_dir/body" >/dev/null \
  || fail 'a fresh capture should be fully refundable and reversible'
pass 'captured 5000, returned 0, remaining 5000'

merchant_call POST "/v1/payments/$partial/refunds" 201 "$run_id-refund-1" \
  '{"amountMinor":1500,"reason":"customer returned one item"}'
first_return=$(jq -er '.returnId' "$work_dir/body")
first_journal=$(jq -er '.journalId' "$work_dir/body")
jq -e '.amountMinor == 1500 and .returnedAmountMinor == 1500 and .remainingRefundableMinor == 3500
       and .sequenceNumber == 1 and .returnType == "REFUND"' "$work_dir/body" >/dev/null \
  || fail 'the first refund receipt is wrong'
cp "$work_dir/body" "$work_dir/first-receipt"
pass 'refunded 1500; 3500 remains'

merchant_call POST "/v1/payments/$partial/refunds" 422 "$run_id-refund-too-much" '{"amountMinor":3501}'
jq -e '.code == "RETURN_EXCEEDS_REFUNDABLE"' "$work_dir/body" >/dev/null || fail 'over-refund was not refused'
pass 'a refund above the remaining amount is refused'

merchant_call POST "/v1/payments/$partial/refunds" 201 "$run_id-refund-2" '{"amountMinor":3500}'
jq -e '.remainingRefundableMinor == 0' "$work_dir/body" >/dev/null || fail 'the remainder was not returned'
[[ "$(balance)" == "$opening_balance" ]] || fail 'the account was not made whole by a full return'
pass 'refunded the remainder; the account is whole again'

merchant_call POST "/v1/payments/$partial/refunds" 422 "$run_id-refund-3" '{"amountMinor":1}'
pass 'nothing further can be returned once the budget is exhausted'

# ---------------------------------------------------------------------------
step 'A historical receipt is not rebuilt from the payment''s current state'
merchant_call POST "/v1/payments/$partial/refunds" 201 "$run_id-refund-1" \
  '{"amountMinor":1500,"reason":"customer returned one item"}'
diff -q <(jq -S . "$work_dir/first-receipt") <(jq -S . "$work_dir/body") >/dev/null \
  || fail 'the replayed receipt differs from the original'
[[ "$(psql_value "SELECT count(*) FROM payment_returns WHERE payment_id = '$partial'")" == 2 ]] \
  || fail 'a replay created another return'
[[ "$(balance)" == "$opening_balance" ]] || fail 'a replay moved money'
pass 'replaying the first key returns its own totals, and credits nothing again'

merchant_call POST "/v1/payments/$partial/refunds" 409 "$run_id-refund-1" '{"amountMinor":1600}'
jq -e '.code == "IDEMPOTENCY_CONFLICT"' "$work_dir/body" >/dev/null || fail 'a changed amount was accepted'
pass 'the same key with a different amount is refused'

# ---------------------------------------------------------------------------
step 'Compensating journals, and the capture evidence they leave alone'
capture_journal=$(psql_value "SELECT id FROM ledger_journals WHERE payment_id = '$partial' AND journal_kind = 'CAPTURE'")
[[ -n "$capture_journal" ]] || fail 'the capture journal is missing'
[[ "$(psql_value "SELECT count(*) FROM ledger_journals WHERE payment_id = '$partial' AND journal_kind = 'CAPTURE'")" == 1 ]] \
  || fail 'a payment must keep exactly one capture journal'
[[ "$(psql_value "SELECT count(*) FROM ledger_journals WHERE payment_id = '$partial' AND journal_kind = 'RETURN'")" == 2 ]] \
  || fail 'each return must have its own journal'
pass 'one capture journal, two return journals'

[[ "$(psql_value "SELECT sum(amount_minor) FROM ledger_entries WHERE journal_id = '$capture_journal' AND side = 'DEBIT'")" == 5000 ]] \
  || fail 'the capture journal changed'
[[ "$(psql_value "SELECT ledger_account FROM ledger_entries WHERE journal_id = '$first_journal' AND side = 'CREDIT'")" == "wallet:$account_id" ]] \
  || fail 'a return must credit the wallet'
[[ "$(psql_value "SELECT ledger_account FROM ledger_entries WHERE journal_id = '$first_journal' AND side = 'DEBIT'")" == "merchant-clearing:$merchant" ]] \
  || fail 'a return must debit merchant clearing'
[[ "$(psql_value "SELECT source_return_id FROM ledger_journals WHERE id = '$first_journal'")" == "$first_return" ]] \
  || fail 'the return journal does not name its operation'
pass 'the return journal is the exact reverse of the capture, linked to its operation'

# The ledger refuses to be edited, which is what makes compensation the only correction.
if psql_value "UPDATE ledger_entries SET amount_minor = 1 WHERE journal_id = '$capture_journal'" >/dev/null 2>&1; then
  fail 'the ledger accepted an edit'
fi
pass 'the database refuses to edit the original capture journal'

# ---------------------------------------------------------------------------
step 'Post-capture reversal, and why it closes after a partial refund'
reversible=$(captured_payment 4100 reversible)
merchant_call POST "/v1/payments/$reversible/reversal" 201 "$run_id-reversal" '{"reason":"captured in error"}'
jq -e '.returnType == "REVERSAL" and .amountMinor == 4100 and .remainingRefundableMinor == 0' "$work_dir/body" >/dev/null \
  || fail 'the reversal did not return the whole capture'
pass 'a reversal returns the whole captured amount'

partly=$(captured_payment 4100 partly)
merchant_call POST "/v1/payments/$partly/refunds" 201 "$run_id-partly-refund" '{"amountMinor":100}'
merchant_call POST "/v1/payments/$partly/reversal" 409 "$run_id-partly-reversal" '{}'
jq -e '.code == "PAYMENT_NOT_REVERSIBLE"' "$work_dir/body" >/dev/null || fail 'a partially refunded capture was reversed'
pass 'a reversal is refused once anything has been returned'

merchant_call GET "/v1/payments/$partly/returns" 200
jq -e '.reversible == false and .refundable == true and .unavailableReason == "PARTIALLY_RETURNED"' "$work_dir/body" >/dev/null \
  || fail 'the returns summary does not explain why reversal is unavailable'
merchant_call POST "/v1/payments/$partly/refunds" 201 "$run_id-partly-rest" '{"amountMinor":4000}'
pass 'refunding the remainder is still available, and the summary says so'

# ---------------------------------------------------------------------------
step 'An authorization that was never captured cannot be returned'
never_captured=$(authorize 900 "$run_id-open-auth")
merchant_call POST "/v1/payments/$never_captured/refunds" 409 "$run_id-open-refund" '{"amountMinor":100}'
jq -e '.code == "INVALID_PAYMENT_STATE"' "$work_dir/body" >/dev/null || fail 'an uncaptured payment was refunded'
merchant_call GET "/v1/payments/$never_captured/returns" 200
jq -e '.unavailableReason == "NOT_CAPTURED" and .capturedAmountMinor == null' "$work_dir/body" >/dev/null \
  || fail 'an uncaptured payment should report NOT_CAPTURED'
# Releasing that hold is a void: it moves no money and writes no journal.
merchant_call POST "/v1/payments/$never_captured/void" 200 "$run_id-open-void"
[[ "$(psql_value "SELECT count(*) FROM ledger_journals WHERE payment_id = '$never_captured'")" == 0 ]] \
  || fail 'a void wrote a journal'
pass 'an authorization is released with void, which writes no journal'

# ---------------------------------------------------------------------------
step 'Every return has its own ordered event'
events=$(psql_value "SELECT string_agg(event_type, ',' ORDER BY aggregate_sequence) FROM outbox_events WHERE aggregate_id = '$partial'")
[[ "$events" == "payment.authorized.v1,payment.captured.v1,payment.refunded.v1,payment.refunded.v1" ]] \
  || fail "unexpected event stream: $events"
pass 'two partial refunds produced two distinct events, after the capture'
[[ "$(psql_value "SELECT count(*) FROM outbox_events WHERE aggregate_id = '$partial' AND payload -> 'returnOperation' IS NOT NULL AND payload -> 'returnOperation' <> 'null'::jsonb")" == 2 ]] \
  || fail 'refund events do not name their return operation'
pass 'each refund event names the operation it records'

# ---------------------------------------------------------------------------
step 'Reconciliation: a correct account'
merchant_call GET "/v1/reconciliation?accountId=$account_id" 200
jq -e '.status == "CLEAN" and (.findings | length) == 0 and .scope.complete == true' "$work_dir/body" >/dev/null \
  || { jq '{status,findings}' "$work_dir/body"; fail 'a correct account did not reconcile'; }
jq -e '.scope.snapshot | test("REPEATABLE READ")' "$work_dir/body" >/dev/null || fail 'the snapshot is not stated'
jq -e '(.limitations | length) > 0 and (.scope.checks | length) > 0' "$work_dir/body" >/dev/null \
  || fail 'the report states neither its checks nor its limits'
pass 'the account reconciles, and the report states its scope and its limits'
jq '{status, accountsExamined: .scope.accountsExamined, paymentsExamined: .scope.paymentsExamined,
     returnsExamined: .scope.returnsExamined, currencies: .scope.currencies}' "$work_dir/body"

# ---------------------------------------------------------------------------
step 'Reconciliation: a discrepancy, detected and reported rather than repaired'
before_skew=$(balance)
psql_value "UPDATE accounts SET balance_minor = balance_minor - 250 WHERE id = '$account_id'" >/dev/null
skewed=1
merchant_call GET "/v1/reconciliation?accountId=$account_id" 200
jq -e '.status == "DISCREPANCIES_FOUND"' "$work_dir/body" >/dev/null || fail 'the skewed balance was not detected'
jq -e '[.findings[] | select(.type == "ACCOUNT_BALANCE_MISMATCH")] | length == 1' "$work_dir/body" >/dev/null \
  || fail 'no balance finding was produced'
jq -e --argjson expected "$before_skew" \
  '.findings[0] | .expectedMinor == $expected and .actualMinor == ($expected - 250) and .deltaMinor == -250
   and .currency == "CAD" and .severity == "CRITICAL" and (.references | length) > 0' "$work_dir/body" >/dev/null \
  || { jq '.findings' "$work_dir/body"; fail 'the finding does not carry usable evidence'; }
pass 'the discrepancy is reported with expected, actual, delta, currency and references'
jq '.findings[0] | {type, resourceType, resourceId, expectedMinor, actualMinor, deltaMinor, currency, detail}' "$work_dir/body"

# Reading the report twice changes nothing: this capability reports, it does not repair.
returns_before_report=$(psql_value "SELECT count(*) FROM payment_returns WHERE account_id = '$account_id'")
journals_before_report=$(psql_value "SELECT count(*) FROM ledger_journals j JOIN payments p ON p.id = j.payment_id WHERE p.account_id = '$account_id'")
merchant_call GET "/v1/reconciliation?accountId=$account_id" 200
[[ "$(balance)" == "$((before_skew - 250))" ]] || fail 'reconciliation changed the balance'
# Counted rather than hard-coded, so adding a step to this demo cannot quietly turn this into an
# assertion about a number nobody maintains.
[[ "$(psql_value "SELECT count(*) FROM payment_returns WHERE account_id = '$account_id'")" == "$returns_before_report" ]] \
  || fail 'reconciliation created or removed a return'
[[ "$(psql_value "SELECT count(*) FROM ledger_journals j JOIN payments p ON p.id = j.payment_id WHERE p.account_id = '$account_id'")" == "$journals_before_report" ]] \
  || fail 'reconciliation created or removed a journal'
pass 'running the report again repairs nothing and creates nothing'

psql_value "UPDATE accounts SET balance_minor = balance_minor + 250 WHERE id = '$account_id'" >/dev/null
skewed=0
merchant_call GET "/v1/reconciliation?accountId=$account_id" 200
jq -e '.status == "CLEAN"' "$work_dir/body" >/dev/null || fail 'the account did not reconcile after the fixture was undone'
pass 'the demo undoes its own fixture and the account reconciles again'

# ---------------------------------------------------------------------------
step 'Reconciliation boundaries'
merchant_call GET "/v1/reconciliation?accountId=$account_id&paymentLimit=1" 200
jq -e '.status == "INCOMPLETE" and (.findings | length) == 0 and .scope.complete == false
       and (.scope.incompleteReason | test("more than 1 payments match"))' "$work_dir/body" >/dev/null \
  || fail 'a bounded report was described as clean'
pass 'a bounded examination is never reported as clean'

merchant_call GET "/v1/ops/reconciliation?merchantId=$merchant" 403
pass 'a merchant identity cannot reach the administrative reconciliation view'
admin_call GET "/v1/ops/reconciliation?merchantId=$merchant&accountId=$account_id" 200
jq -e '.merchantId == "'"$merchant"'"' "$work_dir/body" >/dev/null || fail 'the administrative view returned the wrong merchant'
pass 'the administrator view is authorised on the server, not by hiding a control'

printf '\nLifecycle and reconciliation demo passed: %s checks.\n' "$checks"
printf 'Account %s was created by this run and left reconciling.\n' "$account_id"
