package com.decisionrail.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of an authoritative payment search result.
 *
 * <p>Read from the {@code payments} table rather than from the asynchronous activity projection, so a
 * payment is discoverable as soon as its transaction commits, including while the broker is down and
 * nothing has been delivered or projected.
 *
 * <p>{@code riskOutcome} is the stored risk decision and is a different thing from {@code status}. A
 * payment with status DECLINED and riskOutcome APPROVE was declined for insufficient funds, which
 * {@code failureCode} names.
 */
public record PaymentSummaryView(
        UUID id,
        UUID accountId,
        long amountMinor,
        String currency,
        String country,
        PaymentStatus status,
        String riskOutcome,
        int riskScore,
        String policyVersion,
        String failureCode,
        Instant createdAt,
        Instant updatedAt) {}
