#!/usr/bin/env python3
"""
Turns one k6 summary and the environment it ran in into a sanitised result.

Extracted from collect.sh so it can be run against a crafted summary and checked, which is what
collector-check.sh does. The property worth checking is not arithmetic: it is that every reported
figure comes from the measured phase. This module reads only `{phase:measured}` sub-metrics and fails
loudly when one is absent, rather than silently falling back to an aggregate that contains warmup.

Everything it needs arrives as environment variables so nothing is interpolated into source by a shell.
"""
import json
import os
import platform
import subprocess
import sys
import datetime


def env(name, default=""):
    return os.environ.get(name, default)


summary_path, result_path = sys.argv[1], sys.argv[2]
with open(summary_path) as handle:
    summary = json.load(handle)

metrics = summary.get("metrics", {})


def seconds(duration):
    """k6 durations as used by this harness: 60s, 90s, 2m. Parsed rather than assumed."""
    text = str(duration).strip()
    if text.endswith("ms"):
        return float(text[:-2]) / 1000
    if text.endswith("s"):
        return float(text[:-1])
    if text.endswith("m"):
        return float(text[:-1]) * 60
    return float(text)


# The denominator for every rate below, taken from the configured measured duration rather than from
# k6's own per-sub-metric rate, which divides by the whole run.
MEASURED_SECONDS = seconds(env("DURATION"))

# Everything reported as a measurement comes from the measured phase only.
#
# k6 tags every sample with its scenario's tags, including samples of custom metrics, and writes a
# sub-metric into the summary export for each tag combination a threshold names. The aggregate entries
# beside them still contain warmup samples. Reading those while labelling the result "warmup excluded"
# is exactly the defect this collector was corrected for, so the phase is not optional here: a missing
# sub-metric is a hard failure rather than a silent fall back to the aggregate.
MEASURED = "{phase:measured}"


def measured(name):
    key = name + MEASURED
    if key not in metrics:
        raise SystemExit(
            f"{key} is missing from the k6 summary. The scenario must tag its phase and declare a "
            f"threshold on that sub-metric; falling back to the aggregate '{name}' would mix warmup "
            f"samples into a result labelled as excluding them."
        )
    return metrics[key]


def trend(name):
    values = measured(name)
    if not values or values.get("count") in (None, 0):
        return None
    return {
        "samples": values.get("count"),
        "p50_ms": values.get("med"),
        "p95_ms": values.get("p(95)"),
        "p99_ms": values.get("p(99)"),
        "max_ms": values.get("max"),
    }


def counter(name):
    return measured(name).get("count", 0)


def aggregate_trend(name):
    """The whole run including warmup, reported separately and labelled as such."""
    values = metrics.get(name, {})
    if not values:
        return None
    return {"samples": values.get("count"), "p50_ms": values.get("med"), "max_ms": values.get("max")}

def revision():
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    except Exception:
        return "unknown"

def dirty():
    try:
        return bool(subprocess.check_output(["git", "status", "--porcelain"], text=True).strip())
    except Exception:
        return None

