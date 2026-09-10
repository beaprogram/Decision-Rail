package com.decisionrail.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A clock tests advance by hand.
 *
 * <p>Time-dependent state machines are tested by moving time, not by sleeping. A sleep makes the
 * test slow and still only proves that something happened within a window; advancing an injected
 * clock proves the transition happens at the boundary and not before it.
 */
public final class MutableClock extends Clock {
    private final ZoneId zone;
    private volatile Instant now;

    public MutableClock(Instant start) {
        this(start, ZoneId.of("UTC"));
    }

    private MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() { return zone; }

    @Override
    public Clock withZone(ZoneId other) { return new MutableClock(now, other); }

    @Override
    public Instant instant() { return now; }
}
