package com.decisionrail.events;

import com.decisionrail.telemetry.OriginTrace;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * All SQL for the transactional outbox: appending intent inside the payment transaction,
 * claiming work with a fencing lease, and recording delivery outcomes.
 *
 * <p><b>Ordering.</b> {@link #claim} only offers an event when it is the lowest unpublished
 * sequence for its payment. Because a claimed row is not published, this also guarantees at
 * most one in-flight event per payment, so a later lifecycle event cannot overtake an
 * unfinished earlier one. A terminally FAILED event is likewise unpublished, which is why it
 * blocks exactly one payment's stream while every other payment keeps draining.
 *
 * <p><b>Fencing.</b> Completion statements require the lease token the claim returned. A
 * worker that stalled past its lease, and whose row was reclaimed by another worker, updates
 * zero rows instead of overwriting the new owner's state.
 */
@Repository
public class OutboxStore {
    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Assigns the next per-payment sequence. Callers run inside the payment transaction
     * while holding the payment row (newly inserted for an authorization, locked FOR UPDATE
     * for capture and void), so no concurrent writer can assign the same number. The unique
     * constraint on (aggregate_id, aggregate_sequence) is the backstop if that ever changes.
     */
    public long nextSequence(UUID aggregateId) {
        Long next = jdbc.queryForObject(
                "SELECT coalesce(max(aggregate_sequence), 0) + 1 FROM outbox_events WHERE aggregate_id = ?",
                Long.class, aggregateId);
        return next == null ? 1L : next;
    }

    /**
     * Appends committed intent. Runs inside the caller's payment transaction.
     *
     * <p>The origin trace is written here, in that same transaction, which is the only place it can be
     * captured usefully: after this returns, the thread that knew the trace is gone, and delivery may
     * not happen until after a restart. The payload is untouched, so event identity and the
     * fingerprint consumers deduplicate on are exactly what they were.
     */
    public void append(UUID eventId, UUID aggregateId, long sequence, String merchantId,
                       String eventType, int schemaVersion, String payload, Instant occurredAt,
                       Optional<OriginTrace> origin) {
        jdbc.update("""
                INSERT INTO outbox_events
                    (id, aggregate_id, aggregate_sequence, aggregate_type, merchant_id, event_type,
                     schema_version, payload, occurred_at, status, attempts, next_attempt_at, partition_key,
                     origin_trace_id, origin_span_id, origin_trace_sampled)
                VALUES (?, ?, ?, 'payment', ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?, ?, ?, ?)
                """, eventId, aggregateId, sequence, merchantId, eventType, schemaVersion, payload,
                Timestamp.from(occurredAt), Timestamp.from(occurredAt), aggregateId.toString(),
                origin.map(OriginTrace::traceId).orElse(null), origin.map(OriginTrace::spanId).orElse(null),
                origin.map(OriginTrace::sampled).orElse(null));
    }

    /**
     * Claims up to {@code limit} events that are due and unblocked, taking a lease on each.
     * Uses SKIP LOCKED so concurrent workers never queue behind each other. The caller must
     * commit this claim before contacting the broker: the attempt count and lease have to be
     * durable, and no database lock may be held across a network call.
     */
    public List<ClaimedEvent> claim(String owner, int limit, Instant now, Instant leaseExpiry) {
        UUID leaseToken = UUID.randomUUID();
        return jdbc.query("""
                WITH claimable AS (
                    SELECT candidate.id
                    FROM outbox_events candidate
                    WHERE candidate.status = 'PENDING'
                      AND candidate.next_attempt_at <= ?
                      AND candidate.aggregate_sequence = (
                            SELECT min(earlier.aggregate_sequence)
                            FROM outbox_events earlier
                            WHERE earlier.aggregate_id = candidate.aggregate_id
                              AND earlier.status <> 'PUBLISHED')
                    ORDER BY candidate.next_attempt_at, candidate.aggregate_sequence, candidate.id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE outbox_events target
                SET status = 'CLAIMED',
                    attempts = target.attempts + 1,
                    last_attempt_at = ?,
                    lease_owner = ?,
                    lease_token = ?,
                    lease_expires_at = ?
                FROM claimable
                WHERE target.id = claimable.id
                RETURNING target.id, target.aggregate_id, target.aggregate_sequence, target.merchant_id,
                          target.event_type, target.schema_version, target.payload, target.partition_key,
                          target.occurred_at, target.attempts, target.lease_token,
                          target.origin_trace_id, target.origin_span_id, target.origin_trace_sampled
                """,
                (rs, row) -> new ClaimedEvent(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                        rs.getTimestamp(9).toInstant(), rs.getInt(10), rs.getObject(11, UUID.class),
                        OriginTrace.ofStored(rs.getString(12), rs.getString(13),
                                rs.getObject(14, Boolean.class))),
                Timestamp.from(now), limit, Timestamp.from(now), owner, leaseToken, Timestamp.from(leaseExpiry));
    }

    /**
     * Records broker acknowledgement. Returns false when the lease was lost, in which case
     * the caller must not treat the send as recorded: another worker owns the row now and a
     * duplicate delivery is expected. Consumers deduplicate by event id.
     */
    public boolean markPublished(UUID id, UUID leaseToken, Instant publishedAt, Integer partition, Long offset) {
        return jdbc.update("""
                UPDATE outbox_events
                SET status = 'PUBLISHED', published_at = ?, broker_partition = ?, broker_offset = ?,
                    last_error = NULL, lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND lease_token = ? AND status = 'CLAIMED'
                """, Timestamp.from(publishedAt), partition, offset, id, leaseToken) == 1;
    }

    /** Returns the event to PENDING with a new due time. Fenced by lease token. */
    public boolean scheduleRetry(UUID id, UUID leaseToken, Instant nextAttemptAt, String error) {
        return jdbc.update("""
                UPDATE outbox_events
                SET status = 'PENDING', next_attempt_at = ?, last_error = ?,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND lease_token = ? AND status = 'CLAIMED'
                """, Timestamp.from(nextAttemptAt), truncate(error), id, leaseToken) == 1;
    }

    /**
     * Reschedules an event whose send the breaker refused to attempt, returning the attempt the
     * claim optimistically counted. The broker was never contacted, so this must not erode the
     * retry budget: otherwise a short protective window would terminally fail a whole backlog
     * of events that never had a delivery attempt at all.
     */
    public boolean scheduleRetryAfterShortCircuit(UUID id, UUID leaseToken, Instant nextAttemptAt, String error) {
        return jdbc.update("""
                UPDATE outbox_events
                SET status = 'PENDING', next_attempt_at = ?, last_error = ?,
                    attempts = greatest(0, attempts - 1),
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND lease_token = ? AND status = 'CLAIMED'
                """, Timestamp.from(nextAttemptAt), truncate(error), id, leaseToken) == 1;
    }

    /** Marks the retry budget exhausted. The event keeps its identity and payload. */
    public boolean markFailed(UUID id, UUID leaseToken, String error) {
        return jdbc.update("""
                UPDATE outbox_events
                SET status = 'FAILED', last_error = ?,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND lease_token = ? AND status = 'CLAIMED'
                """, truncate(error), id, leaseToken) == 1;
    }

    /**
     * Returns rows whose lease expired to the pending pool so a crashed worker's work is
     * recovered. The reclaim does not reset attempts: a worker that died mid-send may well
     * have reached the broker, and that attempt really happened.
     */
    public int reclaimExpiredLeases(Instant now) {
        return jdbc.update("""
                UPDATE outbox_events
                SET status = 'PENDING', next_attempt_at = ?, lease_owner = NULL, lease_token = NULL,
                    lease_expires_at = NULL, last_error = 'Reclaimed after lease expiry'
                WHERE status = 'CLAIMED' AND lease_expires_at < ?
                """, Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * Operator redrive of terminally failed events. Identity and payload are preserved; only
     * the attempt budget and schedule are reset. Redrive never skips an earlier event: the
     * ordering predicate in {@link #claim} still requires the lowest unpublished sequence, so
     * a later event stays blocked until its predecessor is redriven too.
     */
    public List<UUID> redrive(String merchantId, UUID aggregateId, List<UUID> eventIds, Instant now, int limit) {
        List<Object> arguments = new ArrayList<>();
        StringBuilder filter = new StringBuilder("status = 'FAILED'");
        if (merchantId != null) {
            filter.append(" AND merchant_id = ?");
            arguments.add(merchantId);
        }
        if (aggregateId != null) {
            filter.append(" AND aggregate_id = ?");
            arguments.add(aggregateId);
        }
        if (eventIds != null && !eventIds.isEmpty()) {
            filter.append(" AND id = ANY (?::uuid[])");
            arguments.add("{" + String.join(",", eventIds.stream().map(UUID::toString).toList()) + "}");
        }
        // The bound selection and the filter live in the same subquery so the limit always
        // applies to rows that actually match, never to a wider set that is filtered later.
        arguments.add(limit);
        arguments.add(Timestamp.from(now));
        String sql = """
                WITH chosen AS (
                    SELECT id FROM outbox_events
                    WHERE %s
                    ORDER BY aggregate_id, aggregate_sequence
                    LIMIT ?
                )
                UPDATE outbox_events target
                SET status = 'PENDING', attempts = 0, next_attempt_at = ?, last_error = NULL,
                    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL
                FROM chosen
                WHERE target.id = chosen.id
                RETURNING target.id
                """.formatted(filter);
        return jdbc.query(sql, (rs, row) -> rs.getObject(1, UUID.class), arguments.toArray());
    }

    /** Payments whose delivery stream is stalled behind a terminally failed event. */
    public List<UUID> blockedAggregates(int limit) {
        return jdbc.query("""
                SELECT DISTINCT aggregate_id FROM outbox_events WHERE status = 'FAILED'
                ORDER BY aggregate_id LIMIT ?
                """, (rs, row) -> rs.getObject(1, UUID.class), limit);
    }

    public OutboxBacklog backlog(Instant now, String breakerState) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of("PENDING", "CLAIMED", "PUBLISHED", "FAILED")) {
            counts.put(status, 0L);
        }
        jdbc.query("SELECT status, count(*) FROM outbox_events GROUP BY status",
                rs -> { counts.put(rs.getString(1), rs.getLong(2)); });
        Timestamp oldest = jdbc.queryForObject(
                "SELECT min(occurred_at) FROM outbox_events WHERE status IN ('PENDING', 'CLAIMED')", Timestamp.class);
        Long blocked = jdbc.queryForObject(
                "SELECT count(DISTINCT aggregate_id) FROM outbox_events WHERE status = 'FAILED'", Long.class);
        Instant oldestAt = oldest == null ? null : oldest.toInstant();
        long ageSeconds = oldestAt == null ? 0 : Math.max(0, now.getEpochSecond() - oldestAt.getEpochSecond());
        return new OutboxBacklog(Map.copyOf(counts), oldestAt, ageSeconds, blocked == null ? 0 : blocked, breakerState);
    }

    /**
     * One payment's events in lifecycle order, with their delivery state.
     *
     * <p>Ordered by the durable per-payment sequence, which is the only thing that establishes order.
     * The failure detail is reduced to its exception type: the full stored message is administrative
     * diagnostic text and does not belong in a merchant-facing response.
     */
    public List<DeliveryRecordView> eventsForPayment(UUID aggregateId, int limit) {
        return jdbc.query("""
                SELECT id, aggregate_sequence, event_type, occurred_at, status, published_at, attempts,
                       broker_partition, broker_offset, last_error
                FROM outbox_events WHERE aggregate_id = ?
                ORDER BY aggregate_sequence LIMIT ?
                """, (rs, n) -> new DeliveryRecordView(
                        rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getString(5),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                        rs.getInt(7), (Integer) rs.getObject(8), (Long) rs.getObject(9),
                        failureKind(rs.getString(10))),
                aggregateId, limit);
    }

    /**
     * Terminally failed events, oldest first, for administrative inspection.
     *
     * <p>Oldest first because the oldest terminal failure is the one blocking its payment's stream.
     * {@code blocksLaterEvents} reports whether that payment has further unpublished events queued
     * behind this one, which is what tells an operator that redriving this event unblocks a stream
     * rather than just clearing one row.
     */
    public List<FailedEventView> failedEvents(int limit, int offset) {
        return jdbc.query("""
                SELECT failed.id, failed.aggregate_id, failed.merchant_id, failed.event_type,
                       failed.aggregate_sequence, failed.attempts, failed.occurred_at,
                       failed.last_attempt_at, failed.last_error,
                       EXISTS (SELECT 1 FROM outbox_events later
                               WHERE later.aggregate_id = failed.aggregate_id
                                 AND later.aggregate_sequence > failed.aggregate_sequence
                                 AND later.status <> 'PUBLISHED') AS blocks_later
                FROM outbox_events failed
                WHERE failed.status = 'FAILED'
                ORDER BY failed.occurred_at, failed.id
                LIMIT ? OFFSET ?
                """, (rs, n) -> new FailedEventView(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                        rs.getLong(5), rs.getInt(6), rs.getTimestamp(7).toInstant(),
                        rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant(),
                        rs.getString(9), rs.getBoolean(10)),
                limit, offset);
    }

    /** The exception type from a stored "Type: message" failure, without the message. */
    private static String failureKind(String lastError) {
        if (lastError == null || lastError.isBlank()) return null;
        int separator = lastError.indexOf(':');
        String kind = separator < 0 ? lastError : lastError.substring(0, separator);
        return kind.length() <= 64 ? kind.strip() : kind.substring(0, 64).strip();
    }

    public long countByStatus(String status) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }

    /** Age in seconds of the oldest event that has not been delivered yet. */
    public long oldestUndeliveredAgeSeconds(Instant now) {
        Timestamp oldest = jdbc.queryForObject(
                "SELECT min(occurred_at) FROM outbox_events WHERE status IN ('PENDING', 'CLAIMED')", Timestamp.class);
        return oldest == null ? 0 : Math.max(0, now.getEpochSecond() - oldest.toInstant().getEpochSecond());
    }

    private static String truncate(String error) {
        if (error == null) return null;
        String single = error.replaceAll("\\s+", " ").strip();
        return single.length() <= 500 ? single : single.substring(0, 500);
    }
}
