package com.decisionrail.payments;

import java.time.Instant;
import java.util.UUID;

/** One committed return operation, with the compensating journal that recorded it. */
public record PaymentReturnView(
        UUID id,
        UUID paymentId,
        UUID accountId,
        ReturnType returnType,
        long amountMinor,
        String currency,
        String reason,
        int sequenceNumber,
        UUID journalId,
        Instant createdAt) {}
