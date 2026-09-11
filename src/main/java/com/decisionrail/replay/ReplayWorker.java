package com.decisionrail.replay;

import com.decisionrail.decision.DecisionEngine;
import com.decisionrail.decision.DecisionInput;
import com.decisionrail.decision.PolicyEvaluation;
import com.decisionrail.decision.ReasonContribution;
import com.decisionrail.events.DeliveryFaults;
import com.decisionrail.policy.PolicyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Processes replay jobs in bounded batches, resumably.
 *
 * <p>Recovery after an interruption needs no special path. Item state and the primary key on
 * recorded results already make re-processing harmless, and every job aggregate is recomputed
 * from the results table rather than incremented, so a resumed job converges on the same totals
 * a single uninterrupted run would have produced. A worker that dies mid-job leaves its lease
 * behind; once the lease expires another worker picks the job up and continues from the first
 * item that has no result.
 *
 * <p>Evaluation itself is pure: a pinned input and an immutable policy snapshot in, an evaluation
 * out. It reads no account balance, no hold, and no present-day state of any kind.
 */
@Component
public class ReplayWorker {
    private static final Logger log = LoggerFactory.getLogger(ReplayWorker.class);

    private final ReplayStore store;
    private final PolicyService policies;
    private final DecisionEngine engine;
    private final DeliveryFaults faults;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final int batchSize;
    private final Duration leaseDuration;
    private final String workerId;

