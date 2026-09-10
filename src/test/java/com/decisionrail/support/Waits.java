package com.decisionrail.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Bounded polling helpers.
 *
 * <p>Used instead of fixed sleeps: a sleep either makes a test slow or makes it flaky, and it
 * never states what the test is actually waiting for. These helpers fail with the condition's
 * description and the last observed value, so a timeout is diagnosable.
 */
public final class Waits {
    private static final Duration INTERVAL = Duration.ofMillis(25);

    private Waits() {}

    public static void until(String description, Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        AssertionError lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) return;
                lastFailure = null;
            } catch (AssertionError assertionFailure) {
                lastFailure = assertionFailure;
            }
            sleep();
        }
        if (lastFailure != null) throw lastFailure;
        throw new AssertionError("Timed out after " + timeout.toMillis() + "ms waiting for: " + description);
    }

    /** Polls until the supplier returns a non-null, non-empty value. */
    public static <T> T value(String description, Duration timeout, Supplier<T> supplier) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            T candidate = supplier.get();
            if (candidate != null && !(candidate instanceof java.util.Collection<?> c && c.isEmpty())) {
                return candidate;
            }
            sleep();
        }
        throw new AssertionError("Timed out after " + timeout.toMillis() + "ms waiting for: " + description);
    }

    /**
     * Asserts a condition stays false for the whole window. Used to show that an isolated
     * component did not produce an effect, where a single immediate check would prove nothing.
     */
    public static void neverDuring(String description, Duration window, BooleanSupplier condition) {
        long deadline = System.nanoTime() + window.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                throw new AssertionError("Condition became true but must not: " + description);
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(INTERVAL.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", interrupted);
        }
    }
}
