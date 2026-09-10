package com.decisionrail.shadow;

import com.decisionrail.events.EventEnvelope;
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

/**
 * Shadow persistence.
 *
 * <p>Every statement here touches only shadow tables. There is no write to accounts, payments,
 * ledger journals, audit events, or the outbox anywhere in this class, which is what makes shadow
 * evaluation structurally incapable of producing a financial effect rather than merely careful
 * not to.
 */
@Repository
public class ShadowStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ShadowStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Settings(boolean enabled, String candidateVersion, String updatedBy, Instant updatedAt) {}

    public Settings settings() {
        return jdbc.queryForObject("SELECT enabled, candidate_version, updated_by, updated_at FROM shadow_settings WHERE id = 1",
                (rs, row) -> new Settings(rs.getBoolean(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()));
    }

    public void updateSettings(boolean enabled, String candidateVersion, String updatedBy, Instant now) {
        jdbc.update("""
                UPDATE shadow_settings SET enabled = ?, candidate_version = ?, updated_by = ?, updated_at = ?
                WHERE id = 1
                """, enabled, candidateVersion, updatedBy, Timestamp.from(now));
    }

    /**
     * Enqueues durable shadow work for a delivered authorization event.
     *
     * <p>Keyed on the event id, so a redelivered event produces no second task. Returns false when
     * the task already existed.
     */
    public boolean enqueue(EventEnvelope envelope, String candidateVersion) {
        EventEnvelope.Payment payment = envelope.payment();
        EventEnvelope.Decision decision = payment.decision();
        return jdbc.update("""
                INSERT INTO shadow_tasks (event_id, payment_id, merchant_id, aggregate_sequence, candidate_version,
                        amount_minor, currency, country, baseline_outcome, baseline_score,
                        baseline_policy_version, baseline_reasons)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (event_id) DO NOTHING
                """, envelope.eventId(), payment.id(), envelope.merchantId(), envelope.aggregateSequence(),
                candidateVersion, payment.amountMinor(), payment.currency(), payment.country(),
                decision.outcome(), decision.score(), decision.ruleSetVersion(), encode(decision.reasons())) == 1;
    }

    /** Claims a bounded batch of due tasks with a fencing lease. */
    public List<ShadowTask> claim(String owner, int limit, Instant now, Instant leaseExpiry) {
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                WITH claimable AS (
                    SELECT event_id FROM shadow_tasks
                    WHERE state = 'PENDING' AND next_attempt_at <= ?
                    ORDER BY next_attempt_at, enqueued_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE shadow_tasks target
                SET state = 'CLAIMED', attempts = target.attempts + 1, lease_owner = ?, lease_token = ?,
                    lease_expires_at = ?
                FROM claimable
                WHERE target.event_id = claimable.event_id
                RETURNING target.event_id, target.payment_id, target.merchant_id, target.candidate_version,
                          target.amount_minor, target.currency, target.country, target.baseline_outcome,
                          target.baseline_score, target.baseline_reasons::text, target.attempts, target.lease_token
                """, (rs, row) -> new ShadowTask(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6).trim(), rs.getString(7).trim(),
                        rs.getString(8), rs.getInt(9), rs.getString(10), rs.getInt(11), rs.getObject(12, UUID.class)),
                Timestamp.from(now), limit, owner, token, Timestamp.from(leaseExpiry));
    }

    /**
     * Records a comparison. The primary key on (candidate_version, payment_id) means a repeated
     * delivery or a payment retry produces one comparison, not several.
     *
     * @return true when this call inserted the comparison
     */
    public boolean recordComparison(ShadowTask task, String candidateOutcome, Integer candidateScore,
                                    Integer candidateRawScore, boolean capped, String candidateReasons,
                                    boolean diverged, long evaluationNanos, String errorCode) {
        return jdbc.update("""
                INSERT INTO shadow_comparisons (candidate_version, payment_id, merchant_id, source_event_id,
                        baseline_outcome, baseline_score, baseline_reasons, candidate_outcome, candidate_score,
                        candidate_raw_score, candidate_score_capped, candidate_reasons, diverged,
                        evaluation_nanos, error_code)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (candidate_version, payment_id) DO NOTHING
                """, task.candidateVersion(), task.paymentId(), task.merchantId(), task.eventId(),
                task.baselineOutcome(), task.baselineScore(), task.baselineReasons(), candidateOutcome,
                candidateScore, candidateRawScore, capped, candidateReasons, diverged, evaluationNanos, errorCode) == 1;
    }

    public boolean finishTask(UUID eventId, UUID leaseToken, String state, String error, Instant completedAt) {
        return jdbc.update("""
                UPDATE shadow_tasks SET state = ?, last_error = ?, completed_at = ?,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                WHERE event_id = ? AND lease_token = ? AND state = 'CLAIMED'
                """, state, truncate(error), Timestamp.from(completedAt), eventId, leaseToken) == 1;
    }

    public boolean scheduleRetry(UUID eventId, UUID leaseToken, Instant nextAttemptAt, String error) {
        return jdbc.update("""
                UPDATE shadow_tasks SET state = 'PENDING', next_attempt_at = ?, last_error = ?,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                WHERE event_id = ? AND lease_token = ? AND state = 'CLAIMED'
                """, Timestamp.from(nextAttemptAt), truncate(error), eventId, leaseToken) == 1;
    }

    /** Returns tasks abandoned by a dead worker to the pending pool. */
    public int reclaimExpiredLeases(Instant now) {
        return jdbc.update("""
                UPDATE shadow_tasks SET state = 'PENDING', next_attempt_at = ?, lease_owner = NULL,
                    lease_token = NULL, lease_expires_at = NULL, last_error = 'Reclaimed after lease expiry'
                WHERE state = 'CLAIMED' AND lease_expires_at < ?
                """, Timestamp.from(now), Timestamp.from(now));
    }

    public long countTasks(String state) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM shadow_tasks WHERE state = ?", Long.class, state);
        return count == null ? 0 : count;
    }

    public long countComparisons(boolean divergedOnly) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM shadow_comparisons WHERE (? = false OR diverged)", Long.class, divergedOnly);
        return count == null ? 0 : count;
    }

    /** Merchant-scoped read. Another merchant's comparison reads as absent. */
    public List<ShadowComparisonView> comparisons(String merchantId, UUID paymentId) {
        return jdbc.query(COMPARISON_SELECT + " WHERE merchant_id = ? AND payment_id = ? ORDER BY evaluated_at DESC",
                this::mapComparison, merchantId, paymentId);
    }

    public List<ShadowComparisonView> recentComparisons(String merchantId, boolean divergedOnly, int limit) {
        return jdbc.query(COMPARISON_SELECT + " WHERE merchant_id = ? AND (? = false OR diverged)"
                        + " ORDER BY evaluated_at DESC, payment_id LIMIT ?",
                this::mapComparison, merchantId, divergedOnly, limit);
    }

    private static final String COMPARISON_SELECT = """
            SELECT payment_id, candidate_version, baseline_outcome, baseline_score, baseline_reasons::text,
                   candidate_outcome, candidate_score, candidate_raw_score, candidate_score_capped,
                   candidate_reasons::text, diverged, evaluation_nanos, error_code, evaluated_at
            FROM shadow_comparisons""";

    private ShadowComparisonView mapComparison(ResultSet rs, int row) throws SQLException {
        return new ShadowComparisonView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4),
                readTree(rs.getString(5)), rs.getString(6), (Integer) rs.getObject(7), (Integer) rs.getObject(8),
                rs.getBoolean(9), readTree(rs.getString(10)), rs.getBoolean(11), rs.getLong(12), rs.getString(13),
                rs.getTimestamp(14).toInstant());
    }

    private JsonNode readTree(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            throw new IllegalStateException("Stored shadow explanation is not readable JSON", unreadable);
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Could not encode shadow baseline explanation", impossible);
        }
    }

    private static String truncate(String error) {
        if (error == null) return null;
        String single = error.replaceAll("\\s+", " ").strip();
        return single.length() <= 500 ? single : single.substring(0, 500);
    }
}
