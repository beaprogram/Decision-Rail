package com.decisionrail.shadow;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Evaluates queued shadow tasks against their pinned candidate policy.
 *
 * <h2>Isolation</h2>
 * This class cannot produce a financial effect. It does not depend on the payment service or the
 * payment store, so it has no way to reserve funds, capture, void, write a ledger entry, change a
 * stored decision, or append an outbox event. The only tables it writes are the shadow task queue
 * and the comparison table. An architecture test asserts those dependencies stay absent, because
 * the guarantee should not rest on nobody adding an import later.
 *
 * <h2>Independence from authorization</h2>
 * Nothing in the authorization path waits for this worker. Work arrives from committed events, so a
 * candidate that is slow, throwing, or stuck behind a restart changes only when comparisons appear.
 * A payment command cannot be delayed or failed by it.
 *
 * <h2>Bounds</h2>
 * Each cycle claims at most {@code app.shadow.batch-size} tasks and submits them to a fixed pool of
 * {@code app.shadow.concurrency} threads, waiting for the batch before claiming again. The queue is
 * therefore bounded by the batch size, and database usage is bounded by one short transaction per
 * task plus one claim per cycle.
 */
@Component
public class ShadowWorker {
    private static final Logger log = LoggerFactory.getLogger(ShadowWorker.class);

    private final ShadowStore store;
    private final PolicyService policies;
    private final DecisionEngine engine;
    private final DeliveryFaults faults;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration leaseDuration;
    private final Duration retryDelay;
    private final ExecutorService pool;
    private final String workerId;

