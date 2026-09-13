#!/usr/bin/env bash
# Runs one benchmark scenario against isolated infrastructure and checks the result is correct.
#
# Everything it touches is its own: its own PostgreSQL, its own broker, its own accounts, its own
# application process on its own port. It never reads or writes the development stack, never stops the
# development broker, and never spends a seeded demo balance.
#
#   ./benchmark/run.sh spread 50 60s      # ordinary traffic across many accounts, 50/s for 60s
#   ./benchmark/run.sh hot 50 60s         # the same rate aimed at one account
#   ./benchmark/run.sh retries 20 30s     # identical commands resent under their original key
#
# Results land in benchmark/results/<scenario>-<rate>-<timestamp>.json, sanitised: no credentials, no
# account identifiers, no payment identifiers.
set -euo pipefail

SCENARIO="${1:-spread}"
RATE="${2:-50}"
DURATION="${3:-60s}"
REPETITION="${4:-1}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="$ROOT/benchmark/compose.bench.yaml"
RESULTS="$ROOT/benchmark/results"
PORT="${BENCH_PORT:-8081}"
BASE_URL="http://127.0.0.1:${PORT}"
CONTAINER_BASE_URL="http://host.docker.internal:${PORT}"
ACCOUNT_COUNT="${ACCOUNT_COUNT:-24}"
# Large enough that a run cannot exhaust it and silently turn into insufficient-funds declines, which
# would stop the benchmark measuring payment work and start it measuring rejection work.
ACCOUNT_BALANCE_MINOR="${ACCOUNT_BALANCE_MINOR:-100000000}"
WARMUP="${WARMUP:-15s}"
SEED="${SEED:-20260913}"
TRACING_SAMPLE_RATE="${TRACING_SAMPLE_RATE:-1.0}"
OTLP_EXPORT_ENABLED="${OTLP_EXPORT_ENABLED:-false}"

PSQL=(docker compose --file "$COMPOSE" exec -T bench-database psql --username decisionrail --dbname decisionrail_bench)

log() { printf '\n=== %s\n' "$*"; }

fail() { printf 'BENCHMARK FAILED: %s\n' "$*" >&2; exit 1; }

