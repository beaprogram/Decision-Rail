package com.decisionrail.events;

import java.time.Instant;
import java.util.UUID;

/**
 * One outbox row's delivery state, as the events layer sees it.
 *
 * <p>Deliberately owned by this package rather than shaped to a caller's response model, so the event
 * stores stay free of any dependency on the payments layer that reads them.
 *
 * @param failureKind the most recent failure's exception type, without its message
 */
public record DeliveryRecordView(
        UUID eventId,
        long aggregateSequence,
        String eventType,
        Instant occurredAt,
        String status,
        Instant publishedAt,
        int attempts,
        Integer brokerPartition,
        Long brokerOffset,
        String failureKind) {}
