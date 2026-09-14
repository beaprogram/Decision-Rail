package com.decisionrail.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Consumer-side persistence: deduplication records, the activity projection, and quarantine. */
@Repository
public class InboxStore {
    private final JdbcTemplate jdbc;

    public InboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What the deduplication insert observed. */
    public enum Claim { FIRST_DELIVERY, DUPLICATE, IDENTITY_CONFLICT }

    /**
     * Records the event identity for this consumer group.
     *
     * <p>A second delivery of the same id is a duplicate and must produce no further effect.
     * The same id carrying a different payload is not a duplicate at all: it means event
     * identity was reused for different content, which the consumer refuses rather than
     * silently applying either version.
     */
    public Claim claim(String group, EventEnvelope envelope, String fingerprint,
                       String topic, int partition, long offset) {
        int inserted = jdbc.update("""
                INSERT INTO consumed_events
                    (consumer_group, event_id, aggregate_id, merchant_id, aggregate_sequence,
                     event_type, payload_fingerprint, topic, kafka_partition, kafka_offset)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (consumer_group, event_id) DO NOTHING
                """, group, envelope.eventId(), envelope.aggregateId(), envelope.merchantId(),
                envelope.aggregateSequence(), envelope.eventType(), fingerprint, topic, partition, offset);
        if (inserted == 1) {
            return Claim.FIRST_DELIVERY;
        }
        String existing = jdbc.queryForObject(
                "SELECT payload_fingerprint FROM consumed_events WHERE consumer_group = ? AND event_id = ?",
                String.class, group, envelope.eventId());
        return fingerprint.equals(existing) ? Claim.DUPLICATE : Claim.IDENTITY_CONFLICT;
    }

    public boolean merchantExists(String merchantId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM merchants WHERE id = ?", Long.class, merchantId);
        return count != null && count > 0;
    }

    /**
     * Applies the event to the projection.
     *
     * @return true when the projection advanced. False means the event carried an older
     *         sequence than the projection already holds, so it was recorded as consumed but
     *         deliberately not applied: a read model must not move backwards.
     */
    public boolean applyToProjection(EventEnvelope envelope, Instant now) {
        EventEnvelope.Payment payment = envelope.payment();
        EventEnvelope.Return operation = envelope.returnOperation();
        return jdbc.update("""
                INSERT INTO payment_activity
                    (payment_id, merchant_id, account_id, amount_minor, currency, country, last_status,
                     last_event_type, last_sequence, risk_outcome, risk_score, policy_version, failure_code,
                     applied_event_count, first_event_at, last_event_at, updated_at,
                     captured_amount_minor, returned_amount_minor, last_return_at, return_event_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (payment_id) DO UPDATE SET
                    last_status = EXCLUDED.last_status,
                    last_event_type = EXCLUDED.last_event_type,
                    last_sequence = EXCLUDED.last_sequence,
                    risk_outcome = EXCLUDED.risk_outcome,
                    risk_score = EXCLUDED.risk_score,
                    policy_version = EXCLUDED.policy_version,
                    failure_code = EXCLUDED.failure_code,
                    applied_event_count = payment_activity.applied_event_count + 1,
                    last_event_at = EXCLUDED.last_event_at,
                    updated_at = EXCLUDED.updated_at,
                    -- Kept once stated. An event that does not state the captured amount - every event
                    -- written before returns existed, and every pre-capture lifecycle event - must not
                    -- erase one an earlier event did state.
                    captured_amount_minor = coalesce(EXCLUDED.captured_amount_minor, payment_activity.captured_amount_minor),
                    -- Returns only ever accumulate, and a lifecycle event states zero. GREATEST is what
                    -- stops such an event from reporting that returned money came back, which would be
                    -- a read model claiming money moved when nothing did.
                    returned_amount_minor = GREATEST(payment_activity.returned_amount_minor, EXCLUDED.returned_amount_minor),
                    last_return_at = coalesce(EXCLUDED.last_return_at, payment_activity.last_return_at),
                    return_event_count = payment_activity.return_event_count + EXCLUDED.return_event_count
                WHERE payment_activity.last_sequence < EXCLUDED.last_sequence
                """, payment.id(), envelope.merchantId(), payment.accountId(), payment.amountMinor(),
                payment.currency(), payment.country(), payment.status(), envelope.eventType(),
                envelope.aggregateSequence(), payment.decision().outcome(), payment.decision().score(),
                payment.decision().ruleSetVersion(), payment.failureCode(),
                Timestamp.from(payment.createdAt()), Timestamp.from(envelope.occurredAt()), Timestamp.from(now),
                payment.capturedAmountMinor(), payment.returnedAmountMinor(),
                operation == null ? null : Timestamp.from(operation.occurredAt()),
                operation == null ? 0 : 1) == 1;
    }

