package com.decisionrail.decision;

import java.util.HashSet;
import java.util.List;

/** An immutable policy snapshot; decision history retains its version. */
public record DecisionRuleSet(String version, List<DecisionRule> rules) {
    public static final int MAX_RULES = 32;

    public DecisionRuleSet {
        if (version == null || !version.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("rule set version must be an identifier of at most 64 characters");
        }
        if (rules == null || rules.isEmpty() || rules.size() > MAX_RULES) {
            throw new IllegalArgumentException("rule set must contain 1 to " + MAX_RULES + " rules");
        }
        if (rules.stream().anyMatch(rule -> rule == null)) {
            throw new IllegalArgumentException("rules cannot contain null");
        }
        rules = List.copyOf(rules);
        var codes = new HashSet<String>();
        for (DecisionRule rule : rules) {
            if (!codes.add(rule.code())) {
                throw new IllegalArgumentException("duplicate rule code: " + rule.code());
            }
        }
    }
}
