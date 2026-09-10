package com.decisionrail.resilience;

import com.decisionrail.events.DeliveryProperties;
import com.decisionrail.events.OutboxStore;
import java.time.Clock;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Reports asynchronous delivery capability, deliberately separate from liveness and readiness.
 *
 * <p>The three questions are genuinely different:
 * <ul>
 *   <li><b>liveness</b> - is this process alive and worth keeping.</li>
 *   <li><b>readiness</b> - should payment traffic arrive here. This includes the database,
 *       because no payment command can succeed without it, and excludes the broker, because
 *       payment commands succeed perfectly well while the broker is down.</li>
 *   <li><b>degraded asynchronous capability</b> - this indicator. Events are accumulating or
 *       terminally failing. The synchronous API is healthy and must stay in rotation, but an
 *       operator needs to know that downstream consumers are falling behind.</li>
 * </ul>
 * Collapsing the third into readiness would take a working payment API out of service because
 * of a broker outage, which is exactly the failure mode the outbox design exists to avoid.
 */
@Component("asyncDelivery")
public class AsyncDeliveryHealthIndicator implements HealthIndicator {
    public static final Status DEGRADED = new Status("DEGRADED", "Asynchronous event delivery is impaired");

    private final OutboxStore outbox;
    private final CircuitBreaker breaker;
    private final DeliveryProperties properties;
    private final Clock clock;

    public AsyncDeliveryHealthIndicator(OutboxStore outbox, CircuitBreaker brokerBreaker,
                                        DeliveryProperties properties, Clock clock) {
        this.outbox = outbox;
        this.breaker = brokerBreaker;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Health health() {
        long pending = outbox.countByStatus("PENDING") + outbox.countByStatus("CLAIMED");
        long failed = outbox.countByStatus("FAILED");
        long ageSeconds = outbox.oldestUndeliveredAgeSeconds(clock.instant());
        CircuitBreaker.State breakerState = breaker.state();

        boolean brokerImpaired = breakerState != CircuitBreaker.State.CLOSED;
        boolean backlogStale = ageSeconds > properties.backlog().degradedAge().toSeconds();
        boolean backlogLarge = pending > properties.backlog().degradedCount();
        boolean degraded = brokerImpaired || backlogStale || backlogLarge || failed > 0;

        return Health.status(degraded ? DEGRADED : Status.UP)
                .withDetail("brokerBreaker", breakerState.name())
                .withDetail("undeliveredEvents", pending)
                .withDetail("oldestUndeliveredAgeSeconds", ageSeconds)
                .withDetail("terminallyFailedEvents", failed)
                .withDetail("degradedAgeThresholdSeconds", properties.backlog().degradedAge().toSeconds())
                .withDetail("degradedCountThreshold", properties.backlog().degradedCount())
                .withDetail("paymentApiAffected", false)
                .build();
    }
}
