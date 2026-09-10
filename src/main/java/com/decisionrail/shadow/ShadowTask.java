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
        UUID leaseToken) {}
