package com.decisionrail.decision;

/** Rule order is part of the versioned policy. Terminal rules stop further evaluation. */
public record DecisionRule(
        String code,
        String description,
        RuleExpression expression,
        int scoreContribution,
        DecisionFlag flag,
        boolean terminal) {
    public DecisionRule {
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("rule code must be an uppercase identifier of at most 64 characters");
        }
        if (description == null || description.isBlank() || description.length() > 512) {
            throw new IllegalArgumentException("rule description must contain 1 to 512 characters");
        }
        if (scoreContribution < 1 || scoreContribution > 100 || flag == null) {
            throw new IllegalArgumentException("rule requires a flag and a score contribution between 1 and 100");
        }
        RuleEvaluator.validate(expression);
    }
}
