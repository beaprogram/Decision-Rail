package com.decisionrail.decision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class DecisionEngineTest {
    private final DecisionEngine engine = new DecisionEngine();

    @ParameterizedTest
    @CsvSource({
            "1, CA, 0, APPROVE", "99999, CA, 0, APPROVE",
            "100000, CA, 30, REVIEW", "499999, CA, 30, REVIEW",
            "500000, CA, 60, DECLINE", "1000000000000, CA, 60, DECLINE",
            "99999, US, 20, APPROVE", "100000, US, 50, REVIEW",
            "499999, US, 50, REVIEW", "500000, US, 80, DECLINE"
    })
    void exactAmountBoundariesProduceExpectedDecisions(long amount, String country, int score, DecisionOutcome outcome) {
        for (String currency : List.of("CAD", "USD")) {
            DecisionResult result = engine.evaluate(new DecisionInput(amount, currency, country));
            assertAll(
                    () -> assertEquals(score, result.score()),
                    () -> assertEquals(outcome, result.outcome()),
                    () -> assertEquals("demo-v1", result.ruleSetVersion()),
                    () -> assertEquals(score, result.reasons().stream().mapToInt(ReasonContribution::scoreContribution).sum())
            );
        }
    }

    @ParameterizedTest
    @CsvSource({"0, APPROVE", "29, APPROVE", "30, REVIEW", "59, REVIEW", "60, DECLINE", "100, DECLINE"})
    void outcomeThresholdsAreInclusive(int score, DecisionOutcome outcome) {
        assertEquals(outcome, DecisionOutcome.fromScore(score));
    }

    @Test
    void syntheticBlockedCountryStopsEvaluationBeforeOtherSignals() {
        DecisionResult result = engine.evaluate(new DecisionInput(500_000, "USD", "ZZ"));
        assertAll(
                () -> assertEquals(DecisionOutcome.DECLINE, result.outcome()),
                () -> assertEquals(100, result.score()),
                () -> assertEquals(Set.of(DecisionFlag.TEST_COUNTRY_BLOCKED), result.flags()),
                () -> assertEquals(List.of("TEST_COUNTRY_BLOCKED"), codes(result)),
                () -> assertTrue(result.reasons().getFirst().description().contains("Synthetic"))
        );
    }

    @Test
    void independentSignalsHaveOrderedAndExactContributions() {
        DecisionResult result = engine.evaluate(new DecisionInput(100_000, "CAD", "US"));
        assertAll(
                () -> assertEquals(List.of("ELEVATED_AMOUNT", "CROSS_BORDER"), codes(result)),
                () -> assertEquals(List.of(30, 20), result.reasons().stream().map(ReasonContribution::scoreContribution).toList()),
                () -> assertEquals(Set.of(DecisionFlag.ELEVATED_AMOUNT, DecisionFlag.CROSS_BORDER), result.flags())
        );
    }

    @Test
    void amountBandsNeverDoubleCount() {
        DecisionResult result = engine.evaluate(new DecisionInput(500_000, "CAD", "CA"));
        assertEquals(List.of("HIGH_AMOUNT"), codes(result));
        assertEquals(Set.of(DecisionFlag.HIGH_AMOUNT), result.flags());
    }

    @Test
    void cleanInputIncludesAnExplicitZeroPointExplanation() {
        DecisionResult result = engine.evaluate(new DecisionInput(1, "CAD", "CA"));
        assertEquals(List.of("NO_RISK_SIGNALS"), codes(result));
        assertTrue(result.flags().isEmpty());
        assertEquals(0, result.reasons().getFirst().scoreContribution());
    }

    @Test
    void resultsAndActivePolicyCannotBeMutated() {
        DecisionResult result = engine.evaluate(new DecisionInput(500_000, "USD", "US"));
        assertAll(
                () -> assertThrows(UnsupportedOperationException.class, () -> result.reasons().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> result.flags().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> engine.activeRuleSet().rules().clear()),
                () -> assertThrows(UnsupportedOperationException.class, () -> DecisionInput.SUPPORTED_CURRENCIES.clear())
        );
        var rule = (RuleExpression.All) engine.activeRuleSet().rules().get(2).expression();
        assertThrows(UnsupportedOperationException.class, () -> rule.children().clear());
    }

    @Test
    void resultConstructorDefensivelyCopiesCollections() {
        var reasons = new ArrayList<>(List.of(new ReasonContribution("HIGH_AMOUNT", "Synthetic amount rule.", 60)));
        var flags = EnumSet.of(DecisionFlag.HIGH_AMOUNT);
        var result = new DecisionResult(DecisionOutcome.DECLINE, 60, "demo-v1", reasons, flags);
        reasons.clear();
        flags.clear();
        assertEquals(1, result.reasons().size());
        assertEquals(Set.of(DecisionFlag.HIGH_AMOUNT), result.flags());
    }

    @Test
    void flagIterationIsStableForDifferentInputOrdersAndRemainsImmutable() {
        var reasons = List.of(
                new ReasonContribution("HIGH_AMOUNT", "Synthetic amount rule.", 60),
                new ReasonContribution("CROSS_BORDER", "Synthetic country rule.", 20));
        var forward = new java.util.LinkedHashSet<>(List.of(DecisionFlag.HIGH_AMOUNT, DecisionFlag.CROSS_BORDER));
        var reverse = new java.util.LinkedHashSet<>(List.of(DecisionFlag.CROSS_BORDER, DecisionFlag.HIGH_AMOUNT));
        var first = new DecisionResult(DecisionOutcome.DECLINE, 80, "demo-v1", reasons, forward);
        var second = new DecisionResult(DecisionOutcome.DECLINE, 80, "demo-v1", reasons, reverse);
        var expectedOrder = List.of(DecisionFlag.HIGH_AMOUNT, DecisionFlag.CROSS_BORDER);
        assertEquals(expectedOrder, new ArrayList<>(first.flags()));
        assertEquals(expectedOrder, new ArrayList<>(second.flags()));
        forward.clear();
        reverse.clear();
        assertEquals(expectedOrder, new ArrayList<>(first.flags()));
        assertEquals(expectedOrder, new ArrayList<>(second.flags()));
        assertThrows(UnsupportedOperationException.class, () -> first.flags().clear());
        assertThrows(UnsupportedOperationException.class, () -> second.flags().add(DecisionFlag.ELEVATED_AMOUNT));
        var empty = engine.evaluate(new DecisionInput(1, "CAD", "CA"));
        assertTrue(empty.flags().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> empty.flags().add(DecisionFlag.HIGH_AMOUNT));
    }

    @Test
    void repeatedConcurrentEvaluationsProduceIdenticalResults() {
        var input = new DecisionInput(100_000, "USD", "US");
        var expected = engine.evaluate(input);
        assertTrue(IntStream.range(0, 1_000).parallel()
                .mapToObj(ignored -> engine.evaluate(input)).allMatch(expected::equals));
    }

    @Test
    void invalidResultStateAndNullInputAreRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> engine.evaluate(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> DecisionOutcome.fromScore(-1)),
                () -> assertThrows(IllegalArgumentException.class, () -> DecisionOutcome.fromScore(101)),
                () -> assertThrows(IllegalArgumentException.class, () -> new DecisionResult(
                        DecisionOutcome.APPROVE, 60, "demo-v1", List.of(new ReasonContribution("X", "test", 60)), Set.of())),
                () -> assertThrows(IllegalArgumentException.class, () -> new DecisionResult(
                        DecisionOutcome.DECLINE, 60, "demo-v1", List.of(new ReasonContribution("X", "test", 30)), Set.of()))
        );
    }

    private static List<String> codes(DecisionResult result) {
        return result.reasons().stream().map(ReasonContribution::code).toList();
    }
}
