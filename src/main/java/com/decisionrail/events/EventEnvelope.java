package com.decisionrail.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The published wire contract for a payment lifecycle event.
 *
 * <p>Identity, routing, and ordering information live in the envelope so a consumer can
 * deduplicate and order without understanding the payload body. {@code schemaVersion} is
 * the compatibility gate: a consumer that does not support a version must refuse the
 * event rather than guess. Unknown extra fields are tolerated by consumers so that a
 * future additive change does not break older readers.
 *
 * <p>{@code aggregateSequence} is a durable per-payment counter assigned inside the
 * committing payment transaction. It is the only ordering authority; broker timestamps,
 * random event ids, and partition assignment are not.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID aggregateId,
        String aggregateType,
        long aggregateSequence,
        String merchantId,
        Instant occurredAt,
        Instant recordedAt,
        Payment payment) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final String PAYMENT_AGGREGATE = "payment";

    /** Immutable snapshot of the payment as it existed when the event was recorded. */
    public record Payment(
            UUID id,
            UUID accountId,
            long amountMinor,
            String currency,
            String country,
            String status,
            Decision decision,
            String failureCode,
            Instant createdAt,
            Instant updatedAt) {}

    /**
     * The risk decision that was actually stored for this payment. It is independent of
     * {@link Payment#status()}: an APPROVE risk decision can still produce a DECLINED
     * payment when the account lacked available funds.
     */
    public record Decision(
            String outcome,
            int score,
            String ruleSetVersion,
            List<Reason> reasons,
            List<String> flags) {}

    public record Reason(String code, String description, int scoreContribution) {}
}
