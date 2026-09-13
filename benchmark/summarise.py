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
# The built-in scenario tag, not a custom one. k6 attaches a scenario's custom tags to ordinary
# samples but not to the iterations its executor drops: those carry global run tags and the built-in
# scenario tag only. Selecting drops on a custom tag therefore creates a sub-metric that matches
# nothing and reports zero, while the aggregate holds every drop the run produced.
MEASURED = "{scenario:measured}"
WARMUP = "{scenario:warmup}"


def measured(name, required=True):
    """
    The measured-phase sub-metric, with the two absences kept apart.

    A scenario that never records a metric simply has neither entry, and reporting null for it is
    correct: the retries scenario measures replay divergence, not capture latency. A scenario whose
    aggregate is present while its sub-metric is missing is the regression this guards against, and
    that is refused rather than quietly answered from the aggregate.
    """
    key = name + MEASURED
    if key in metrics:
        return metrics[key]
    if name in metrics:
        raise SystemExit(
            f"{key} is missing from the k6 summary while the aggregate '{name}' is present. The "
            f"scenario must tag its phase and declare a threshold on that sub-metric; answering from "
            f"the aggregate would mix warmup samples into a result labelled as excluding them."
        )
    if required:
        raise SystemExit(f"neither {key} nor {name} is in the k6 summary; the scenario recorded nothing")
    return {}


def trend(name):
    values = measured(name, required=False)
    if not values or values.get("count") in (None, 0):
        return None
    return {
        "samples": values.get("count"),
        "p50_ms": values.get("med"),
        "p95_ms": values.get("p(95)"),
        "p99_ms": values.get("p(99)"),
        "max_ms": values.get("max"),
    }


def counter(name, required=True):
    return measured(name, required=required).get("count", 0)


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

