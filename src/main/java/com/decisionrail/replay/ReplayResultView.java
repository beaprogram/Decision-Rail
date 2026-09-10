package com.decisionrail.replay;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * A per-payment comparison between the stored baseline risk decision and the candidate policy.
 *
 * <p>{@code diverged} compares risk outcomes only. It is not a statement about what would have
 * happened to the payment: available funds, currency checks, and lifecycle state are not part of
 * a policy evaluation.
 */
public record ReplayResultView(
        UUID paymentId,
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
        String paymentStatus,
        String paymentFailureCode) {}
