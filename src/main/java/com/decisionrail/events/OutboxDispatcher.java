package com.decisionrail.events;

import com.decisionrail.resilience.Backoff;
import com.decisionrail.resilience.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves committed outbox rows to the broker.
 *
 * <p>The cycle is deliberately three separate transactions:
 * <ol>
 *   <li>claim a bounded batch and commit the lease and attempt count,</li>
 *   <li>send to the broker with no database transaction open,</li>
 *   <li>record the outcome under the lease token.</li>
 * </ol>
 * Step 2 must not run inside step 1: holding a transaction - and therefore row locks on
 * rows a payment command may touch - across a broker round trip is how an asynchronous
 * dependency turns into synchronous payment latency. This split is what keeps payment
 * commands working while the broker is down.
 *
 * <p>The window between a broker acknowledgement and step 3 is real and unavoidable
 * without a distributed transaction. A crash inside it leaves a claimed row that is later
 * reclaimed and sent again, which is why the guarantee is at-least-once delivery with
 * idempotent consumer effects, not exactly-once processing.
 */
@Component
public class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxStore store;
    private final EventPublisher publisher;
    private final DeliveryProperties properties;
    private final DeliveryFaults faults;
    private final CircuitBreaker breaker;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Backoff backoff;
    private final ExecutorService sendPool;
    private final String workerId;

    public OutboxDispatcher(OutboxStore store, EventPublisher publisher, DeliveryProperties properties,
                            DeliveryFaults faults, CircuitBreaker brokerBreaker, TransactionTemplate transactions,
                            Clock clock, MeterRegistry metrics) {
        this.store = store;
        this.publisher = publisher;
        this.properties = properties;
        this.faults = faults;
        this.breaker = brokerBreaker;
        this.transactions = transactions;
        this.clock = clock;
        this.metrics = metrics;
        this.backoff = new Backoff(properties.dispatcher().backoffBase(), properties.dispatcher().backoffCeiling(),
                properties.dispatcher().backoffMultiplier(), new java.util.Random());
        this.workerId = buildWorkerId();
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "outbox-send-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.sendPool = Executors.newFixedThreadPool(properties.dispatcher().sendConcurrency(), threads);
    }

    public String workerId() { return workerId; }

    /** Outcome of one dispatch cycle. Used by operators, metrics, and tests. */
    public record Cycle(int reclaimed, int claimed, int published, int retried, int failed,
                        int leaseLost, int shortCircuited, boolean skippedWhileBreakerOpen) {
        public boolean idle() { return claimed == 0 && reclaimed == 0; }
    }

    /**
     * Runs exactly one bounded cycle. Safe to run concurrently in several processes: claims
     * use SKIP LOCKED and completions are fenced by lease token.
     */
    public Cycle dispatchOnce() {
        Instant now = clock.instant();
        int reclaimed = transactions.execute(status -> store.reclaimExpiredLeases(now));
        if (reclaimed > 0) {
            metrics.counter("decisionrail.outbox.leases.reclaimed").increment(reclaimed);
            log.info("Reclaimed {} outbox events from expired leases", reclaimed);
        }
        // While the breaker is fully open nothing is claimed at all. Claiming a batch only to
        // reject every send would churn leases and attempt counters for work that is not going
        // to be attempted. The backlog simply ages, which is what the degraded health signal and
        // the backlog-age metric are for. A half-open breaker does claim normally: its probe gate
        // admits one real send and the rest are rescheduled without cost.
        if (breaker.state() == CircuitBreaker.State.OPEN) {
            metrics.counter("decisionrail.outbox.cycle.skipped").increment();
            return new Cycle(reclaimed, 0, 0, 0, 0, 0, 0, true);
        }
        List<ClaimedEvent> claimed = transactions.execute(status -> store.claim(
                workerId, properties.dispatcher().batchSize(), now, now.plus(properties.dispatcher().leaseDuration())));
        if (claimed == null || claimed.isEmpty()) {
            return new Cycle(reclaimed, 0, 0, 0, 0, 0, 0, false);
        }
        // Every task in this batch is one claimed row, so the queue is bounded by batch size
        // and the dispatcher never claims again until the whole batch has been resolved.
        List<Future<Outcome>> running = new ArrayList<>(claimed.size());
        for (ClaimedEvent event : claimed) {
            running.add(sendPool.submit(() -> deliver(event)));
        }
        int published = 0;
        int retried = 0;
        int failed = 0;
        int leaseLost = 0;
        int shortCircuited = 0;
        for (Future<Outcome> pending : running) {
            Outcome outcome;
            try {
                outcome = pending.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception unexpected) {
                log.error("Outbox delivery task failed unexpectedly: {}", unexpected.getClass().getSimpleName());
                continue;
            }
            switch (outcome) {
                case PUBLISHED -> published++;
                case RETRY_SCHEDULED -> retried++;
                case TERMINALLY_FAILED -> failed++;
                case LEASE_LOST -> leaseLost++;
                case SHORT_CIRCUITED -> shortCircuited++;
            }
        }
        return new Cycle(reclaimed, claimed.size(), published, retried, failed, leaseLost, shortCircuited, false);
    }

    private enum Outcome { PUBLISHED, RETRY_SCHEDULED, TERMINALLY_FAILED, LEASE_LOST, SHORT_CIRCUITED }

    private Outcome deliver(ClaimedEvent event) {
        EventPublisher.Acknowledgement acknowledgement;
        try {
            acknowledgement = publisher.publish(event);
        } catch (BrokerSendException failure) {
            return recordFailure(event, failure);
        } catch (RuntimeException unexpected) {
            return recordFailure(event, new BrokerSendException(
                    "Unexpected publish failure: " + unexpected.getClass().getSimpleName(), true, unexpected));
        }
        try {
            // Failpoint for the acknowledged-but-not-recorded window. Inert in production.
            faults.afterAcknowledgement(event.aggregateId());
        } catch (DeliveryFaults.SimulatedWorkerCrash crash) {
            log.warn("Injected crash after acknowledgement for event {}; the row stays claimed until its lease expires", event.id());
            return Outcome.LEASE_LOST;
        }
        boolean recorded = Boolean.TRUE.equals(transactions.execute(status -> store.markPublished(
                event.id(), event.leaseToken(), clock.instant(), acknowledgement.partition(), acknowledgement.offset())));
        if (!recorded) {
            // The send really happened; another worker now owns the row and will send again.
            metrics.counter("decisionrail.outbox.lease.lost").increment();
            log.warn("Lease lost for event {} after acknowledgement; a duplicate delivery is expected", event.id());
            return Outcome.LEASE_LOST;
        }
        metrics.counter("decisionrail.outbox.published").increment();
        return Outcome.PUBLISHED;
    }

    private Outcome recordFailure(ClaimedEvent event, BrokerSendException failure) {
        String detail = failure.getMessage();
        if (failure.shortCircuited()) {
            // Never contacted the broker: reschedule and refund the optimistic attempt.
            Instant retryAt = clock.instant().plus(backoff.delayFor(1));
            boolean refunded = Boolean.TRUE.equals(transactions.execute(status ->
                    store.scheduleRetryAfterShortCircuit(event.id(), event.leaseToken(), retryAt, detail)));
            return refunded ? Outcome.SHORT_CIRCUITED : Outcome.LEASE_LOST;
        }
        boolean budgetExhausted = !failure.retryable() || event.attempts() >= properties.dispatcher().maxAttempts();
        if (budgetExhausted) {
            boolean recorded = Boolean.TRUE.equals(transactions.execute(status ->
                    store.markFailed(event.id(), event.leaseToken(), detail)));
            if (!recorded) return Outcome.LEASE_LOST;
            metrics.counter("decisionrail.outbox.failed.terminal").increment();
            log.error("Event {} for payment {} is terminally failed after {} attempts; payment stream is blocked until redrive",
                    event.id(), event.aggregateId(), event.attempts());
            return Outcome.TERMINALLY_FAILED;
        }
        Instant nextAttemptAt = clock.instant().plus(backoff.delayFor(event.attempts()));
        boolean recorded = Boolean.TRUE.equals(transactions.execute(status ->
                store.scheduleRetry(event.id(), event.leaseToken(), nextAttemptAt, detail)));
        if (!recorded) return Outcome.LEASE_LOST;
        metrics.counter("decisionrail.outbox.retry.scheduled").increment();
        return Outcome.RETRY_SCHEDULED;
    }

    /** Operator-facing backlog snapshot including the breaker state protecting the broker. */
    public OutboxBacklog backlog() {
        return store.backlog(clock.instant(), breaker.state().name());
    }

    public List<UUID> redrive(String merchantId, UUID aggregateId, List<UUID> eventIds, int limit) {
        List<UUID> redriven = transactions.execute(status ->
                store.redrive(merchantId, aggregateId, eventIds, clock.instant(), limit));
        List<UUID> result = redriven == null ? List.of() : redriven;
        if (!result.isEmpty()) {
            metrics.counter("decisionrail.outbox.redriven").increment(result.size());
            log.info("Redrove {} failed outbox events", result.size());
        }
        return result;
    }

    /** Exposed so a test can recover a crashed worker's claim at an explicit instant. */
    public int reclaimExpiredLeases(Instant asOf) {
        Integer reclaimed = transactions.execute(status -> store.reclaimExpiredLeases(asOf));
        return reclaimed == null ? 0 : reclaimed;
    }

    private static String buildWorkerId() {
        String host;
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception unavailable) {
            host = "unknown-host";
        }
        String candidate = host + "/" + ProcessHandle.current().pid() + "/" + UUID.randomUUID().toString().substring(0, 8);
        return candidate.length() <= 64 ? candidate : candidate.substring(candidate.length() - 64);
    }
}
