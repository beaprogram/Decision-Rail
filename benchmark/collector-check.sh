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
    "op_authorize":                    {"count": 20, "med": 5000, "p(95)": 5000, "p(99)": 5000, "max": 9000},
    "op_authorize{scenario:measured}":  {"count": 10, "med": 100,  "p(95)": 120,  "p(99)": 130,  "max": 140},
    "op_authorize{scenario:warmup}":    {"count": 10, "med": 9000, "p(95)": 9000, "p(99)": 9000, "max": 9000},
    "op_capture{scenario:measured}":    {"count": 6,  "med": 110,  "p(95)": 130,  "p(99)": 140,  "max": 150},
    "op_void{scenario:measured}":       {"count": 4,  "med": 90,   "p(95)": 100,  "p(99)": 110,  "max": 120},
    "http_req_duration{scenario:measured}": {"count": 20, "med": 105, "p(95)": 125, "p(99)": 135, "max": 145},
    "http_reqs":                     {"count": 40, "rate": 4.0},
    "http_reqs{scenario:measured}":     {"count": 20, "rate": 2.0},
    "iterations{scenario:measured}":    {"count": 10, "rate": 1.0},
    "dropped_iterations": {"count": 5},
    "dropped_iterations{scenario:measured}": {"count": 3},
    "dropped_iterations{scenario:warmup}": {"count": 2},
    "http_req_failed":               {"value": 0.5},
    "http_req_failed{scenario:measured}": {"value": 0.0},
    "unexpected_errors{scenario:measured}": {"count": 0}
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

# The backlog timeline: markers are not measurements, gaps are not the configured delay, and a failed
# query is not a backlog of zero. Every one of those was previously wrong or unreported.
printf 'epoch_seconds\tunpublished\tunprojected\tevent\n'  > "$WORK/timeline.tsv"
{
  printf '1000\t10\t4\tsample\n'      # observation
  printf '1002\t40\t9\tsample\n'      # +2s, the configured delay
  printf '1003\t\t\tbroker_stop\n'    # marker, not a measurement
  printf '1006\t120\t30\tsample\n'    # +4s: queries took longer than the delay
  printf '1008\t\t\tsample\n'         # the query returned nothing: unknown, not zero
  printf '1013\t95\t12\tsample\n'     # +7s after the last real observation
  printf '1014\t\t\tload_end\n'       # marker
  printf '1015\t0\t0\tsample\n'       # drained
  printf '1016\t\t\tdrained\n'        # marker
} >> "$WORK/timeline.tsv"

SCENARIO=check REPETITION=timeline RATE=10 DURATION=10s WARMUP_SETTING=5s ACCOUNT_COUNT=4 SEED=1 \
AUTHORIZED=0 CAPTURED=6 VOIDED=4 DECLINED=0 KEYS=20 JOURNALS=6 EVENTS=20 \
DRAIN_SECONDS=1 PEAK_BACKLOG=0 PEAK_SAMPLES=0 SAMPLE_INTERVAL=2 RECOVERY_DRAIN=-1 \
SAMPLE_RATE=1.0 OTLP_ENABLED=false OUTAGE=true SAMPLES_FILE="$WORK/timeline.tsv" \
  python3 "$ROOT/benchmark/summarise.py" "$WORK/summary.json" "$WORK/timeline-result.json" > /dev/null

python3 - "$WORK/timeline-result.json" <<'PY'
import json, sys
asynchronous = json.load(open(sys.argv[1]))["asynchronous"]
failures = []

def check(condition, message):
    if not condition:
        failures.append(f"{message} (got {asynchronous.get('backlogObservations')} observations, "
                        f"{asynchronous.get('backlogTimelineMarkers')} markers, "
                        f"{asynchronous.get('backlogFailedObservations')} failed, "
                        f"max {asynchronous.get('observedMaxUndeliveredBacklog')}, "
                        f"gaps {asynchronous.get('backlogObservedGapSeconds')})")

# Five rows carry a usable measurement; three are markers and one is a failed query.
check(asynchronous["backlogObservations"] == 5, "observation rows were miscounted")
check(asynchronous["backlogTimelineMarkers"] == 3, "event markers were miscounted")
check(asynchronous["backlogFailedObservations"] == 1, "a failed query was not reported as such")

# The failed row must not become a zero, which would drag a minimum down and look like a drained
# backlog at a moment when nothing was known.
check(asynchronous["observedMaxUndeliveredBacklog"] == 120, "the observed maximum is wrong")

# Spacing is what was observed, not what was configured. Both are reported.
gaps = asynchronous["backlogObservedGapSeconds"]
check(gaps is not None and gaps["min"] == 2 and gaps["max"] == 7,
      "observed gaps do not describe the timeline")
check(asynchronous["backlogConfiguredDelaySeconds"] == 2.0, "the configured delay is not reported")
check(gaps["max"] != asynchronous["backlogConfiguredDelaySeconds"],
      "this fixture is meant to have irregular spacing; it no longer does")

if failures:
    print("TIMELINE CHECK FAILED:")
    for failure in failures:
        print("  -", failure)
    raise SystemExit(1)
print("timeline check passed: markers, failed queries and irregular spacing are all reported honestly")
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