result = {
    "recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "measuredRevision": revision(),
    "workingTreeDirty": dirty(),
    "artifact": {
        "jarSha256": env("JAR_SHA"),
        "jarBuiltAt": env("JAR_BUILT_AT"),
        "note": "the packaged jar actually executed; a revision alone does not establish what ran",
    },
    "scenario": env("SCENARIO"),
    # A label, not a number: CI passes "ci", and a run identified by a word is still a run.
    "repetition": env("REPETITION"),
    "workload": {
        "model": "open (constant arrival rate); the generator keeps offering the rate whether or not the application keeps up",
        "offeredRatePerSecond": float(env("RATE")),
        "duration": env("DURATION"),
        # Excluded by reading only measured-phase sub-metrics, not by assuming the aggregate is clean.
        "warmupExcluded": True,
        "warmupSeparateScenario": env("WARMUP_SETTING"),
        "accounts": int(env("ACCOUNT_COUNT")),
        "seed": int(env("SEED")),
        "authentication": "HTTP Basic, enabled; password verification is included in every measured request",
    },
    "population": {
        "phase": "measured",
        "windowSeconds": MEASURED_SECONDS,
        "definition": (
            "Samples tagged with the measured scenario. Warmup runs as a separate scenario at half "
            "rate and none of its samples appear in any figure below."
        ),
        "iterationsStartedInWindow": counter("iterations"),
        "lateCompletionsNote": (
            "An iteration started inside the window but finishing after it still belongs to this "
            "population and its samples are included; k6 lets in-flight iterations finish during "
            "graceful stop rather than discarding them. The window is therefore when work was offered, "
            "not when the last response arrived."
        ),
    },
    "achieved": {
        "httpRequests": counter("http_reqs"),
        # Computed here as count over the declared measured window. k6's own `rate` on a sub-metric
        # divides by the whole run's duration, warmup included, which understates the measured phase:
        # a probe of 6 measured hits in a 2.07s run with a 2s warmup was reported by k6 as 2.90/s
        # rather than the true measured rate. The denominator is stated above so it can be checked.
        "httpRequestRatePerSecond": round(counter("http_reqs") / MEASURED_SECONDS, 3) if MEASURED_SECONDS else None,
        "iterationRatePerSecond": round(counter("iterations") / MEASURED_SECONDS, 3) if MEASURED_SECONDS else None,
        # Non-zero means the generator could not start work on time: the offered rate was not achieved
        # and the latency figures describe less load than the headline number suggests.
        "droppedIterations": counter("dropped_iterations"),
        "failedRequestRate": measured("http_req_failed").get("value"),
        "unexpectedErrors": counter("unexpected_errors") if "unexpected_errors" + MEASURED in metrics else None,
    },
    "wholeRunIncludingWarmup": {
        "note": "Reported only so the contaminated aggregate is visible rather than hidden; not a measurement.",
        "authorizeAggregate": aggregate_trend("op_authorize"),
        "httpRequests": metrics.get("http_reqs", {}).get("count"),
    },
    "businessOperations": {
        "authorizedRemaining": int(env("AUTHORIZED")),
        "captured": int(env("CAPTURED")),
        "voided": int(env("VOIDED")),
        "declined": int(env("DECLINED")),
        "idempotencyKeysUsed": int(env("KEYS")),
        "captureJournals": int(env("JOURNALS")),
        "durableEventsWritten": int(env("EVENTS")),
    },
    "latencyMilliseconds": {
        "authorize": trend("op_authorize"),
        "capture": trend("op_capture"),
        "void": trend("op_void"),
        "allHttp": trend("http_req_duration"),
    },
    "asynchronous": {
        "brokerOutageInjected": env("OUTAGE") == "true",
        # An observed maximum at the sampling resolution below, taken while the load was running, not
        # a true peak and not a reading taken after the generator stopped.
        "observedMaxUndeliveredBacklog": int(env("PEAK_BACKLOG")),
        "backlogSamples": int(env("PEAK_SAMPLES")) if env("PEAK_SAMPLES") else None,
        "backlogSampleIntervalSeconds": float(env("SAMPLE_INTERVAL")),
        "drainSecondsFromLoadEnd": int(env("DRAIN_SECONDS")),
        # Only meaningful for an outage run: -1 means no broker recovery happened in this run.
        "drainSecondsFromBrokerReachable": (int(env("RECOVERY_DRAIN")) if int(env("RECOVERY_DRAIN")) >= 0 else None),
        "recoveryMeans": "the broker answered a topic listing again, not merely that the container start command returned",
        "drainedMeans": "no unpublished outbox row remains and every payment's projection matches its authoritative status",
        "timeline": "per-sample observations with timestamps are in the .backlog.tsv beside this file",
    },
    "telemetry": {
        "tracingSampleRate": float(env("SAMPLE_RATE")),
        "otlpExportEnabled": env("OTLP_ENABLED") == "true",
        "prometheusScrapedDuringRun": False,
        "note": "Spans are created at the sampled rate regardless of export. Export off means they are dropped rather than sent.",
    },
    "environment": {
        "os": platform.platform(),
        "arch": platform.machine(),
        "cpuCount": os.cpu_count(),
        "java": subprocess.run(["java", "-version"], capture_output=True, text=True).stderr.splitlines()[0]
                if subprocess.run(["java", "-version"], capture_output=True).returncode == 0 else "unknown",
        "database": "PostgreSQL 16 in Docker, tmpfs storage, fsync=off, max_connections=200, shared_buffers=256MB",
        "broker": "Apache Kafka 3.9.1, single node, replication factor 1",
        "applicationHost": "same machine as the load generator and the infrastructure containers",
    },
    "limitations": [
        "Load generator, application, database and broker share one machine, so they compete for the same CPU.",
        "PostgreSQL runs on tmpfs with fsync off, which flatters write latency and is not a production storage profile.",
        "A single JVM and a single broker node. No replication, no horizontal scaling, no network between tiers.",
        "One run of one workload shape. Percentiles are reported per run and never averaged across runs.",
    ],
}
with open(result_path, "w") as handle:
    json.dump(result, handle, indent=2)
    handle.write("\n")
print(f"wrote {result_path}")
