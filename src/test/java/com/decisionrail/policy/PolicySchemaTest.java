package com.decisionrail.policy;

import com.decisionrail.decision.DecisionEngine;
import com.decisionrail.decision.DecisionInput;
import com.decisionrail.decision.DecisionOutcome;
import com.decisionrail.decision.DecisionRuleSet;
import com.decisionrail.decision.PolicyEvaluation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Policy document validation, canonical hashing, and evaluator semantics.
 *
 * <p>Pure: no database, no web server, no broker. The same properties hold wherever a policy is
 * evaluated, which is the point of keeping the engine free of infrastructure.
 */
class PolicySchemaTest {
    private final ObjectMapper json = new ObjectMapper();
    private final DecisionEngine engine = new DecisionEngine();

    @Test
    void aValidDocumentTranslatesIntoAnEvaluableSnapshot() {
        DecisionRuleSet ruleSet = translate("candidate-a", """
                {"rules":[
                  {"code":"BIG","description":"At least 1000 minor units.","scoreContribution":60,
                   "flag":"HIGH_AMOUNT","terminal":false,
                   "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}
                ]}""");
        assertThat(ruleSet.version()).isEqualTo("candidate-a");
        assertThat(ruleSet.rules()).hasSize(1);
        PolicyEvaluation evaluation = engine.evaluatePolicy(ruleSet, new DecisionInput(2_500, "CAD", "CA"));
        assertThat(evaluation.outcome()).isEqualTo(DecisionOutcome.DECLINE);
        assertThat(evaluation.score()).isEqualTo(60);
        assertThat(evaluation.reasons()).extracting("code").containsExactly("BIG");
    }

    @Test
    void theHashIdentifiesMeaningRatherThanFormatting() {
        String compact = """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER",
                 "terminal":false,"expression":{"operator":"COUNTRY_IN","countries":["US","GB"]}}]}""";
        // Same policy: reordered keys, different whitespace, lower-case codes, reordered country set.
        String reformatted = """
                {
                  "rules": [
                    {
                      "expression": { "countries": ["gb", "us"], "operator": "country_in" },
                      "terminal": false,
                      "flag": "cross_border",
                      "scoreContribution": 10,
                      "description": "d",
                      "code": "x"
                    }
                  ]
                }""";
        assertThat(hashOf("v1", reformatted)).isEqualTo(hashOf("v1", compact));
        // The version identifier is not part of the content hash.
        assertThat(hashOf("other-name", compact)).isEqualTo(hashOf("v1", compact));

        // Any semantic change does change it.
        String differentBound = compact.replace("\"US\",\"GB\"", "\"US\",\"FR\"");
        assertThat(hashOf("v1", differentBound)).isNotEqualTo(hashOf("v1", compact));
        String differentScore = compact.replace("\"scoreContribution\":10", "\"scoreContribution\":11");
        assertThat(hashOf("v1", differentScore)).isNotEqualTo(hashOf("v1", compact));
    }

    @Test
    void ruleOrderIsPartOfTheDefinitionBecauseItChangesEvaluation() {
        String terminalFirst = """
                {"rules":[
                  {"code":"STOP","description":"Terminal block.","scoreContribution":100,"flag":"TEST_COUNTRY_BLOCKED",
                   "terminal":true,"expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}},
                  {"code":"EXTRA","description":"Would add more.","scoreContribution":20,"flag":"CROSS_BORDER",
                   "terminal":false,"expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1}}
                ]}""";
        String terminalSecond = """
                {"rules":[
                  {"code":"EXTRA","description":"Would add more.","scoreContribution":20,"flag":"CROSS_BORDER",
                   "terminal":false,"expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1}},
                  {"code":"STOP","description":"Terminal block.","scoreContribution":100,"flag":"TEST_COUNTRY_BLOCKED",
                   "terminal":true,"expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}
                ]}""";
        assertThat(hashOf("v", terminalFirst)).isNotEqualTo(hashOf("v", terminalSecond));

        DecisionInput input = new DecisionInput(2_000, "CAD", "CA");
        PolicyEvaluation first = engine.evaluatePolicy(translate("v", terminalFirst), input);
        PolicyEvaluation second = engine.evaluatePolicy(translate("v", terminalSecond), input);
        // A matching terminal rule stops evaluation, so the later contribution never applies.
        assertThat(first.rawScore()).isEqualTo(100);
        assertThat(first.terminated()).isTrue();
        assertThat(first.rulesEvaluated()).isEqualTo(1);
        // With the terminal rule second, the earlier rule has already contributed.
        assertThat(second.rawScore()).isEqualTo(120);
        assertThat(second.reasons()).extracting("code").containsExactly("EXTRA", "STOP");
    }

