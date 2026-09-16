package com.decisionrail.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Event contract validation against the bounds the consumer's own tables accept.
 *
 * <p>Validation used to check a normalised copy of a value while leaving the original in the
 * envelope: a currency of "cad" passed because its uppercase form is supported, and then failed the
 * projection's CHECK constraint on insert. That arrived as a database exception rather than a
 * contract violation, so the consumer's retry policy treated it as a transient storage outage and
 * retried it forever, blocking the partition behind a record that could never succeed.
 *
 * <p>Several fields had the same shape of problem with no validation at all: status, policy version,
 * and failure code are all stored in bounded columns that a long value overflows.
 *
 * <p>Rejecting rather than rewriting is deliberate. Silently upper-casing a payload would change the
 * bytes a consumer deduplicates and fingerprints, which is not something a consumer may do to an
 * immutable event it received.
 */
class EventContractBoundsTest {
    private final ObjectMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
    private final EventContract contract = new EventContract(mapper);

    @Test
    void aCanonicalEventIsAccepted() {
        EventContract.Parsed parsed = contract.parse(envelope(Map.of()));
        assertThat(parsed.envelope().payment().currency()).isEqualTo("CAD");
        assertThat(parsed.envelope().payment().country()).isEqualTo("CA");
        assertThat(parsed.fingerprint()).hasSize(64);
    }

    @Test
    void aNonCanonicalCurrencyIsRejectedBeforeItCanReachStorage() {
        // The value the projection column would refuse, refused here instead.
        assertRejected("MALFORMED", "currency", payment("\"currency\":\"cad\""));
        assertRejected("MALFORMED", "currency", payment("\"currency\":\"Cad\""));
        assertRejected("MALFORMED", "currency", payment("\"currency\":\"EUR\""));
        assertRejected("MALFORMED", "currency", payment("\"currency\":\"CADX\""));
    }

    @Test
    void aNonCanonicalCountryIsRejected() {
        assertRejected("MALFORMED", "country", payment("\"country\":\"ca\""));
        assertRejected("MALFORMED", "country", payment("\"country\":\"Ca\""));
        assertRejected("MALFORMED", "country", payment("\"country\":\"CAN\""));
    }

    @Test
    void aStatusOutsideTheKnownLifecycleIsRejected() {
        // varchar(16) in the projection, and only five values are meaningful.
        assertRejected("MALFORMED", "status", payment("\"status\":\"" + "X".repeat(40) + "\""));
        assertRejected("MALFORMED", "status", payment("\"status\":\"SETTLED\""));
        assertRejected("MALFORMED", "status", payment("\"status\":\"authorized\""));
        for (String status : new String[]{"AUTHORIZED", "CAPTURED", "VOIDED", "DECLINED", "REVIEW"}) {
            assertThatCode(() -> contract.parse(payment("\"status\":\"" + status + "\"")))
                    .as("status %s must be accepted", status).doesNotThrowAnyException();
        }
    }

    @Test
    void aPolicyVersionLongerThanItsColumnIsRejected() {
        assertRejected("MALFORMED", "policy version", decision("\"ruleSetVersion\":\"" + "v".repeat(65) + "\""));
        assertRejected("MALFORMED", "policy version", decision("\"ruleSetVersion\":\"not a version!\""));
        assertThatCode(() -> contract.parse(decision("\"ruleSetVersion\":\"" + "v".repeat(64) + "\"")))
                .as("a policy version exactly at the column width is accepted").doesNotThrowAnyException();
    }

    @Test
    void aFailureCodeLongerThanItsColumnIsRejected() {
        assertRejected("MALFORMED", "failureCode", payment("\"failureCode\":\"" + "F".repeat(65) + "\""));
        assertThatCode(() -> contract.parse(payment("\"failureCode\":\"INSUFFICIENT_FUNDS\"")))
                .doesNotThrowAnyException();
        assertThatCode(() -> contract.parse(payment("\"failureCode\":null"))).doesNotThrowAnyException();
    }

    @Test
    void aReasonCodeOrDescriptionBeyondItsStoredShapeIsRejected() {
        assertRejected("MALFORMED", "reason", decision(
                "\"reasons\":[{\"code\":\"" + "C".repeat(200) + "\",\"description\":\"d\",\"scoreContribution\":0}]"));
        assertRejected("MALFORMED", "reason", decision(
                "\"reasons\":[{\"code\":\"OK\",\"description\":\"\",\"scoreContribution\":0}]"));
    }

