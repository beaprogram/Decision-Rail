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

    public DecisionResult evaluate(DecisionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("decision input is required");
        }
        int score = 0;
        List<ReasonContribution> reasons = new ArrayList<>();
        Set<DecisionFlag> flags = EnumSet.noneOf(DecisionFlag.class);
        for (DecisionRule rule : ACTIVE_RULE_SET.rules()) {
            if (!evaluator.evaluateValidated(rule.expression(), input).matched()) {
                continue;
            }
            score += rule.scoreContribution();
            reasons.add(new ReasonContribution(rule.code(), rule.description(), rule.scoreContribution()));
            flags.add(rule.flag());
            if (rule.terminal()) {
                break;
            }
        }
        if (reasons.isEmpty()) {
            reasons.add(new ReasonContribution("NO_RISK_SIGNALS", "No synthetic demo risk rules matched.", 0));
        }
        return new DecisionResult(DecisionOutcome.fromScore(score), score, ACTIVE_RULE_SET.version(), reasons, flags);
    }
}
