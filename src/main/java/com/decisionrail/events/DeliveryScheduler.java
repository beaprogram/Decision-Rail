package com.decisionrail.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the dispatcher on a timer.
 *
 * <p>Separate from {@link OutboxDispatcher} so tests can run exactly one deterministic cycle
 * with the timer switched off, instead of racing a background thread. Setting
 * {@code app.events.dispatcher.enabled=false} removes this bean and nothing else.
 */
@Component
@ConditionalOnProperty(name = "app.events.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
public class DeliveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduler.class);

    private final OutboxDispatcher dispatcher;

    public DeliveryScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        log.info("Outbox dispatcher scheduled with worker id {}", dispatcher.workerId());
    }

    /**
     * Fixed delay, not fixed rate: a slow cycle must not queue overlapping cycles behind it.
     * Each cycle claims at most one bounded batch, which is the dispatcher's backpressure.
     */
    @Scheduled(fixedDelayString = "${app.events.dispatcher.poll-interval:250ms}", initialDelayString = "2s")
    void dispatch() {
        try {
            OutboxDispatcher.Cycle cycle = dispatcher.dispatchOnce();
            if (!cycle.idle()) {
                log.debug("Dispatch cycle {}", cycle);
            }
        } catch (RuntimeException failure) {
            // Never let a failed cycle kill the schedule; the next cycle retries from durable state.
            log.error("Outbox dispatch cycle failed: {}", failure.getClass().getSimpleName(), failure);
        }
    }
}
