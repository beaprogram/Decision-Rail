package com.decisionrail.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Delivery, retry, and breaker configuration for the asynchronous event path.
 *
 * <p>Every bound value has a default so the application starts without broker tuning.
 * Bounds are validated in the constructors: a misconfigured batch size or deadline would
 * otherwise surface as an unbounded worker or a send that never gives up.
 */
@ConfigurationProperties(prefix = "app.events")
public record DeliveryProperties(
        @DefaultValue("decisionrail.payments.v1") String topic,
        @DefaultValue("decisionrail-projection") String projectionGroup,
        @DefaultValue("decisionrail-shadow") String shadowGroup,
        @DefaultValue Dispatcher dispatcher,
        @DefaultValue Breaker breaker,
        @DefaultValue Backlog backlog) {

    public DeliveryProperties {
        if (topic == null || !topic.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) {
            throw new IllegalArgumentException("app.events.topic must be a valid Kafka topic name");
        }
        if (projectionGroup == null || projectionGroup.isBlank() || shadowGroup == null || shadowGroup.isBlank()) {
            throw new IllegalArgumentException("consumer group names are required");
        }
        if (projectionGroup.equals(shadowGroup)) {
            throw new IllegalArgumentException("projection and shadow consumers must use distinct groups so one cannot starve the other");
        }
    }

    /**
     * @param batchSize      maximum events claimed per dispatch cycle; bounds memory and
     *                       the number of rows a single worker can hold a lease on.
     * @param sendTimeout    how long the dispatcher waits for a broker acknowledgement
     *                       before treating the send as failed. Scheduling a send is not
     *                       success; only an acknowledgement is.
     * @param leaseDuration  how long a claim is owned. A worker that dies leaves rows
     *                       claimed until the lease expires and another worker reclaims them.
     * @param maxAttempts    application-level attempt budget. On exhaustion the event
     *                       becomes terminally FAILED and blocks its payment's stream until
     *                       an operator redrives it.
     */
    public record Dispatcher(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("64") int batchSize,
            @DefaultValue("250ms") Duration pollInterval,
            @DefaultValue("30s") Duration leaseDuration,
            @DefaultValue("8") int maxAttempts,
            @DefaultValue("5s") Duration sendTimeout,
            @DefaultValue("200ms") Duration backoffBase,
            @DefaultValue("30s") Duration backoffCeiling,
            @DefaultValue("2.0") double backoffMultiplier,
            @DefaultValue("4") int sendConcurrency) {

        public Dispatcher {
            if (batchSize < 1 || batchSize > 1_000) throw new IllegalArgumentException("app.events.dispatcher.batch-size must be 1..1000");
            if (maxAttempts < 1 || maxAttempts > 100) throw new IllegalArgumentException("app.events.dispatcher.max-attempts must be 1..100");
            if (sendConcurrency < 1 || sendConcurrency > 64) throw new IllegalArgumentException("app.events.dispatcher.send-concurrency must be 1..64");
            requirePositive(pollInterval, "poll-interval");
            requirePositive(leaseDuration, "lease-duration");
            requirePositive(sendTimeout, "send-timeout");
            // Below this the derived Kafka client timeouts cannot satisfy both the broker's own
            // delivery.timeout >= linger + request.timeout rule and the requirement that the
            // client gives up before the application deadline.
            if (sendTimeout.toMillis() < 1_500) {
                throw new IllegalArgumentException("app.events.dispatcher.send-timeout must be at least 1500ms");
            }
            requirePositive(backoffBase, "backoff-base");
            requirePositive(backoffCeiling, "backoff-ceiling");
            if (backoffCeiling.compareTo(backoffBase) < 0) {
                throw new IllegalArgumentException("app.events.dispatcher.backoff-ceiling must be at least backoff-base");
            }
            if (leaseDuration.compareTo(sendTimeout) <= 0) {
                throw new IllegalArgumentException("lease-duration must exceed send-timeout so a live worker cannot lose its own claim mid-send");
            }
        }
    }

    public record Breaker(
            @DefaultValue("5") int failureThreshold,
            @DefaultValue("10s") Duration openDuration,
            @DefaultValue("1") int probeSuccessesToClose) {

        public Breaker {
            if (failureThreshold < 1 || failureThreshold > 1_000) throw new IllegalArgumentException("failure-threshold must be 1..1000");
            if (probeSuccessesToClose < 1 || probeSuccessesToClose > 100) throw new IllegalArgumentException("probe-successes-to-close must be 1..100");
            requirePositive(openDuration, "open-duration");
        }
    }

    /** Thresholds at which the asynchronous path reports itself degraded. */
    public record Backlog(
            @DefaultValue("120s") Duration degradedAge,
            @DefaultValue("1000") long degradedCount) {

        public Backlog {
            requirePositive(degradedAge, "degraded-age");
            if (degradedCount < 1) throw new IllegalArgumentException("degraded-count must be positive");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("app.events." + name + " must be a positive duration");
        }
    }
}
