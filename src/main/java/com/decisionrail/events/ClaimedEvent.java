package com.decisionrail.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An outbox row a worker currently owns.
 *
 * @param leaseToken the fencing token. Every completion statement requires it, so a worker
 *                   whose lease expired and was reclaimed by someone else cannot overwrite
 *                   the new owner's result.
 */
public record ClaimedEvent(
        UUID id,
        UUID aggregateId,
        long aggregateSequence,
        String merchantId,
        String eventType,
        int schemaVersion,
        String payload,
        String partitionKey,
        Instant occurredAt,
        int attempts,
        UUID leaseToken) {}