    @Test
    void overlappingRulesAccumulateAndAScoreAboveTheScaleIsCappedExplicitly() {
        // Three overlapping rules all match the same input: 60 + 40 + 30 = 130 raw.
        DecisionRuleSet ruleSet = translate("overflow", """
                {"rules":[
                  {"code":"A","description":"a","scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,
                   "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":100}},
                  {"code":"B","description":"b","scoreContribution":40,"flag":"ELEVATED_AMOUNT","terminal":false,
                   "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":200}},
                  {"code":"C","description":"c","scoreContribution":30,"flag":"CROSS_BORDER","terminal":false,
                   "expression":{"operator":"CURRENCY_IN","currencies":["CAD"]}}
                ]}""");
        PolicyEvaluation evaluation = engine.evaluatePolicy(ruleSet, new DecisionInput(5_000, "CAD", "CA"));
        assertThat(evaluation.rawScore()).isEqualTo(130);
        assertThat(evaluation.score()).isEqualTo(100);
        assertThat(evaluation.scoreCapped()).isTrue();
        assertThat(evaluation.outcome()).isEqualTo(DecisionOutcome.DECLINE);
        // Reasons still reconcile, against the raw total rather than the capped one.
        assertThat(evaluation.reasons()).extracting("code").containsExactly("A", "B", "C");
        assertThat(evaluation.reasons().stream().mapToInt(r -> r.scoreContribution()).sum()).isEqualTo(130);
        // A capped evaluation is never allowed to masquerade as an authoritative stored decision.
        assertThatThrownBy(evaluation::toDecisionResult).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void evaluationIsDeterministicAcrossRepeatedRuns() {
        DecisionRuleSet ruleSet = translate("determinism", """
                {"rules":[
                  {"code":"ANY_MATCH","description":"Composite.","scoreContribution":30,"flag":"ELEVATED_AMOUNT",
                   "terminal":false,
                   "expression":{"operator":"ANY","children":[
                      {"operator":"COUNTRY_OUTSIDE","countries":["CA"]},
                      {"operator":"ALL","children":[
                         {"operator":"AMOUNT_AT_LEAST","amountMinor":1000},
                         {"operator":"AMOUNT_LESS_THAN","amountMinor":9000}]}]}}
                ]}""");
        DecisionInput input = new DecisionInput(2_000, "CAD", "CA");
        PolicyEvaluation first = engine.evaluatePolicy(ruleSet, input);
        for (int run = 0; run < 50; run++) {
            PolicyEvaluation repeat = engine.evaluatePolicy(ruleSet, input);
            assertThat(repeat).isEqualTo(first);
            assertThat(repeat.flags()).containsExactlyElementsOf(first.flags());
            assertThat(repeat.reasons()).isEqualTo(first.reasons());
        }
    }

    @Test
    void theBuiltInPolicyStillProducesItsOriginalDecisions() {
        // The generalised evaluator must not have changed demo-v1 behaviour.
        assertThat(engine.evaluate(new DecisionInput(50_000, "CAD", "CA")).outcome()).isEqualTo(DecisionOutcome.APPROVE);
        assertThat(engine.evaluate(new DecisionInput(100_000, "CAD", "CA")).outcome()).isEqualTo(DecisionOutcome.REVIEW);
        assertThat(engine.evaluate(new DecisionInput(500_000, "CAD", "CA")).outcome()).isEqualTo(DecisionOutcome.DECLINE);
        var blocked = engine.evaluate(new DecisionInput(1_000, "CAD", "ZZ"));
        assertThat(blocked.outcome()).isEqualTo(DecisionOutcome.DECLINE);
        assertThat(blocked.score()).isEqualTo(100);
        assertThat(blocked.ruleSetVersion()).isEqualTo(DecisionEngine.RULE_SET_VERSION);
    }

    @Test
    void unsupportedOperatorsAndInvalidOperandsAreRejected() {
        assertRejected("$.rules[0].expression.operator", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER","terminal":false,
                 "expression":{"operator":"REGEX_MATCH","pattern":".*"}}]}""");
        assertRejected("$.rules[0].expression.amountMinor", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER","terminal":false,
                 "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":"lots"}}]}""");
        assertRejected("$.rules[0].expression.amountMinor", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER","terminal":false,
                 "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1.5}}]}""");
        // Out-of-range operand. This is an input error, so it must arrive as a structured policy
        // validation failure naming its path, not as an unchecked exception that reads as a server
        // fault to the caller.
        assertRejected("$.rules[0].expression.amountMinor", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER","terminal":false,
                 "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":9999999999999}}]}""");
        assertRejected("$.rules[0].expression.countries", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER","terminal":false,
                 "expression":{"operator":"COUNTRY_IN","countries":[]}}]}""");
        assertRejected("$.rules[0].flag", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"INVENTED_FLAG","terminal":false,
                 "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1}}]}""");
    }

    @Test
    void duplicateRuleCodesAreRejected() {
        assertRejected("$.rules[1].code", """
                {"rules":[
                  {"code":"SAME","description":"d","scoreContribution":10,"flag":"CROSS_BORDER","terminal":false,
                   "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1}},
                  {"code":"SAME","description":"other","scoreContribution":20,"flag":"HIGH_AMOUNT","terminal":false,
                   "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":2}}
                ]}""");
    }

    @Test
    void invalidScoreContributionsAreRejected() {
        for (String score : List.of("0", "-5", "101", "\"40\"")) {
            assertRejected("$.rules[0].scoreContribution", """
                    {"rules":[{"code":"X","description":"d","scoreContribution":%s,"flag":"CROSS_BORDER","terminal":false,
                     "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1}}]}""".formatted(score));
        }
    }

    @Test
    void nestingAndSizeLimitsAreEnforced() {
        String nested = "{\"operator\":\"AMOUNT_AT_LEAST\",\"amountMinor\":1}";
        for (int depth = 0; depth < 12; depth++) {
            nested = "{\"operator\":\"ALL\",\"children\":[" + nested + "]}";
        }
        final String deep = nested;
        assertRejected("$.rules[0].expression", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER","terminal":false,
                 "expression":%s}]}""".formatted(deep));
        assertThatThrownBy(() -> translate("v", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER","terminal":false,
                 "expression":%s}]}""".formatted(deep)))
                .hasMessageContaining("depth");

        StringBuilder tooManyChildren = new StringBuilder();
        for (int child = 0; child < 20; child++) {
            if (child > 0) tooManyChildren.append(',');
            tooManyChildren.append("{\"operator\":\"AMOUNT_AT_LEAST\",\"amountMinor\":").append(child + 1).append('}');
        }
        assertRejected("$.rules[0].expression.children", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER","terminal":false,
                 "expression":{"operator":"ALL","children":[%s]}}]}""".formatted(tooManyChildren));

        StringBuilder tooManyRules = new StringBuilder();
        for (int rule = 0; rule < 40; rule++) {
            if (rule > 0) tooManyRules.append(',');
            tooManyRules.append("""
                    {"code":"R%d","description":"d","scoreContribution":1,"flag":"CROSS_BORDER","terminal":false,
                     "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1}}""".formatted(rule));
        }
        assertRejected("$.rules", "{\"rules\":[" + tooManyRules + "]}");
        assertRejected("$.rules", "{\"rules\":[]}");
    }

    @Test
    void unknownFieldsAreRejectedSoATypoCannotSilentlyDisableARule() {
        assertRejected("$.rules[0]", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER",
                 "termianl":true,"expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1}}]}""");
        assertRejected("$.rules[0].expression", """
                {"rules":[{"code":"X","description":"d","scoreContribution":10,"flag":"CROSS_BORDER","terminal":false,
                 "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1,"script":"exit"}}]}""");
    }

    private void assertRejected(String expectedPath, String document) {
        assertThatThrownBy(() -> translate("candidate", document))
                .as("document should be rejected: %s", document)
                .isInstanceOf(PolicyValidationException.class)
                .hasMessageContaining(expectedPath);
    }

    private DecisionRuleSet translate(String versionId, String document) {
        return PolicySchema.toRuleSet(versionId, read(document));
    }

    private String hashOf(String versionId, String document) {
        return PolicyCanonicalizer.hash(PolicyCanonicalizer.canonicalJson(translate(versionId, document)));
    }

    private JsonNode read(String document) {
        try {
            return json.readTree(document);
        } catch (Exception unreadable) {
            throw new AssertionError("test document is not valid JSON", unreadable);
        }
    }
}
