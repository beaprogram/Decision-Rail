package com.decisionrail.resilience;

import com.decisionrail.shadow.ShadowStore;
import com.decisionrail.telemetry.Cached;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Queue-depth gauges for the asynchronous workloads.
 *
 * <p>Every label here is a bounded enumeration: a task state, a job status. None of them carries a
 * payment id, a merchant id, a policy version, or any other value that grows with traffic, because an
 * unbounded label set turns a metrics backend into an outage of its own. Per-payment detail belongs in
 * the operator APIs, which are queried deliberately rather than scraped continuously.
 *
 * <p>Each group is one cached aggregate query rather than a count per label per scrape. Ten full
 * counts every fifteen seconds over tables that only grow is a cost that rises with retained history,
 * and it lands hardest exactly when the system is busiest. Readings are at most
 * {@link #FRESHNESS} old, and report no data rather than zero when the database cannot answer.
 */
@Component
public class WorkloadMetrics {
    /** How stale a queue-depth reading may be. Stated in the metric catalogue. */
    public static final Duration FRESHNESS = Duration.ofSeconds(5);

    private static final String[] TASK_STATES = {"PENDING", "CLAIMED", "DONE", "FAILED"};
    private static final String[] JOB_STATUSES = {"PENDING", "RUNNING", "COMPLETED", "FAILED"};

    public WorkloadMetrics(JdbcTemplate jdbc, ShadowStore shadow, MeterRegistry registry, Clock clock) {
        Cached<Map<String, Long>> tasks = new Cached<>("shadow.tasks", FRESHNESS, clock,
                () -> countsByKey(shadow, TASK_STATES));
        for (String state : TASK_STATES) {
            Gauge.builder("decisionrail.shadow.tasks", () -> tasks.reading(current -> current.getOrDefault(state, 0L)))
                    .description("Shadow evaluation tasks by state, at most " + FRESHNESS.toSeconds() + "s old")
                    .tag("state", state)
                    .register(registry);
        }

        Cached<long[]> comparisons = new Cached<>("shadow.comparisons", FRESHNESS, clock,
                () -> new long[]{shadow.countComparisons(false), shadow.countComparisons(true)});
        Gauge.builder("decisionrail.shadow.comparisons.recorded", () -> comparisons.reading(values -> values[0]))
                .description("Recorded shadow comparisons")
                .register(registry);
        Gauge.builder("decisionrail.shadow.comparisons.diverged", () -> comparisons.reading(values -> values[1]))
                .description("Recorded shadow comparisons whose candidate outcome differed from the baseline")
                .register(registry);

        Cached<Map<String, Long>> jobs = new Cached<>("replay.jobs", FRESHNESS, clock, () -> jobCounts(jdbc));
        for (String status : JOB_STATUSES) {
            Gauge.builder("decisionrail.replay.jobs", () -> jobs.reading(current -> current.getOrDefault(status, 0L)))
                    .description("Replay jobs by status, at most " + FRESHNESS.toSeconds() + "s old")
                    .tag("status", status)
                    .register(registry);
        }
        // Replay progress as remaining work, which is what an operator watching a run needs.
        Cached<Long> remaining = new Cached<>("replay.items.remaining", FRESHNESS, clock,
                () -> countRemainingItems(jdbc));
        Gauge.builder("decisionrail.replay.items.remaining", () -> remaining.reading(Long::doubleValue))
                .description("Pinned replay inputs not yet evaluated across all unfinished jobs")
                .register(registry);
    }

    private static Map<String, Long> countsByKey(ShadowStore shadow, String[] states) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String state : states) counts.put(state, shadow.countTasks(state));
        return counts;
    }

    /** One grouped scan instead of one count per status. */
    private static Map<String, Long> jobCounts(JdbcTemplate jdbc) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : JOB_STATUSES) counts.put(status, 0L);
        jdbc.query("SELECT status, count(*) FROM replay_jobs GROUP BY status",
                rs -> { counts.put(rs.getString(1), rs.getLong(2)); });
        return counts;
    }

    private static long countRemainingItems(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM replay_job_items item
                JOIN replay_jobs job ON job.id = item.job_id
                WHERE item.state = 'PENDING' AND job.status IN ('PENDING', 'RUNNING')
                """, Long.class);
        return count == null ? 0 : count;
    }
}
