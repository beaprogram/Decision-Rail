package com.decisionrail.telemetry;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The trace identity of the operation that produced a durable record.
 *
 * <p>Stored beside the record rather than carried in a thread. The work it correlates happens later,
 * on another thread, and frequently in another process after a restart, so nothing held in a
 * {@code ThreadLocal} can survive to meet it.
 *
 * <p>Values are validated here, at the boundary where they become durable and later become an
 * outbound header. A traceparent assembled from unchecked text would let anything that can influence
 * these fields write arbitrary bytes into a header we send.
 */
public record OriginTrace(String traceId, String spanId) {
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-f]{16}$");
    /** The all-zero ids OpenTelemetry uses for "no span", which are valid hex but identify nothing. */
    private static final String NO_TRACE = "0".repeat(32);
    private static final String NO_SPAN = "0".repeat(16);

    /**
     * @return the pair when both halves are well-formed and non-zero, otherwise empty. Absence is a
     *         normal outcome: tracing may be off, and rows written before it existed have no context.
     */
    public static Optional<OriginTrace> of(String traceId, String spanId) {
        if (traceId == null || spanId == null) return Optional.empty();
        String trace = traceId.trim().toLowerCase();
        String span = spanId.trim().toLowerCase();
        if (!TRACE_ID.matcher(trace).matches() || !SPAN_ID.matcher(span).matches()) return Optional.empty();
        if (trace.equals(NO_TRACE) || span.equals(NO_SPAN)) return Optional.empty();
        return Optional.of(new OriginTrace(trace, span));
    }

    /**
     * The W3C {@code traceparent} for this origin, sampled.
     *
     * <p>The sampled flag is set because a record only carries an origin when its producing span was
     * itself recorded; propagating "not sampled" would hide the very delivery an operator is trying to
     * follow. Sampling is decided once, at the request that started the work, not again per hop.
     */
    public String traceParent() {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    /**
     * Reads a W3C {@code traceparent}.
     *
     * <p>Version {@code 00} only. A future version may add fields after the flags, and the
     * specification says an unknown version should be parsed leniently, but this system is the only
     * producer of these headers and accepting shapes nothing here emits would widen the input surface
     * for nothing. An unparseable value yields empty, which starts a new trace rather than failing.
     */
    public static Optional<OriginTrace> fromTraceParent(String header) {
        if (header == null) return Optional.empty();
        String[] parts = header.trim().split("-");
        if (parts.length != 4 || !parts[0].equals("00")) return Optional.empty();
        return of(parts[1], parts[2]);
    }
}
