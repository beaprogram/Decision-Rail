package com.decisionrail.resilience;

import com.decisionrail.shadow.ShadowStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Queue-depth gauges for the asynchronous workloads this phase introduced.
 *
 * <p>Every label here is a bounded enumeration: a delivery status, a task state, a job status. None
 * of them carries a payment id, a merchant id, a policy version, or any other value that grows with
 * traffic, because an unbounded label set turns a metrics backend into an outage of its own.
 * Per-payment detail belongs in the operator APIs, which are queried deliberately rather than
 * scraped continuously.
 */
@Component
public class WorkloadMetrics {
    public WorkloadMetrics(JdbcTemplate jdbc, ShadowStore shadow, MeterRegistry registry) {
        for (String state : new String[]{"PENDING", "CLAIMED", "DONE", "FAILED"}) {
            Gauge.builder("decisionrail.shadow.tasks", () -> shadow.countTasks(state))
                    .description("Shadow evaluation tasks by state")
                    .tag("state", state)
                    .register(registry);
        }
        Gauge.builder("decisionrail.shadow.comparisons.recorded", () -> shadow.countComparisons(false))
                .description("Recorded shadow comparisons")
                .register(registry);
        Gauge.builder("decisionrail.shadow.comparisons.diverged", () -> shadow.countComparisons(true))
                .description("Recorded shadow comparisons whose candidate outcome differed from the baseline")
                .register(registry);

        for (String status : new String[]{"PENDING", "RUNNING", "COMPLETED", "FAILED"}) {
            Gauge.builder("decisionrail.replay.jobs", () -> countJobs(jdbc, status))
                    .description("Replay jobs by status")
                    .tag("status", status)
                    .register(registry);
        }
        // Replay progress as remaining work, which is what an operator watching a run needs.
        Gauge.builder("decisionrail.replay.items.remaining", () -> countRemainingItems(jdbc))
                .description("Pinned replay inputs not yet evaluated across all unfinished jobs")
                .register(registry);
    }

    private static long countJobs(JdbcTemplate jdbc, String status) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM replay_jobs WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
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