    @Test
    void anUnsupportedSchemaOrEventTypeIsStillDistinguishedFromMalformedContent() {
        assertThatThrownBy(() -> contract.parse(envelope(Map.of("schemaVersion", "99"))))
                .isInstanceOf(EventContractException.class)
                .extracting(thrown -> ((EventContractException) thrown).reason())
                .isEqualTo("UNSUPPORTED_SCHEMA");
        // payment.refunded.v1 stood here until checkpoint 9 made it a supported type. The distinction
        // being tested is unchanged; the example has to be a type this consumer genuinely does not
        // know, or the test would be asserting that a supported event is refused.
        assertThatThrownBy(() -> contract.parse(envelope(Map.of("eventType", "\"payment.settled.v1\""))))
                .isInstanceOf(EventContractException.class)
                .extracting(thrown -> ((EventContractException) thrown).reason())
                .isEqualTo("UNSUPPORTED_TYPE");
    }

    @Test
    void unreadableContentIsStillMalformed() {
        assertThatThrownBy(() -> contract.parse("{ not json"))
                .isInstanceOf(EventContractException.class)
                .extracting(thrown -> ((EventContractException) thrown).reason())
                .isEqualTo("MALFORMED");
    }

    @Test
    void anAdditiveUnknownFieldIsStillTolerated() {
        // schemaVersion is the compatibility gate; an extra field must not break an older reader.
        assertThatCode(() -> contract.parse(envelope(Map.of("somethingNew", "\"value\"")))).doesNotThrowAnyException();
    }

    // ----- helpers -----

    private void assertRejected(String expectedReason, String expectedMention, String raw) {
        assertThatThrownBy(() -> contract.parse(raw))
                .as("should be rejected: %s", expectedMention)
                .isInstanceOf(EventContractException.class)
                .hasMessageContaining(expectedMention)
                .extracting(thrown -> ((EventContractException) thrown).reason())
                .isEqualTo(expectedReason);
    }

    // ----- return events -----

    @Test
    void aReturnEventIsAcceptedWithItsOperation() {
        EventContract.Parsed parsed = contract.parse(refundEvent(java.util.Map.of()));
        assertThat(parsed.envelope().eventType()).isEqualTo("payment.refunded.v1");
        assertThat(parsed.envelope().returnOperation().amountMinor()).isEqualTo(1_000);
        assertThat(parsed.envelope().payment().capturedAmountMinor()).isEqualTo(2_500);
        assertThat(parsed.envelope().payment().returnedAmountMinor()).isEqualTo(1_000);
    }

    @Test
    void aHistoricalEventWithoutAnyReturnFieldsIsStillAccepted() {
        // Every event written before checkpoint 9 looks exactly like this. Its bytes are immutable, so
        // the contract has to accept their absence rather than expect a backfill that must never happen.
        EventContract.Parsed parsed = contract.parse(envelope(Map.of()));
        assertThat(parsed.envelope().payment().capturedAmountMinor()).isNull();
        assertThat(parsed.envelope().payment().returnedAmountMinor()).isZero();
        assertThat(parsed.envelope().returnOperation()).isNull();
    }

    @Test
    void aReturnEventWithoutItsOperationIsRejected() {
        // Without this block two partial refunds are indistinguishable, so it is required rather than
        // treated as optional detail.
        assertRejected("MALFORMED", "return operation",
                envelope(Map.of("eventType", "\"payment.refunded.v1\"",
                        "payment", capturedPaymentObject("", "", 1_000))));
    }

    @Test
    void aLifecycleEventCarryingAReturnOperationIsRejected() {
        assertRejected("MALFORMED", "must not carry",
                envelope(Map.of("returnOperation", returnObject(""))));
    }

    @Test
    void aReturnThatDisagreesWithItsOwnTotalsIsRejected() {
        // The event states both the operation's amount and the running total it produced, so they can
        // be checked against each other rather than taken on trust by whatever applies them.
        assertRejected("MALFORMED", "returnedAmountMinor must include this return",
                refundEvent(Map.of("payment", capturedPaymentObject("", "", 500))));
        assertRejected("MALFORMED", "cannot exceed capturedAmountMinor",
                refundEvent(Map.of("payment", capturedPaymentObject("\"capturedAmountMinor\":900", "", 1_000))));
    }

    @Test
    void theEventTypeAndTheOperationTypeMustAgree() {
        // The operation type was checked only against the set of known types, never against the event
        // carrying it. A partial refund could therefore arrive labelled as a reversal, and the
        // full-capture rule was skipped because that rule keyed off the nested type rather than the
        // event's - so the very check that would have caught it was the one being evaded.
        assertRejected("MALFORMED", "must match the event type",
                envelope(Map.of("eventType", "\"payment.reversed.v1\"",
                        "payment", capturedPaymentObject("", "", 1_000),
                        "returnOperation", returnObject("\"type\":\"REFUND\""))));
        assertRejected("MALFORMED", "must match the event type",
                refundEvent(Map.of("payment", capturedPaymentObject("", "", 2_500),
                        "returnOperation", returnObject("\"type\":\"REVERSAL\"", "\"amountMinor\":2500"))));
    }