cleanup() {
  if [[ -n "${SAMPLER_PID:-}" ]]; then kill "$SAMPLER_PID" 2>/dev/null || true; fi
  if [[ -n "${OUTAGE_PID:-}" ]]; then kill "$OUTAGE_PID" 2>/dev/null || true; fi
  if [[ -n "${APP_PID:-}" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  if [[ "${KEEP_STACK:-false}" != "true" ]]; then
    docker compose --file "$COMPOSE" down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# Read, never written anywhere: not into a result file, not into a URL, not into a log line. Each
# identity keeps its own password because the application refuses to start when two of them match,
# which is a check worth keeping rather than working around.
#
# An already-exported variable wins over the file, so CI supplies these from its secrets and no .env is
# needed there. Locally the generated .env is the source.
read_secret() {
  if [[ -n "${!1:-}" ]]; then printf '%s' "${!1}"; return; fi
  [[ -f "$ROOT/.env" ]] || fail "$1 is not set and there is no .env; run scripts/prepare-local-env.sh"
  grep -E "^$1=" "$ROOT/.env" | cut -d= -f2-
}
MERCHANT_PASSWORD="$(read_secret MERCHANT_DEMO_PASSWORD)"
OTHER_PASSWORD="$(read_secret MERCHANT_OTHER_PASSWORD)"
OPERATIONS_SECRET="$(read_secret OPERATIONS_PASSWORD)"
ADMIN_SECRET="$(read_secret ADMIN_PASSWORD)"
for name in MERCHANT_PASSWORD OTHER_PASSWORD OPERATIONS_SECRET ADMIN_SECRET; do
  [[ -n "${!name}" ]] || fail "$name is missing from .env"
done

JAR="$ROOT/target/decisionrail-0.1.0.jar"
[[ -f "$JAR" ]] || fail "build the application first: ./mvnw package (the jar under test must be the packaged one)"
# The artifact's own identity, recorded with the result. A revision alone does not establish what was
# measured: a jar can be older than the checkout it sits in, and this is how that is caught rather than
# assumed. The working tree state is recorded alongside it for the same reason.
JAR_SHA="$(shasum -a 256 "$JAR" | cut -d' ' -f1)"
# Captured before the run writes anything. Collecting it afterwards always reports a dirty tree,
# because the result files the run is about to produce are themselves untracked.
SOURCE_REVISION="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
SOURCE_DIRTY="$(test -z "$(git -C "$ROOT" status --porcelain 2>/dev/null)" && echo false || echo true)"
JAR_BUILT_AT="$(date -u -r "$JAR" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || stat -c %y "$JAR")"

mkdir -p "$RESULTS"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RESULT_FILE="$RESULTS/${SCENARIO}-${RATE}-rep${REPETITION}-${STAMP}.json"
SUMMARY_FILE="$RESULTS/${SCENARIO}-${RATE}-rep${REPETITION}-${STAMP}.summary.json"

log "Starting isolated benchmark infrastructure"
docker compose --file "$COMPOSE" up -d --wait

log "Starting the packaged application on port $PORT"
JDBC_URL="jdbc:postgresql://127.0.0.1:55435/decisionrail_bench" \
JDBC_USERNAME="decisionrail" \
JDBC_PASSWORD="local-bench-only" \
KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:19094" \
MERCHANT_DEMO_PASSWORD="$MERCHANT_PASSWORD" \
MERCHANT_OTHER_PASSWORD="$OTHER_PASSWORD" \
OPERATIONS_PASSWORD="$OPERATIONS_SECRET" \
ADMIN_PASSWORD="$ADMIN_SECRET" \
DEMO_ENABLED=false \
PORT="$PORT" \
TRACING_SAMPLE_RATE="$TRACING_SAMPLE_RATE" \
OTLP_EXPORT_ENABLED="$OTLP_EXPORT_ENABLED" \
  java -jar "$JAR" > "$RESULTS/app-${STAMP}.log" 2>&1 &
APP_PID=$!

deadline=$((SECONDS + 120))
until curl --fail --silent --max-time 2 "$BASE_URL/actuator/health" > /dev/null 2>&1; do
  (( SECONDS < deadline )) || fail "the application did not become healthy; see $RESULTS/app-${STAMP}.log"
  kill -0 "$APP_PID" 2>/dev/null || fail "the application exited; see $RESULTS/app-${STAMP}.log"
  sleep 2
done

log "Seeding $ACCOUNT_COUNT dedicated accounts"
"${PSQL[@]}" --quiet --command "
    INSERT INTO accounts (id, merchant_id, currency, opening_balance_minor, balance_minor)
    SELECT gen_random_uuid(), 'demo-merchant', 'CAD', $ACCOUNT_BALANCE_MINOR, $ACCOUNT_BALANCE_MINOR
    FROM generate_series(1, $ACCOUNT_COUNT);" > /dev/null
ACCOUNT_IDS="$("${PSQL[@]}" --quiet --tuples-only --no-align --command \
    "SELECT string_agg(id::text, ',') FROM accounts WHERE merchant_id = 'demo-merchant';" | tr -d '[:space:]')"
[[ -n "$ACCOUNT_IDS" ]] || fail "accounts were not seeded"

# Backlog is sampled while the load is running, not after it.
#
# The previous harness initialised its peak counter after the generator had already exited, so the
# figure it published as a peak was whatever remained once nothing new was arriving. It could not
# observe the outage at all. This samples on a fixed interval from before the load starts until the
# backlog has drained, writes each observation with a timestamp, and the reported figure is an
# observed maximum at that resolution rather than a true peak.
SAMPLE_INTERVAL="${BACKLOG_SAMPLE_INTERVAL:-2}"
SAMPLES_FILE="$RESULTS/${SCENARIO}-${RATE}-rep${REPETITION}-${STAMP}.backlog.tsv"
printf 'epoch_seconds\tunpublished\tunprojected\tevent\n' > "$SAMPLES_FILE"
SAMPLER_PID=""
start_sampler() {
  (
    while :; do
      unpublished="$("${PSQL[@]}" --quiet --tuples-only --no-align --command \
          "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED';" 2>/dev/null | tr -d '[:space:]')"
      unprojected="$("${PSQL[@]}" --quiet --tuples-only --no-align --command \
          "SELECT count(*) FROM payments p LEFT JOIN payment_activity a ON a.payment_id = p.id
           WHERE a.payment_id IS NULL OR a.last_status <> p.status;" 2>/dev/null | tr -d '[:space:]')"
      printf '%s\t%s\t%s\t%s\n' "$(date +%s)" "${unpublished:-}" "${unprojected:-}" "sample" >> "$SAMPLES_FILE"
      sleep "$SAMPLE_INTERVAL"
    done
  ) &
  SAMPLER_PID=$!
}

mark() { printf '%s\t\t\t%s\n' "$(date +%s)" "$1" >> "$SAMPLES_FILE"; }

# A one-off fault demonstration, labelled as such in the report rather than mixed into the steady-state
# figures. It stops this stack's own broker and nothing else; the development broker is never touched.
OUTAGE_PID=""
BROKER_RECOVERED_AT=""
if [[ "${BROKER_OUTAGE:-false}" == "true" ]]; then
  OUTAGE_SECONDS="${OUTAGE_SECONDS:-20}"
  # Placed inside the measured phase: the warmup runs first, so the delay clears it before stopping
  # the broker. An outage during warmup would not appear in any reported figure.
  OUTAGE_DELAY="${OUTAGE_DELAY:-25}"
  log "A $OUTAGE_SECONDS second broker outage will be injected ${OUTAGE_DELAY}s in, inside the measured phase"
  RECOVERY_MARK="$RESULTS/.recovered-${STAMP}"
  (
    sleep "$OUTAGE_DELAY"
    mark "broker_stop"
    docker compose --file "$COMPOSE" stop bench-broker >/dev/null 2>&1
    sleep "$OUTAGE_SECONDS"
    docker compose --file "$COMPOSE" start bench-broker >/dev/null 2>&1
    # Recovery is when the broker answers again, not when the start command returned.
    until docker compose --file "$COMPOSE" exec -T bench-broker \
        /opt/kafka/bin/kafka-topics.sh --bootstrap-server bench-broker:9092 --list >/dev/null 2>&1; do
      sleep 1
    done
    date +%s > "$RECOVERY_MARK"
    mark "broker_reachable"
  ) &
  OUTAGE_PID=$!
fi

start_sampler

log "Running scenario '$SCENARIO' at $RATE/s for $DURATION (warmup $WARMUP, repetition $REPETITION)"
SCRIPT="payments.js"
MODE="$SCENARIO"
case "$SCENARIO" in
  spread|hot) ;;
  retries) SCRIPT="retries.js"; MODE="spread" ;;
  *) fail "unknown scenario '$SCENARIO'; use spread, hot or retries" ;;
