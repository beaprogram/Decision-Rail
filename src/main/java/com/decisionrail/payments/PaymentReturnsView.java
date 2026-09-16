package com.decisionrail.payments;

import java.util.List;
import java.util.UUID;

/**
 * Everything a caller needs to decide what may still be returned on one payment, and what already was.
 *
 * <p>{@code capturedAmountMinor} is null for a payment that was never captured, which is different
 * from a captured payment with nothing left: the first cannot be refunded at all, the second has been
 * refunded in full. {@code remainingRefundableMinor} is zero in both cases, so the two are
 * distinguished by {@code refundable} and its stated reason rather than by an amount.
 *
 * @param refundable        whether a refund would be accepted right now
 * @param reversible        whether a reversal would be accepted right now. A reversal is refused once
 *                          anything has been returned, so this goes false after the first refund even
 *                          though refunds remain possible. Derived from the payment's authoritative
 *                          return count, never from whether the history page below happens to be empty.
 * @param unavailableReason the stable code explaining refusal, or null when both are available. The
 *                          server decides this; the dashboard renders it and never derives its own.
 * @param returnCount       how many return operations this payment has in total. This is the number a
 *                          caller should show; {@code returns} is one page of them and is usually
 *                          shorter.
 * @param returns           one page of return history, newest first
 * @param nextCursor        pass back as {@code cursor} to read the next, older page, or null when this
 *                          page is the last one
 * @param pageLimit         the page size this response was built with
 */
public record PaymentReturnsView(
        UUID paymentId,
        UUID accountId,
        PaymentStatus status,
        String currency,
        Long capturedAmountMinor,
        long returnedAmountMinor,
        long remainingRefundableMinor,
        boolean refundable,
        boolean reversible,
        String unavailableReason,
        long returnCount,
        List<PaymentReturnView> returns,
        String nextCursor,
        int pageLimit) {}