    @Test
    void validReturnsOfEachShapeAreStillAccepted() {
        // A partial refund, a refund of the whole capture, and a reversal. All three must survive the
        // correspondence rule; it exists to reject disagreement, not to narrow what is legal.
        assertThatCode(() -> contract.parse(refundEvent(Map.of()))).doesNotThrowAnyException();
        assertThatCode(() -> contract.parse(refundEvent(Map.of(
                "payment", capturedPaymentObject("", "", 2_500),
                "returnOperation", returnObject("\"amountMinor\":2500")))))
                .doesNotThrowAnyException();
        assertThatCode(() -> contract.parse(envelope(Map.of(
                "eventType", "\"payment.reversed.v1\"",
                "aggregateSequence", "3",
                "payment", capturedPaymentObject("", "", 2_500),
                "returnOperation", returnObject("\"type\":\"REVERSAL\"", "\"amountMinor\":2500")))))
                .doesNotThrowAnyException();
    }

    @Test
    void aReversalThatDoesNotReturnTheWholeCaptureIsRejected() {
        // A partial "reversal" is a refund wearing the wrong name, and the two have different meanings
        // for what may happen next.
        assertRejected("MALFORMED", "reversal must return the full captured amount",
                envelope(Map.of("eventType", "\"payment.reversed.v1\"",
                        "payment", capturedPaymentObject("", "", 1_000),
                        "returnOperation", returnObject("\"type\":\"REVERSAL\""))));
    }

    @Test
    void aReturnOnAPaymentThatWasNeverCapturedIsRejected() {
        // The totals agree with each other here, so the only thing wrong is that money is being
        // returned from a payment that never took any.
        assertRejected("MALFORMED", "captured payment",
                refundEvent(Map.of("payment", capturedPaymentObject("\"status\":\"AUTHORIZED\"", "", 1_000))));
        assertRejected("MALFORMED", "must state the captured amount",
                refundEvent(Map.of("payment", capturedPaymentObject("\"capturedAmountMinor\":null", "", 1_000))));
    }

    @Test
    void returnOperationFieldsAreBoundedLikeTheirColumns() {
        assertRejected("MALFORMED", "return type", refundEvent(Map.of("returnOperation", returnObject("\"type\":\"CHARGEBACK\""))));
        assertRejected("MALFORMED", "return amountMinor", refundEvent(Map.of("returnOperation", returnObject("\"amountMinor\":0"))));
        assertRejected("MALFORMED", "return currency", refundEvent(Map.of("returnOperation", returnObject("\"currency\":\"usd\""))));
        assertRejected("MALFORMED", "match the payment currency", refundEvent(Map.of("returnOperation", returnObject("\"currency\":\"USD\""))));
        assertRejected("MALFORMED", "return sequenceNumber", refundEvent(Map.of("returnOperation", returnObject("\"sequenceNumber\":0"))));
        // varchar(140) on payment_returns.reason.
        assertRejected("MALFORMED", "return reason",
                refundEvent(Map.of("returnOperation", returnObject("\"reason\":\"" + "r".repeat(141) + "\""))));
    }

    /** A canonical refund event: a captured payment, 1000 returned of 2500, with its operation. */
    private String refundEvent(java.util.Map<String, String> overrides) {
        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("eventType", "\"payment.refunded.v1\"");
        fields.put("aggregateSequence", "3");
        fields.put("payment", capturedPaymentObject("", "", 1_000));
        fields.put("returnOperation", returnObject(""));
        fields.putAll(overrides);
        return envelope(fields);
    }

