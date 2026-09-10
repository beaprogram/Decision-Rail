package com.decisionrail.events;

import java.time.Instant;
import java.util.Map;

/**
 * Durable backlog and failure visibility for operators.
 *
 * @param countsByStatus      row count per delivery status
 * @param oldestPendingAt     when the oldest undelivered event was recorded, or null
 * @param oldestPendingAgeSeconds age of that event, the signal that matters during an outage
 * @param blockedPaymentCount payments whose stream is stalled behind a terminally failed event
 */
public record OutboxBacklog(
        Map<String, Long> countsByStatus,
        Instant oldestPendingAt,
        long oldestPendingAgeSeconds,
        long blockedPaymentCount,
        String breakerState) {}
