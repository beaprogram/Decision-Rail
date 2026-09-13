#!/usr/bin/env bash
# Proves, with the pinned k6, which selector actually receives dropped iterations.
#
# collector-check.sh feeds the summariser a hand-written summary. That establishes what the summariser
# does with JSON it is given; it cannot establish what k6 puts in that JSON, and the defect this guards
# against lived in exactly that gap. A fixture with a non-zero dropped_iterations{phase:measured} was
# asserting on a population k6 never produces.
#
# So this runs a real generator. No application, no database, no broker, no HTTP: two scenarios offer
# more work than their virtual users can serve, the executor drops what it cannot start, and the
# attribution is read out of the real summary.
#
#   ./benchmark/k6-attribution-check.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
K6_IMAGE="grafana/k6:0.55.0"
# Staged under $HOME because this repository lives on a volume Docker Desktop does not share.
STAGE="${BENCH_STAGE_DIR:-$HOME/.decisionrail-bench-stage}/attribution"
rm -rf "$STAGE"; mkdir -p "$STAGE"
cp "$ROOT/benchmark/k6/attribution-probe.js" "$STAGE/"

run_probe() {
  local mode="$1" out="$2"
  docker run --rm --user "$(id -u):$(id -g)" \
    --env PROBE_MODE="$mode" \
    --volume "$STAGE:/probe" \
    "$K6_IMAGE" run --summary-export "/probe/$out" /probe/attribution-probe.js > "$STAGE/$mode.log" 2>&1 \
    || { grep -E "level=error" "$STAGE/$mode.log" | head -3; }
  [[ -f "$STAGE/$out" ]] || { echo "the probe produced no summary; see $STAGE/$mode.log" >&2; exit 1; }
}

echo "=== Saturating workload: the executor must drop iterations it cannot start"
run_probe saturating saturating.json

echo "=== Quiet workload: the same script at a rate its pool can serve"
run_probe quiet quiet.json

SCENARIO=attribution REPETITION=check RATE=50 DURATION=4s WARMUP_SETTING=3s ACCOUNT_COUNT=0 SEED=1 \
AUTHORIZED=0 CAPTURED=0 VOIDED=0 DECLINED=0 KEYS=0 JOURNALS=0 EVENTS=0 \
DRAIN_SECONDS=0 PEAK_BACKLOG=0 PEAK_SAMPLES=0 SAMPLE_INTERVAL=2 RECOVERY_DRAIN=-1 \
SAMPLE_RATE=1.0 OTLP_ENABLED=false OUTAGE=false JAR_SHA=none JAR_BUILT_AT=none \
SOURCE_REVISION=none SOURCE_DIRTY=false \
  python3 "$ROOT/benchmark/summarise.py" "$STAGE/saturating.json" "$STAGE/saturating-result.json" > /dev/null

SCENARIO=attribution REPETITION=quiet RATE=4 DURATION=4s WARMUP_SETTING=3s ACCOUNT_COUNT=0 SEED=1 \
AUTHORIZED=0 CAPTURED=0 VOIDED=0 DECLINED=0 KEYS=0 JOURNALS=0 EVENTS=0 \
DRAIN_SECONDS=0 PEAK_BACKLOG=0 PEAK_SAMPLES=0 SAMPLE_INTERVAL=2 RECOVERY_DRAIN=-1 \
SAMPLE_RATE=1.0 OTLP_ENABLED=false OUTAGE=false JAR_SHA=none JAR_BUILT_AT=none \
SOURCE_REVISION=none SOURCE_DIRTY=false \
  python3 "$ROOT/benchmark/summarise.py" "$STAGE/quiet.json" "$STAGE/quiet-result.json" > /dev/null

python3 - "$STAGE" <<'PY'
import json, sys

stage = sys.argv[1]
saturating = json.load(open(f"{stage}/saturating.json"))["metrics"]
quiet = json.load(open(f"{stage}/quiet.json"))["metrics"]
reported = json.load(open(f"{stage}/saturating-result.json"))
quiet_reported = json.load(open(f"{stage}/quiet-result.json"))

failures = []
def check(condition, message):
    if not condition:
        failures.append(message)

def count(metrics, key):
    return metrics.get(key, {}).get("count", 0) or 0

aggregate = count(saturating, "dropped_iterations")
by_scenario_measured = count(saturating, "dropped_iterations{scenario:measured}")
by_scenario_warmup = count(saturating, "dropped_iterations{scenario:warmup}")
by_phase_measured = count(saturating, "dropped_iterations{phase:measured}")
by_phase_warmup = count(saturating, "dropped_iterations{phase:warmup}")

