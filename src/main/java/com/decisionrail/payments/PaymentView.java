package com.decisionrail.payments;

import com.decisionrail.decision.DecisionResult;
import java.time.Instant;
import java.util.UUID;

public record PaymentView(UUID id, UUID accountId, long amountMinor, String currency, String country,
        PaymentStatus status, DecisionResult decision, String failureCode, Instant createdAt, Instant updatedAt) {}
