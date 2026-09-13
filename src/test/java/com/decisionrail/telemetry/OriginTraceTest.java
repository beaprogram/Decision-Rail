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
        assertThat(OriginTrace.of(TRACE, SPAN)).contains(new OriginTrace(TRACE, SPAN));
    }

    @Test
    void rendersTheW3cHeaderSampled() {
        assertThat(OriginTrace.of(TRACE, SPAN).orElseThrow().traceParent())
                .isEqualTo("00-" + TRACE + "-" + SPAN + "-01");
    }

    @Test
    void rejectsAnythingThatIsNotExactlyHexOfTheRightLength() {
        assertThat(OriginTrace.of(null, SPAN)).isEmpty();
        assertThat(OriginTrace.of(TRACE, null)).isEmpty();
        assertThat(OriginTrace.of(TRACE.substring(1), SPAN)).isEmpty();
        assertThat(OriginTrace.of(TRACE + "a", SPAN)).isEmpty();
        assertThat(OriginTrace.of(TRACE, SPAN.substring(1))).isEmpty();
        // The characters that would let a value break out of the header it is written into.
        assertThat(OriginTrace.of("4bf92f3577b34da6a3ce929d0e0e47 \n", SPAN)).isEmpty();
        assertThat(OriginTrace.of("../" + TRACE.substring(3), SPAN)).isEmpty();
        assertThat(OriginTrace.of(TRACE.toUpperCase().replace('4', 'g'), SPAN)).isEmpty();
    }

    @Test
    void rejectsTheAllZeroIdsThatAreValidHexButIdentifyNothing() {
        // OpenTelemetry uses these for "no span". Storing them would produce rows that look correlated
        // and lead nowhere, which is worse than an honest absence.
        assertThat(OriginTrace.of("0".repeat(32), SPAN)).isEmpty();
        assertThat(OriginTrace.of(TRACE, "0".repeat(16))).isEmpty();
    }

    @Test
    void normalisesCaseAndSurroundingWhitespaceRatherThanRejectingIt() {
        assertThat(OriginTrace.of("  " + TRACE.toUpperCase() + "  ", SPAN.toUpperCase()))
                .contains(new OriginTrace(TRACE, SPAN));
    }
}
