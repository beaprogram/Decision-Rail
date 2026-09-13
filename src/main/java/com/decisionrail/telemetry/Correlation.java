package com.decisionrail.telemetry;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reads the trace the current thread is on, so a durable record can be stamped with it.
 *
 * <p>A thin seam on purpose. It keeps the tracing library out of stores and services, which need to
 * know only that correlation is optional, and it gives tests a place to stand without a tracer.
 * Nothing here fails a caller: telemetry is never allowed to decide whether a payment commits.
 */
@Component
public class Correlation {
    private final Tracer tracer;

    public Correlation(Tracer tracer) {
        this.tracer = tracer;
    }

    /** The current span's identity, or empty when tracing is disabled or nothing is in progress. */
    public Optional<OriginTrace> current() {
        try {
            Span span = tracer.currentSpan();
            if (span == null) return Optional.empty();
            TraceContext context = span.context();
            // The decision is read, never inferred. A span that was not sampled still has valid
            // identifiers, so their presence says nothing about whether anything was recorded. A null
            // from the bridge means "no decision expressed", which is not a decision to record.
            return OriginTrace.of(context.traceId(), context.spanId(), Boolean.TRUE.equals(context.sampled()));
        } catch (RuntimeException unavailable) {
            // A tracing failure is not a payment failure. Correlation is best effort by design.
            return Optional.empty();
        }
    }

    /** The current trace id alone, for records that only need to point at an operation. */
    public Optional<String> currentTraceId() {
        return current().map(OriginTrace::traceId);
    }

    /**
     * Adds one bounded attribute to whatever span is running.
     *
     * <p>Used to point a request at an operation it is replaying. A span attribute is the right home
     * for a high-cardinality identifier: it is attached to one trace and queried deliberately, unlike a
     * metric label, which multiplies the stored series for every distinct value.
     */
    public void tagCurrentSpan(String key, String value) {
        if (value == null) return;
        try {
            Span span = tracer.currentSpan();
            if (span != null) span.tag(key, value);
        } catch (RuntimeException ignored) {
            // Telemetry never changes what a command does.
        }
    }
}
