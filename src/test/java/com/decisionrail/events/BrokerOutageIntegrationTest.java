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
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Behaviour while the broker is genuinely unreachable, and recovery once it returns.
 *
 * <p>The outage is real rather than simulated: this context starts pointed at a closed local
 * port, so every send fails in the Kafka client exactly as it would against a dead broker.
 * Recovery repoints the live producer factory at the working test broker, which exercises the
 * breaker's half-open probe and the dispatcher's resumption from durable state.
 *
 * <p>Listeners are not started here. The question under test is what the producing side and
 * the payment API do during an outage, and a consumer pinned to the same dead port would only
 * add noise.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BrokerOutageIntegrationTest {
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "test.outage." + RUN;
    /** Nothing listens on port 1, so connections are refused immediately and deterministically. */
    private static final String UNREACHABLE = "127.0.0.1:1";
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final Duration BUDGET = Duration.ofSeconds(40);

    @DynamicPropertySource
    static void pointAtADeadBroker(DynamicPropertyRegistry registry) {
        BrokerProbe.requireReachable();
        BrokerProbe.ensureTopic(TOPIC, 3);
        registry.add("spring.kafka.bootstrap-servers", () -> UNREACHABLE);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("app.events.topic", () -> TOPIC);
        registry.add("app.events.projection-group", () -> "test-outage-projection-" + RUN);
        registry.add("app.events.shadow-group", () -> "test-outage-shadow-" + RUN);
        registry.add("app.events.dispatcher.batch-size", () -> "4");
        registry.add("app.events.dispatcher.send-timeout", () -> "1500ms");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxStore outbox;
    @Autowired CircuitBreaker brokerBreaker;
    @Autowired DefaultKafkaProducerFactory<String, String> producerFactory;
    @Autowired DeliveryFaults faults;

    private UUID accountId;

    @BeforeEach
    void prepare() {
        faults.clear();
        brokerBreaker.reset();
        producerFactory.updateConfigs(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, UNREACHABLE));
        producerFactory.reset();
        accountId = newAccount(1_000_000);
    }

    @Test
    void aPaymentCommittedDuringABrokerOutageKeepsItsEventIntentAndItsMoney() throws Exception {
        UUID payment = authorize(accountId, 7_500);
        // The payment API does not depend on the broker at all.
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment))
                .isEqualTo("AUTHORIZED");
        assertThat(heldMinor()).isEqualTo(7_500);

        UUID eventId = eventId(payment);
        assertThat(status(eventId)).isEqualTo("PENDING");

        // Attempts are made and fail; the committed intent is never lost or rewritten.
        Waits.until("the breaker opens after repeated send failures", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return brokerBreaker.state() != CircuitBreaker.State.CLOSED;
        });
        assertThat(status(eventId)).isIn("PENDING", "CLAIMED");
        assertThat(attempts(eventId)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE id = ?", String.class, eventId))
                .isNotBlank();

        // A sustained outage stops costing broker round trips: the open breaker skips claiming.
        OutboxDispatcher.Cycle skipped = dispatcher.dispatchOnce();
        assertThat(skipped.skippedWhileBreakerOpen()).isTrue();
        assertThat(skipped.claimed()).isZero();

        // The retry budget is not eroded by attempts that never reached the broker.
        int attemptsBefore = attempts(eventId);
        for (int cycle = 0; cycle < 10; cycle++) dispatcher.dispatchOnce();
        assertThat(attempts(eventId)).isLessThanOrEqualTo(attemptsBefore + 1);
        assertThat(status(eventId)).isNotEqualTo("FAILED");
    }

    @Test
    void theApiStaysReadyWhileAsynchronousDeliveryReportsItselfDegraded() throws Exception {
        authorize(accountId, 4_400);
        Waits.until("the breaker opens", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return brokerBreaker.state() != CircuitBreaker.State.CLOSED;
        });

        // Readiness is about the synchronous payment path, which is healthy.
        assertThat(health("/actuator/health/readiness").path("status").asText()).isEqualTo("UP");
        assertThat(health("/actuator/health/liveness").path("status").asText()).isEqualTo("UP");

        JsonNode async = health("/actuator/health/async");
        assertThat(async.path("status").asText()).isEqualTo("DEGRADED");
        JsonNode details = async.path("components").path("asyncDelivery").path("details");
        assertThat(details.path("brokerBreaker").asText()).isIn("OPEN", "HALF_OPEN");
        assertThat(details.path("undeliveredEvents").asLong()).isGreaterThan(0);
        assertThat(details.path("paymentApiAffected").asBoolean()).isFalse();

        // And a merchant can still authorise a payment while delivery is degraded.
        UUID duringOutage = authorize(accountId, 1_250);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, duringOutage))
                .isEqualTo("AUTHORIZED");
    }

    @Test
    void deliveryResumesWithTheSameEventIdentityOnceTheBrokerReturns() throws Exception {
        UUID payment = authorize(accountId, 6_100);
        UUID eventId = eventId(payment);
        Waits.until("the breaker opens during the outage", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return brokerBreaker.state() != CircuitBreaker.State.CLOSED;
        });
        assertThat(status(eventId)).isNotEqualTo("PUBLISHED");

        // The broker comes back. Nothing clears the breaker by hand: it has to recover through
        // its own half-open probe, which is the behaviour being verified.
        producerFactory.updateConfigs(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BrokerProbe.bootstrapServers()));
        producerFactory.reset();

        Waits.until("the backlog drains after recovery", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "PUBLISHED".equals(status(eventId));
        });
        assertThat(brokerBreaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(jdbc.queryForObject("SELECT broker_offset FROM outbox_events WHERE id = ?", Long.class, eventId))
                .isNotNull();

        // The event delivered after recovery carries the identity committed before the outage.
        List<ConsumerRecord<String, String>> records = BrokerProbe.drain(TOPIC, Duration.ofSeconds(5));
        List<String> deliveredIds = records.stream().map(record -> {
            try {
                return json.readTree(record.value()).path("eventId").asText();
            } catch (Exception unreadable) {
                return "";
            }
        }).toList();
        assertThat(deliveredIds).contains(eventId.toString());
    }

    // ----- helpers -----

    private JsonNode health(String path) throws Exception {
        String body = mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private long heldMinor() {
        return jdbc.queryForObject("SELECT held_minor FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private UUID eventId(UUID payment) {
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence LIMIT 1",
                UUID.class, payment);
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private int attempts(UUID eventId) {
        return jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, eventId);
    }

    private UUID newAccount(long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,'demo-merchant','CAD',?,?)",
                id, balance, balance);
        return id;
    }

    private UUID authorize(UUID account, long amount) throws Exception {
        String body = json.writeValueAsString(Map.of("accountId", account, "amountMinor", amount, "currency", "CAD", "country", "CA"));
        String response = mvc.perform(post("/v1/payments/authorizations").header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        assertThat(node.path("status").asText()).as("authorization must succeed during a broker outage").isEqualTo("AUTHORIZED");
        return UUID.fromString(node.path("id").asText());
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
