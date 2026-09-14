package com.decisionrail.events;

import com.decisionrail.resilience.CircuitBreaker;
import com.decisionrail.support.BrokerProbe;
import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A refund committed while the broker is unreachable, and its delivery afterwards.
 *
 * <p>Its own context and its own topic, deliberately separate from {@link BrokerOutageIntegrationTest}.
 * The dispatcher claims the oldest due events across the whole outbox, so adding a test that produces
 * undelivered events to that class changes which events its other tests are actually observing. Kept
 * apart, each class's assertions describe the events it created.
 *
 * <p>The outage is real: this context starts pointed at a closed local port, so every send fails in
 * the Kafka client exactly as it would against a dead broker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefundDeliveryIntegrationTest {
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "test.refund-outage." + RUN;
    /** Nothing listens on port 1, so connections are refused immediately and deterministically. */
    private static final String UNREACHABLE = "127.0.0.1:1";
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final Duration BUDGET = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void pointAtADeadBroker(DynamicPropertyRegistry registry) {
        BrokerProbe.requireReachable();
        BrokerProbe.ensureTopic(TOPIC, 1);
        registry.add("spring.kafka.bootstrap-servers", () -> UNREACHABLE);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("app.events.topic", () -> TOPIC);
        registry.add("app.events.projection-group", () -> "test-refund-outage-projection-" + RUN);
        registry.add("app.events.shadow-group", () -> "test-refund-outage-shadow-" + RUN);
        // The largest budget the configuration accepts. The point here is ordered recovery, not
        // exhausting retries, so no event should reach a terminal failure while the broker is down.
        registry.add("app.events.dispatcher.max-attempts", () -> "100");
        registry.add("app.events.dispatcher.send-timeout", () -> "1500ms");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired CircuitBreaker brokerBreaker;
    @Autowired DefaultKafkaProducerFactory<String, String> producerFactory;

    private UUID accountId;

    @BeforeEach
    void prepare() {
        brokerBreaker.reset();
        producerFactory.updateConfigs(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, UNREACHABLE));
        producerFactory.reset();
        accountId = newAccount(1_000_000);
    }

    @Test
    void aRefundCommitsDuringTheOutageAndItsEventIsDeliveredInOrderAfterRecovery() throws Exception {
        UUID payment = authorize(9_000);
        capture(payment);
        long balanceAfterCapture = balance();

        Waits.until("the breaker opens during the outage", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return brokerBreaker.state() != CircuitBreaker.State.CLOSED;
        });

        // A valid refund must commit whether or not the broker can be reached. Money moving and an
        // event becoming deliverable are different concerns, and only the first one is synchronous.
        UUID refundId = refund(payment, 3_000);
        assertThat(balance()).isEqualTo(balanceAfterCapture + 3_000);
        assertThat(jdbc.queryForObject("SELECT returned_amount_minor FROM payments WHERE id = ?", Long.class, payment))
                .isEqualTo(3_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_journals WHERE source_return_id = ?",
                Long.class, refundId)).isEqualTo(1);

        // Its event intent is durable and undelivered, not lost.
        UUID refundEvent = eventOfType(payment, "payment.refunded.v1");
        assertThat(status(refundEvent)).isNotEqualTo("PUBLISHED");

        // The broker returns. Nothing clears the breaker by hand: it recovers through its own probe.
        producerFactory.updateConfigs(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BrokerProbe.bootstrapServers()));
        producerFactory.reset();
        Waits.until("this payment's backlog drains after recovery", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "PUBLISHED".equals(status(refundEvent));
        });

        List<Map<String, Object>> published = jdbc.queryForList("""
                SELECT event_type, aggregate_sequence, broker_offset FROM outbox_events
                WHERE aggregate_id = ? ORDER BY aggregate_sequence
                """, payment);
        assertThat(published).extracting(row -> row.get("event_type"))
                .containsExactly("payment.authorized.v1", "payment.captured.v1", "payment.refunded.v1");
        // One partition, so offsets are assigned in send order: this is the order the broker received
        // them. A refund cannot overtake the capture it compensates.
        List<Long> offsets = published.stream().map(row -> (Long) row.get("broker_offset")).toList();
        assertThat(offsets).doesNotContainNull().isSorted();

        // And the identity committed during the outage is what was delivered.
        List<String> deliveredIds = BrokerProbe.drain(TOPIC, Duration.ofSeconds(10)).stream()
                .map(record -> {
                    try {
                        return json.readTree(record.value()).path("eventId").asText();
                    } catch (Exception unreadable) {
                        return "";
                    }
                }).toList();
        assertThat(deliveredIds).contains(refundEvent.toString());
    }

    @Test
    void aRefundsEventIntentSurvivesARestartOfEverythingHoldingIt() throws Exception {
        UUID payment = authorize(5_000);
        capture(payment);
        UUID refundId = refund(payment, 1_500);
        UUID refundEvent = eventOfType(payment, "payment.refunded.v1");

        // Everything in memory is discarded: the dispatcher's breaker, its lease bookkeeping and any
        // claim it held. A killed process leaves exactly this - a row in the database and nothing
        // else - and the point is that recovery comes from that row rather than from anything the
        // previous process was keeping.
        jdbc.update("""
                UPDATE outbox_events SET status = 'PENDING', lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, next_attempt_at = now()
                WHERE id = ? AND status = 'CLAIMED'
                """, refundEvent);
        brokerBreaker.reset();
        producerFactory.updateConfigs(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BrokerProbe.bootstrapServers()));
        producerFactory.reset();

        Waits.until("the refund event is delivered from durable state alone", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "PUBLISHED".equals(status(refundEvent));
        });

        // The money was never in doubt; it committed before any of this.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_returns WHERE id = ?", Long.class, refundId))
                .isEqualTo(1);
        JsonNode payload = json.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_events WHERE id = ?", String.class, refundEvent));
        assertThat(payload.path("returnOperation").path("id").asText()).isEqualTo(refundId.toString());
        assertThat(payload.path("eventId").asText()).isEqualTo(refundEvent.toString());
    }

    // ----- helpers -----

    private long balance() {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private UUID eventOfType(UUID payment, String eventType) {
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                UUID.class, payment, eventType);
    }

    private UUID newAccount(long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,'demo-merchant','CAD',?,?)",
                id, balance, balance);
        return id;
    }

    private UUID authorize(long amount) throws Exception {
        String body = json.writeValueAsString(
                Map.of("accountId", accountId, "amountMinor", amount, "currency", "CAD", "country", "CA"));
        var response = mvc.perform(post("/v1/payments/authorizations").header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText());
    }

    private void capture(UUID payment) throws Exception {
        int status = mvc.perform(post("/v1/payments/{id}/capture", payment).header("Authorization", DEMO)
                .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn().getResponse().getStatus();
        assertThat(status).as("capture must succeed during a broker outage").isEqualTo(200);
    }

    private UUID refund(UUID payment, long amountMinor) throws Exception {
        String body = json.writeValueAsString(Map.of("amountMinor", amountMinor, "reason", "outage refund"));
        var response = mvc.perform(post("/v1/payments/{id}/refunds", payment).header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("a refund must commit during a broker outage").isEqualTo(201);
        return UUID.fromString(json.readTree(response.getContentAsString()).path("returnId").asText());
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
