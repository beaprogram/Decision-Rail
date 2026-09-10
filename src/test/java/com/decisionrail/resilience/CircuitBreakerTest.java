package com.decisionrail.resilience;

import com.decisionrail.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The breaker's state machine, verified by advancing an injected clock rather than sleeping. */
class CircuitBreakerTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-10T00:00:00Z"));
    private final CircuitBreaker breaker = new CircuitBreaker("test", 3, Duration.ofSeconds(10), 1, clock);

    @Test
    void closedAdmitsEveryCallAndASuccessClearsTheFailureCount() {
        for (int call = 0; call < 20; call++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordSuccess();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Failures below the threshold do not open it, and a success resets the run.
        breaker.tryAcquire();
        breaker.recordFailure();
        breaker.tryAcquire();
        breaker.recordFailure();
        assertThat(breaker.consecutiveFailures()).isEqualTo(2);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        breaker.tryAcquire();
        breaker.recordSuccess();
        assertThat(breaker.consecutiveFailures()).isZero();

        // Only consecutive failures reaching the threshold open it.
        for (int failure = 0; failure < 3; failure++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.openTransitions()).isEqualTo(1);
    }

    @Test
    void openRejectsWithoutCallingTheDependencyUntilTheWaitHasFullyElapsed() {
        open();
        long rejectedBefore = breaker.rejectedCalls();
        for (int call = 0; call < 5; call++) {
            assertThat(breaker.tryAcquire()).isFalse();
        }
        assertThat(breaker.rejectedCalls()).isEqualTo(rejectedBefore + 5);

        // Still open right up to the boundary: the transition is not early.
        clock.advance(Duration.ofSeconds(10));
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();

        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void halfOpenAdmitsExactlyOneProbeAtATime() {
        open();
        clock.advance(Duration.ofSeconds(11));

        assertThat(breaker.tryAcquire()).as("first caller becomes the probe").isTrue();
        assertThat(breaker.tryAcquire()).as("no second concurrent probe").isFalse();
        assertThat(breaker.tryAcquire()).isFalse();

        // A successful probe closes the breaker and releases the probe slot.
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.consecutiveFailures()).isZero();
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aFailedProbeReopensAndRestartsTheWaitInsteadOfDeclaringRecovery() {
        open();
        clock.advance(Duration.ofSeconds(11));
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.openTransitions()).isEqualTo(2);
        // The wait restarts from the failed probe, not from the original opening.
        clock.advance(Duration.ofSeconds(10));
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // And this time the probe succeeds.
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void severalProbeSuccessesCanBeRequiredBeforeClosing() {
        CircuitBreaker cautious = new CircuitBreaker("cautious", 1, Duration.ofSeconds(5), 2, clock);
        cautious.tryAcquire();
        cautious.recordFailure();
        assertThat(cautious.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofSeconds(6));
        cautious.tryAcquire();
        cautious.recordSuccess();
        assertThat(cautious.state()).as("one success is not enough").isEqualTo(CircuitBreaker.State.HALF_OPEN);
        cautious.tryAcquire();
        cautious.recordSuccess();
        assertThat(cautious.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void stateCodeIsBoundedSoItIsSafeAsAGauge() {
        assertThat(breaker.stateCode()).isZero();
        open();
        assertThat(breaker.stateCode()).isEqualTo(2);
        clock.advance(Duration.ofSeconds(11));
        assertThat(breaker.stateCode()).isEqualTo(1);
        breaker.reset();
        assertThat(breaker.stateCode()).isZero();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void invalidConfigurationIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new CircuitBreaker("", 1, Duration.ofSeconds(1), 1, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreaker("n", 0, Duration.ofSeconds(1), 1, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreaker("n", 1, Duration.ZERO, 1, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreaker("n", 1, Duration.ofSeconds(1), 0, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreaker("n", 1, Duration.ofSeconds(1), 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void open() {
        for (int failure = 0; failure < 3; failure++) {
            breaker.tryAcquire();
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