    public void quarantine(String group, String reason, UUID eventId, String topic, int partition, long offset, String detail) {
        jdbc.update("""
                INSERT INTO consumer_quarantine
                    (id, consumer_group, reason, event_id, topic, kafka_partition, kafka_offset, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (consumer_group, topic, kafka_partition, kafka_offset) DO NOTHING
                """, UUID.randomUUID(), group, reason, eventId, topic, partition, offset,
                detail == null ? reason : detail.length() > 500 ? detail.substring(0, 500) : detail);
    }

    /** Merchant-scoped projection read. Ownership is part of the query, not a later check. */
    public PaymentActivityView activity(String merchantId, UUID paymentId) {
        List<PaymentActivityView> rows = jdbc.query("""
                SELECT payment_id, account_id, amount_minor, currency, country, last_status, last_event_type,
                       last_sequence, risk_outcome, risk_score, policy_version, failure_code,
                       applied_event_count, first_event_at, last_event_at,
                       captured_amount_minor, returned_amount_minor, last_return_at, return_event_count
                FROM payment_activity WHERE merchant_id = ? AND payment_id = ?
                """, InboxStore::mapActivity, merchantId, paymentId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<PaymentActivityView> recentActivity(String merchantId, int limit) {
        return jdbc.query("""
                SELECT payment_id, account_id, amount_minor, currency, country, last_status, last_event_type,
                       last_sequence, risk_outcome, risk_score, policy_version, failure_code,
                       applied_event_count, first_event_at, last_event_at,
                       captured_amount_minor, returned_amount_minor, last_return_at, return_event_count
                FROM payment_activity WHERE merchant_id = ?
                ORDER BY last_event_at DESC, payment_id LIMIT ?
                """, InboxStore::mapActivity, merchantId, limit);
    }

    /**
     * Which consumer groups have recorded each of a payment's events, from their own deduplication
     * records.
     *
     * <p>A group absent from an event's list has not recorded that event. That is different from the
     * group having failed, and different again from the event not having been published, so the
     * timeline presents the three separately rather than inferring one from another.
     */
    public Map<UUID, List<ConsumptionRecordView>> consumptionsForPayment(UUID aggregateId, int limit) {
        Map<UUID, List<ConsumptionRecordView>> byEvent = new LinkedHashMap<>();
        jdbc.query("""
                SELECT event_id, consumer_group, consumed_at FROM consumed_events
                WHERE aggregate_id = ? ORDER BY aggregate_sequence, consumer_group LIMIT ?
                """, rs -> {
            byEvent.computeIfAbsent(rs.getObject(1, UUID.class), key -> new ArrayList<>())
                    .add(new ConsumptionRecordView(rs.getString(2), rs.getTimestamp(3).toInstant()));
        }, aggregateId, limit);
        return byEvent;
    }

    public long consumedCount(String group, UUID aggregateId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM consumed_events WHERE consumer_group = ? AND aggregate_id = ?",
                Long.class, group, aggregateId);
        return count == null ? 0 : count;
    }

    public long quarantineCount(String group) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM consumer_quarantine WHERE consumer_group = ?", Long.class, group);
        return count == null ? 0 : count;
    }

    private static PaymentActivityView mapActivity(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new PaymentActivityView(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4).trim(),
                rs.getString(5).trim(), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9),
                rs.getInt(10), rs.getString(11), rs.getString(12), rs.getInt(13),
                rs.getTimestamp(14).toInstant(), rs.getTimestamp(15).toInstant(),
                rs.getObject(16, Long.class), rs.getLong(17),
                rs.getTimestamp(18) == null ? null : rs.getTimestamp(18).toInstant(), rs.getInt(19));
    }
}
