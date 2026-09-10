package com.decisionrail.replay;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A replay comparison report.
 *
 * <p>Two things are stated explicitly because they are easy to misread:
 * <ul>
 *   <li><b>The divergence denominator.</b> {@code divergenceRate} is
 *       {@code divergenceCount / completedCount}: the share of <em>successfully evaluated</em>
 *       inputs whose candidate risk outcome differs from the stored baseline risk outcome. Items
 *       that failed or were never evaluated are excluded, so a partially finished job does not
 *       report a rate against a denominator it has not reached.</li>
 *   <li><b>What divergence means.</b> It compares risk decisions only. A payment declined for
 *       insufficient funds has an APPROVE baseline risk outcome and is compared as APPROVE; the
 *       report never treats a funding decline as a policy decline.</li>
 * </ul>
 *
 * @param timingMethod how the timings were obtained, so they are not mistaken for a benchmark
 * @param labelledOutcomeDataAvailable always false here. No fraud labels exist for this synthetic
 *        data, so precision, recall, and false-positive rates are not computed. Publishing such
 *        figures without labels would be inventing them.
 */
public record ReplayReport(
        UUID jobId,
        String merchantId,
        String status,
        String candidateVersion,
        String candidateHash,
        String baselinePolicyVersion,
        String baselineSource,
        int inputCount,
        int completedCount,
        int failedCount,
        int pendingCount,
        Map<String, Integer> candidateOutcomeCounts,
        Map<String, Long> baselineOutcomeCounts,
        int divergenceCount,
        String divergenceDenominator,
        Double divergenceRate,
        Timings evaluationTimings,
        String timingMethod,
        boolean labelledOutcomeDataAvailable,
        Instant windowFrom,
        Instant windowTo,
        Instant createdAt,
        Instant completedAt) {

    /** @param meanNanos null when nothing has been evaluated yet, rather than a misleading zero. */
    public record Timings(long totalNanos, Long meanNanos, int measuredEvaluations) {}

    public static final String TIMING_METHOD = "System.nanoTime around in-process candidate policy "
            + "evaluation only. Excludes database access, JSON decoding, batching, and scheduling. "
            + "Single JVM, no warmup control or repetition, so these are observed values for this "
            + "run and not a performance benchmark.";
    public static final String DIVERGENCE_DENOMINATOR = "completedCount: inputs whose candidate "
            + "evaluation succeeded. Failed and not-yet-evaluated inputs are excluded.";
}
