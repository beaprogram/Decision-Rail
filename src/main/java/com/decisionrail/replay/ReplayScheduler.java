package com.decisionrail.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the replay worker on a timer.
 *
 * <p>Separate from {@link ReplayWorker} so tests can run one deterministic batch with the timer
 * off, which is what makes the interrupt-and-resume checks reproducible.
 */
@Component
@ConditionalOnProperty(name = "app.replay.enabled", havingValue = "true", matchIfMissing = true)
public class ReplayScheduler {
    private static final Logger log = LoggerFactory.getLogger(ReplayScheduler.class);

    private final ReplayWorker worker;

    public ReplayScheduler(ReplayWorker worker) {
        this.worker = worker;
        log.info("Replay worker scheduled with id {} and batch size {}", worker.workerId(), worker.batchSize());
    }

    /** Fixed delay so a slow batch cannot queue overlapping batches behind it. */
    @Scheduled(fixedDelayString = "${app.replay.poll-interval:500ms}", initialDelayString = "3s")
    void run() {
        try {
            ReplayWorker.Cycle cycle = worker.runOnce();
            if (cycle.didWork()) {
                log.debug("Replay cycle {}", cycle);
            }
        } catch (RuntimeException failure) {
            log.error("Replay cycle failed: {}", failure.getClass().getSimpleName(), failure);
        }
    }
}
