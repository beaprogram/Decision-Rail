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
 *
 * @param returnOperation the return this event records, or null for a lifecycle event. A refund does
 *                        not change a payment's status, so two partial refunds produce two events
 *                        whose payment snapshots differ only in a total. This block is what names the
 *                        operation, so a consumer can tell one from the other without inferring it.
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
        Payment payment,
        Return returnOperation) {

    public static final int SUPPORTED_SCHEMA_VERSION = 1;
    public static final String PAYMENT_AGGREGATE = "payment";

    /**
     * Immutable snapshot of the payment as it existed when the event was recorded.
     *
     * @param capturedAmountMinor what the capture moved, or null when this payment was never
     *                            captured. Null also for every event written before checkpoint 9,
     *                            including captures: those events keep the exact bytes they were
     *                            published with, because their identity and the fingerprint consumers
     *                            deduplicate on depend on it. A consumer must read absent as "not
     *                            stated" rather than as zero.
     * @param returnedAmountMinor total returned as of this event. Zero on every lifecycle event, and
     *                            on every event written before checkpoint 9.
     */
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
            Instant updatedAt,
            Long capturedAmountMinor,
            long returnedAmountMinor) {}

    /**
     * The return operation an event records.
     *
     * @param sequenceNumber this return's position among the payment's returns, starting at 1. Not the
     *                       envelope's aggregate sequence, which counts every event.
     */
    public record Return(
            UUID id,
            String type,
            long amountMinor,
            String currency,
            String reason,
            int sequenceNumber,
            Instant occurredAt) {}

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
