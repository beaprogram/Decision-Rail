package com.decisionrail.resilience;

import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Backoff growth, jitter bounds, and the published worst-case retry budget. */
class BackoffTest {
    private final Backoff backoff = new Backoff(Duration.ofMillis(200), Duration.ofSeconds(30), 2.0, new Random(42));

    @Test
    void delaysGrowExponentiallyAndStayWithinTheirJitterWindow() {
        // Full jitter: every delay lies in [base, min(ceiling, base * multiplier^(attempt-1))].
        for (int attempt = 1; attempt <= 12; attempt++) {
            long upperMillis = Math.min(30_000L, (long) (200 * Math.pow(2.0, attempt - 1)));
            for (int sample = 0; sample < 200; sample++) {
                Duration delay = backoff.delayFor(attempt);
                assertThat(delay.toMillis())
                        .as("attempt %d must stay within [200, %d]", attempt, upperMillis)
                        .isBetween(Math.min(200L, upperMillis), upperMillis);
            }
        }
    }

    @Test
    void theCeilingCapsGrowthSoLongOutagesDoNotProduceUnboundedWaits() {
        for (int sample = 0; sample < 100; sample++) {
            assertThat(backoff.delayFor(40).toMillis()).isLessThanOrEqualTo(30_000);
        }
    }

    @Test
    void jitterSpreadsRetriesSoABacklogDoesNotRetryInSynchronisedWaves() {
        Set<Long> observed = new HashSet<>();
        for (int sample = 0; sample < 200; sample++) {
            observed.add(backoff.delayFor(8).toMillis());
        }
        // Without jitter every event in a backlog would retry at the same instant.
        assertThat(observed).as("a fixed delay would collapse to one value").hasSizeGreaterThan(50);
    }

    @Test
    void theWorstCaseTotalDocumentsTheRetryBudget() {
        // Deterministic and independent of jitter: the sum of each attempt's upper bound.
        Duration budget = backoff.worstCaseTotal(8);
        long expected = 200 + 400 + 800 + 1_600 + 3_200 + 6_400 + 12_800 + 25_600;
        assertThat(budget.toMillis()).isEqualTo(expected);
        assertThat(budget).isLessThan(Duration.ofMinutes(1));
        // With the ceiling reached, additional attempts add at most the ceiling each.
        assertThat(backoff.worstCaseTotal(9).minus(budget).toMillis()).isEqualTo(30_000);
    }

    @Test
    void invalidConfigurationAndAttemptNumbersAreRejected() {
        assertThatThrownBy(() -> new Backoff(Duration.ZERO, Duration.ofSeconds(1), 2.0, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(2), Duration.ofSeconds(1), 2.0, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofMillis(1), Duration.ofSeconds(1), 0.5, new Random()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofMillis(1), Duration.ofSeconds(1), 2.0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backoff.delayFor(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
