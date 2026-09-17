package com.decisionrail.publicdemo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A count of events per key over a sliding window, kept in memory.
 *
 * <p>In memory is the right scope for this: the public demo is a single instance, and a budget that
 * resets when the process restarts is acceptable for what it protects - the instance's own capacity,
 * not a financial invariant. Nothing financial depends on it. The financial rules live in the database
 * and are unchanged.
 *
 * <p>Keys are bounded by pruning entries whose window has emptied, so a scan of addresses cannot grow
 * the map without bound: an address that stops arriving is forgotten within one window.
 */
final class SlidingWindowBudget {
    private final Clock clock;
    private final int limit;
    private final Duration window;
    private final Map<String, Deque<Instant>> events = new ConcurrentHashMap<>();

    SlidingWindowBudget(Clock clock, int limit, Duration window) {
        this.clock = clock;
        this.limit = limit;
        this.window = window;
    }

    /** Records one event for the key and answers whether it was within budget. */
    boolean tryAcquire(String key) {
        Instant now = clock.instant();
        Deque<Instant> recent = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent, now);
            if (recent.size() >= limit) return false;
            recent.addLast(now);
            return true;
        }
    }

    /** Records one event without a limit; for counting failures that lock a key out later. */
    void record(String key) {
        Instant now = clock.instant();
        Deque<Instant> recent = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent, now);
            recent.addLast(now);
        }
    }

    boolean exhausted(String key) {
        Deque<Instant> recent = events.get(key);
        if (recent == null) return false;
        synchronized (recent) {
            prune(recent, clock.instant());
            return recent.size() >= limit;
        }
    }

    void clear(String key) {
        events.remove(key);
    }

    /** Seconds until the oldest counted event leaves the window, for a Retry-After header. */
    long secondsUntilRelief(String key) {
        Deque<Instant> recent = events.get(key);
        if (recent == null) return 0;
        synchronized (recent) {
            Instant oldest = recent.peekFirst();
            if (oldest == null) return 0;
            long seconds = Duration.between(clock.instant(), oldest.plus(window)).toSeconds();
            return Math.max(1, seconds);
        }
    }

    /** An event exactly one window old has left it: relief arrives at {@code oldest + window}, not after. */
    private void prune(Deque<Instant> recent, Instant now) {
        Instant cutoff = now.minus(window);
        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) recent.pollFirst();
    }

    /** Drops keys with nothing left in their window. Called opportunistically, not on every request. */
    void forgetIdle() {
        Instant cutoff = clock.instant().minus(window);
        events.entrySet().removeIf(entry -> {
            Deque<Instant> recent = entry.getValue();
            synchronized (recent) {
                return recent.isEmpty() || !recent.peekLast().isAfter(cutoff);
            }
        });
    }
}
