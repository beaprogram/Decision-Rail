package com.decisionrail.payments;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A payment's lifecycle as three separate kinds of durable evidence, never merged into one guess.
 *
 * <p>The activity projection holds only a payment's latest projected state, so it cannot answer what
 * happened in what order. This model is assembled from records that do: the audit trail written inside
 * each payment transaction, the outbox rows with their durable per-payment sequence, and the
 * per-consumer deduplication records.
 *
 * <p>Three distinctions are kept deliberately visible because collapsing them would misinform an
 * operator:
 * <ul>
 *   <li><b>Committed</b> is when the payment transaction wrote the event intent. It is the only stage
 *       that is certain for every listed event.</li>
 *   <li><b>Published</b> is a broker acknowledgement. It does not mean any consumer has processed the
 *       event, and nothing here implies that it does.</li>
 *   <li><b>Consumed</b> is per consumer group, from that group's own deduplication record. A group
 *       absent from an event's list has not recorded it, which is different from having failed.</li>
 * </ul>
 *
 * <p>Nothing is interpolated. An event that has not been published has no publication timestamp rather
 * than an invented one, and no transition is synthesised to fill a gap.
 */
public record PaymentTimelineView(
        UUID paymentId,
        UUID accountId,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt,
        List<CommandEntry> commands,
        List<EventEntry> events,
        ProjectionState projection) {

    /** One accepted payment command, from the audit record written inside its transaction. */
    public record CommandEntry(String action, Instant occurredAt) {}

    /**
     * One lifecycle event and its delivery state.
     *
     * @param sequence       durable per-payment sequence; this is what establishes order
     * @param lastFailureKind the kind of the most recent delivery failure, as an exception type only.
     *                       Deliberately not the stored message: a merchant-facing response should not
     *                       carry internal diagnostic text. Administrators see the full detail on the
     *                       failed-event list instead.
     */
    public record EventEntry(
            UUID eventId,
            long sequence,
            String eventType,
            Instant committedAt,
            String deliveryStatus,
            Instant publishedAt,
            int attempts,
            Integer brokerPartition,
            Long brokerOffset,
            String lastFailureKind,
            List<ConsumerEntry> consumers) {}

    /** A consumer group's own record that it processed the event. */
    public record ConsumerEntry(String consumerGroup, Instant consumedAt) {}

    /**
     * The projection's current state, or null when no event for this payment has been projected yet.
     * {@code lastSequence} shows how far the read model has advanced, which is not necessarily as far
     * as publication has.
     */
    public record ProjectionState(
            String lastStatus,
            String lastEventType,
            long lastSequence,
            int appliedEventCount,
            Instant firstEventAt,
            Instant lastEventAt) {}
}
