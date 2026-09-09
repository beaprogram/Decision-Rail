package com.decisionrail.decision;

import java.util.Locale;
import java.util.Set;

/** Amounts are currency minor units; this demo does not convert foreign exchange. */
public record DecisionInput(long amountMinor, String currency, String country) {
    public static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;
    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("CAD", "USD");

    public DecisionInput {
        if (amountMinor <= 0 || amountMinor > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("amountMinor must be between 1 and " + MAX_AMOUNT_MINOR);
        }
        currency = normalizeCode(currency, "currency", 3);
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("currency must be CAD or USD for the synthetic demo policy");
        }
        country = normalizeCode(country, "country", 2);
    }

    static String normalizeCode(String value, String name, int length) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{" + length + "}")) {
            throw new IllegalArgumentException(name + " must contain " + length + " ASCII letters");
        }
        return normalized;
    }
}
