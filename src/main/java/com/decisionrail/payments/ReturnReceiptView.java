package com.decisionrail.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * The response to a return command, and the exact object stored as its idempotent result.
 *
 * <p>Self-contained on purpose. The totals below are what they were when this return committed, so a
 * later refund on the same payment does not change what this key returns. Rebuilding this from the
 * payment's current state on replay would quietly turn a historical receipt into a live view, which
 * is the one thing a durable idempotent response must never do.
 *
 * @param remainingRefundableMinor what was left immediately after this return, not what is left now
 * @param journalId                the compensating journal this return created, so the money movement
 *                                 can be inspected from the receipt alone
 */
public record ReturnReceiptView(
        UUID returnId,
        UUID paymentId,
        UUID accountId,
        ReturnType returnType,
        long amountMinor,
        String currency,
        String reason,
        int sequenceNumber,
        UUID journalId,
        long capturedAmountMinor,
        long returnedAmountMinor,
        long remainingRefundableMinor,
        Instant createdAt) {}
