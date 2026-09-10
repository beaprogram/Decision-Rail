package com.decisionrail.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** What a pinned candidate would have decided for one authorization that already happened. */
public record ShadowComparisonView(
        UUID paymentId,
        String candidateVersion,
        String baselineOutcome,
        int baselineScore,
        JsonNode baselineReasons,
        String candidateOutcome,
        Integer candidateScore,
        Integer candidateRawScore,
        boolean candidateScoreCapped,
        JsonNode candidateReasons,
        boolean diverged,
        long evaluationNanos,
        String errorCode,
        Instant evaluatedAt) {}
