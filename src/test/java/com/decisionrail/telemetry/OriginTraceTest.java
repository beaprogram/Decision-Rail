package com.decisionrail.telemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validation at the point where trace context becomes durable and later becomes an outbound header.
 *
 * <p>A traceparent assembled from unchecked text is an injection surface, and a header is not a place
 * where "mostly hex" is good enough.
 */
class OriginTraceTest {
    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String SPAN = "00f067aa0ba902b7";

    @Test
    void acceptsAWellFormedPair() {
        assertThat(OriginTrace.of(TRACE, SPAN, true)).contains(new OriginTrace(TRACE, SPAN, true));
    }

    @Test
    void rendersTheSamplingDecisionItWasGiven() {
        assertThat(OriginTrace.of(TRACE, SPAN, true).orElseThrow().traceParent())
                .isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
        // An unsampled context is still propagated, with flags that say so. Emitting 01 here would
        // reverse a decision the deployment already took.
        assertThat(OriginTrace.of(TRACE, SPAN, false).orElseThrow().traceParent())
                .isEqualTo("00-" + TRACE + "-" + SPAN + "-00");
    }

    @Test
    void readsTheSamplingDecisionBackOutOfAHeader() {
        assertThat(OriginTrace.fromTraceParent("00-" + TRACE + "-" + SPAN + "-01").orElseThrow().sampled()).isTrue();
        assertThat(OriginTrace.fromTraceParent("00-" + TRACE + "-" + SPAN + "-00").orElseThrow().sampled()).isFalse();
        // Reserved bits are ignored rather than rejected, as a receiver is asked to do; only bit 0 is
        // the sampled flag.
        assertThat(OriginTrace.fromTraceParent("00-" + TRACE + "-" + SPAN + "-03").orElseThrow().sampled()).isTrue();
        assertThat(OriginTrace.fromTraceParent("00-" + TRACE + "-" + SPAN + "-02").orElseThrow().sampled()).isFalse();
        assertThat(OriginTrace.fromTraceParent("00-" + TRACE + "-" + SPAN + "-zz")).isEmpty();
    }

    @Test
    void treatsAStoredRowWithNoDecisionAsUnsampled() {
        // V7 rows have a trace and no flag. Defaulting them to sampled would re-record work the
        // deployment had decided not to record, which is the behaviour the decision column exists to
        // stop; the trace still stays continuous.
        assertThat(OriginTrace.ofStored(TRACE, SPAN, null).orElseThrow().sampled()).isFalse();
        assertThat(OriginTrace.ofStored(TRACE, SPAN, Boolean.TRUE).orElseThrow().sampled()).isTrue();
        assertThat(OriginTrace.ofStored(null, null, Boolean.TRUE)).isEmpty();
    }

    @Test
    void rejectsAnythingThatIsNotExactlyHexOfTheRightLength() {
        assertThat(OriginTrace.of(null, SPAN, true)).isEmpty();
        assertThat(OriginTrace.of(TRACE, null, true)).isEmpty();
        assertThat(OriginTrace.of(TRACE.substring(1), SPAN, true)).isEmpty();
        assertThat(OriginTrace.of(TRACE + "a", SPAN, true)).isEmpty();
        assertThat(OriginTrace.of(TRACE, SPAN.substring(1), true)).isEmpty();
        // The characters that would let a value break out of the header it is written into.
        assertThat(OriginTrace.of("4bf92f3577b34da6a3ce929d0e0e47 \n", SPAN, true)).isEmpty();
        assertThat(OriginTrace.of("../" + TRACE.substring(3), SPAN, true)).isEmpty();
        assertThat(OriginTrace.of(TRACE.toUpperCase().replace('4', 'g'), SPAN, true)).isEmpty();
    }

    @Test
    void rejectsTheAllZeroIdsThatAreValidHexButIdentifyNothing() {
        // OpenTelemetry uses these for "no span". Storing them would produce rows that look correlated
        // and lead nowhere, which is worse than an honest absence.
        assertThat(OriginTrace.of("0".repeat(32), SPAN, true)).isEmpty();
        assertThat(OriginTrace.of(TRACE, "0".repeat(16), true)).isEmpty();
    }

    @Test
    void normalisesCaseAndSurroundingWhitespaceRatherThanRejectingIt() {
        assertThat(OriginTrace.of("  " + TRACE.toUpperCase() + "  ", SPAN.toUpperCase(), true))
                .contains(new OriginTrace(TRACE, SPAN, true));
    }
}