esac

# The load generator runs in a container, and this repository lives on an external volume that Docker
# Desktop does not share by default. Rather than ask every reader to reconfigure their Docker file
# sharing, the scripts and the result file are staged through a directory under $HOME, which is shared
# out of the box, and copied back afterwards. The staging directory holds no credentials: they are
# passed as environment variables to the container and never written to disk.
STAGE="${BENCH_STAGE_DIR:-$HOME/.decisionrail-bench-stage}"
mkdir -p "$STAGE/scripts" "$STAGE/results"
cp "$ROOT/benchmark/k6/"*.js "$STAGE/scripts/"

# The application runs on the host, the generator in a container. host.docker.internal is how a
# container reaches the host on Docker Desktop, and --add-host makes the same name work on Linux, so
# one command line covers both. Using --network host instead would work on Linux and silently point at
# the container's own loopback on macOS, which is how the first attempt at this produced a clean-looking
# run in which every request failed.
# Runs as the invoking user so the summary it writes into the staged results directory is owned by
# whoever started the run. The k6 image runs as its own non-root user by default, which on Linux cannot
# write into a bind mount owned by someone else; on macOS Docker Desktop the ownership is mapped for
# you, so the failure only appears in CI.
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --add-host=host.docker.internal:host-gateway \
  --env BASE_URL="$CONTAINER_BASE_URL" \
  --env MERCHANT_PASSWORD="$MERCHANT_PASSWORD" \
  --env ACCOUNT_IDS="$ACCOUNT_IDS" \
  --env HOT_ACCOUNT_ID="${ACCOUNT_IDS%%,*}" \
  --env MODE="$MODE" \
  --env RATE="$RATE" \
  --env DURATION="$DURATION" \
  --env WARMUP="$WARMUP" \
  --env SEED="$SEED" \
  --volume "$STAGE/scripts:/scripts:ro" \
  --volume "$STAGE/results:/results" \
  grafana/k6:0.55.0 run --summary-export "/results/summary.json" "/scripts/$SCRIPT" \
  || fail "the load generator reported a threshold breach; see $STAGE/results/summary.json"
