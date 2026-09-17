package com.decisionrail.publicdemo;

import com.decisionrail.payments.PaymentException;
import com.decisionrail.replay.ReplayAdmissionPolicy;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The visitor's replay limits, decided inside the job-creation transaction.
 *
 * <p>Two limits, both here so both are atomic with the creation they govern:
 * <ul>
 *   <li><b>In flight</b> - at most {@code maxRunningReplayJobs} jobs PENDING or RUNNING. Counting
 *       before creating is a race unless the counters are serialised, so the visitor's merchant row
 *       is locked first: concurrent creators queue on that lock, and each sees the jobs the previous
 *       one committed. A refused creator holds nothing and rolls back nothing.</li>
 *   <li><b>Per hour</b> - at most {@code replayJobsPerHour} jobs created in the last hour, counted
 *       from the rows rather than an in-memory bucket, so a refusal never costs an allowance and a
 *       restart never forgets one.</li>
 * </ul>
 * They are separate controls with separate messages: the first bounds what runs at once, the second
 * how much history the visitor can generate. Nobody but the visitor is admitted through this policy;
 * everyone else is admitted unconditionally.
 *
 * <p>The lock is on {@code merchants}, a row no financial transaction locks, so no lock order is
 * introduced against the payment-then-account order the rest of the system keeps.
 */
public final class VisitorReplayAdmission implements ReplayAdmissionPolicy {
    private final PublicDemoProperties properties;
    private final JdbcTemplate jdbc;

    public VisitorReplayAdmission(PublicDemoProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    @Override
    public void admit(String merchantId) {
        if (!properties.isVisitor(merchantId)) return;
        // Serialises every visitor creation for the rest of this transaction.
        jdbc.queryForList("SELECT id FROM merchants WHERE id = ? FOR UPDATE", merchantId);

        Integer inFlight = jdbc.queryForObject(
                "SELECT count(*) FROM replay_jobs WHERE merchant_id = ? AND status IN ('PENDING', 'RUNNING')",
                Integer.class, merchantId);
        if (inFlight != null && inFlight >= properties.maxRunningReplayJobs()) {
            throw new PaymentException("DEMO_CAPACITY_EXHAUSTED", 429,
                    "The demo visitor already has a replay job running. Wait for it to finish, then start another.");
        }
        // created_at is stamped by the database, so the hour is measured on the database clock too;
        // the application clock is not consulted, and a skew between the two cannot open or close
        // the allowance.
        Integer lastHour = jdbc.queryForObject(
                "SELECT count(*) FROM replay_jobs WHERE merchant_id = ? AND created_at > now() - interval '1 hour'",
                Integer.class, merchantId);
        if (lastHour != null && lastHour >= properties.replayJobsPerHour()) {
            throw new PaymentException("DEMO_CAPACITY_EXHAUSTED", 429,
                    "The demo visitor has started its hourly allowance of " + properties.replayJobsPerHour()
                            + " replay jobs. Existing jobs and their reports stay readable.");
        }
    }
}
