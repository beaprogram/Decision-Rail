package com.decisionrail.telemetry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A value recomputed at most once per interval, for gauges backed by database aggregates.
 *
 * <h2>Why this exists</h2>
 * A gauge is read on every scrape. The delivery and workload gauges were each a {@code count(*)} over
 * a table that grows for the life of the deployment, sixteen of them per scrape, so the cost of
 * observing the system rose with the history it had accumulated. Under a benchmark that is not a
 * detail: the measurement starts competing with the thing being measured.
 *
 * <h2>What a reader is promised</h2>
 * A value is at most {@code ttl} old. That is the honest resolution of these series and it is
 * documented in the metric catalogue; anyone alerting on backlog should use a window wider than it.
 *
 * <h2>What happens when the database will not answer</h2>
 * The gauge reports {@code NaN}, which Prometheus stores as absent rather than as a number. It does
 * not report zero. A zero backlog and an unreachable database look identical on a graph and mean
 * opposite things, and the one that gets someone paged at the wrong time is the false zero. The
 * failure is logged at debug on every refresh and the underlying error still surfaces wherever it
 * genuinely belongs: this class only decides what a gauge says, never whether a query failure is
 * swallowed elsewhere.
 */
public final class Cached<T> {
    private static final Logger log = LoggerFactory.getLogger(Cached.class);

    private final Duration ttl;
    private final Clock clock;
    private final Supplier<T> loader;
    private final String name;

    private volatile T value;
    private volatile Instant loadedAt = Instant.EPOCH;
    private volatile boolean available;

    public Cached(String name, Duration ttl, Clock clock, Supplier<T> loader) {
        this.name = name;
        this.ttl = ttl;
        this.clock = clock;
        this.loader = loader;
    }

    /** The cached value, refreshed when older than the interval. Never throws. */
    public synchronized T value() {
        Instant now = clock.instant();
        if (available && Duration.between(loadedAt, now).compareTo(ttl) < 0) return value;
        try {
            value = loader.get();
            loadedAt = now;
            available = true;
        } catch (RuntimeException failure) {
            // Deliberately not rethrown: a scrape must not fail, and a stale figure must not be
            // presented as current either, so availability is what changes.
            available = false;
            log.debug("Could not refresh gauge source {}; it will report no data", name, failure);
        }
        return value;
    }

    /** False when the last refresh failed, so gauges can report no data instead of a wrong zero. */
    public boolean available() {
        value();
        return available;
    }

    /** Reads one number out of the cached value, or NaN when the source is unavailable. */
    public double reading(java.util.function.ToDoubleFunction<T> extract) {
        T current = value();
        if (!available || current == null) return Double.NaN;
        return extract.applyAsDouble(current);
    }
}
