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
public record OriginTrace(String traceId, String spanId, boolean sampled) {
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SPAN_ID = Pattern.compile("^[0-9a-f]{16}$");
    private static final Pattern FLAGS = Pattern.compile("^[0-9a-f]{2}$");
    /** The all-zero ids OpenTelemetry uses for "no span", which are valid hex but identify nothing. */
    private static final String NO_TRACE = "0".repeat(32);
    private static final String NO_SPAN = "0".repeat(16);

    /**
     * @param sampled whether the producing span was actually recorded. Carried rather than assumed: an
     *                unsampled span has perfectly valid identifiers, and treating those as evidence of
     *                sampling is how a deployment's decision not to record gets silently reversed at
     *                the next hop.
     * @return the triple when both identifiers are well-formed and non-zero, otherwise empty. Absence
     *         is a normal outcome: tracing may be off, and rows written before it existed have no
     *         context.
     */
    public static Optional<OriginTrace> of(String traceId, String spanId, boolean sampled) {
        if (traceId == null || spanId == null) return Optional.empty();
        String trace = traceId.trim().toLowerCase();
        String span = spanId.trim().toLowerCase();
        if (!TRACE_ID.matcher(trace).matches() || !SPAN_ID.matcher(span).matches()) return Optional.empty();
        if (trace.equals(NO_TRACE) || span.equals(NO_SPAN)) return Optional.empty();
        return Optional.of(new OriginTrace(trace, span, sampled));
    }

    /**
     * Reads a stored origin, where a missing decision means unsampled.
     *
     * <p>Rows written before the decision was stored carry a trace but no flag. Defaulting those to
     * sampled would reintroduce the behaviour this exists to prevent, so they propagate as unsampled:
     * the trace stays continuous and nothing downstream starts recording on its own authority.
     */
    public static Optional<OriginTrace> ofStored(String traceId, String spanId, Boolean sampled) {
        return of(traceId, spanId, Boolean.TRUE.equals(sampled));
    }

    /**
     * The W3C {@code traceparent} for this origin, carrying the real sampling decision.
     *
     * <p>Flags are {@code 01} when the producing span was recorded and {@code 00} when it was not. An
     * unsampled context is still propagated rather than dropped: dropping it would leave the next hop
     * with no parent, which makes it start a new root and take its own sampling decision — the same
     * work recorded after the deployment had decided not to record it, and under a fresh trace id that
     * leads back to nothing.
     */
    public String traceParent() {
        return "00-" + traceId + "-" + spanId + (sampled ? "-01" : "-00");
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
        if (!FLAGS.matcher(parts[3]).matches()) return Optional.empty();
        // Bit 0 of the flags byte is "sampled". Other bits are reserved and ignored rather than
        // rejected, which is what the specification asks of a receiver.
        boolean sampled = (Integer.parseInt(parts[3], 16) & 0x01) == 0x01;
        return of(parts[1], parts[2], sampled);
    }
}
