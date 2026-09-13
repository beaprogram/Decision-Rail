package com.decisionrail.telemetry;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Trace continuity across the asynchronous boundary.
 *
 * <h2>What is a child and what is a link</h2>
 * A publication attempt is a <em>child</em> of the command that produced the event, not a new trace
 * with a link back to it. The whole point of this checkpoint is that an operator can follow one
 * synthetic payment from its HTTP command through to a committed projection effect, and a child span
 * puts that on one screen in any backend. Links are supported unevenly, and a trace that has to be
 * reassembled by hand is not the correlation anyone asked for.
 *
 * <p>The cost is stated rather than hidden: a trace stays open as long as delivery takes, which under
 * a broker outage can be minutes, and each retry adds another child. A backend that closes traces on a
 * fixed window will show such a trace in pieces. That is the trade recorded in ADR-0006.
 *
 * <p>Each attempt is its own span, so a retry is visible as a distinct attempt rather than as a longer
 * first one, and every attempt names the durable origin it belongs to.
 *
 * <h2>What is deliberately not propagated</h2>
 * Only {@code traceparent} is written and read. No baggage: it is attacker-influenced text that would
 * travel into the logs and metrics of every downstream hop. An inbound header that is absent or
 * malformed produces a new trace rather than an error, because delivery of a committed event must
 * never depend on telemetry being well-formed.
 *
 * <h2>Why the header is assembled here</h2>
 * The bridge's {@code Propagator} is not used. On this stack it silently injected nothing and silently
 * failed to establish a remote parent, leaving each publication in a trace of its own - which looks
 * exactly like working correlation until someone opens a trace and finds it empty. The W3C
 * {@code traceparent} is a fixed 55-character format, it is validated on the way in and out by
 * {@link OriginTrace}, and this system is the only producer of these headers. Building it directly is
 * a few lines, is covered by tests that would fail if it regressed, and cannot fail quietly.
 */
@Component
public class DeliveryTracing {
    private static final Logger log = LoggerFactory.getLogger(DeliveryTracing.class);
    public static final String TRACE_PARENT = "traceparent";

    private final Tracer tracer;

