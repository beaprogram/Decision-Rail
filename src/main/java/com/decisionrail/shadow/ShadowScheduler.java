package com.decisionrail.shadow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives the shadow worker on a timer. Disabled in tests so cycles are explicit. */
@Component
@ConditionalOnProperty(name = "app.shadow.enabled", havingValue = "true", matchIfMissing = true)
public class ShadowScheduler {
    private static final Logger log = LoggerFactory.getLogger(ShadowScheduler.class);

    private final ShadowWorker worker;

    public ShadowScheduler(ShadowWorker worker) {
        this.worker = worker;
        log.info("Shadow worker scheduled with id {} and batch size {}", worker.workerId(), worker.batchSize());
    }

    @Scheduled(fixedDelayString = "${app.shadow.poll-interval:500ms}", initialDelayString = "3s")
    void run() {
        try {
            ShadowWorker.Cycle cycle = worker.runOnce();
            if (!cycle.idle()) {
                log.debug("Shadow cycle {}", cycle);
            }
        } catch (RuntimeException failure) {
            log.error("Shadow cycle failed: {}", failure.getClass().getSimpleName(), failure);
        }
    }
}