    public ShadowWorker(ShadowStore store, PolicyService policies, DecisionEngine engine, DeliveryFaults faults,
                        TransactionTemplate transactions, ObjectMapper json, Clock clock, MeterRegistry metrics,
                        @Value("${app.shadow.batch-size:50}") int batchSize,
                        @Value("${app.shadow.concurrency:2}") int concurrency,
                        @Value("${app.shadow.max-attempts:3}") int maxAttempts,
                        @Value("${app.shadow.lease-duration:30s}") Duration leaseDuration,
                        @Value("${app.shadow.retry-delay:1s}") Duration retryDelay) {
        if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("app.shadow.batch-size must be 1..500");
        if (concurrency < 1 || concurrency > 16) throw new IllegalArgumentException("app.shadow.concurrency must be 1..16");
        if (maxAttempts < 1 || maxAttempts > 50) throw new IllegalArgumentException("app.shadow.max-attempts must be 1..50");
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("app.shadow.lease-duration must be positive");
        }
        if (retryDelay == null || retryDelay.isNegative()) {
            throw new IllegalArgumentException("app.shadow.retry-delay must not be negative");
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
        this.maxAttempts = maxAttempts;
        this.leaseDuration = leaseDuration;
        this.retryDelay = retryDelay;
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "shadow-eval-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.pool = Executors.newFixedThreadPool(concurrency, threads);
        this.workerId = "shadow/" + ProcessHandle.current().pid() + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String workerId() { return workerId; }

    public int batchSize() { return batchSize; }

    public record Cycle(int reclaimed, int claimed, int evaluated, int failed, int alreadyRecorded, int retried) {
        public boolean idle() { return claimed == 0 && reclaimed == 0; }
    }

    /** Runs one bounded cycle. Safe to run concurrently: claims use SKIP LOCKED and leases fence. */
    public Cycle runOnce() {
        Instant now = clock.instant();
        int reclaimed = orZero(transactions.execute(status -> store.reclaimExpiredLeases(now)));
        List<ShadowTask> claimed = transactions.execute(status ->
                store.claim(workerId, batchSize, now, now.plus(leaseDuration)));
        if (claimed == null || claimed.isEmpty()) {
            return new Cycle(reclaimed, 0, 0, 0, 0, 0);
        }
        List<Future<Outcome>> running = new ArrayList<>(claimed.size());
        for (ShadowTask task : claimed) {
            running.add(pool.submit(() -> evaluate(task)));
        }
        int evaluated = 0;
        int failed = 0;
        int alreadyRecorded = 0;
        int retried = 0;
        for (Future<Outcome> pending : running) {
            Outcome outcome;
            try {
                outcome = pending.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception unexpected) {
                log.error("Shadow evaluation task failed unexpectedly: {}", unexpected.getClass().getSimpleName());
                continue;
            }
            switch (outcome) {
                case EVALUATED -> evaluated++;
                case FAILED -> failed++;
                case ALREADY_RECORDED -> alreadyRecorded++;
                case RETRY_SCHEDULED -> retried++;
                case LEASE_LOST -> { }
            }
        }
        return new Cycle(reclaimed, claimed.size(), evaluated, failed, alreadyRecorded, retried);
    }

    private enum Outcome { EVALUATED, FAILED, ALREADY_RECORDED, RETRY_SCHEDULED, LEASE_LOST }

    private Outcome evaluate(ShadowTask task) {
        try {
            var snapshot = policies.snapshot(task.candidateVersion());
            // Injected delay and failure live here so slow and throwing candidates can be
            // exercised without a real misbehaving policy. Inert outside local and test config.
            faults.beforeShadowEvaluation(task.paymentId());
            DecisionInput input = new DecisionInput(task.amountMinor(), task.currency(), task.country());
            long startedAt = System.nanoTime();
            PolicyEvaluation evaluation = engine.evaluatePolicy(snapshot, input);
            long elapsedNanos = System.nanoTime() - startedAt;
            boolean diverged = !evaluation.outcome().name().equals(task.baselineOutcome());
            boolean inserted = orFalse(transactions.execute(status -> {
                boolean recorded = store.recordComparison(task, evaluation.outcome().name(), evaluation.score(),
                        evaluation.rawScore(), evaluation.scoreCapped(), encodeReasons(evaluation.reasons()),
                        diverged, elapsedNanos, null);
                store.finishTask(task.eventId(), task.leaseToken(), "DONE", null, clock.instant());
                return recorded;
            }));
            if (inserted) {
                metrics.counter("decisionrail.shadow.comparisons", "diverged", Boolean.toString(diverged)).increment();
                return Outcome.EVALUATED;
            }
            metrics.counter("decisionrail.shadow.duplicates").increment();
            return Outcome.ALREADY_RECORDED;
        } catch (RuntimeException failure) {
            return recordFailure(task, failure);
        }
    }

    private Outcome recordFailure(ShadowTask task, RuntimeException failure) {
        String detail = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        metrics.counter("decisionrail.shadow.failures").increment();
        if (task.attempts() >= maxAttempts) {
            // Terminal: record why, so a failed candidate is visible instead of silently missing.
            boolean recorded = orFalse(transactions.execute(status -> {
                boolean inserted = store.recordComparison(task, null, null, null, false, null, false, 0,
                        truncate(failure.getClass().getSimpleName()));
                store.finishTask(task.eventId(), task.leaseToken(), "FAILED", detail, clock.instant());
                return inserted;
            }));
            log.warn("Shadow evaluation for payment {} failed terminally after {} attempts: {}",
                    task.paymentId(), task.attempts(), failure.getClass().getSimpleName());
            return recorded ? Outcome.FAILED : Outcome.ALREADY_RECORDED;
        }
        boolean scheduled = orFalse(transactions.execute(status ->
                store.scheduleRetry(task.eventId(), task.leaseToken(), clock.instant().plus(retryDelay), detail)));
        return scheduled ? Outcome.RETRY_SCHEDULED : Outcome.LEASE_LOST;
    }

    private String encodeReasons(List<ReasonContribution> reasons) {
        try {
            return json.writeValueAsString(reasons);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Could not encode candidate explanation", impossible);
        }
    }

    private static int orZero(Integer value) { return value == null ? 0 : value; }

    private static boolean orFalse(Boolean value) { return Boolean.TRUE.equals(value); }

    private static String truncate(String value) {
        return value == null ? "UNKNOWN" : value.length() <= 64 ? value : value.substring(0, 64);
    }
}
