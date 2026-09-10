package com.decisionrail.policy;

import com.decisionrail.decision.DecisionRule;
import com.decisionrail.decision.DecisionRuleSet;
import com.decisionrail.decision.RuleExpression;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Produces the canonical form of a policy definition and its content hash.
 *
 * <p>The hash identifies what a policy <em>means</em>, not how it was typed. Two submissions
 * that differ only in whitespace, key order, code casing, or the order of a country set produce
 * the same hash; any change to rule order, a contribution, an operator, or a bound produces a
 * different one. That is what makes the hash usable as the thing a replay job and a shadow
 * comparison pin themselves to.
 *
 * <p>Canonical rules:
 * <ul>
 *   <li>object keys in ascending code-point order, no insignificant whitespace;</li>
 *   <li>rule order and {@code ALL}/{@code ANY} child order preserved, because both change
 *       evaluation: rules accumulate in order and terminate early, children short circuit;</li>
 *   <li>currency and country sets sorted ascending, because set membership has no order;</li>
 *   <li>codes already normalised to upper case by the schema translator.</li>
 * </ul>
 *
 * <p>The version identifier is deliberately excluded. The hash answers "is this the same
 * definition", which is a separate question from "what is it called", and excluding the name is
 * what lets the service detect an attempt to rebind an existing name to different content.
 */
public final class PolicyCanonicalizer {
    private PolicyCanonicalizer() {}

    public static String canonicalJson(DecisionRuleSet ruleSet) {
        if (ruleSet == null) {
            throw new IllegalArgumentException("rule set is required");
        }
        StringBuilder out = new StringBuilder(256);
        out.append("{\"rules\":[");
        List<DecisionRule> rules = ruleSet.rules();
        for (int index = 0; index < rules.size(); index++) {
            if (index > 0) out.append(',');
            appendRule(out, rules.get(index));
        }
        return out.append("]}").toString();
    }

    public static String hash(String canonicalJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void appendRule(StringBuilder out, DecisionRule rule) {
        out.append("{\"code\":").append(quote(rule.code()))
                .append(",\"description\":").append(quote(rule.description()))
                .append(",\"expression\":");
        appendExpression(out, rule.expression());
        out.append(",\"flag\":").append(quote(rule.flag().name()))
                .append(",\"scoreContribution\":").append(rule.scoreContribution())
                .append(",\"terminal\":").append(rule.terminal())
                .append('}');
    }

    private static void appendExpression(StringBuilder out, RuleExpression expression) {
        switch (expression) {
            case RuleExpression.All all -> appendChildren(out, "ALL", all.children());
            case RuleExpression.Any any -> appendChildren(out, "ANY", any.children());
            case RuleExpression.AmountAtLeast value ->
                    out.append("{\"amountMinor\":").append(value.amountMinor()).append(",\"operator\":\"AMOUNT_AT_LEAST\"}");
            case RuleExpression.AmountLessThan value ->
                    out.append("{\"amountMinor\":").append(value.amountMinor()).append(",\"operator\":\"AMOUNT_LESS_THAN\"}");
            case RuleExpression.CurrencyIn value ->
                    appendCodes(out, "currencies", value.currencies(), "CURRENCY_IN");
            case RuleExpression.CountryIn value ->
                    appendCodes(out, "countries", value.countries(), "COUNTRY_IN");
            case RuleExpression.CountryOutside value ->
                    appendCodes(out, "countries", value.countries(), "COUNTRY_OUTSIDE");
        }
    }

    private static void appendChildren(StringBuilder out, String operator, List<RuleExpression> children) {
        out.append("{\"children\":[");
        for (int index = 0; index < children.size(); index++) {
            if (index > 0) out.append(',');
            appendExpression(out, children.get(index));
        }
        out.append("],\"operator\":").append(quote(operator)).append('}');
    }

    private static void appendCodes(StringBuilder out, String field, java.util.Set<String> codes, String operator) {
        out.append("{\"").append(field).append("\":[");
        List<String> sorted = codes.stream().sorted().toList();
        for (int index = 0; index < sorted.size(); index++) {
            if (index > 0) out.append(',');
            out.append(quote(sorted.get(index)));
        }
        out.append("],\"operator\":").append(quote(operator)).append('}');
    }

    private static String quote(String value) {
        return '"' + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + '"';
    }
}