    private String capturedPaymentObject(String paymentOverride, String decisionOverride, long returned) {
        java.util.LinkedHashMap<String, String> payment = new java.util.LinkedHashMap<>();
        payment.put("id", "\"" + PAYMENT_ID + "\"");
        payment.put("accountId", "\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"");
        payment.put("amountMinor", "2500");
        payment.put("currency", "\"CAD\"");
        payment.put("country", "\"CA\"");
        payment.put("status", "\"CAPTURED\"");
        payment.put("decision", decisionObject(decisionOverride));
        payment.put("failureCode", "null");
        payment.put("createdAt", "\"2026-09-10T12:00:00Z\"");
        payment.put("updatedAt", "\"2026-09-10T12:05:00Z\"");
        payment.put("capturedAmountMinor", "2500");
        payment.put("returnedAmountMinor", Long.toString(returned));
        applyOverride(payment, paymentOverride);
        return render(payment);
    }

    private String returnObject(String... overrides) {
        java.util.LinkedHashMap<String, String> operation = new java.util.LinkedHashMap<>();
        operation.put("id", "\"cccccccc-dddd-eeee-ffff-111111111111\"");
        operation.put("type", "\"REFUND\"");
        operation.put("amountMinor", "1000");
        operation.put("currency", "\"CAD\"");
        operation.put("reason", "\"customer returned an item\"");
        operation.put("sequenceNumber", "1");
        operation.put("occurredAt", "\"2026-09-10T12:05:00Z\"");
        // Varargs because a fixture often has to change two fields at once - a reversal names both its
        // type and the full captured amount - and applying only the first silently produced an event
        // that was invalid for a different reason than the test intended.
        for (String override : overrides) applyOverride(operation, override);
        return render(operation);
    }

    private static final String PAYMENT_ID = "11111111-2222-3333-4444-555555555555";

    /** Builds a canonical envelope with the given top-level fields replaced or added. */
    private String envelope(java.util.Map<String, String> overrides) {
        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("eventId", "\"66666666-7777-8888-9999-000000000000\"");
        fields.put("eventType", "\"payment.authorized.v1\"");
        fields.put("schemaVersion", "1");
        fields.put("aggregateId", "\"" + PAYMENT_ID + "\"");
        fields.put("aggregateType", "\"payment\"");
        fields.put("aggregateSequence", "1");
        fields.put("merchantId", "\"demo-merchant\"");
        fields.put("occurredAt", "\"2026-09-10T12:00:00Z\"");
        fields.put("recordedAt", "\"2026-09-10T12:00:00Z\"");
        fields.put("payment", paymentObject("", ""));
        fields.putAll(overrides);
        return "{" + String.join(",", fields.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue()).toList()) + "}";
    }

    /** Canonical envelope whose payment object has one field overridden. */
    private String payment(String paymentOverride) {
        return envelope(java.util.Map.of("payment", paymentObject(paymentOverride, "")));
    }

    /** Canonical envelope whose decision object has one field overridden. */
    private String decision(String decisionOverride) {
        return envelope(java.util.Map.of("payment", paymentObject("", decisionOverride)));
    }

    private String paymentObject(String paymentOverride, String decisionOverride) {
        java.util.LinkedHashMap<String, String> payment = new java.util.LinkedHashMap<>();
        payment.put("id", "\"" + PAYMENT_ID + "\"");
        payment.put("accountId", "\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"");
        payment.put("amountMinor", "2500");
        payment.put("currency", "\"CAD\"");
        payment.put("country", "\"CA\"");
        payment.put("status", "\"AUTHORIZED\"");
        payment.put("decision", decisionObject(decisionOverride));
        payment.put("failureCode", "null");
        payment.put("createdAt", "\"2026-09-10T12:00:00Z\"");
        payment.put("updatedAt", "\"2026-09-10T12:00:00Z\"");
        applyOverride(payment, paymentOverride);
        return render(payment);
    }

    private String decisionObject(String decisionOverride) {
        java.util.LinkedHashMap<String, String> decision = new java.util.LinkedHashMap<>();
        decision.put("outcome", "\"APPROVE\"");
        decision.put("score", "0");
        decision.put("ruleSetVersion", "\"demo-v1\"");
        decision.put("reasons", "[{\"code\":\"NO_RISK_SIGNALS\",\"description\":\"None matched.\",\"scoreContribution\":0}]");
        decision.put("flags", "[]");
        applyOverride(decision, decisionOverride);
        return render(decision);
    }

    private static void applyOverride(java.util.Map<String, String> target, String override) {
        if (override == null || override.isBlank()) return;
        int separator = override.indexOf(':');
        String key = override.substring(0, separator).replace("\"", "");
        target.put(key, override.substring(separator + 1));
    }

    private static String render(java.util.Map<String, String> fields) {
        return "{" + String.join(",", fields.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue()).toList()) + "}";
    }

    private static final class Map {
        static java.util.Map<String, String> of(Object... pairs) {
            java.util.LinkedHashMap<String, String> result = new java.util.LinkedHashMap<>();
            for (int index = 0; index < pairs.length; index += 2) {
                result.put((String) pairs[index], (String) pairs[index + 1]);
            }
            return result;
        }
    }
}
