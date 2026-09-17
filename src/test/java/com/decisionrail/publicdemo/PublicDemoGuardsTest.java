package com.decisionrail.publicdemo;

import com.decisionrail.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a public instance refuses to start with, and how a budget window behaves at its edges.
 *
 * <p>The guards are the part of the deployment that cannot be verified by looking at a running
 * instance: a running instance is one that passed them. So they are checked directly, as the plain
 * decisions they are.
 */
class PublicDemoGuardsTest {
    private static final PublicDemoProperties PUBLIC = new PublicDemoProperties(
            true, "visitor", "a-public-visitor-password", 0, 0, 0, 0, 0, 0, null);

    @Test
    void faultInjectionCannotBeOnAPublicInstance() {
        assertThatThrownBy(() -> new PublicDemoConfig().publicDemoGuards(PUBLIC, true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fault-injection")
                .hasMessageContaining("EVENTS_FAULT_INJECTION_ENABLED");
    }

    @Test
    void sessionCookiesMustBeSecureOnAPublicInstance() {
        assertThatThrownBy(() -> new PublicDemoConfig().publicDemoGuards(PUBLIC, false, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Secure")
                .hasMessageContaining("SESSION_COOKIE_SECURE");
    }

    @Test
    void theVisitorPasswordMustBeARealValue() {
        PublicDemoProperties placeholder = new PublicDemoProperties(
                true, "visitor", "REPLACE-ME-REPLACE-ME", 0, 0, 0, 0, 0, 0, null);
        assertThatThrownBy(() -> new PublicDemoConfig().publicDemoGuards(placeholder, false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VISITOR_PASSWORD");
        PublicDemoProperties tooShort = new PublicDemoProperties(true, "visitor", "short", 0, 0, 0, 0, 0, 0, null);
        assertThatThrownBy(() -> new PublicDemoConfig().publicDemoGuards(tooShort, false, true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCorrectlyConfiguredPublicInstanceStarts() {
        assertThat(new PublicDemoConfig().publicDemoGuards(PUBLIC, false, true)).isNotNull();
    }

    @Test
    void defaultsFillInWhenLimitsAreLeftUnset() {
        assertThat(PUBLIC.commandsPerMinute()).isEqualTo(30);
        assertThat(PUBLIC.replayJobsPerHour()).isEqualTo(6);
        assertThat(PUBLIC.maxRunningReplayJobs()).isEqualTo(1);
        assertThat(PUBLIC.reconciliationPerMinute()).isEqualTo(10);
        assertThat(PUBLIC.maxPaymentsPerAccount()).isEqualTo(300);
        assertThat(PUBLIC.authFailuresPerWindow()).isEqualTo(10);
        assertThat(PUBLIC.authFailureWindow()).isEqualTo(Duration.ofMinutes(15));
        assertThat(PUBLIC.isVisitor("visitor")).isTrue();
        assertThat(PUBLIC.isVisitor("demo-merchant")).isFalse();
        assertThat(new PublicDemoProperties(false, "visitor", "x", 0, 0, 0, 0, 0, 0, null).isVisitor("visitor"))
                .as("nobody is budgeted when the demo is off").isFalse();
    }

    @Test
    void aBudgetWindowAdmitsItsLimitThenRefusesUntilTheOldestEventAges() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-17T00:00:00Z"));
        SlidingWindowBudget budget = new SlidingWindowBudget(clock, 2, Duration.ofMinutes(1));

        assertThat(budget.tryAcquire("a")).isTrue();
        clock.advance(Duration.ofSeconds(20));
        assertThat(budget.tryAcquire("a")).isTrue();
        assertThat(budget.tryAcquire("a")).as("the third within a minute").isFalse();
        assertThat(budget.tryAcquire("b")).as("another key has its own window").isTrue();
        assertThat(budget.secondsUntilRelief("a")).isEqualTo(40);

        clock.advance(Duration.ofSeconds(40));
        assertThat(budget.tryAcquire("a")).as("the first event is exactly a minute old and has left").isTrue();
        assertThat(budget.tryAcquire("a")).isFalse();

        budget.clear("a");
        assertThat(budget.exhausted("a")).isFalse();
        clock.advance(Duration.ofHours(1));
        budget.forgetIdle();
        assertThat(budget.exhausted("b")).isFalse();
    }
}
