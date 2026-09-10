package com.decisionrail.events;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Typed, local-only failpoints used to exercise delivery failure windows deterministically.
 *
 * <p>Every switch is inert unless {@code app.events.fault-injection-enabled} is true, which is
 * set only by the test profile and the documented local fault demo. There is no HTTP surface:
 * nothing here accepts a command, a host, or a path, so it cannot be used to run arbitrary work
 * or to point at infrastructure it was not compiled against.
 *
 * <p>These exist because the interesting failures are races that sleeping cannot reproduce
 * reliably: a broker acknowledgement followed by a worker crash before the outbox row is
 * updated, and a consumer commit followed by a crash before the offset is acknowledged.
 *
 * <p>Faults are scoped to specific payments rather than applied globally. A dispatcher cycle
 * claims whatever is due across the whole outbox, so a global switch would fail unrelated
 * events and make a test's observations depend on what other tests happened to leave behind.
 * Targeting one payment also matches the property most worth proving: one payment's stream can
 * fail terminally while every other payment keeps draining.
 */
@Component
public class DeliveryFaults {
    private final boolean enabled;

    private final Set<UUID> crashAfterAcknowledgement = ConcurrentHashMap.newKeySet();
    private final Set<UUID> rejectSends = ConcurrentHashMap.newKeySet();
    private volatile long shadowEvaluationDelayMillis;
    private final Set<UUID> failShadowEvaluation = ConcurrentHashMap.newKeySet();

    public DeliveryFaults(@Value("${app.events.fault-injection-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    /** Simulates a worker that dies between broker acknowledgement and its outbox update. */
    public void afterAcknowledgement(UUID aggregateId) {
        if (enabled && crashAfterAcknowledgement.contains(aggregateId)) {
            throw new SimulatedWorkerCrash("Simulated worker crash after broker acknowledgement");
        }
    }

    /** Simulates a send that never reaches the broker, without reconfiguring the client. */
    public void beforeSend(UUID aggregateId) {
        if (enabled && rejectSends.contains(aggregateId)) {
            throw new BrokerSendException("Simulated broker unavailability for payment " + aggregateId, true);
        }
    }

    public void beforeShadowEvaluation(UUID paymentId) {
        if (!enabled) return;
        long delay = shadowEvaluationDelayMillis;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during injected shadow delay", interrupted);
            }
        }
        if (failShadowEvaluation.contains(paymentId)) {
            throw new IllegalStateException("Simulated candidate policy evaluation failure");
        }
    }

    public void crashAfterAcknowledgementFor(UUID aggregateId) {
        requireEnabled();
        crashAfterAcknowledgement.add(aggregateId);
    }

    public void stopCrashingAfterAcknowledgementFor(UUID aggregateId) {
        crashAfterAcknowledgement.remove(aggregateId);
    }

    public void rejectSendsFor(UUID aggregateId) {
        requireEnabled();
        rejectSends.add(aggregateId);
    }

    public void allowSendsFor(UUID aggregateId) {
        rejectSends.remove(aggregateId);
    }

    public void failShadowEvaluationFor(UUID paymentId) {
        requireEnabled();
        failShadowEvaluation.add(paymentId);
    }

    public void setShadowEvaluationDelayMillis(long value) {
        if (value < 0) throw new IllegalArgumentException("delay must not be negative");
        if (value > 0) requireEnabled();
        this.shadowEvaluationDelayMillis = value;
    }

    public void clear() {
        crashAfterAcknowledgement.clear();
        rejectSends.clear();
        failShadowEvaluation.clear();
        shadowEvaluationDelayMillis = 0;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new IllegalStateException(
                    "Fault injection is disabled; set app.events.fault-injection-enabled only in local or test configuration");
        }
    }

    /** Thrown by an injected failpoint; never produced by normal operation. */
    public static class SimulatedWorkerCrash extends RuntimeException {
        public SimulatedWorkerCrash(String message) { super(message); }
    }
}
