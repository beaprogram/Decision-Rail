package com.decisionrail.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A read-only statement of whether recorded money agrees with the evidence for it.
 *
 * <p>{@code status} is never CLEAN unless the examined population was complete. A bounded report that
 * found nothing wrong in the part it looked at is {@code INCOMPLETE}, because "no findings" and "no
 * discrepancies exist" are different claims and only the second is reassuring.
 *
 * @param limitations what this report cannot establish, stated in the report itself rather than only
 *                    in documentation someone may not be reading
 */
public record ReconciliationReport(
        String merchantId,
        Instant generatedAt,
        Status status,
        Scope scope,
        List<ReconciliationFinding> findings,
        List<String> limitations) {

    public enum Status {
        /** Everything in scope was examined and agreed. */
        CLEAN,
        /** Everything in scope was examined; some of it disagreed. */
        DISCREPANCIES_FOUND,
        /** A limit stopped the examination. Nothing found so far, which is not the same as nothing wrong. */
        INCOMPLETE,
        /** A limit stopped the examination, and what was examined already disagreed. */
        INCOMPLETE_WITH_DISCREPANCIES
    }

    /**
     * What was actually looked at.
     *
     * @param snapshot            the isolation the whole report was read under, so a reader knows
     *                            whether two of its numbers could have come from different moments
     * @param complete            whether every account, payment and return in scope was examined
     * @param incompleteReason    which limit stopped it, or null
     * @param checks              the comparisons performed, so an absent check is visible rather than
     *                            mistaken for a passing one
     */
    public record Scope(
            UUID accountFilter,
            int accountLimit,
            int paymentLimit,
            int accountsExamined,
            int paymentsExamined,
            int returnsExamined,
            List<String> currencies,
            String snapshot,
            boolean complete,
            String incompleteReason,
            List<String> checks) {}

    static Status statusFor(boolean complete, boolean anyFindings) {
        if (complete) return anyFindings ? Status.DISCREPANCIES_FOUND : Status.CLEAN;
        return anyFindings ? Status.INCOMPLETE_WITH_DISCREPANCIES : Status.INCOMPLETE;
    }
}
