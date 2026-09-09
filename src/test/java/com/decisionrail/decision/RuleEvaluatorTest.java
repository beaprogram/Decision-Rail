package com.decisionrail.decision;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class RuleEvaluatorTest {
    private final RuleEvaluator evaluator = new RuleEvaluator();
    private final DecisionInput input = new DecisionInput(50, "CAD", "CA");

    @Test
    void allStopsImmediatelyAfterFirstFalseCondition() {
        var expression = new RuleExpression.All(List.of(
                new RuleExpression.AmountAtLeast(100), new RuleExpression.CurrencyIn(Set.of("CAD"))));
        assertEquals(new RuleEvaluator.Evaluation(false, 1), evaluator.evaluate(expression, input));
    }

    @Test
    void anyStopsImmediatelyAfterFirstTrueCondition() {
        var expression = new RuleExpression.Any(List.of(
                new RuleExpression.AmountLessThan(100), new RuleExpression.CurrencyIn(Set.of("USD"))));
        assertEquals(new RuleEvaluator.Evaluation(true, 1), evaluator.evaluate(expression, input));
    }

    @Test
    void nestedOperatorsPreserveOrderedEvaluationAndCorrectSemantics() {
        var expression = new RuleExpression.All(List.of(
                new RuleExpression.Any(List.of(new RuleExpression.CountryIn(Set.of("US")), new RuleExpression.CountryIn(Set.of("ca")))),
                new RuleExpression.CurrencyIn(Set.of("cad"))));
        assertEquals(new RuleEvaluator.Evaluation(true, 3), evaluator.evaluate(expression, input));
    }

    @Test
    void evaluatesAllFalseAnyAndAllTrueAll() {
        assertEquals(new RuleEvaluator.Evaluation(false, 2), evaluator.evaluate(new RuleExpression.Any(List.of(
                new RuleExpression.AmountAtLeast(100), new RuleExpression.CountryOutside(Set.of("CA")))), input));
        assertEquals(new RuleEvaluator.Evaluation(true, 2), evaluator.evaluate(new RuleExpression.All(List.of(
                new RuleExpression.AmountAtLeast(50), new RuleExpression.AmountLessThan(51))), input));
    }

    @Test
    void acceptsExactMaximumDepthAndRejectsOneAdditionalLevelBeforeEvaluation() {
        RuleExpression expression = new RuleExpression.AmountAtLeast(0);
        for (int level = 1; level < RuleEvaluator.MAX_DEPTH; level++) {
            expression = new RuleExpression.All(List.of(expression));
        }
        assertEquals(new RuleEvaluator.Evaluation(true, 1), evaluator.evaluate(expression, input));
        RuleExpression tooDeep = new RuleExpression.All(List.of(expression));
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(tooDeep, input));
    }

    @Test
    void countsTheEntireTreeForLimitsEvenWhenEvaluationWouldShortCircuit() {
        var children = new ArrayList<RuleExpression>();
        for (int group = 0; group < 7; group++) {
            children.add(new RuleExpression.All(IntStream.range(0, 16)
                    .<RuleExpression>mapToObj(i -> new RuleExpression.AmountAtLeast(0)).toList()));
        }
        // Root + seven groups of 17 nodes + eight leaves = exactly 128 nodes.
        for (int leaf = 0; leaf < 8; leaf++) {
            children.add(new RuleExpression.AmountAtLeast(0));
        }
        assertTrue(evaluator.evaluate(new RuleExpression.Any(children), input).matched());
        children.add(new RuleExpression.AmountAtLeast(0));
        var tooLarge = new RuleExpression.Any(children);
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(tooLarge, input));
    }

    @Test
    void rejectsInvalidExpressionShapesAndOperands() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new RuleExpression.All(List.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new RuleExpression.Any(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new RuleExpression.All(
                        IntStream.range(0, 17).<RuleExpression>mapToObj(i -> new RuleExpression.AmountAtLeast(0)).toList())),
                () -> assertThrows(IllegalArgumentException.class, () -> new RuleExpression.CountryIn(Set.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new RuleExpression.CountryIn(Set.of("CAN"))),
                () -> assertThrows(IllegalArgumentException.class, () -> new RuleExpression.CurrencyIn(Set.of("CA"))),
                () -> assertThrows(IllegalArgumentException.class, () -> new RuleExpression.AmountAtLeast(-1)),
                () -> assertThrows(IllegalArgumentException.class, () -> new RuleExpression.AmountLessThan(Long.MAX_VALUE)),
                () -> assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(null, input)),
                () -> assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(new RuleExpression.AmountAtLeast(0), null))
        );
    }

    @Test
    void childrenAndCodeSetsAreDefensivelyCopied() {
        var children = new ArrayList<RuleExpression>(List.of(new RuleExpression.AmountAtLeast(0)));
        var all = new RuleExpression.All(children);
        children.clear();
        assertEquals(1, all.children().size());
        assertThrows(UnsupportedOperationException.class, () -> all.children().clear());
        var countries = new java.util.HashSet<>(Set.of("CA"));
        var condition = new RuleExpression.CountryIn(countries);
        countries.add("US");
        assertEquals(Set.of("CA"), condition.countries());
        assertThrows(UnsupportedOperationException.class, () -> condition.countries().clear());
    }

    @Test
    void ruleSetsRejectDuplicateCodesAndExcessiveRuleCounts() {
        DecisionRule rule = rule("R0");
        assertThrows(IllegalArgumentException.class, () -> new DecisionRuleSet("demo-v1", List.of(rule, rule)));
        var rules = IntStream.range(0, DecisionRuleSet.MAX_RULES).mapToObj(i -> rule("R" + i)).toList();
        assertEquals(DecisionRuleSet.MAX_RULES, new DecisionRuleSet("demo-v1", rules).rules().size());
        var tooMany = new ArrayList<>(rules);
        tooMany.add(rule("EXTRA"));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRuleSet("demo-v1", tooMany));
        assertThrows(IllegalArgumentException.class, () -> new DecisionRuleSet("", List.of(rule)));
    }

    private static DecisionRule rule(String code) {
        return new DecisionRule(code, "Synthetic test rule.", new RuleExpression.AmountAtLeast(1), 20,
                DecisionFlag.HIGH_AMOUNT, false);
    }
}
