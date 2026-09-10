package com.decisionrail.resilience;

import java.time.Duration;
import java.util.Random;

/**
 * Exponential backoff with full jitter and a hard ceiling.
 *
 * <p>Jitter matters here because a broker outage makes every queued event fail at almost
 * the same instant. Without it the whole backlog would retry in synchronized waves,
 * reproducing the load spike that the backoff is meant to spread out.
 *
 * <p>The supplied {@link Random} is seedable so tests can assert exact schedules instead
 * of accepting a range.
 */
public final class Backoff {
    private final Duration base;
    private final Duration ceiling;
    private final double multiplier;
    private final Random random;

    public Backoff(Duration base, Duration ceiling, double multiplier, Random random) {
        if (base == null || base.isNegative() || base.isZero()) throw new IllegalArgumentException("base must be positive");
        if (ceiling == null || ceiling.compareTo(base) < 0) throw new IllegalArgumentException("ceiling must be at least base");
        if (multiplier < 1.0 || multiplier > 10.0) throw new IllegalArgumentException("multiplier must be between 1 and 10");
        if (random == null) throw new IllegalArgumentException("random is required");
        this.base = base;
        this.ceiling = ceiling;
        this.multiplier = multiplier;
        this.random = random;
    }

    /**
     * @param attempt 1 for the first retry after an initial failure.
     * @return a delay in {@code [base, min(ceiling, base * multiplier^(attempt-1))]}.
     */
    public Duration delayFor(int attempt) {
        if (attempt < 1) throw new IllegalArgumentException("attempt must be at least 1");
        double scaled = base.toMillis() * Math.pow(multiplier, Math.min(attempt - 1, 32));
        long upper = (long) Math.min(scaled, (double) ceiling.toMillis());
        long lower = Math.min(base.toMillis(), upper);
        long span = upper - lower;
        long jittered = span <= 0 ? lower : lower + random.nextLong(span + 1);
        return Duration.ofMillis(jittered);
    }

    /** Worst-case total wait across the given number of retries, used to document the retry budget. */
    public Duration worstCaseTotal(int retries) {
        long total = 0;
        for (int attempt = 1; attempt <= retries; attempt++) {
            double scaled = base.toMillis() * Math.pow(multiplier, Math.min(attempt - 1, 32));
            total += (long) Math.min(scaled, (double) ceiling.toMillis());
        }
        return Duration.ofMillis(total);
    }
}
