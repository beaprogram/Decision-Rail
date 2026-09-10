package com.decisionrail.decision;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The result of evaluating an explicitly supplied policy snapshot.
 *
 * <p>This is deliberately a different type from {@link DecisionResult}. A stored decision is
 * authoritative evidence of what happened to a payment and its persisted shape must never
 * change, so it keeps its strict invariant that reason contributions sum exactly to the score.
 * A candidate policy under evaluation needs to express one thing a stored decision cannot:
 * what happens when rule contributions add up past the top of the scale.
 *
 * <h2>Score overflow</h2>
 * Contributions are each bounded to 1..100 and a policy may hold up to 32 rules, so a policy
 * can reach a raw total above 100. Rejecting such policies at creation time would require
 * proving which rule combinations are jointly reachable, which depends on terminal rules and
 * overlapping expressions and is not something a validator can decide honestly. Instead the
 * behaviour is explicit and recorded:
 * <ul>
 *   <li>{@code rawScore} is the exact sum of matched contributions, so reasons always reconcile;</li>
 *   <li>{@code score} is {@code min(100, rawScore)}, the value the outcome thresholds see;</li>
 *   <li>{@code scoreCapped} records that the cap applied, so a capped comparison is never
 *       mistaken for a genuine 100.</li>
 * </ul>
 * Capping rather than wrapping or failing keeps the outcome monotone: adding a rule that
 * matches can never make a decision less severe.
 *
 * <h2>Overlapping and terminal rules</h2>
 * Rules are evaluated in policy order and every matching rule contributes, so overlapping
 * rules accumulate rather than replacing one another. A matching terminal rule stops
 * evaluation immediately, so later rules contribute nothing. {@code rulesEvaluated} records
 * how far evaluation got, which makes a terminal short circuit visible in a comparison report.
 */
public record PolicyEvaluation(
        DecisionOutcome outcome,
        int score,
        int rawScore,
        boolean scoreCapped,
        String policyVersion,
        List<ReasonContribution> reasons,
        Set<DecisionFlag> flags,
        int rulesEvaluated,
        boolean terminated) {

    public static final int MAX_SCORE = 100;

    public PolicyEvaluation {
        if (rawScore < 0) {
            throw new IllegalArgumentException("rawScore cannot be negative");
        }
        int expectedScore = Math.min(MAX_SCORE, rawScore);
        if (score != expectedScore) {
            throw new IllegalArgumentException("score must be min(100, rawScore)");
        }
        if (scoreCapped != (rawScore > MAX_SCORE)) {
            throw new IllegalArgumentException("scoreCapped must agree with whether rawScore exceeded the maximum");
        }
        if (outcome == null || outcome != DecisionOutcome.fromScore(score)) {
            throw new IllegalArgumentException("outcome must agree with the capped score thresholds");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion is required");
        }
        if (reasons == null || reasons.isEmpty() || flags == null) {
            throw new IllegalArgumentException("reasons and flags are required");
        }
        if (rulesEvaluated < 0) {
            throw new IllegalArgumentException("rulesEvaluated cannot be negative");
        }
        reasons = List.copyOf(reasons);
        // Enum iteration order is stable across restarts, so serialized flags stay comparable.
        flags = Collections.unmodifiableSet(flags.isEmpty()
                ? EnumSet.noneOf(DecisionFlag.class)
                : EnumSet.copyOf(flags));
        if (reasons.stream().mapToLong(ReasonContribution::scoreContribution).sum() != rawScore) {
            throw new IllegalArgumentException("reason contributions must sum exactly to rawScore");
        }
    }

    /**
     * Converts to the authoritative stored decision shape.
     *
     * @throws IllegalStateException when the score was capped, because a stored decision
     *         requires its reasons to sum exactly to its score and a capped evaluation cannot
     *         satisfy that. The built-in policy never caps; this guards a future change to it.
     */
    public DecisionResult toDecisionResult() {
        if (scoreCapped) {
            throw new IllegalStateException("A capped evaluation cannot become an authoritative stored decision");
        }
        return new DecisionResult(outcome, score, policyVersion, reasons, flags);
    }
}
