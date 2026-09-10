package com.decisionrail.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All SQL for replay jobs, their membership snapshot, and their recorded results. */
@Repository
public class ReplayStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ReplayStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void createJob(UUID id, String merchantId, String candidateVersion, String candidateHash,
                          int requestedLimit, Instant windowFrom, Instant windowTo) {
        jdbc.update("""
                INSERT INTO replay_jobs (id, merchant_id, candidate_version, candidate_hash, status,
                                         requested_limit, input_count, window_from, window_to)
                VALUES (?, ?, ?, ?, 'PENDING', ?, 0, ?, ?)
                """, id, merchantId, candidateVersion, candidateHash, requestedLimit,
                windowFrom == null ? null : Timestamp.from(windowFrom), Timestamp.from(windowTo));
    }

    /**
     * Materialises the job's membership and input snapshot in one statement, inside the creating
     * transaction.
     *
     * <p>This is the whole reason job totals are stable. Membership is a fixed set of rows rather
     * than a timestamp cursor that each batch re-queries, so a payment that commits after the job
     * is created cannot join it and cannot change a denominator partway through a run. A payment
     * that was still uncommitted when this statement ran is simply not a member, which is a
     * documented property of the snapshot rather than a race.
     *
     * <p>The baseline copied here is {@code decision->>'outcome'}: the stored risk decision, not
     * the payment's lifecycle status.
     */
    public int materialiseMembership(UUID jobId, String merchantId, Instant windowFrom, Instant windowTo, int limit) {
        return jdbc.update("""
                INSERT INTO replay_job_items (job_id, payment_id, item_sequence, amount_minor, currency, country,
                        baseline_outcome, baseline_score, baseline_policy_version, baseline_reasons,
                        payment_status, payment_failure_code, payment_created_at)
                SELECT ?, chosen.id,
                       row_number() OVER (ORDER BY chosen.created_at, chosen.id),
                       chosen.amount_minor, chosen.currency, chosen.country,
                       chosen.decision ->> 'outcome',
                       (chosen.decision ->> 'score')::integer,
                       chosen.decision ->> 'ruleSetVersion',
                       chosen.decision -> 'reasons',
                       chosen.status, chosen.failure_code, chosen.created_at
                FROM (
                    SELECT p.id, p.created_at, p.amount_minor, p.currency, p.country, p.decision, p.status, p.failure_code
                    FROM payments p
                    WHERE p.merchant_id = ?
                      AND p.created_at < ?
                      AND (?::timestamptz IS NULL OR p.created_at >= ?::timestamptz)
                    ORDER BY p.created_at, p.id
                    LIMIT ?
                ) AS chosen
                """, jobId, merchantId, Timestamp.from(windowTo),
                windowFrom == null ? null : Timestamp.from(windowFrom),
                windowFrom == null ? null : Timestamp.from(windowFrom), limit);
    }

    public void setInputCount(UUID jobId, int inputCount) {
        jdbc.update("UPDATE replay_jobs SET input_count = ? WHERE id = ?", inputCount, jobId);
    }

    public void recordRequest(String merchantId, String key, String requestHash, UUID jobId) {
        jdbc.update("""
                INSERT INTO replay_job_requests (merchant_id, idempotency_key, request_hash, job_id)
                VALUES (?, ?, ?, ?)
                """, merchantId, key, requestHash, jobId);
    }

    /** Returns the previously created job for this retry, or null when the key is unused. */
    public ExistingRequest findRequest(String merchantId, String key) {
        List<ExistingRequest> rows = jdbc.query("""
                SELECT request_hash, job_id FROM replay_job_requests WHERE merchant_id = ? AND idempotency_key = ?
                """, (rs, row) -> new ExistingRequest(rs.getString(1), rs.getObject(2, UUID.class)), merchantId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public record ExistingRequest(String requestHash, UUID jobId) {}

    /**
     * Takes a lease on one runnable job. A job already leased by a live worker is skipped, and a
     * job whose worker died is picked up once its lease expires.
     */
    public ClaimedJob claimJob(String owner, Instant now, Instant leaseExpiry) {
        UUID token = UUID.randomUUID();
        List<ClaimedJob> claimed = jdbc.query("""
                WITH runnable AS (
                    SELECT id FROM replay_jobs
                    WHERE status IN ('PENDING', 'RUNNING')
                      AND (lease_expires_at IS NULL OR lease_expires_at < ?)
                    ORDER BY created_at, id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE replay_jobs target
                SET status = 'RUNNING', lease_owner = ?, lease_token = ?, lease_expires_at = ?,
                    started_at = coalesce(target.started_at, ?)
                FROM runnable
                WHERE target.id = runnable.id
                RETURNING target.id, target.merchant_id, target.candidate_version, target.lease_token
                """, (rs, row) -> new ClaimedJob(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, UUID.class)),
                Timestamp.from(now), owner, token, Timestamp.from(leaseExpiry), Timestamp.from(now));
        return claimed.isEmpty() ? null : claimed.getFirst();
    }

    public record ClaimedJob(UUID jobId, String merchantId, String candidateVersion, UUID leaseToken) {}

    /** Claims a bounded batch of not-yet-processed items. */
    public List<ReplayItem> claimItems(UUID jobId, int limit) {
        return jdbc.query("""
                SELECT payment_id, item_sequence, amount_minor, currency, country, baseline_outcome,
                       baseline_score, baseline_policy_version, baseline_reasons::text,
                       payment_status, payment_failure_code
                FROM replay_job_items
                WHERE job_id = ? AND state = 'PENDING'
                ORDER BY item_sequence
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (rs, row) -> new ReplayItem(rs.getObject(1, UUID.class), rs.getInt(2), rs.getLong(3),
                        rs.getString(4).trim(), rs.getString(5).trim(), rs.getString(6), rs.getInt(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)),
                jobId, limit);
    }

    /**
     * Records one comparison. {@code ON CONFLICT DO NOTHING} is what makes resuming safe: an item
     * that was evaluated before an interruption cannot produce a second row, so no total is
     * inflated by a restart.
     *
     * @return true when this call inserted the result
     */
    public boolean recordResult(UUID jobId, ReplayItem item, String candidateOutcome, Integer candidateScore,
                                Integer candidateRawScore, boolean capped, String candidateReasons,
                                boolean diverged, long evaluationNanos, String errorCode) {
        return jdbc.update("""
                INSERT INTO replay_results (job_id, payment_id, baseline_outcome, baseline_score, baseline_reasons,
                        candidate_outcome, candidate_score, candidate_raw_score, candidate_score_capped,
                        candidate_reasons, diverged, evaluation_nanos, error_code)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (job_id, payment_id) DO NOTHING
                """, jobId, item.paymentId(), item.baselineOutcome(), item.baselineScore(), item.baselineReasons(),
                candidateOutcome, candidateScore, candidateRawScore, capped, candidateReasons,
                diverged, evaluationNanos, errorCode) == 1;
    }

    public void markItem(UUID jobId, UUID paymentId, String state) {
        jdbc.update("UPDATE replay_job_items SET state = ? WHERE job_id = ? AND payment_id = ?", state, jobId, paymentId);
    }

    public int pendingItemCount(UUID jobId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM replay_job_items WHERE job_id = ? AND state = 'PENDING'", Integer.class, jobId);
        return count == null ? 0 : count;
    }

    /**
     * Recomputes every aggregate from the recorded results.
     *
     * <p>Derivation rather than accumulation is deliberate. An incremented counter would double
     * count whenever a batch is retried or a job resumes after a crash; recomputing from the
     * results table is idempotent, so running it any number of times yields the same totals.
     */
    public void refreshAggregates(UUID jobId, UUID leaseToken) {
        jdbc.update("""
                UPDATE replay_jobs target SET
                    completed_count = totals.completed,
                    failed_count = totals.failed,
                    skipped_count = greatest(0, target.input_count - totals.completed - totals.failed),
                    approve_count = totals.approve,
                    review_count = totals.review,
                    decline_count = totals.decline,
                    divergence_count = totals.diverged,
                    evaluation_nanos_total = totals.nanos
                FROM (
                    SELECT count(*) FILTER (WHERE error_code IS NULL) AS completed,
                           count(*) FILTER (WHERE error_code IS NOT NULL) AS failed,
                           count(*) FILTER (WHERE candidate_outcome = 'APPROVE') AS approve,
                           count(*) FILTER (WHERE candidate_outcome = 'REVIEW') AS review,
                           count(*) FILTER (WHERE candidate_outcome = 'DECLINE') AS decline,
                           count(*) FILTER (WHERE diverged) AS diverged,
                           coalesce(sum(evaluation_nanos), 0) AS nanos
                    FROM replay_results WHERE job_id = ?
                ) AS totals
                WHERE target.id = ? AND target.lease_token = ?
                """, jobId, jobId, leaseToken);
    }

    /** Fenced completion: a worker whose lease was taken over cannot close someone else's job. */
    public boolean completeJob(UUID jobId, UUID leaseToken, Instant completedAt) {
        return jdbc.update("""
                UPDATE replay_jobs SET status = 'COMPLETED', completed_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND lease_token = ? AND status = 'RUNNING'
                """, Timestamp.from(completedAt), jobId, leaseToken) == 1;
    }

    public void releaseLease(UUID jobId, UUID leaseToken) {
        jdbc.update("""
                UPDATE replay_jobs SET lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND lease_token = ?
                """, jobId, leaseToken);
    }

    public void failJob(UUID jobId, UUID leaseToken, String detail) {
        jdbc.update("""
                UPDATE replay_jobs SET status = 'FAILED', failure_detail = ?,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND lease_token = ?
                """, detail == null ? "unknown" : detail.length() > 500 ? detail.substring(0, 500) : detail, jobId, leaseToken);
    }

    /** Ownership is part of the query: another merchant's job reads as absent, not forbidden. */
    public ReplayJobView findJob(String merchantId, UUID jobId) {
        List<ReplayJobView> rows = jdbc.query(JOB_SELECT + " WHERE id = ? AND merchant_id = ?",
                ReplayStore::mapJob, jobId, merchantId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<ReplayJobView> listJobs(String merchantId, int limit) {
        return jdbc.query(JOB_SELECT + " WHERE merchant_id = ? ORDER BY created_at DESC, id LIMIT ?",
                ReplayStore::mapJob, merchantId, limit);
    }

    public List<ReplayResultView> results(UUID jobId, boolean divergedOnly, int limit, int offset) {
        return jdbc.query("""
                SELECT r.payment_id, r.baseline_outcome, r.baseline_score, r.baseline_reasons::text,
                       r.candidate_outcome, r.candidate_score, r.candidate_raw_score, r.candidate_score_capped,
                       r.candidate_reasons::text, r.diverged, r.evaluation_nanos, r.error_code,
                       i.payment_status, i.payment_failure_code
                FROM replay_results r
                JOIN replay_job_items i ON i.job_id = r.job_id AND i.payment_id = r.payment_id
                WHERE r.job_id = ? AND (? = false OR r.diverged)
                ORDER BY i.item_sequence LIMIT ? OFFSET ?
                """, this::mapResult, jobId, divergedOnly, limit, offset);
    }

    /** Reads one membership row so a report can name the baseline policy version. No locking. */
    public List<ReplayItem> sampleItem(UUID jobId) {
        return jdbc.query("""
                SELECT payment_id, item_sequence, amount_minor, currency, country, baseline_outcome,
                       baseline_score, baseline_policy_version, baseline_reasons::text,
                       payment_status, payment_failure_code
                FROM replay_job_items WHERE job_id = ? ORDER BY item_sequence LIMIT 1
                """, (rs, row) -> new ReplayItem(rs.getObject(1, UUID.class), rs.getInt(2), rs.getLong(3),
                        rs.getString(4).trim(), rs.getString(5).trim(), rs.getString(6), rs.getInt(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)), jobId);
    }

    public long baselineOutcomeCount(UUID jobId, String outcome) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM replay_job_items WHERE job_id = ? AND baseline_outcome = ?", Long.class, jobId, outcome);
        return count == null ? 0 : count;
    }

    private static final String JOB_SELECT = """
            SELECT id, merchant_id, candidate_version, candidate_hash, baseline_source, status, input_count,
                   completed_count, failed_count, approve_count, review_count, decline_count, divergence_count,
                   evaluation_nanos_total, window_from, window_to, created_at, started_at, completed_at, failure_detail
            FROM replay_jobs""";

    private static ReplayJobView mapJob(ResultSet rs, int row) throws SQLException {
        int inputCount = rs.getInt(7);
        int completed = rs.getInt(8);
        int failed = rs.getInt(9);
        return new ReplayJobView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), inputCount, completed, failed,
                Math.max(0, inputCount - completed - failed),
                rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getInt(13), rs.getLong(14),
                timestamp(rs, 15), timestamp(rs, 16), timestamp(rs, 17), timestamp(rs, 18), timestamp(rs, 19),
                rs.getString(20));
    }

    private ReplayResultView mapResult(ResultSet rs, int row) throws SQLException {
        return new ReplayResultView(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3),
                readTree(rs.getString(4)), rs.getString(5), (Integer) rs.getObject(6), (Integer) rs.getObject(7),
                rs.getBoolean(8), readTree(rs.getString(9)), rs.getBoolean(10), rs.getLong(11), rs.getString(12),
                rs.getString(13), rs.getString(14));
    }

    private JsonNode readTree(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new IllegalStateException("Stored replay explanation is not readable JSON", unreadable);
        }
    }

    private static Instant timestamp(ResultSet rs, int index) throws SQLException {
        Timestamp value = rs.getTimestamp(index);
        return value == null ? null : value.toInstant();
    }
}
