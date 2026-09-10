package com.decisionrail.replay;

import java.util.UUID;

/**
 * One pinned input from a job's membership snapshot.
 *
 * <p>Everything needed to reproduce the evaluation is here, copied at job creation: the original
 * amount, currency, and country, and the risk decision that was actually stored. Replay never
 * reads the account's present balance or hold, so a job produces the same comparison regardless
 * of what has happened to that account since.
 *
 * <p>{@code paymentStatus} and {@code paymentFailureCode} are carried for reporting only. A
 * payment with status DECLINED and baseline outcome APPROVE was declined for insufficient funds;
 * the baseline risk decision is the APPROVE, and it is compared as such.
 */
public record ReplayItem(
        UUID paymentId,
        int itemSequence,
        long amountMinor,
        String currency,
        String country,
        String baselineOutcome,
        int baselineScore,
        String baselinePolicyVersion,
        String baselineReasons,
        String paymentStatus,
        String paymentFailureCode) {}