cp "$STAGE/results/summary.json" "$SUMMARY_FILE"
mark "load_end"
LOAD_END_EPOCH=$(date +%s)
if [[ -n "$OUTAGE_PID" ]]; then wait "$OUTAGE_PID" 2>/dev/null || true; fi

log "Waiting for asynchronous delivery to drain"
drain_deadline=$((SECONDS + 300))
while :; do
  remaining="$("${PSQL[@]}" --quiet --tuples-only --no-align --command \
      "SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED';" | tr -d '[:space:]')"
  behind="$("${PSQL[@]}" --quiet --tuples-only --no-align --command \
      "SELECT count(*) FROM payments p LEFT JOIN payment_activity a ON a.payment_id = p.id
       WHERE a.payment_id IS NULL OR a.last_status <> p.status;" | tr -d '[:space:]')"
  # Drained means two things at once: nothing is left to publish, and the read model has caught up
  # with every payment. Either alone would let this finish while work was still outstanding.
  [[ "$remaining" == "0" && "$behind" == "0" ]] && break
  (( SECONDS < drain_deadline )) || fail "delivery did not drain: $remaining unpublished, $behind payments unprojected"
  sleep 2
done
mark "drained"
DRAINED_EPOCH=$(date +%s)
kill "$SAMPLER_PID" 2>/dev/null || true
wait "$SAMPLER_PID" 2>/dev/null || true

# Two different clocks, reported separately because they answer different questions.
#   load end to drained      - how long the tail took once nothing new was arriving
#   broker reachable to drained - how long recovery itself took, which only exists for an outage run
DRAIN_SECONDS=$((DRAINED_EPOCH - LOAD_END_EPOCH))
RECOVERY_DRAIN_SECONDS=-1
if [[ -n "${RECOVERY_MARK:-}" && -f "${RECOVERY_MARK:-}" ]]; then
  RECOVERY_DRAIN_SECONDS=$(( DRAINED_EPOCH - $(cat "$RECOVERY_MARK") ))
  rm -f "$RECOVERY_MARK"
fi
# The observed maximum across the samples taken during load, outage and drain, at the interval above.
PEAK_BACKLOG="$(awk -F'\t' 'NR>1 && $2 ~ /^[0-9]+$/ && $2+0 > max { max = $2+0 } END { print max+0 }' "$SAMPLES_FILE")"
PEAK_SAMPLES="$(awk 'NR>1' "$SAMPLES_FILE" | wc -l | tr -d '[:space:]')"

log "Checking correctness of the workload just measured"
VERIFY_OUTPUT="$("${PSQL[@]}" --quiet --tuples-only --no-align --variable=accounts="$ACCOUNT_IDS" \
    --file /dev/stdin < "$ROOT/benchmark/verify.sql" 2>&1 || true)"
if [[ -n "$(printf '%s' "$VERIFY_OUTPUT" | tr -d '[:space:]')" ]]; then
  printf '%s\n' "$VERIFY_OUTPUT" >&2
  fail "post-run correctness checks reported findings"
fi

log "Collecting the environment this measurement was taken in"
"$ROOT/benchmark/collect.sh" "$SCENARIO" "$RATE" "$DURATION" "$REPETITION" "$SUMMARY_FILE" "$RESULT_FILE" \
  "$DRAIN_SECONDS" "$TRACING_SAMPLE_RATE" "$OTLP_EXPORT_ENABLED" "$ACCOUNT_COUNT" "$SEED" "$PEAK_BACKLOG" \
  "${BROKER_OUTAGE:-false}" "$WARMUP" "$PEAK_SAMPLES" "$RECOVERY_DRAIN_SECONDS" "$SAMPLE_INTERVAL" \
  "$JAR_SHA" "$JAR_BUILT_AT" "$SOURCE_REVISION" "$SOURCE_DIRTY"

printf '\nBenchmark complete. Correctness checks passed. Result: %s\n' "$RESULT_FILE"
