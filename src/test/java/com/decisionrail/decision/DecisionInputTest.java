package com.decisionrail.decision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DecisionInputTest {
    @Test
    void normalizesWhitespaceAndCaseBeforeEvaluation() {
        var input = new DecisionInput(1, " cad ", " ca ");
        assertEquals("CAD", input.currency());
        assertEquals("CA", input.country());
        assertEquals("ZZ", new DecisionInput(1, "usd", "zz").country());
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 1_000_000_000_001L, Long.MAX_VALUE})
    void rejectsInvalidMinorUnitAmounts(long amount) {
        assertThrows(IllegalArgumentException.class, () -> new DecisionInput(amount, "CAD", "CA"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "EUR", "GBP", "US", "USDD", "123", "C$D"})
    void rejectsUnsupportedOrMalformedCurrencies(String currency) {
        assertThrows(IllegalArgumentException.class, () -> new DecisionInput(1, currency, "CA"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "C", "CAN", "12", "C1", "C-"})
    void rejectsMalformedCountryCodes(String country) {
        assertThrows(IllegalArgumentException.class, () -> new DecisionInput(1, "CAD", country));
    }
}
