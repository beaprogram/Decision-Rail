package com.decisionrail.decision;

import java.util.List;
import java.util.Set;

/** Immutable, reproducible decision with contributions that sum exactly to score. */
public record DecisionResult(
        DecisionOutcome outcome,
        int score,
        String ruleSetVersion,
        List<ReasonContribution> reasons,
        Set<DecisionFlag> flags) {
    public DecisionResult {
        if (outcome == null || outcome != DecisionOutcome.fromScore(score)) {
            throw new IllegalArgumentException("outcome must agree with score thresholds");
        }
        if (ruleSetVersion == null || ruleSetVersion.isBlank()) {
            throw new IllegalArgumentException("ruleSetVersion is required");
        }
        if (reasons == null || reasons.isEmpty() || flags == null) {
            throw new IllegalArgumentException("reasons and flags are required");
        }
        reasons = List.copyOf(reasons);
        flags = Set.copyOf(flags);
        if (reasons.stream().mapToLong(ReasonContribution::scoreContribution).sum() != score) {
            throw new IllegalArgumentException("reason contributions must sum exactly to score");
        }
    }
}
