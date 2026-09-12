package com.decisionrail.payments;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Validated, bounded filters for a merchant payment search.
 *
 * <p>Validation happens here rather than in the controller so the same bounds apply however the query
 * arrives. Every filter is an enumerated value, a UUID, or a timestamp; none of them reaches SQL as
 * text, and the store binds them as parameters.
 *
 * @param limit page size, clamped to 1..200. The cap is the point: an operator screen must not be
 *              able to ask for a merchant's entire history in one request.
 */
public record PaymentSearchQuery(
        UUID paymentId,
        UUID accountId,
        Set<PaymentStatus> statuses,
        Set<String> riskOutcomes,
        String currency,
        Instant createdFrom,
        Instant createdTo,
        int limit,
        PaymentCursor cursor) {

    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_LIMIT = 25;
    private static final Set<String> RISK_OUTCOMES = Set.of("APPROVE", "REVIEW", "DECLINE");
    private static final Set<String> CURRENCIES = Set.of("CAD", "USD");

    public PaymentSearchQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new PaymentException("INVALID_SEARCH_REQUEST", 400,
                    "limit must be between 1 and " + MAX_LIMIT + ".");
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new PaymentException("INVALID_SEARCH_REQUEST", 400, "createdFrom must not be after createdTo.");
        }
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        riskOutcomes = riskOutcomes == null ? Set.of() : Set.copyOf(riskOutcomes);
    }

    /** Parses raw request values, rejecting anything outside the supported enumerations. */
    public static PaymentSearchQuery parse(String paymentId, String accountId, String status, String riskOutcome,
                                          String currency, String createdFrom, String createdTo,
                                          Integer limit, String cursor) {
        return new PaymentSearchQuery(
                uuid("paymentId", paymentId),
                uuid("accountId", accountId),
                statuses(status),
                riskOutcomes(riskOutcome),
                currency(currency),
                instant("createdFrom", createdFrom),
                instant("createdTo", createdTo),
                limit == null ? DEFAULT_LIMIT : limit,
                PaymentCursor.decode(cursor));
    }

    private static Set<PaymentStatus> statuses(String raw) {
        Set<PaymentStatus> parsed = new LinkedHashSet<>();
        for (String value : split(raw)) {
            try {
                parsed.add(PaymentStatus.valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                throw new PaymentException("INVALID_SEARCH_REQUEST", 400, "Unsupported payment status " + value + ".");
            }
        }
        return parsed;
    }

    private static Set<String> riskOutcomes(String raw) {
        Set<String> parsed = new LinkedHashSet<>();
        for (String value : split(raw)) {
            String outcome = value.toUpperCase(Locale.ROOT);
            if (!RISK_OUTCOMES.contains(outcome)) {
                throw new PaymentException("INVALID_SEARCH_REQUEST", 400, "Unsupported risk outcome " + value + ".");
            }
            parsed.add(outcome);
        }
        return parsed;
    }

    private static String currency(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String currency = raw.strip().toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(currency)) {
            throw new PaymentException("INVALID_SEARCH_REQUEST", 400, "Unsupported currency " + raw + ".");
        }
        return currency;
    }

    private static String[] split(String raw) {
        if (raw == null || raw.isBlank()) return new String[0];
        return raw.strip().split("\\s*,\\s*");
    }

    private static UUID uuid(String field, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return UUID.fromString(raw.strip());
        } catch (IllegalArgumentException malformed) {
            throw new PaymentException("INVALID_SEARCH_REQUEST", 400, field + " must be a UUID.");
        }
    }

    private static Instant instant(String field, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.strip());
        } catch (java.time.format.DateTimeParseException malformed) {
            throw new PaymentException("INVALID_SEARCH_REQUEST", 400,
                    field + " must be an ISO-8601 instant such as 2026-09-11T00:00:00Z.");
        }
    }
}
