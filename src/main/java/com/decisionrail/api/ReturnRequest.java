package com.decisionrail.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * A refund request.
 *
 * <p>The amount is required and exact. There is no "refund whatever is left" form: the remainder
 * changes as other refunds commit, so one idempotency key could mean two different amounts at two
 * different moments. A caller that wants to return everything reads the remaining amount from
 * {@code GET /v1/payments/{id}/returns} and sends that number.
 *
 * <p>Bounds here mirror the service and the database rather than replacing them. Validation at the
 * edge produces a better message; the financial rule is still enforced inside the transaction.
 */
public record ReturnRequest(
        @Min(1) @Max(1_000_000_000_000L) Long amountMinor,
        @Size(max = 140) String reason) {}
