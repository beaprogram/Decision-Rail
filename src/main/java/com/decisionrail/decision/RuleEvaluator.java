package com.decisionrail.decision;

import java.util.List;

/** Bounded interpreter with deterministic, left-to-right short circuit evaluation. */
public final class RuleEvaluator {
    public static final int MAX_DEPTH = 8;
    public static final int MAX_NODES = 128;
    public static final int MAX_CHILDREN = 16;

    public record Evaluation(boolean matched, int conditionsEvaluated) {}

    public Evaluation evaluate(RuleExpression expression, DecisionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("decision input is required");
        }
        validate(expression);
        return evaluateValidated(expression, input);
    }

    static void validate(RuleExpression expression) {
        if (expression == null) {
            throw new IllegalArgumentException("rule expression is required");
        }
        validateNode(expression, 1, new int[]{0});
    }

    private static void validateNode(RuleExpression expression, int depth, int[] count) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("rule expression exceeds maximum depth " + MAX_DEPTH);
        }
        if (++count[0] > MAX_NODES) {
            throw new IllegalArgumentException("rule expression exceeds maximum node count " + MAX_NODES);
        }
        List<RuleExpression> children = switch (expression) {
            case RuleExpression.All all -> all.children();
            case RuleExpression.Any any -> any.children();
            default -> List.of();
        };
        for (RuleExpression child : children) {
            validateNode(child, depth + 1, count);
        }
    }

    Evaluation evaluateValidated(RuleExpression expression, DecisionInput input) {
        return switch (expression) {
            case RuleExpression.All all -> evaluateChildren(all.children(), input, true);
            case RuleExpression.Any any -> evaluateChildren(any.children(), input, false);
            case RuleExpression.AmountAtLeast condition -> leaf(input.amountMinor() >= condition.amountMinor());
            case RuleExpression.AmountLessThan condition -> leaf(input.amountMinor() < condition.amountMinor());
            case RuleExpression.CurrencyIn condition -> leaf(condition.currencies().contains(input.currency()));
            case RuleExpression.CountryIn condition -> leaf(condition.countries().contains(input.country()));
            case RuleExpression.CountryOutside condition -> leaf(!condition.countries().contains(input.country()));
        };
    }

    private Evaluation evaluateChildren(List<RuleExpression> children, DecisionInput input, boolean all) {
        int visited = 0;
        for (RuleExpression child : children) {
            Evaluation result = evaluateValidated(child, input);
            visited += result.conditionsEvaluated();
            if (all && !result.matched()) {
                return new Evaluation(false, visited);
            }
            if (!all && result.matched()) {
                return new Evaluation(true, visited);
            }
        }
        return new Evaluation(all, visited);
    }

    private static Evaluation leaf(boolean matched) {
        return new Evaluation(matched, 1);
    }
}
