package com.decisionrail.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A terminally failed outbox event, for administrative inspection before a redrive.
 *
 * <p>Administrative rather than merchant-facing, which is why it carries {@code lastError} in full.
 * The stored value is bounded to 500 characters by the schema and is what an operator needs to decide
 * whether a redrive will help or repeat the same failure.
 *
 * @param blocksLaterEvents true when this payment has further unpublished events queued behind this
 *                          one. Redriving only a later event cannot help while an earlier one is still
 *                          failed, so this is the signal that the whole payment's stream is stalled.
 */
public record FailedEventView(
        UUID eventId,
        UUID paymentId,
        String merchantId,
        String eventType,
        long aggregateSequence,
        int attempts,
        Instant occurredAt,
        Instant lastAttemptAt,
        String lastError,
        boolean blocksLaterEvents) {}
