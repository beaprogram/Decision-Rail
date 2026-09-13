#!/usr/bin/env bash
# Checks that the result summariser reports the measured phase and nothing else.
#
# This exists because the defect it guards against is invisible in a passing run: reading the
# aggregate metric produces a plausible-looking result file, with percentiles computed over a
# population that is part warmup, under a field that says warmup was excluded. Nothing fails, the
# numbers are simply wrong.
#
# The crafted summary below makes the two populations impossible to confuse: measured latency is 100,
# warmup latency is 9000, and the aggregate that mixes them is 5000. A summariser reading aggregates
# reports 5000 and fails here.
#
#   ./benchmark/collector-check.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cat > "$WORK/summary.json" <<'JSON'
{
  "metrics": {
    "op_authorize":                  {"count": 20, "med": 5000, "p(95)": 5000, "p(99)": 5000, "max": 9000},
    "op_authorize{phase:measured}":  {"count": 10, "med": 100,  "p(95)": 120,  "p(99)": 130,  "max": 140},
    "op_authorize{phase:warmup}":    {"count": 10, "med": 9000, "p(95)": 9000, "p(99)": 9000, "max": 9000},
    "op_capture{phase:measured}":    {"count": 6,  "med": 110,  "p(95)": 130,  "p(99)": 140,  "max": 150},
    "op_void{phase:measured}":       {"count": 4,  "med": 90,   "p(95)": 100,  "p(99)": 110,  "max": 120},
    "http_req_duration{phase:measured}": {"count": 20, "med": 105, "p(95)": 125, "p(99)": 135, "max": 145},
    "http_reqs":                     {"count": 40, "rate": 4.0},
    "http_reqs{phase:measured}":     {"count": 20, "rate": 2.0},
    "iterations{phase:measured}":    {"count": 10, "rate": 1.0},
    "dropped_iterations{phase:measured}": {"count": 3},
    "http_req_failed":               {"value": 0.5},
    "http_req_failed{phase:measured}": {"value": 0.0},
    "unexpected_errors{phase:measured}": {"count": 0}
  }
}
JSON

SCENARIO=check REPETITION=check RATE=10 DURATION=10s WARMUP_SETTING=5s ACCOUNT_COUNT=4 SEED=1 \
AUTHORIZED=0 CAPTURED=6 VOIDED=4 DECLINED=0 KEYS=20 JOURNALS=6 EVENTS=20 \
DRAIN_SECONDS=1 PEAK_BACKLOG=7 PEAK_SAMPLES=5 SAMPLE_INTERVAL=2 RECOVERY_DRAIN=-1 \
SAMPLE_RATE=1.0 OTLP_ENABLED=false OUTAGE=false \
  python3 "$ROOT/benchmark/summarise.py" "$WORK/summary.json" "$WORK/result.json"

python3 - "$WORK/result.json" <<'PY'
import json, sys
result = json.load(open(sys.argv[1]))
failures = []

def check(condition, message):
    if not condition:
        failures.append(message)

authorize = result["latencyMilliseconds"]["authorize"]
# The measured population, not the aggregate and not the warmup.
check(authorize["samples"] == 10, f"authorize samples came from the wrong population: {authorize['samples']}")
check(authorize["p50_ms"] == 100, f"authorize p50 is not the measured value: {authorize['p50_ms']}")
check(authorize["p95_ms"] == 120, f"authorize p95 is not the measured value: {authorize['p95_ms']}")
check(authorize["max_ms"] != 9000, "authorize max is the warmup value")

check(result["achieved"]["httpRequests"] == 20, "http requests came from the aggregate")
check(result["achieved"]["droppedIterations"] == 3, "dropped iterations came from the aggregate")
check(result["achieved"]["failedRequestRate"] == 0.0, "failure rate came from the aggregate")

# The rate must be count over the declared window, not k6's own rate field, which divides by the
# whole run including warmup. 20 requests over a 10s measured window is 2.0/s; k6's aggregate would
# say 4.0 and its measured sub-metric would also say 2.0 for the wrong reason.
check(result["achieved"]["httpRequestRatePerSecond"] == 2.0,
      f"request rate was not computed over the measured window: {result['achieved']['httpRequestRatePerSecond']}")
check(result["population"]["windowSeconds"] == 10.0, "the declared window is wrong")
check(result["population"]["iterationsCompletedInWindow"] == 10, "iteration count came from the wrong population")

# The contaminated aggregate is still reported, clearly labelled, so it is visible rather than hidden.
check(result["wholeRunIncludingWarmup"]["authorizeAggregate"]["samples"] == 20,
      "the whole-run aggregate should still be reported for comparison")

if failures:
    print("COLLECTOR CHECK FAILED:")
    for failure in failures:
        print("  -", failure)
    raise SystemExit(1)
print("collector check passed: every reported figure came from the measured phase")
PY

# And the other half of the contract: a missing sub-metric must stop the run rather than fall back.
cat > "$WORK/aggregate-only.json" <<'JSON'
{"metrics": {"op_authorize": {"count": 20, "med": 5000, "p(95)": 5000, "p(99)": 5000, "max": 9000}}}
JSON
if SCENARIO=check REPETITION=check RATE=10 DURATION=10s WARMUP_SETTING=5s ACCOUNT_COUNT=4 SEED=1 \
   AUTHORIZED=0 CAPTURED=0 VOIDED=0 DECLINED=0 KEYS=0 JOURNALS=0 EVENTS=0 \
   DRAIN_SECONDS=1 PEAK_BACKLOG=0 PEAK_SAMPLES=0 SAMPLE_INTERVAL=2 RECOVERY_DRAIN=-1 \
   SAMPLE_RATE=1.0 OTLP_ENABLED=false OUTAGE=false \
     python3 "$ROOT/benchmark/summarise.py" "$WORK/aggregate-only.json" "$WORK/should-not-exist.json" 2>/dev/null; then
  echo "COLLECTOR CHECK FAILED: a summary with no measured sub-metrics was accepted" >&2
  exit 1
fi
echo "collector check passed: a summary without measured sub-metrics is refused"
