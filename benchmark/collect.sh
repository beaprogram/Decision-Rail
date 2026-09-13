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

python3 - "$SUMMARY_FILE" "$RESULT_FILE" <<PY
import json, platform, subprocess, sys, datetime, os

summary_path, result_path = sys.argv[1], sys.argv[2]
with open(summary_path) as handle:
    summary = json.load(handle)

metrics = summary.get("metrics", {})

def trend(name):
    values = metrics.get(name, {})
    if not values:
        return None
    return {
        "samples": values.get("count"),
        "p50_ms": values.get("med"),
        "p95_ms": values.get("p(95)"),
        "p99_ms": values.get("p(99)"),
        "max_ms": values.get("max"),
    }

def counter(name):
    return metrics.get(name, {}).get("count", 0)

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
    "scenario": "$SCENARIO",
    "repetition": int("$REPETITION"),
    "workload": {
        "model": "open (constant arrival rate); the generator keeps offering the rate whether or not the application keeps up",
        "offeredRatePerSecond": float("$RATE"),
        "duration": "$DURATION",
        "warmupExcluded": True,
        "accounts": int("$ACCOUNT_COUNT"),
        "seed": int("$SEED"),
        "authentication": "HTTP Basic, enabled; password verification is included in every measured request",
    },
    "achieved": {
        "httpRequests": counter("http_reqs"),
        "httpRequestRatePerSecond": metrics.get("http_reqs", {}).get("rate"),
        # Non-zero means the generator could not start work on time: the offered rate was not achieved
        # and the latency figures describe less load than the headline number suggests.
        "droppedIterations": counter("dropped_iterations"),
        "failedRequestRate": metrics.get("http_req_failed", {}).get("value"),
    },
    "businessOperations": {
        "authorizedRemaining": int("$AUTHORIZED"),
        "captured": int("$CAPTURED"),
        "voided": int("$VOIDED"),
        "declined": int("$DECLINED"),
        "idempotencyKeysUsed": int("$KEYS"),
        "captureJournals": int("$JOURNALS"),
        "durableEventsWritten": int("$EVENTS"),
    },
    "latencyMilliseconds": {
        "authorize": trend("op_authorize"),
        "capture": trend("op_capture"),
        "void": trend("op_void"),
        "allHttp": trend("http_req_duration"),
    },
    "asynchronous": {
        "brokerOutageInjected": "$OUTAGE" == "true",
        "peakUndeliveredBacklog": int("$PEAK_BACKLOG"),
        "drainSecondsAfterLoadStopped": int("$DRAIN_SECONDS"),
        "drainedMeans": "no unpublished outbox row remains and every payment's projection matches its authoritative status",
    },
    "telemetry": {
        "tracingSampleRate": float("$SAMPLE_RATE"),
        "otlpExportEnabled": "$OTLP_ENABLED" == "true",
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
PY
