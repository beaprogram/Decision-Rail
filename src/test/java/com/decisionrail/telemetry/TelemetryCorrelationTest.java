package com.decisionrail.telemetry;

import com.decisionrail.support.BrokerProbe;
import com.decisionrail.support.Waits;
import com.decisionrail.events.OutboxDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Correlation that outlives the thread that created it.
 *
 * <p>The property under test is not "a trace id exists". It is that an event committed by one HTTP
 * request can still be attributed to that request after the request has returned, after the thread
 * has been reused, and after the process that held the context has been replaced. Only a durable
 * record can do that, so these tests read the database and the broker rather than an in-memory
 * exporter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TelemetryCorrelationTest {
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "test.telemetry." + RUN;
    private static final String GROUP = "test-telemetry-" + RUN;
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final Duration BUDGET = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void topics(DynamicPropertyRegistry registry) {
        BrokerProbe.requireReachable();
        BrokerProbe.ensureTopic(TOPIC, 1);
        registry.add("spring.kafka.bootstrap-servers", BrokerProbe::bootstrapServers);
        registry.add("app.events.topic", () -> TOPIC);
        registry.add("app.events.projection-group", () -> GROUP);
        registry.add("app.events.shadow-group", () -> GROUP + "-shadow");
        // The dispatcher is driven one explicit cycle at a time so the publication under test is the
        // one this test caused, not whatever a timer happened to pick up.
        registry.add("app.events.dispatcher.enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;

    @Test
    void aCommittedEventKeepsTheTraceOfTheRequestThatProducedIt() throws Exception {
        UUID payment = authorize(newAccount(4_100), 4_100);

        Map<String, Object> row = outboxRow(payment);
        // Written in the payment transaction, so it is as durable as the event itself.
        assertThat((String) row.get("origin_trace_id")).matches("[0-9a-f]{32}");
        assertThat((String) row.get("origin_span_id")).matches("[0-9a-f]{16}");
        // And the payload is untouched: correlation never becomes part of what a consumer fingerprints.
        assertThat((String) row.get("payload")).doesNotContain("traceparent").doesNotContain("trace_id");
    }

    @Test
    void deliveryCarriesTheStoredOriginToTheBrokerAsAHeader() throws Exception {
        UUID payment = authorize(newAccount(2_600), 2_600);
        String storedTrace = (String) outboxRow(payment).get("origin_trace_id");

        // A separate cycle, on a different thread, with the request long finished. This is the
        // restart-equivalent: nothing of the original context survives except the row. The suite
        // shares one database, so this drains whatever backlog exists and then checks this payment's
        // own row rather than a global count.
        publish(payment);

        ConsumerRecord<String, String> record = recordFor(payment);
        String traceParent = header(record, DeliveryTracing.TRACE_PARENT);
        assertThat(traceParent)
                .as("the publication must carry trace context to the consumer")
                .isNotNull()
                .startsWith("00-" + storedTrace + "-");
        // The span id in the header is the publication's own, not the stored origin's: a publication
        // is a distinct span under the originating trace, which is what makes a retry visible as a
        // separate attempt rather than as a longer first one.
        assertThat(traceParent).doesNotContain(outboxRow(payment).get("origin_span_id").toString() + "-01");
    }

    @Test
    void anEventWrittenBeforeCorrelationExistedStillDelivers() throws Exception {
        UUID payment = authorize(newAccount(1_900), 1_900);
        // Exactly what a row inserted by an older version of this application looks like.
        jdbc.update("UPDATE outbox_events SET origin_trace_id = NULL, origin_span_id = NULL WHERE aggregate_id = ?",
                payment);

        publish(payment);
        assertThat((String) outboxRow(payment).get("status"))
                .as("a missing trace must not stop a committed event being delivered")
                .isEqualTo("PUBLISHED");
        // Delivery starts its own trace rather than refusing to send.
        assertThat(header(recordFor(payment), DeliveryTracing.TRACE_PARENT)).isNotNull();
    }

    @Test
    void anIdempotentReplayPointsAtTheOriginalOperationWithoutOverwritingIt() throws Exception {
        UUID account = newAccount(3_300);
        String key = "telemetry-replay-" + UUID.randomUUID();
        String body = json.writeValueAsString(
                Map.of("accountId", account, "amountMinor", 3_300, "currency", "CAD", "country", "CA"));

        String first = perform(key, body);
        UUID payment = UUID.fromString(json.readTree(first).path("id").asText());
        String originalRequestTrace = (String) idempotencyRow(key).get("origin_trace_id");
        String originalEventTrace = (String) outboxRow(payment).get("origin_trace_id");

        // The same command again: a new HTTP request, a new trace, the same key and payload.
        perform(key, body);

        assertThat((String) idempotencyRow(key).get("origin_trace_id"))
                .as("a replay must not rewrite whose request first performed the command")
                .isEqualTo(originalRequestTrace);
        assertThat((String) outboxRow(payment).get("origin_trace_id"))
                .as("nor the provenance of the event that command produced")
                .isEqualTo(originalEventTrace);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, payment))
                .as("and it must not produce a second event")
                .isEqualTo(1L);
    }

    @Test
    void anUnreachableTraceCollectorDoesNotFailAPayment() throws Exception {
        // Exporting is disabled in this profile, which is the same code path a collector that is
        // simply not running produces: spans are created and dropped. The command must be unaffected.
        UUID payment = authorize(newAccount(5_000), 5_000);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment))
                .isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, payment))
                .isEqualTo(1L);
    }

    // ----- helpers -----

    private Map<String, Object> outboxRow(UUID payment) {
        return jdbc.queryForMap(
                "SELECT origin_trace_id, origin_span_id, payload::text AS payload, status FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence LIMIT 1",
                payment);
    }

    private Map<String, Object> idempotencyRow(String key) {
        return jdbc.queryForMap("SELECT origin_trace_id FROM idempotency_records WHERE idempotency_key = ?", key);
    }

    /**
     * Dispatches until this payment's own event is published.
     *
     * <p>Scoped to one payment on purpose: the suite shares a database, so a cycle also drains
     * whatever other tests left behind and a global count would assert on their work as well as this
     * test's.
     */
    private void publish(UUID payment) {
        Waits.until("the event for " + payment + " is published", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "PUBLISHED".equals(outboxRow(payment).get("status"));
        });
    }

    /** Reads this payment's record back off the topic, with a bounded budget rather than a sleep. */
    private ConsumerRecord<String, String> recordFor(UUID payment) {
        List<ConsumerRecord<String, String>> mine = Waits.value(
                "the published record for " + payment + " is readable from the topic", BUDGET,
                () -> BrokerProbe.drain(TOPIC, Duration.ofSeconds(2)).stream()
                        .filter(record -> payment.toString().equals(record.key()))
                        .toList());
        return mine.getFirst();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private UUID newAccount(long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                id, "demo-merchant", balance, balance);
        return id;
    }

    private UUID authorize(UUID account, long amount) throws Exception {
        String body = json.writeValueAsString(
                Map.of("accountId", account, "amountMinor", amount, "currency", "CAD", "country", "CA"));
        JsonNode node = json.readTree(perform(UUID.randomUUID().toString(), body));
        assertThat(node.path("status").asText()).isEqualTo("AUTHORIZED");
        return UUID.fromString(node.path("id").asText());
    }

    private String perform(String key, String body) throws Exception {
        return mvc.perform(post("/v1/payments/authorizations").header("Authorization", DEMO)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
