package com.decisionrail.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Merchant-scoped read model built from delivered events.
 *
 * <p>It deliberately carries no balance, hold, or ledger amount. The projection is a
 * convenience view of lifecycle and risk history; the authoritative financial record stays
 * in accounts, payments, and the capture journal.
 *
 * <p>{@code riskOutcome} is the stored risk decision and is not the same thing as
 * {@code lastStatus}: an APPROVE risk outcome with a DECLINED status and an
 * INSUFFICIENT_FUNDS failure code is a normal, correct combination.
 */
public record PaymentActivityView(
        UUID paymentId,
        UUID accountId,
        long amountMinor,
        String currency,
        String country,
        String lastStatus,
        String lastEventType,
        long lastSequence,
        String riskOutcome,
        int riskScore,
        String policyVersion,
        String failureCode,
        int appliedEventCount,
        Instant firstEventAt,
        Instant lastEventAt) {}
