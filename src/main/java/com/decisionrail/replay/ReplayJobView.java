package com.decisionrail.replay;

import java.time.Instant;
import java.util.UUID;

/** Durable state of a replay job. Counts are derived from recorded results, never incremented. */
public record ReplayJobView(
        UUID id,
        String merchantId,
        String candidateVersion,
        String candidateHash,
        String baselineSource,
        String status,
        int inputCount,
        int completedCount,
        int failedCount,
        int pendingCount,
        int approveCount,
        int reviewCount,
        int declineCount,
        int divergenceCount,
        long evaluationNanosTotal,
        Instant windowFrom,
        Instant windowTo,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String failureDetail) {}
