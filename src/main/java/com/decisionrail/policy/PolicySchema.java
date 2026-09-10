package com.decisionrail.policy;

import com.decisionrail.decision.DecisionFlag;
import com.decisionrail.decision.DecisionRule;
import com.decisionrail.decision.DecisionRuleSet;
import com.decisionrail.decision.RuleEvaluator;
import com.decisionrail.decision.RuleExpression;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The candidate policy document format, and the only way JSON becomes an evaluable policy.
 *
 * <h2>Document shape</h2>
 * <pre>
 * {
 *   "rules": [
 *     {
 *       "code": "HIGH_AMOUNT",              uppercase identifier, unique within the policy
 *       "description": "...",               1..512 characters, shown in explanations
 *       "scoreContribution": 40,            1..100, added when the rule matches
 *       "flag": "HIGH_AMOUNT",              one of the platform decision flags
 *       "terminal": false,                  true stops evaluation at this rule
 *       "expression": { "operator": "...", ... }
 *     }
 *   ]
 * }
 * </pre>
 *
 * <h2>Expression operators</h2>
 * <ul>
 *   <li>{@code ALL} / {@code ANY} with {@code children}: 1..16 sub-expressions, evaluated
 *       left to right with short circuit.</li>
 *   <li>{@code AMOUNT_AT_LEAST} / {@code AMOUNT_LESS_THAN} with {@code amountMinor}: integer
 *       currency minor units, 0..1000000000000.</li>
 *   <li>{@code CURRENCY_IN} with {@code currencies}: 1..32 supported currency codes.</li>
 *   <li>{@code COUNTRY_IN} / {@code COUNTRY_OUTSIDE} with {@code countries}: 1..32 two-letter
 *       country codes.</li>
 * </ul>
 *
 * <h2>What a policy cannot contain</h2>
 * There is no expression, script, template, callback, or URL form. The operator set above is
 * closed and maps onto a sealed Java type, so a policy document cannot introduce new
 * behaviour, reach the network, read the filesystem, or cause reflection. An unknown operator
 * is a validation error rather than a no-op, so a typo cannot silently disable a rule.
 *
 * <p>Depth, node count, child count, and rule count are bounded by the evaluator's own limits,
 * which makes evaluation cost bounded by the document rather than by the input.
 *
 * <p>Decision flags are a platform vocabulary, not something a policy defines. A candidate
 * selects from the existing flags so that a baseline and a candidate evaluation remain
 * comparable on the same axes.
 */
public final class PolicySchema {
    public static final Set<String> OPERATORS = Set.of(
            "ALL", "ANY", "AMOUNT_AT_LEAST", "AMOUNT_LESS_THAN", "CURRENCY_IN", "COUNTRY_IN", "COUNTRY_OUTSIDE");
    private static final Set<String> RULE_FIELDS = Set.of(
            "code", "description", "scoreContribution", "flag", "terminal", "expression");

    private PolicySchema() {}