def backlog_timeline(path):
    """
    Reads the sampler's timeline and describes what was actually observed.

    Three kinds of row live in that file and only one of them is a measurement. Markers record when the
    broker stopped, when it answered again, when the load ended and when the backlog cleared; counting
    them as samples inflated the reported sample count by exactly the number of interesting events in
    the run. A row whose counts are blank is a failed query, which is not a backlog of zero and must not
    be averaged or maximised as though it were.

    The interval is reported as what it is. The sampler queries the database and then sleeps for the
    configured delay, so the spacing between observations is that delay plus however long the queries
    took. Calling the configured value an observation interval overstates the resolution.
    """
    empty = {
        "observations": 0, "markers": 0, "failedObservations": 0,
        "observedMaxUndeliveredBacklog": None, "observedGapSeconds": None,
    }
    if not path or not os.path.exists(path):
        return empty

    observations, markers, failed = [], 0, 0
    with open(path) as handle:
        next(handle, None)  # header
        for line in handle:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 4:
                continue
            stamp, unpublished, _unprojected, event = parts[0], parts[1], parts[2], parts[3]
            if event != "sample":
                markers += 1
                continue
            if not unpublished.strip().isdigit() or not stamp.strip().isdigit():
                # A query that failed or returned nothing. Recorded as unknown, never as zero.
                failed += 1
                continue
            observations.append((int(stamp), int(unpublished)))

    if not observations:
        return {**empty, "markers": markers, "failedObservations": failed}

    gaps = [later - earlier for (earlier, _), (later, __) in zip(observations, observations[1:])]
    gaps.sort()
    return {
        "observations": len(observations),
        "markers": markers,
        "failedObservations": failed,
        "observedMaxUndeliveredBacklog": max(value for _, value in observations),
        "observedGapSeconds": ({
            "min": gaps[0],
            "median": gaps[len(gaps) // 2],
            "max": gaps[-1],
        } if gaps else None),
    }


timeline = backlog_timeline(env("SAMPLES_FILE"))


def drop_attribution():
    """
    Dropped iterations split by scenario, reconciled against the aggregate.

    The reconciliation is the point. A sub-metric that matches no events is indistinguishable from one
    that matched events summing to zero, and the difference is the whole defect: a run with hundreds of
    drops reported a confident zero. So the partitions must account for the aggregate, and a shortfall
    is refused rather than reported.
    """
    aggregate = metrics.get("dropped_iterations", {}).get("count", 0) or 0
    measured_drops = metrics.get("dropped_iterations" + MEASURED, {}).get("count")
    warmup_drops = metrics.get("dropped_iterations" + WARMUP, {}).get("count")

    known = [value for value in (measured_drops, warmup_drops) if value is not None]
    attributed = sum(known)

    if aggregate > 0 and not known:
        raise SystemExit(
            f"the run dropped {aggregate:.0f} iterations and none of them are attributed to a "
            f"scenario. Declare thresholds on dropped_iterations{MEASURED} (and {WARMUP} when a warmup "
            f"exists) so the drops can be attributed; reporting the aggregate as a measured count would "
            f"blame warmup drops on the measured phase, and reporting zero would be worse."
        )
    if aggregate > 0 and attributed == 0:
        raise SystemExit(
            f"the run dropped {aggregate:.0f} iterations but every scenario partition is zero. That is "
            f"the signature of a selector matching nothing - k6 does not attach custom scenario tags to "
            f"executor-dropped iterations. Select on the built-in scenario tag."
        )
    unattributed = aggregate - attributed
    if abs(unattributed) > 0.5:
        raise SystemExit(
            f"scenario partitions account for {attributed:.0f} of {aggregate:.0f} dropped iterations; "
            f"{unattributed:.0f} are unaccounted for. Every scenario that can drop work must have a "
            f"threshold declared on its partition."
        )
    return {
        "measured": measured_drops if measured_drops is not None else 0,
        "warmup": warmup_drops,
        "aggregateAllScenarios": aggregate,
        "reconciles": abs(unattributed) <= 0.5,
        "attributedBy": "the built-in scenario tag; custom scenario tags do not reach executor-dropped iterations",
    }


drops = drop_attribution()

result = {
    "recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "measuredRevision": env("SOURCE_REVISION") or revision(),
    # As it was before the run started. Read afterwards it is always dirty, because the run's own
    # result files are untracked by then.
    "workingTreeDirtyAtStart": env("SOURCE_DIRTY") or str(dirty()).lower(),
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
    "dropAttribution": drops,
    "population": {
        "phase": "measured",
        "windowSeconds": MEASURED_SECONDS,
        "definition": (
            "Samples tagged with the measured scenario. Warmup runs as a separate scenario at half "
            "rate and none of its samples appear in any figure below."
        ),
        # k6 increments its iteration counter when an iteration ends, so this is completions
        # tagged with the measured scenario, not starts. Reported beside offeredIterations so the
        # difference - work that began in the window and had not finished when graceful stop ended -
        # is visible rather than mistaken for dropped work.
        "iterationsCompletedInWindow": counter("iterations"),
        # Four different quantities, kept apart because they are not interchangeable.
        #
        #   offeredIterations           configured: rate x window. Not a measurement.
        #   droppedIterations           measured: the executor had no free virtual user when the
        #                               iteration was due, so it never started.
        #   iterationsCompletedInWindow measured: iterations that finished and carried the scenario tag,
        #                               including any that finished during the graceful stop period.
        #   unaccountedIterations       derived: offered - dropped - completed. Whatever this is, it is
        #                               not established by these counters alone.
        #
        # The report used to call the unaccounted remainder "iterations still running when graceful stop
        # ended". Nothing here shows that. An arrival-rate executor schedules on a tick and the boundary
        # arithmetic need not land on an exact multiple, so a small remainder is ordinary scheduling
        # behaviour rather than evidence of interrupted work. Claiming otherwise needs k6's own
        # interrupted-iteration reporting, which is not collected here.
        "offeredIterations": float(env("RATE")) * MEASURED_SECONDS,
        "unaccountedIterations": round(
            float(env("RATE")) * MEASURED_SECONDS - counter("iterations") - drops["measured"], 3),
        "unaccountedNote": (
            "Derived, not measured: offered minus completed minus dropped. It is not evidence of "
            "interrupted iterations. No interrupted-iteration count is collected, so any explanation "
            "of this remainder would be an assumption."
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
        # Iterations the executor could not start because no virtual user was free. Non-zero means the
        # offered rate was not achieved, and the latency figures describe less load than the headline
        # number suggests. Attributed by scenario, and reconciled against the aggregate below.
        "droppedIterations": drops["measured"],
        "failedRequestRate": measured("http_req_failed").get("value"),
        "unexpectedErrors": counter("unexpected_errors", required=False)
                            if "unexpected_errors" + MEASURED in metrics else None,
    },
    # Present only for the retry scenario, which is a correctness check rather than a latency
    # measurement: every replay must return the original result.
    "idempotentReplays": ({
        "originalsSent": counter("originals_sent", required=False),
        "replaysSent": counter("replays_sent", required=False),
        "divergentReplays": counter("divergent_replays", required=False),
    } if "replays_sent" + MEASURED in metrics else None),
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
        # An observed maximum across the observations below, taken while the load was running. Not a
        # true peak: it is the largest value the sampler happened to catch, at the spacing reported
        # beside it, and not a reading taken after the generator stopped.
        "observedMaxUndeliveredBacklog": timeline["observedMaxUndeliveredBacklog"],
        "backlogObservations": timeline["observations"],
        # Event markers in the timeline - broker_stop, broker_reachable, load_end, drained. Not
        # measurements, and previously counted as though they were.
        "backlogTimelineMarkers": timeline["markers"],
        # Queries that returned nothing. Reported rather than folded into the observations, because an
        # unanswered query is not a backlog of zero.
        "backlogFailedObservations": timeline["failedObservations"],
        # What the sampler waits between queries, which is not the same as how far apart the
        # observations landed: each cycle is this delay plus the time the queries themselves took.
        "backlogConfiguredDelaySeconds": float(env("SAMPLE_INTERVAL")),
        "backlogObservedGapSeconds": timeline["observedGapSeconds"],
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
