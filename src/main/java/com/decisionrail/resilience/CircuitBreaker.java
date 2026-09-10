package com.decisionrail.resilience;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * A small, deterministic circuit breaker for a single outbound dependency boundary.
 *
 * <p>It exists for one concrete purpose: stop the outbox dispatcher from spending its
 * whole send deadline on every queued event while the broker is unreachable. Without it,
 * a broker outage converts into sustained dispatcher threads blocked on socket timeouts,
 * a backlog that is retried far faster than it can drain, and attempt counters that burn
 * through the retry budget for events that never had a chance to be delivered.
 *
 * <p>What counts as a failure: a send that the producer reports as failed, and a send
 * whose acknowledgement does not arrive within the configured deadline. What does not
 * count: serialization or contract errors, which are defects in the event rather than
 * dependency faults and are marked terminal instead of retried.
 *
 * <h2>States</h2>
 * <ul>
 *   <li><b>CLOSED</b> - every send is attempted. Consecutive failures are counted;
 *       a success resets the count. Reaching {@code failureThreshold} opens the breaker.</li>
 *   <li><b>OPEN</b> - sends are rejected without touching the broker, so queued work is
 *       rescheduled cheaply instead of waiting on a dead socket. After
 *       {@code openDuration} has elapsed the next caller is promoted to HALF_OPEN.</li>
 *   <li><b>HALF_OPEN</b> - exactly one probe send is admitted at a time. A successful
 *       probe closes the breaker and clears the failure count. A failed probe returns to
 *       OPEN and restarts the wait, so recovery is never declared from a single lucky
 *       connection attempt while other callers keep hammering the dependency.</li>
 * </ul>
 *
 * <p>The clock is injected so state transitions are tested by advancing time rather than
 * by sleeping. All methods are synchronized; contention is one short critical section per
 * send, which is negligible next to a network round trip.
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final Duration openDuration;
    private final int probeSuccessesToClose;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private int probeSuccesses;
    private boolean probeInFlight;
    private Instant openedAt;
    private long rejectedCalls;
    private long openTransitions;

    public CircuitBreaker(String name, int failureThreshold, Duration openDuration, int probeSuccessesToClose, Clock clock) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("breaker name is required");
        if (failureThreshold < 1) throw new IllegalArgumentException("failureThreshold must be at least 1");
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration must be positive");
        }
        if (probeSuccessesToClose < 1) throw new IllegalArgumentException("probeSuccessesToClose must be at least 1");
        if (clock == null) throw new IllegalArgumentException("clock is required");
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.probeSuccessesToClose = probeSuccessesToClose;
        this.clock = clock;
    }

    public String name() { return name; }

    /**
     * Reserves permission to call the dependency.
     *
     * @return true when the call may proceed. A HALF_OPEN reservation is exclusive: the
     *         caller must report the outcome through {@link #recordSuccess()} or
     *         {@link #recordFailure()} so the probe slot is released.
     */
    public synchronized boolean tryAcquire() {
        if (state == State.OPEN && clock.instant().isAfter(openedAt.plus(openDuration))) {
            state = State.HALF_OPEN;
            probeSuccesses = 0;
            probeInFlight = false;
        }
        switch (state) {
            case CLOSED -> { return true; }
            case HALF_OPEN -> {
                if (probeInFlight) {
                    rejectedCalls++;
                    return false;
                }
                probeInFlight = true;
                return true;
            }
            default -> {
                rejectedCalls++;
                return false;
            }
        }
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {
            probeInFlight = false;
            if (++probeSuccesses >= probeSuccessesToClose) {
                state = State.CLOSED;
                probeSuccesses = 0;
                openedAt = null;
            }
        }
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            probeInFlight = false;
            open();
            return;
        }
        if (state == State.CLOSED && ++consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    /** Clears breaker state. Intended for operator-driven recovery and for test setup. */
    public synchronized void reset() {
        state = State.CLOSED;
        consecutiveFailures = 0;
        probeSuccesses = 0;
        probeInFlight = false;
        openedAt = null;
    }

    public synchronized State state() {
        if (state == State.OPEN && clock.instant().isAfter(openedAt.plus(openDuration))) {
            return State.HALF_OPEN;
        }
        return state;
    }

    public synchronized int consecutiveFailures() { return consecutiveFailures; }

    public synchronized long rejectedCalls() { return rejectedCalls; }

    public synchronized long openTransitions() { return openTransitions; }

    /** 0 = CLOSED, 1 = HALF_OPEN, 2 = OPEN. Bounded values keep this usable as a gauge. */
    public synchronized int stateCode() {
        return switch (state()) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
        };
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
        consecutiveFailures = failureThreshold;
        probeSuccesses = 0;
        probeInFlight = false;
        openTransitions++;
    }
}