    /** Translates a validated document into the immutable snapshot the pure engine evaluates. */
    public static DecisionRuleSet toRuleSet(String versionId, JsonNode document) {
        if (document == null || !document.isObject()) {
            throw new PolicyValidationException("$", "policy definition must be a JSON object");
        }
        rejectUnknownFields("$", document, Set.of("rules", "version"));
        JsonNode rules = document.get("rules");
        if (rules == null || !rules.isArray()) {
            throw new PolicyValidationException("$.rules", "rules must be an array");
        }
        if (rules.isEmpty() || rules.size() > DecisionRuleSet.MAX_RULES) {
            throw new PolicyValidationException("$.rules",
                    "a policy must contain 1 to " + DecisionRuleSet.MAX_RULES + " rules but had " + rules.size());
        }
        List<DecisionRule> translated = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int index = 0; index < rules.size(); index++) {
            String path = "$.rules[" + index + "]";
            JsonNode rule = rules.get(index);
            if (!rule.isObject()) {
                throw new PolicyValidationException(path, "each rule must be a JSON object");
            }
            rejectUnknownFields(path, rule, RULE_FIELDS);
            String code = requiredText(path + ".code", rule, "code").toUpperCase(Locale.ROOT);
            if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new PolicyValidationException(path + ".code",
                        "rule code must be an uppercase identifier of at most 64 characters");
            }
            if (!codes.add(code)) {
                // Duplicate identifiers would make an explanation ambiguous about which rule fired.
                throw new PolicyValidationException(path + ".code", "duplicate rule code " + code);
            }
            String description = requiredText(path + ".description", rule, "description");
            JsonNode score = rule.get("scoreContribution");
            if (score == null || !score.isInt()) {
                throw new PolicyValidationException(path + ".scoreContribution",
                        "scoreContribution must be an integer");
            }
            if (score.asInt() < 1 || score.asInt() > 100) {
                throw new PolicyValidationException(path + ".scoreContribution",
                        "scoreContribution must be between 1 and 100 but was " + score.asInt());
            }
            DecisionFlag flag = parseFlag(path + ".flag", requiredText(path + ".flag", rule, "flag"));
            JsonNode terminal = rule.get("terminal");
            if (terminal == null || !terminal.isBoolean()) {
                throw new PolicyValidationException(path + ".terminal", "terminal must be a boolean");
            }
            RuleExpression expression = parseExpression(path + ".expression", rule.get("expression"));
            translated.add(new DecisionRule(code, description, expression, score.asInt(), flag, terminal.asBoolean()));
        }
        // DecisionRuleSet re-checks bounds and uniqueness, so the evaluable snapshot is never
        // trusted to be valid merely because it came through this translator.
        return new DecisionRuleSet(versionId, translated);
    }

    private static RuleExpression parseExpression(String path, JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new PolicyValidationException(path, "expression must be a JSON object");
        }
        String operator = requiredText(path + ".operator", node, "operator").toUpperCase(Locale.ROOT);
        if (!OPERATORS.contains(operator)) {
            throw new PolicyValidationException(path + ".operator",
                    "unsupported operator " + operator + "; supported operators are " + OPERATORS.stream().sorted().toList());
        }
        switch (operator) {
            case "ALL", "ANY" -> {
                rejectUnknownFields(path, node, Set.of("operator", "children"));
                JsonNode children = node.get("children");
                if (children == null || !children.isArray() || children.isEmpty()) {
                    throw new PolicyValidationException(path + ".children", "children must be a non-empty array");
                }
                if (children.size() > RuleEvaluator.MAX_CHILDREN) {
                    throw new PolicyValidationException(path + ".children",
                            "at most " + RuleEvaluator.MAX_CHILDREN + " children are allowed but found " + children.size());
                }
                List<RuleExpression> parsed = new ArrayList<>();
                for (int index = 0; index < children.size(); index++) {
                    parsed.add(parseExpression(path + ".children[" + index + "]", children.get(index)));
                }
                return operator.equals("ALL") ? new RuleExpression.All(parsed) : new RuleExpression.Any(parsed);
            }
            case "AMOUNT_AT_LEAST", "AMOUNT_LESS_THAN" -> {
                rejectUnknownFields(path, node, Set.of("operator", "amountMinor"));
                JsonNode amount = node.get("amountMinor");
                if (amount == null || !amount.canConvertToLong() || !amount.isIntegralNumber()) {
                    throw new PolicyValidationException(path + ".amountMinor",
                            "amountMinor must be an integer number of currency minor units");
                }
                return operator.equals("AMOUNT_AT_LEAST")
                        ? new RuleExpression.AmountAtLeast(amount.asLong())
                        : new RuleExpression.AmountLessThan(amount.asLong());
            }
            case "CURRENCY_IN" -> {
                rejectUnknownFields(path, node, Set.of("operator", "currencies"));
                return new RuleExpression.CurrencyIn(codeSet(path + ".currencies", node.get("currencies")));
            }
            default -> {
                rejectUnknownFields(path, node, Set.of("operator", "countries"));
                Set<String> countries = codeSet(path + ".countries", node.get("countries"));
                return operator.equals("COUNTRY_IN")
                        ? new RuleExpression.CountryIn(countries)
                        : new RuleExpression.CountryOutside(countries);
            }
        }
    }

    private static Set<String> codeSet(String path, JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new PolicyValidationException(path, "must be a non-empty array of codes");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                throw new PolicyValidationException(path, "codes must be strings");
            }
            values.add(element.asText());
        }
        return values;
    }

    private static DecisionFlag parseFlag(String path, String value) {
        try {
            return DecisionFlag.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new PolicyValidationException(path, "unknown decision flag " + value + "; supported flags are "
                    + java.util.Arrays.stream(DecisionFlag.values()).map(Enum::name).toList());
        }
    }

    private static String requiredText(String path, JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new PolicyValidationException(path, field + " is required and must be a non-empty string");
        }
        return node.asText().strip();
    }

    /** Unknown fields are rejected so a misspelled key cannot be silently ignored. */
    private static void rejectUnknownFields(String path, JsonNode node, Set<String> allowed) {
        List<String> unknown = new ArrayList<>();
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) unknown.add(name);
        });
        if (!unknown.isEmpty()) {
            throw new PolicyValidationException(path,
                    "unsupported field(s) " + unknown.stream().sorted().toList() + "; allowed fields are " + allowed.stream().sorted().toList());
        }
    }
}
