package com.decisionrail.shadow;

import java.util.UUID;

/** A queued shadow evaluation with its pinned inputs, baseline, and candidate. */
public record ShadowTask(
        UUID eventId,
        UUID paymentId,
        String merchantId,
        String candidateVersion,
        long amountMinor,
        String currency,
        String country,
        String baselineOutcome,
        int baselineScore,
        String baselineReasons,
        int attempts,
        UUID leaseToken,
        /**
         * The trace of the command whose event caused this task, read back from the row. Empty for
         * tasks enqueued before correlation existed, which evaluate normally under a trace of their own.
         */
        java.util.Optional<com.decisionrail.telemetry.OriginTrace> origin) {}
