package com.decisionrail.decision;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Pure, thread-safe evaluator for an explicitly synthetic payment decision policy.
 * It is not a fraud model, regulatory screen, or authorization to move real money.
 */
public final class DecisionEngine {
    public static final String RULE_SET_VERSION = "demo-v1";
    public static final String HOME_COUNTRY = "CA";
    public static final long ELEVATED_AMOUNT_MINOR = 100_000;
    public static final long HIGH_AMOUNT_MINOR = 500_000;

    private static final DecisionRuleSet ACTIVE_RULE_SET = new DecisionRuleSet(RULE_SET_VERSION, List.of(
            new DecisionRule("TEST_COUNTRY_BLOCKED",
                    "Synthetic test country ZZ triggers a terminal demo decline.",
                    new RuleExpression.CountryIn(Set.of("ZZ")), 100,
                    DecisionFlag.TEST_COUNTRY_BLOCKED, true),
            new DecisionRule("HIGH_AMOUNT",
                    "Amount is at least 500000 currency minor units under the demo policy.",
                    new RuleExpression.AmountAtLeast(HIGH_AMOUNT_MINOR), 60,
                    DecisionFlag.HIGH_AMOUNT, false),
            new DecisionRule("ELEVATED_AMOUNT",
                    "Amount is at least 100000 and below 500000 currency minor units under the demo policy.",
                    new RuleExpression.All(List.of(
                            new RuleExpression.AmountAtLeast(ELEVATED_AMOUNT_MINOR),
                            new RuleExpression.AmountLessThan(HIGH_AMOUNT_MINOR))), 30,
                    DecisionFlag.ELEVATED_AMOUNT, false),
            new DecisionRule("CROSS_BORDER",
                    "Country differs from the demo home country CA; no currency conversion is applied.",
                    new RuleExpression.CountryOutside(Set.of(HOME_COUNTRY)), 20,
                    DecisionFlag.CROSS_BORDER, false)
    ));

    private final RuleEvaluator evaluator = new RuleEvaluator();

    public DecisionRuleSet activeRuleSet() {
        return ACTIVE_RULE_SET;
    }

    /**
     * Evaluates the authoritative built-in policy. This is the only path that produces a stored
     * decision, and its behaviour is unchanged: {@code demo-v1} rules, order, contributions, and
     * thresholds are exactly what they were.
     */
    public DecisionResult evaluate(DecisionInput input) {
        return evaluatePolicy(ACTIVE_RULE_SET, input).toDecisionResult();
    }

    /**
     * Evaluates an explicitly supplied immutable policy snapshot.
     *
     * <p>Pure and deterministic: the same snapshot and the same input always produce the same
     * evaluation, which is what makes replay and shadow comparison meaningful. There is no
     * clock, no database, no random source, and no I/O on this path.
     *
     * <p>Outcome thresholds are a platform-level property rather than something a candidate
     * policy sets. A candidate varies rules: their codes, expressions, contributions, flags,
     * terminal behaviour, and order. Letting a candidate also move the thresholds would make
     * two evaluations incomparable, because a divergence could come from either the rules or a
     * relabelled scale, and the report could not tell an operator which.
     */
    public PolicyEvaluation evaluatePolicy(DecisionRuleSet ruleSet, DecisionInput input) {
        if (ruleSet == null) {
            throw new IllegalArgumentException("rule set is required");
        }
        if (input == null) {
            throw new IllegalArgumentException("decision input is required");
        }
        int rawScore = 0;
        int rulesEvaluated = 0;
        boolean terminated = false;
        List<ReasonContribution> reasons = new ArrayList<>();
        Set<DecisionFlag> flags = EnumSet.noneOf(DecisionFlag.class);
        for (DecisionRule rule : ruleSet.rules()) {
            rulesEvaluated++;
            if (!evaluator.evaluateValidated(rule.expression(), input).matched()) {
                continue;
            }
            // Every match contributes: overlapping rules accumulate rather than override.
            rawScore += rule.scoreContribution();
            reasons.add(new ReasonContribution(rule.code(), rule.description(), rule.scoreContribution()));
            flags.add(rule.flag());
            if (rule.terminal()) {
                terminated = true;
                break;
            }
        }
        if (reasons.isEmpty()) {
            reasons.add(new ReasonContribution("NO_RISK_SIGNALS", "No synthetic demo risk rules matched.", 0));
        }
        int score = Math.min(PolicyEvaluation.MAX_SCORE, rawScore);
        return new PolicyEvaluation(DecisionOutcome.fromScore(score), score, rawScore,
                rawScore > PolicyEvaluation.MAX_SCORE, ruleSet.version(), reasons, flags, rulesEvaluated, terminated);
    }
}
