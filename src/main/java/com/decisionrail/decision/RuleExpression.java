package com.decisionrail.decision;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A typed expression tree: no scripts, reflection, network calls, or arbitrary operators. */
public sealed interface RuleExpression permits RuleExpression.All, RuleExpression.Any,
        RuleExpression.AmountAtLeast, RuleExpression.AmountLessThan,
        RuleExpression.CurrencyIn, RuleExpression.CountryIn, RuleExpression.CountryOutside {

    record All(List<RuleExpression> children) implements RuleExpression {
        public All {
            children = checkedChildren(children);
        }
    }

    record Any(List<RuleExpression> children) implements RuleExpression {
        public Any {
            children = checkedChildren(children);
        }
    }

    record AmountAtLeast(long amountMinor) implements RuleExpression {
        public AmountAtLeast {
            checkedAmount(amountMinor);
        }
    }

    record AmountLessThan(long amountMinor) implements RuleExpression {
        public AmountLessThan {
            checkedAmount(amountMinor);
        }
    }

    record CurrencyIn(Set<String> currencies) implements RuleExpression {
        public CurrencyIn {
            currencies = checkedCodes(currencies, "currency", 3);
        }
    }

    record CountryIn(Set<String> countries) implements RuleExpression {
        public CountryIn {
            countries = checkedCodes(countries, "country", 2);
        }
    }

    record CountryOutside(Set<String> countries) implements RuleExpression {
        public CountryOutside {
            countries = checkedCodes(countries, "country", 2);
        }
    }

    private static List<RuleExpression> checkedChildren(List<RuleExpression> children) {
        if (children == null || children.isEmpty() || children.size() > RuleEvaluator.MAX_CHILDREN) {
            throw new IllegalArgumentException("All/Any must contain 1 to " + RuleEvaluator.MAX_CHILDREN + " children");
        }
        if (children.stream().anyMatch(child -> child == null)) {
            throw new IllegalArgumentException("expression children cannot be null");
        }
        return List.copyOf(children);
    }

    private static Set<String> checkedCodes(Set<String> values, String name, int length) {
        if (values == null || values.isEmpty() || values.size() > 32) {
            throw new IllegalArgumentException(name + " set must contain 1 to 32 values");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(DecisionInput.normalizeCode(value, name, length));
        }
        return Set.copyOf(normalized);
    }

    private static void checkedAmount(long amountMinor) {
        if (amountMinor < 0 || amountMinor > DecisionInput.MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("rule amount must be between 0 and " + DecisionInput.MAX_AMOUNT_MINOR);
        }
    }
}
