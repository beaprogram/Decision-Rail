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
        assertThatThrownBy(() -> contract.parse(envelope(Map.of("eventType", "\"payment.refunded.v1\""))))
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
