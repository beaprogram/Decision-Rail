#!/usr/bin/env bash
# Turns one k6 summary plus the environment it ran in into a sanitised, machine-readable result.
#
# What is deliberately not written here: credentials, account identifiers, payment identifiers, trace
# identifiers, or any tenant data. A result file is meant to be committed and read by someone who was
# not there, and none of those help them.
set -euo pipefail

SCENARIO="$1"; RATE="$2"; DURATION="$3"; REPETITION="$4"
SUMMARY_FILE="$5"; RESULT_FILE="$6"; DRAIN_SECONDS="$7"
SAMPLE_RATE="$8"; OTLP_ENABLED="$9"; ACCOUNT_COUNT="${10}"; SEED="${11}"; PEAK_BACKLOG="${12}"; OUTAGE="${13}"
WARMUP_SETTING="${14:-unknown}"; PEAK_SAMPLES="${15:-}"; RECOVERY_DRAIN="${16:--1}"; SAMPLE_INTERVAL="${17:-2}"
JAR_SHA="${18:-unknown}"; JAR_BUILT_AT="${19:-unknown}"
SOURCE_REVISION="${20:-unknown}"; SOURCE_DIRTY="${21:-unknown}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="$ROOT/benchmark/compose.bench.yaml"
PSQL=(docker compose --file "$COMPOSE" exec -T bench-database psql --username decisionrail --dbname decisionrail_bench --quiet --tuples-only --no-align)

count() { "${PSQL[@]}" --command "$1" | tr -d '[:space:]'; }

AUTHORIZED="$(count "SELECT count(*) FROM payments WHERE status = 'AUTHORIZED';")"
CAPTURED="$(count "SELECT count(*) FROM payments WHERE status = 'CAPTURED';")"
VOIDED="$(count "SELECT count(*) FROM payments WHERE status = 'VOIDED';")"
DECLINED="$(count "SELECT count(*) FROM payments WHERE status = 'DECLINED';")"
EVENTS="$(count "SELECT count(*) FROM outbox_events;")"
KEYS="$(count "SELECT count(*) FROM idempotency_records;")"
JOURNALS="$(count "SELECT count(*) FROM ledger_journals;")"

# Every value the summariser needs, passed as environment rather than interpolated into source.
SCENARIO="$SCENARIO" REPETITION="$REPETITION" RATE="$RATE" DURATION="$DURATION" \
WARMUP_SETTING="$WARMUP_SETTING" ACCOUNT_COUNT="$ACCOUNT_COUNT" SEED="$SEED" \
AUTHORIZED="$AUTHORIZED" CAPTURED="$CAPTURED" VOIDED="$VOIDED" DECLINED="$DECLINED" \
KEYS="$KEYS" JOURNALS="$JOURNALS" EVENTS="$EVENTS" DRAIN_SECONDS="$DRAIN_SECONDS" \
PEAK_BACKLOG="$PEAK_BACKLOG" PEAK_SAMPLES="$PEAK_SAMPLES" SAMPLE_INTERVAL="$SAMPLE_INTERVAL" \
RECOVERY_DRAIN="$RECOVERY_DRAIN" SAMPLE_RATE="$SAMPLE_RATE" OTLP_ENABLED="$OTLP_ENABLED" \
OUTAGE="$OUTAGE" JAR_SHA="$JAR_SHA" JAR_BUILT_AT="$JAR_BUILT_AT" SOURCE_REVISION="$SOURCE_REVISION" SOURCE_DIRTY="$SOURCE_DIRTY" \
  python3 "$ROOT/benchmark/summarise.py" "$SUMMARY_FILE" "$RESULT_FILE"
