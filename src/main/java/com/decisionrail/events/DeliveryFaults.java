package com.decisionrail.events;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    private static final Duration GATE_TIMEOUT = Duration.ofSeconds(60);

    private final boolean enabled;

    /**
     * Bounded rendezvous points, armed by a test and opened by it explicitly.
     *
     * <p>A timed sleep cannot express "pause worker A here, let worker B take the job over, then
     * resume A", which is the only way to reproduce a takeover race deterministically. Labels are
     * scoped by worker id so two workers running the same task can be held independently.
     */
    private final Map<String, CountDownLatch> gates = new ConcurrentHashMap<>();
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

    /** Arms a gate. The next production call to {@link #awaitGate} with this label blocks. */
    public void hold(String label) {
        requireEnabled();
        gates.computeIfAbsent(label, key -> new CountDownLatch(1));
    }

    /** Opens a gate, releasing whatever is waiting on it. */
    public void release(String label) {
        CountDownLatch gate = gates.remove(label);
        if (gate != null) gate.countDown();
    }

    public boolean isHeld(String label) {
        CountDownLatch gate = gates.get(label);
        return gate != null && gate.getCount() > 0;
    }

    /**
     * Blocks while a gate with this label is armed. Inert when fault injection is disabled, so no
     * production path can ever wait here. The wait is bounded so a test that forgets to open a gate
     * fails instead of hanging the build.
     */
    public void awaitGate(String label) {
        if (!enabled) return;
        CountDownLatch gate = gates.get(label);
        if (gate == null) return;
        try {
            if (!gate.await(GATE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Injected gate " + label + " was never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting on injected gate " + label, interrupted);
        }
    }

    public void beforeShadowEvaluation(String workerId, UUID paymentId) {
        if (!enabled) return;
        awaitGate(shadowGate(workerId));
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

    /** Label for holding one shadow worker inside its evaluation step. */
    public static String shadowGate(String workerId) { return "shadow-evaluate:" + workerId; }

    /** Label for holding one replay worker just before its batch results commit. */
    public static String replayBatchCommitGate(String workerId) { return "replay-batch-commit:" + workerId; }

    /** Label for holding one replay worker between recomputing totals and deciding completion. */
    public static String replayCompletionGate(String workerId) { return "replay-completion:" + workerId; }

    /**
     * Label for holding one replay worker after its batch has committed and before it finalises,
     * which is the only window in which another worker can legitimately take the job over.
     */
    public static String replayHandoverGate(String workerId) { return "replay-handover:" + workerId; }

    /**
     * Disarms everything, in the one order that is safe.
     *
     * <h2>Why the order is part of the contract</h2>
     * Releasing a gate wakes a worker that is parked inside a failpoint, and that worker's next act is
     * to read the injected failure state. Releasing first therefore hands it a fault set that has not
     * been cleared yet: a test that disarms everything so a worker can finally succeed can instead
     * watch it fail on the way out. That is not a theoretical window. Against the real class, a worker
     * parked at a gate and released by {@code clear()} observed an armed failure on attempt 21 of 50,
     * which is what made the shadow takeover test fail in CI while passing on a rerun.
     *
     * <p>Disarming first closes it completely rather than narrowing it. {@link CountDownLatch#countDown()}
     * happens-before the return from {@link CountDownLatch#await()}, so every write below is guaranteed
     * visible to the released worker. The fix is an ordering guarantee, not a smaller race.
     *
     * <p>{@link #release} deliberately does not disarm anything. Releasing one worker while a fault
     * stays armed is exactly how the stale-worker path is exercised, and folding the two together would
     * remove the ability to express it.
     */
    public void clear() {
        disarmInjectedFailures();
        releaseAllGates();
    }

    /** Turns off every injected failure and delay. Must run before {@link #releaseAllGates}. */
    void disarmInjectedFailures() {
        crashAfterAcknowledgement.clear();
        rejectSends.clear();
        failShadowEvaluation.clear();
        shadowEvaluationDelayMillis = 0;
    }

    /** Opens every armed gate. Must run after {@link #disarmInjectedFailures}. */
    void releaseAllGates() {
        gates.keySet().forEach(this::release);
        gates.clear();
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
