package com.decisionrail.payments;

import java.util.List;

/**
 * One bounded page of payment search results.
 *
 * @param nextCursor       position to pass back for the following page, or null at the end
 * @param matchedCount     matches found, counted only up to {@code matchedCountLimit}
 * @param matchedCountCapped true when the count stopped at its limit, so the real total is at least
 *                         {@code matchedCount}. An exact count would mean scanning every matching row
 *                         on every page request, which is the one part of a search that cannot be
 *                         bounded. Reporting "at least" is honest and cheap; reporting a precise
 *                         total would not be either.
 */
public record PaymentSearchPage(
        List<PaymentSummaryView> payments,
        String nextCursor,
        long matchedCount,
        boolean matchedCountCapped,
        int matchedCountLimit) {}
