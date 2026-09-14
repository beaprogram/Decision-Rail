package com.decisionrail.reconciliation;

import com.decisionrail.payments.PaymentException;
import java.util.UUID;

/**
 * What to reconcile, with every bound resolved before any query runs.
 *
 * <p>The limits are not pagination. They are the point at which the report stops being able to claim
 * it examined everything, and the report says so rather than quietly reporting on a prefix.
 *
 * @param accountLimit how many of the merchant's accounts to examine
 * @param paymentLimit how many payments and return operations to examine in detail. Account-level
 *                     balance and hold checks are never bounded by this: an expected balance derived
 *                     from part of an account's history would be wrong rather than partial.
 */
public record ReconciliationRequest(UUID accountId, int accountLimit, int paymentLimit) {
    public static final int DEFAULT_ACCOUNT_LIMIT = 25;
    public static final int MAX_ACCOUNT_LIMIT = 100;
    public static final int DEFAULT_PAYMENT_LIMIT = 500;
    public static final int MAX_PAYMENT_LIMIT = 5_000;

    public static ReconciliationRequest parse(String accountId, Integer accountLimit, Integer paymentLimit) {
        UUID account = null;
        if (accountId != null && !accountId.isBlank()) {
            try {
                account = UUID.fromString(accountId.strip());
            } catch (IllegalArgumentException notAUuid) {
                throw new PaymentException("INVALID_RECONCILIATION_INPUT", 400, "accountId must be a UUID.");
            }
        }
        return new ReconciliationRequest(account,
                bounded(accountLimit, DEFAULT_ACCOUNT_LIMIT, MAX_ACCOUNT_LIMIT, "accountLimit"),
                bounded(paymentLimit, DEFAULT_PAYMENT_LIMIT, MAX_PAYMENT_LIMIT, "paymentLimit"));
    }

    private static int bounded(Integer value, int fallback, int max, String name) {
        if (value == null) return fallback;
        if (value < 1 || value > max) {
            throw new PaymentException("INVALID_RECONCILIATION_INPUT", 400,
                    name + " must be between 1 and " + max + ".");
        }
        return value;
    }
}
