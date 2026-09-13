package com.decisionrail.events;

import com.decisionrail.telemetry.OriginTrace;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * An outbox row a worker currently owns.
 *
 * @param leaseToken the fencing token. Every completion statement requires it, so a worker
 *                   whose lease expired and was reclaimed by someone else cannot overwrite
 *                   the new owner's result.
 * @param origin     the trace of the command that produced this event, read back from the row.
 *                   Empty for events written before correlation existed, and for any event produced
 *                   while tracing was off; both must still deliver normally.
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
        UUID leaseToken,
        Optional<OriginTrace> origin) {}
