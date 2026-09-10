package com.decisionrail.replay;

import com.decisionrail.payments.PaymentException;
import com.decisionrail.policy.PolicyService;
import com.decisionrail.policy.PolicyVersion;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates replay jobs and serves merchant-scoped job state, results, and reports. */
@Service
public class ReplayService {
    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);
    public static final int MAX_INPUTS = 5_000;
    public static final int DEFAULT_INPUTS = 500;

    private final ReplayStore store;
    private final PolicyService policies;
    private final Clock clock;
    private final MeterRegistry metrics;

    public ReplayService(ReplayStore store, PolicyService policies, Clock clock, MeterRegistry metrics) {
        this.store = store;
        this.policies = policies;
        this.clock = clock;
        this.metrics = metrics;
    }

    public record CreateCommand(String candidateVersion, Integer limit, Instant from) {}

    public record CreatedJob(ReplayJobView job, boolean replayed) {}

    /**
     * Creates a job and materialises its membership in one transaction.
     *
     * <p>Retry semantics are the job's own, not the payment idempotency model: a repeated request
     * returns the same job document rather than a stored payment response. A key reused with a
     * different request is rejected, exactly as for a payment command, because it would otherwise
     * return the wrong job.
     */
    @Transactional(timeout = 30)
    public CreatedJob create(String merchantId, String idempotencyKey, CreateCommand command) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new PaymentException("INVALID_IDEMPOTENCY_KEY", 400,
                    "Idempotency-Key must contain 8-128 letters, digits, dots, underscores, colons or hyphens.");
        }
        int limit = command.limit() == null ? DEFAULT_INPUTS : command.limit();
        if (limit < 1 || limit > MAX_INPUTS) {
            throw new PaymentException("INVALID_REPLAY_REQUEST", 400,
                    "limit must be between 1 and " + MAX_INPUTS + ".");
        }
        PolicyVersion candidate = policies.require(command.candidateVersion());
        // Fails fast if the stored definition cannot be rebuilt, so a job is never created
        // against a candidate that turns out to be unevaluable.
        policies.snapshot(candidate.versionId());

        String fingerprint = sha256("REPLAY|" + candidate.versionId() + "|" + candidate.definitionHash()
                + "|" + limit + "|" + command.from());
        ReplayStore.ExistingRequest existing = store.findRequest(merchantId, idempotencyKey);
        if (existing != null) {
            if (!existing.requestHash().equals(fingerprint)) {
                throw new PaymentException("IDEMPOTENCY_CONFLICT", 409,
                        "This Idempotency-Key was already used for a different replay request.");
            }
            metrics.counter("decisionrail.replay.requests.replayed").increment();
            return new CreatedJob(store.findJob(merchantId, existing.jobId()), true);
        }

        UUID jobId = UUID.randomUUID();
        Instant windowTo = clock.instant();
        store.createJob(jobId, merchantId, candidate.versionId(), candidate.definitionHash(), limit,
                command.from(), windowTo);
        int inputCount = store.materialiseMembership(jobId, merchantId, command.from(), windowTo, limit);
        store.setInputCount(jobId, inputCount);
        store.recordRequest(merchantId, idempotencyKey, fingerprint, jobId);
        metrics.counter("decisionrail.replay.jobs.created").increment();
        log.info("Created replay job {} for merchant {} against candidate {} with {} pinned inputs",
                jobId, merchantId, candidate.versionId(), inputCount);
        return new CreatedJob(store.findJob(merchantId, jobId), false);
    }

    @Transactional(readOnly = true)
    public ReplayJobView job(String merchantId, UUID jobId) {
        ReplayJobView job = store.findJob(merchantId, jobId);
        if (job == null) {
            throw new PaymentException("REPLAY_JOB_NOT_FOUND", 404, "Replay job was not found.");
        }
        return job;
    }

    @Transactional(readOnly = true)
    public List<ReplayJobView> jobs(String merchantId, int limit) {
        return store.listJobs(merchantId, Math.clamp(limit, 1, 100));
    }

    @Transactional(readOnly = true)
    public List<ReplayResultView> results(String merchantId, UUID jobId, boolean divergedOnly, int limit, int offset) {
        // Ownership is established before any result row is read.
        job(merchantId, jobId);
        return store.results(jobId, divergedOnly, Math.clamp(limit, 1, 200), Math.max(0, offset));
    }

    @Transactional(readOnly = true)
    public ReplayReport report(String merchantId, UUID jobId) {
        ReplayJobView job = job(merchantId, jobId);
        Map<String, Integer> candidateCounts = new LinkedHashMap<>();
        candidateCounts.put("APPROVE", job.approveCount());
        candidateCounts.put("REVIEW", job.reviewCount());
        candidateCounts.put("DECLINE", job.declineCount());
        Map<String, Long> baselineCounts = new LinkedHashMap<>();
        for (String outcome : List.of("APPROVE", "REVIEW", "DECLINE")) {
            baselineCounts.put(outcome, store.baselineOutcomeCount(jobId, outcome));
        }
        Double rate = job.completedCount() == 0 ? null
                : Math.round(job.divergenceCount() * 1_000_000.0 / job.completedCount()) / 1_000_000.0;
        Long mean = job.completedCount() + job.failedCount() == 0 ? null
                : job.evaluationNanosTotal() / (job.completedCount() + job.failedCount());
        return new ReplayReport(job.id(), job.merchantId(), job.status(), job.candidateVersion(), job.candidateHash(),
                baselinePolicyVersion(jobId), job.baselineSource(), job.inputCount(), job.completedCount(),
                job.failedCount(), job.pendingCount(), candidateCounts, baselineCounts, job.divergenceCount(),
                ReplayReport.DIVERGENCE_DENOMINATOR, rate,
                new ReplayReport.Timings(job.evaluationNanosTotal(), mean, job.completedCount() + job.failedCount()),
                ReplayReport.TIMING_METHOD, false,
                job.windowFrom(), job.windowTo(), job.createdAt(), job.completedAt());
    }

    private String baselinePolicyVersion(UUID jobId) {
        List<ReplayItem> sample = store.sampleItem(jobId);
        return sample.isEmpty() ? null : sample.getFirst().baselinePolicyVersion();
    }

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
