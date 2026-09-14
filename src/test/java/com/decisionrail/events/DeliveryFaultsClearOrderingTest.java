package com.decisionrail.events;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a worker sees when a test disarms fault injection while that worker is parked at a gate.
 *
 * <p>This is the ordering the shadow takeover test depends on. It holds two workers at gates, lets the
 * stale one fail terminally, and then clears every fault so the rightful owner can finish successfully.
 * If clearing releases the gates before it disarms the failures, the rightful owner wakes into a fault
 * set that is still armed and fails too - and the test sees a cycle that evaluated nothing.
 */
class DeliveryFaultsClearOrderingTest {
    private static final int ATTEMPTS = 50;

    @Test
    void aWorkerReleasedByClearNeverObservesAFaultThatClearIsAboutToDisarm() throws Exception {
        DeliveryFaults faults = new DeliveryFaults(true);

        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            UUID payment = UUID.randomUUID();
            String worker = "worker-" + attempt;
            faults.hold(DeliveryFaults.shadowGate(worker));
            faults.failShadowEvaluationFor(payment);

            AtomicReference<Throwable> observed = new AtomicReference<>();
            // A real rendezvous, not an assumption. The gate being armed says nothing about whether the
            // worker has reached it, and releasing before it arrives would test a different ordering.
            Thread parked = parkedWorker(faults, worker, payment, observed);
            waitUntilParkedOnTheGate(parked);

            faults.clear();

            parked.join(TimeUnit.SECONDS.toMillis(5));
            assertThat(parked.isAlive()).as("the worker must be released by clear()").isFalse();
            assertThat(observed.get())
                    .as("attempt %d: a worker released by clear() saw a fault clear() was about to disarm", attempt)
                    .isNull();
        }
    }

    @Test
    void theOppositeOrderFailsEveryTimeUnderTheSameInterleaving() throws Exception {
        DeliveryFaults faults = new DeliveryFaults(true);
        UUID payment = UUID.randomUUID();
        String worker = "worker-old-order";
        faults.hold(DeliveryFaults.shadowGate(worker));
        faults.failShadowEvaluationFor(payment);

        AtomicReference<Throwable> observed = new AtomicReference<>();
        Thread parked = parkedWorker(faults, worker, payment, observed);
        waitUntilParkedOnTheGate(parked);

        // The order clear() used to use, composed here from the real methods rather than a copy of
        // them, with the interleaving forced rather than hoped for: the worker is released and run to
        // completion before anything is disarmed.
        faults.releaseAllGates();
        parked.join(TimeUnit.SECONDS.toMillis(5));
        faults.disarmInjectedFailures();

        assertThat(observed.get())
                .as("releasing before disarming hands the worker a fault that was about to be cleared")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Simulated candidate policy evaluation failure");
    }

    @Test
    void anArmedFailureStillFiresWhenAWorkerIsReleasedDeliberately() throws Exception {
        DeliveryFaults faults = new DeliveryFaults(true);
        UUID payment = UUID.randomUUID();
        String worker = "worker-still-fails";
        faults.hold(DeliveryFaults.shadowGate(worker));
        faults.failShadowEvaluationFor(payment);

        AtomicReference<Throwable> observed = new AtomicReference<>();
        Thread parked = parkedWorker(faults, worker, payment, observed);
        waitUntilParkedOnTheGate(parked);

        // Releasing one gate is how the stale-worker path is expressed: that worker is meant to wake
        // into an armed failure and fail. Only clear() means "everything off", and the two must stay
        // distinguishable or the takeover interleaving cannot be written at all.
        faults.release(DeliveryFaults.shadowGate(worker));
        parked.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(observed.get())
                .as("a deliberate release must not disarm the fault")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clearDisarmsTheOtherInjectedFailuresToo() {
        DeliveryFaults faults = new DeliveryFaults(true);
        UUID aggregate = UUID.randomUUID();
        faults.crashAfterAcknowledgementFor(aggregate);
        faults.rejectSendsFor(aggregate);
        faults.setShadowEvaluationDelayMillis(50);

        faults.clear();

        // These are read without a gate in front of them, so they were never exposed to the ordering
        // problem; asserting them keeps clear() honest about meaning "everything off".
        faults.afterAcknowledgement(aggregate);
        faults.beforeSend(aggregate);

        // The delay is checked by interrupting the calling thread rather than by timing the call. An
        // armed delay reaches Thread.sleep, which throws immediately when the interrupt flag is already
        // set; a disarmed one never calls sleep at all and returns with the flag untouched. That
        // distinguishes the two states from each other rather than from a wall-clock threshold, so a
        // descheduled machine cannot fail it and a delay short enough to fit under the threshold
        // cannot pass it.
        Thread.currentThread().interrupt();
        try {
            assertThatNoException()
                    .as("a disarmed delay must not sleep, so the interrupt must go unnoticed")
                    .isThrownBy(() -> faults.beforeShadowEvaluation("any-worker", aggregate));
            assertThat(Thread.currentThread().isInterrupted())
                    .as("nothing should have consumed the interrupt, because nothing should have waited")
                    .isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Proves the check above can fail, so its passing means something.
     *
     * <p>An assertion that an interrupt survives a call is worth nothing unless an armed delay actually
     * consumes it. This arms one and shows that the same call then fails instead.
     */
    @Test
    void theDisarmedDelayCheckWouldFailIfTheDelayWereStillArmed() {
        DeliveryFaults faults = new DeliveryFaults(true);
        UUID aggregate = UUID.randomUUID();
        faults.setShadowEvaluationDelayMillis(50);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> faults.beforeShadowEvaluation("any-worker", aggregate))
                    .as("an armed delay reaches Thread.sleep, which refuses to run on an interrupted thread")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Interrupted during injected shadow delay");
        } finally {
            Thread.interrupted();
        }
    }

    /** Starts a worker that parks in the failpoint and records whatever it comes out with. */
    private static Thread parkedWorker(DeliveryFaults faults, String worker, UUID payment,
                                       AtomicReference<Throwable> observed) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            started.countDown();
            try {
                faults.beforeShadowEvaluation(worker, payment);
            } catch (Throwable failure) {
                observed.set(failure);
            }
        }, "parked-worker");
        // A daemon so a failed assertion cannot leave it holding the build open; the joins below are
        // what actually reclaim it on the passing path.
        thread.setDaemon(true);
        thread.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        return thread;
    }

    /**
     * Waits until the thread is actually blocked inside the gate rather than merely started.
     *
     * <p>{@code beforeShadowEvaluation} parks on a {@link CountDownLatch}, so the thread sits in
     * TIMED_WAITING while it is held. Polling for that state is what makes the interleaving controlled:
     * without it the release could land before the worker ever reached the gate, which exercises
     * nothing.
     */
    private static void waitUntilParkedOnTheGate(Thread worker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread.State state = worker.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("the worker never reached the gate");
    }
}