    public DeliveryTracing(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Starts a span for one publication attempt, continuing the trace of the command that produced
     * the event when the row carries one.
     *
     * @return a scope the caller must close; never null, even when tracing is disabled
     */
    public Scope publishAttempt(Optional<OriginTrace> origin, String eventType, int attempt) {
        try {
            Span span = spanBuilder(origin).name("outbox.publish")
                    .tag("messaging.operation", "publish")
                    .tag("decisionrail.event_type", eventType)
                    .tag("decisionrail.attempt", Integer.toString(attempt))
                    .tag("decisionrail.origin_known", Boolean.toString(origin.isPresent()))
                    .tag("decisionrail.origin_sampled", origin.map(o -> Boolean.toString(o.sampled())).orElse("unknown"))
                    .start();
            return new Scope(span, tracer.withSpan(span));
        } catch (RuntimeException unavailable) {
            // Tracing must never stop an event being delivered.
            log.debug("Could not start a publish span; delivery continues untraced", unavailable);
            return Scope.NONE;
        }
    }

    /** Writes the current trace into the record headers, so the consumer can continue this trace. */
    public void inject(Headers headers) {
        try {
            Span span = tracer.currentSpan();
            if (span == null) return;
            // The flags written here are this span's real decision, so a consumer inherits it rather
            // than deciding again.
            OriginTrace.of(span.context().traceId(), span.context().spanId(),
                            Boolean.TRUE.equals(span.context().sampled()))
                    .ifPresent(current -> headers.add(TRACE_PARENT,
                            current.traceParent().getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException unavailable) {
            log.debug("Could not inject trace context into a record; delivery continues", unavailable);
        }
    }

    /**
     * Starts a span for consuming one record, continuing the publisher's trace when the header is
     * present and well-formed.
     *
     * <p>A redelivery of the same record continues the same trace and is a separate span, so "the same
     * event arrived twice" reads as two attempts against one origin rather than as two unrelated
     * events.
     */
    public Scope consume(Headers headers, String group, String operation) {
        try {
            Header header = headers.lastHeader(TRACE_PARENT);
            Optional<OriginTrace> parent = header == null
                    ? Optional.empty()
                    : OriginTrace.fromTraceParent(new String(header.value(), StandardCharsets.UTF_8));
            Span span = spanBuilder(parent).name(operation)
                    .tag("decisionrail.parent_known", Boolean.toString(parent.isPresent()))
                    .tag("messaging.operation", "process")
                    .tag("decisionrail.consumer_group", group)
                    .start();
            return new Scope(span, tracer.withSpan(span));
        } catch (RuntimeException unavailable) {
            log.debug("Could not start a consume span; processing continues untraced", unavailable);
            return Scope.NONE;
        }
    }

    /**
     * A builder parented to a remote trace when one is known, and a new root otherwise.
     *
     * <p>The parent's own sampling decision is propagated, not overridden. Forcing {@code true} here
     * meant an unsampled request produced a sampled publication: work recorded after the deployment had
     * decided not to record it, in a trace whose first span does not exist. An unsampled parent is
     * still set rather than discarded, because discarding it would make this a new root that takes a
     * fresh sampling decision — the same outcome by a longer route, and with a trace id that leads
     * nowhere.
     */
    private Span.Builder spanBuilder(Optional<OriginTrace> parent) {
        if (parent.isEmpty()) return tracer.spanBuilder();
        TraceContext context = tracer.traceContextBuilder()
                .traceId(parent.get().traceId())
                .spanId(parent.get().spanId())
                .sampled(parent.get().sampled())
                .build();
        return tracer.spanBuilder().setParent(context);
    }

    /** Starts a span for a unit of scheduled work that has no inbound request of its own. */
    public Scope backgroundWork(String operation) {
        try {
            Span span = tracer.spanBuilder().name(operation).tag("decisionrail.trigger", "scheduled").start();
            return new Scope(span, tracer.withSpan(span));
        } catch (RuntimeException unavailable) {
            return Scope.NONE;
        }
    }

    /**
     * Starts a span for durable work, continuing the trace stored with it.
     *
     * <p>For work claimed from a table rather than received from a broker: a shadow task evaluated on a
     * worker thread, possibly in a later process, possibly after another worker's lease expired. There
     * is no thread context to inherit and no header to read, only the row, which is exactly why the
     * trace was written into the row in the first place.
     *
     * @param attempt the attempt number, so a retry after a takeover reads as a distinct attempt
     */
    public Scope resumeDurableWork(Optional<OriginTrace> origin, String operation, int attempt) {
        try {
            Span span = spanBuilder(origin).name(operation)
                    .tag("decisionrail.trigger", "durable_task")
                    .tag("decisionrail.attempt", Integer.toString(attempt))
                    .tag("decisionrail.origin_known", Boolean.toString(origin.isPresent()))
                    .start();
            return new Scope(span, tracer.withSpan(span));
        } catch (RuntimeException unavailable) {
            log.debug("Could not start a span for durable work; it continues untraced", unavailable);
            return Scope.NONE;
        }
    }

    /**
     * An open span and its thread scope.
     *
     * <p>Closing is what prevents context leaking onto the next piece of work a pooled thread picks
     * up, which is why every caller uses try-with-resources and why closing tolerates a half-started
     * scope.
     */
    public static final class Scope implements AutoCloseable {
        static final Scope NONE = new Scope(null, null);

        private final Span span;
        private final Tracer.SpanInScope inScope;

        Scope(Span span, Tracer.SpanInScope inScope) {
            this.span = span;
            this.inScope = inScope;
        }

        /** Records a failure on the span without changing what the caller throws. */
        public void failed(Throwable cause) {
            if (span != null) span.error(cause);
        }

        public void tag(String key, String value) {
            if (span != null && value != null) span.tag(key, value);
        }

        @Override
        public void close() {
            try {
                if (inScope != null) inScope.close();
            } finally {
                if (span != null) span.end();
            }
        }
    }
}