print(f"  aggregate dropped_iterations            {aggregate:.0f}")
print(f"  dropped_iterations{{scenario:measured}}   {by_scenario_measured:.0f}")
print(f"  dropped_iterations{{scenario:warmup}}     {by_scenario_warmup:.0f}")
print(f"  dropped_iterations{{phase:measured}}      {by_phase_measured:.0f}   <- the selector that was wrong")
print(f"  dropped_iterations{{phase:warmup}}        {by_phase_warmup:.0f}   <- the selector that was wrong")
print(f"  summariser reported measured drops      {reported['dropAttribution']['measured']:.0f}")
print(f"  quiet workload aggregate drops          {count(quiet, 'dropped_iterations'):.0f}")

# 1. The workload really dropped work, so the rest of the assertions are about something.
check(aggregate > 0, "the saturating probe dropped nothing; it cannot demonstrate attribution")

# 2. The built-in scenario tag separates the two populations.
check(by_scenario_measured > 0, "no drops attributed to the measured scenario")
check(by_scenario_warmup > 0, "no drops attributed to the warmup scenario")

# 3. The partitions account for the aggregate, so nothing is silently unattributed.
check(abs((by_scenario_measured + by_scenario_warmup) - aggregate) < 0.5,
      f"partitions {by_scenario_measured:.0f}+{by_scenario_warmup:.0f} do not reconcile with {aggregate:.0f}")

# 4. The custom scenario tag receives none of them. This is the defect, observed rather than asserted:
#    the sub-metric exists, matches nothing, and would report a confident zero.
check(by_phase_measured == 0 and by_phase_warmup == 0,
      "the custom phase tag now receives dropped iterations; this check's premise needs rechecking "
      "against the pinned k6 version")

# 5. The summariser reports the scenario-attributed count.
check(reported["dropAttribution"]["measured"] == by_scenario_measured,
      f"summariser reported {reported['dropAttribution']['measured']} measured drops, expected {by_scenario_measured:.0f}")
check(reported["dropAttribution"]["warmup"] == by_scenario_warmup,
      "summariser did not report the warmup drops separately")
check(reported["dropAttribution"]["reconciles"] is True, "summariser did not reconcile the partitions")
check(reported["achieved"]["droppedIterations"] == by_scenario_measured,
      "the headline dropped count is not the measured-scenario count")

# 6. A workload that drops nothing reports zero, and reports it for the right reason.
check(count(quiet, "dropped_iterations") == 0, "the quiet probe dropped work; it cannot show a true zero")
check(quiet_reported["achieved"]["droppedIterations"] == 0, "a run with no drops did not report zero")
check(quiet_reported["dropAttribution"]["reconciles"] is True, "a zero-drop run failed reconciliation")

if failures:
    print("\nK6 ATTRIBUTION CHECK FAILED:")
    for failure in failures:
        print("  -", failure)
    raise SystemExit(1)
print("\nk6 attribution check passed: drops are attributed by scenario and reconcile with the aggregate")
PY

# The other half of the contract: a summary with drops but no scenario partition must be refused, not
# reported as zero. This one is a fabricated input on purpose - it is a validation test, not a claim
# about what k6 emits.
cat > "$STAGE/unattributed.json" <<'JSON'
{"metrics": {
  "dropped_iterations": {"count": 285},
  "dropped_iterations{phase:measured}": {"count": 0},
  "op_authorize{scenario:measured}": {"count": 10, "med": 100, "p(95)": 120, "p(99)": 130, "max": 140},
  "http_reqs{scenario:measured}": {"count": 20},
  "iterations{scenario:measured}": {"count": 10},
  "http_req_failed{scenario:measured}": {"value": 0.0}
}}
JSON
if SCENARIO=check REPETITION=check RATE=10 DURATION=10s WARMUP_SETTING=5s ACCOUNT_COUNT=0 SEED=1 \
   AUTHORIZED=0 CAPTURED=0 VOIDED=0 DECLINED=0 KEYS=0 JOURNALS=0 EVENTS=0 \
   DRAIN_SECONDS=0 PEAK_BACKLOG=0 PEAK_SAMPLES=0 SAMPLE_INTERVAL=2 RECOVERY_DRAIN=-1 \
   SAMPLE_RATE=1.0 OTLP_ENABLED=false OUTAGE=false JAR_SHA=none JAR_BUILT_AT=none \
   SOURCE_REVISION=none SOURCE_DIRTY=false \
     python3 "$ROOT/benchmark/summarise.py" "$STAGE/unattributed.json" "$STAGE/never.json" 2>/dev/null; then
  echo "K6 ATTRIBUTION CHECK FAILED: a run with unattributed drops was accepted" >&2
  exit 1
fi
echo "k6 attribution check passed: drops the scenarios do not account for are refused"
