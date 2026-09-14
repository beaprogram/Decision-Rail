package com.decisionrail.payments;

import java.util.UUID;

/**
 * A request to return captured money.
 *
 * @param amountMinor for a refund, the exact amount in the payment's currency. Null means "the whole
 *                    remaining refundable amount", which is the only form a reversal accepts: a
 *                    reversal names no amount because it always returns the entire capture.
 * @param reason      free text kept as provenance on the return operation. Bounded, optional, and
 *                    never interpreted.
 */
public record ReturnCommand(UUID paymentId, ReturnType type, Long amountMinor, String reason) {}