    public ReplayWorker(ReplayStore store, PolicyService policies, DecisionEngine engine, DeliveryFaults faults,
                        TransactionTemplate transactions, ObjectMapper json, Clock clock, MeterRegistry metrics,
                        @Value("${app.replay.batch-size:100}") int batchSize,
                        @Value("${app.replay.lease-duration:60s}") Duration leaseDuration) {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("app.replay.batch-size must be between 1 and 1000");
        }
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("app.replay.lease-duration must be a positive duration");
        }
        this.store = store;
        this.policies = policies;
        this.engine = engine;
        this.faults = faults;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.workerId = "replay/" + ProcessHandle.current().pid() + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String workerId() { return workerId; }

    public int batchSize() { return batchSize; }

    /** Outcome of one batch. {@code alreadyRecorded} counts items a previous run had finished. */
    public record Cycle(UUID jobId, int evaluated, int failed, int alreadyRecorded, boolean jobCompleted) {
        public static Cycle idle() { return new Cycle(null, 0, 0, 0, false); }

        public boolean didWork() { return jobId != null; }
    }

    /** Claims one runnable job and processes at most one bounded batch of its pinned inputs. */
    public Cycle runOnce() {
        Instant now = clock.instant();
        ReplayStore.ClaimedJob claimed = transactions.execute(status ->
                store.claimJob(workerId, now, now.plus(leaseDuration)));
        if (claimed == null) {
            return Cycle.idle();
        }
        try {
            BatchOutcome outcome = processBatch(claimed);
            if (outcome.lostOwnership()) {
                return lostOwnership(claimed);
            }
            // This worker holds no locks here, so this is the window where another worker can take
            // the job over. A test can hold it to exercise that handover. Inert in production.
            faults.awaitGate(DeliveryFaults.replayHandoverGate(workerId));
            boolean completed = finalise(claimed);
            metrics.counter("decisionrail.replay.items.processed").increment(outcome.evaluated() + outcome.failed());
            return new Cycle(claimed.jobId(), outcome.evaluated(), outcome.failed(), outcome.alreadyRecorded(), completed);
        } catch (RuntimeException failure) {
            log.error("Replay job {} failed: {}", claimed.jobId(), failure.getClass().getSimpleName(), failure);
            metrics.counter("decisionrail.replay.jobs.failed").increment();
            transactions.executeWithoutResult(status ->
                    store.failJob(claimed.jobId(), claimed.leaseToken(), failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            return new Cycle(claimed.jobId(), 0, 0, 0, false);
        }
    }

    private record BatchOutcome(int evaluated, int failed, int alreadyRecorded, boolean lostOwnership) {
        static BatchOutcome lost() { return new BatchOutcome(0, 0, 0, true); }
    }

    /**
     * Recomputes totals, counts remaining work, and finalises the job in one transaction, under the
     * job's ownership lock.
     *
     * <p>These were three separate statements, which is what allowed a job to be completed with
     * totals measured before another worker's results landed. Holding the lock across all of them
     * means nothing can write results for this job between the recomputation and the decision, so a
     * completed job's totals always describe its full set of durable results.
     *
     * @return true when this call completed the job
     */
    private boolean finalise(ReplayStore.ClaimedJob job) {
        Boolean completed = transactions.execute(status -> {
            if (!store.lockOwnedJob(job.jobId(), job.leaseToken())) {
                return null;
            }
            store.refreshAggregates(job.jobId(), job.leaseToken());
            // Rendezvous point for the takeover race: a test can hold one worker here, between
            // recomputing totals and deciding whether the job is finished. Inert in production.
            faults.awaitGate(DeliveryFaults.replayCompletionGate(workerId));
            if (store.pendingItemCount(job.jobId()) > 0) {
                store.releaseLease(job.jobId(), job.leaseToken());
                return false;
            }
            return store.completeJob(job.jobId(), job.leaseToken(), clock.instant());
        });
        if (completed == null) {
            log.warn("Replay job {} was taken over before finalisation; leaving it to its current owner", job.jobId());
            metrics.counter("decisionrail.replay.ownership.lost").increment();
            return false;
        }
        if (completed) {
            metrics.counter("decisionrail.replay.jobs.completed").increment();
            log.info("Replay job {} completed", job.jobId());
        }
        return completed;
    }

    private Cycle lostOwnership(ReplayStore.ClaimedJob job) {
        log.warn("Replay job {} was taken over by another worker; discarding this batch", job.jobId());
        metrics.counter("decisionrail.replay.ownership.lost").increment();
        return new Cycle(job.jobId(), 0, 0, 0, false);
    }

    private BatchOutcome processBatch(ReplayStore.ClaimedJob job) {
        // The snapshot is resolved once per batch. It is immutable, so every item in this job sees
        // exactly the same policy regardless of how many batches or restarts the job takes.
        var snapshot = policies.snapshot(job.candidateVersion());
        return transactions.execute(status -> {
            // Nothing is written unless this worker still owns the job. Without this an obsolete
            // owner could commit results and item transitions for a job another worker had taken
            // over, leaving totals and item state describing work nobody was authorised to do.
            if (!store.lockOwnedJob(job.jobId(), job.leaseToken())) {
                return BatchOutcome.lost();
            }
            List<ReplayItem> items = store.claimItems(job.jobId(), batchSize);
            int evaluated = 0;
            int failed = 0;
            int alreadyRecorded = 0;
            for (ReplayItem item : items) {
                boolean inserted;
                try {
                    DecisionInput input = new DecisionInput(item.amountMinor(), item.currency(), item.country());
                    long startedAt = System.nanoTime();
                    PolicyEvaluation evaluation = engine.evaluatePolicy(snapshot, input);
                    long elapsedNanos = System.nanoTime() - startedAt;
                    boolean diverged = !evaluation.outcome().name().equals(item.baselineOutcome());
                    inserted = store.recordResult(job.jobId(), item, evaluation.outcome().name(),
                            evaluation.score(), evaluation.rawScore(), evaluation.scoreCapped(),
                            encodeReasons(evaluation.reasons()), diverged, elapsedNanos, null);
                    store.markItem(job.jobId(), item.paymentId(), "DONE");
                    if (inserted) evaluated++;
                    else alreadyRecorded++;
                } catch (RuntimeException evaluationFailure) {
                    inserted = store.recordResult(job.jobId(), item, null, null, null, false, null,
                            false, 0, truncate(evaluationFailure.getClass().getSimpleName()));
                    store.markItem(job.jobId(), item.paymentId(), "FAILED");
                    if (inserted) failed++;
                    else alreadyRecorded++;
                }
            }
            // Held before this transaction commits, so a test can expire this worker's lease and
            // let another worker take the job over while these writes are still uncommitted.
            faults.awaitGate(DeliveryFaults.replayBatchCommitGate(workerId));
            return new BatchOutcome(evaluated, failed, alreadyRecorded, false);
        });
    }

    private String encodeReasons(List<ReasonContribution> reasons) {
        try {
            return json.writeValueAsString(reasons);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Could not encode candidate explanation", impossible);
        }
    }

    private static String truncate(String value) {
        return value == null ? "UNKNOWN" : value.length() <= 64 ? value : value.substring(0, 64);
    }
}
