package com.decisionrail.events;

import com.decisionrail.support.BrokerProbe;
import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.CommonClientConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The application starting while the broker's name does not resolve, and recovering afterwards.
 *
 * <p>This context is pointed at a hostname in the reserved {@code .invalid} top-level domain, which is
 * guaranteed never to resolve. That is the precise condition that used to fail: a listener container
 * builds its consumer as it starts, the client resolves {@code bootstrap.servers} there and then, and
 * an unresolvable name throws {@code ConfigException} out of Spring's lifecycle processor and takes the
 * whole context with it.
 *
 * <p>A resolvable address with a closed port is a different case and was never affected - the consumer
 * constructs fine and discovers the problem later. The two are not interchangeable, and this test
 * covers the one that actually broke.
 *
 * <p>The context loading at all is the first assertion. If the defect were present this class would
 * fail before a single test method ran.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnresolvableBrokerStartupTest {
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "test.unresolvable." + RUN;
    /** RFC 2606 reserves .invalid precisely so it can never resolve. */
    private static final String UNRESOLVABLE = "broker-that-does-not-exist." + RUN + ".invalid:9092";
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final Duration BUDGET = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void pointAtAnUnresolvableBroker(DynamicPropertyRegistry registry) {
        BrokerProbe.requireReachable();
        BrokerProbe.ensureTopic(TOPIC, 1);
        registry.add("spring.kafka.bootstrap-servers", () -> UNRESOLVABLE);
        registry.add("app.events.topic", () -> TOPIC);
        registry.add("app.events.projection-group", () -> "test-unresolvable-projection-" + RUN);
        registry.add("app.events.shadow-group", () -> "test-unresolvable-shadow-" + RUN);
        // Listeners are wanted here; the point is that wanting them no longer prevents startup.
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        registry.add("app.events.listener-start-interval", () -> "500ms");
        registry.add("app.events.dispatcher.enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ListenerStarter listeners;
    @Autowired org.springframework.kafka.core.ConsumerFactory<String, String> paymentEventConsumerFactory;
    @Autowired DefaultKafkaProducerFactory<String, String> producerFactory;

    /**
     * One scenario, in order, because the second half depends on the state the first half observes.
     *
     * <p>Split into two test methods these would share a context and race: whichever ran first would
     * decide whether the listeners were running, and the other would assert against whatever it found.
     * Making the broker reachable is a one-way door for this context, so the sequence is written out
     * rather than left to the runner.
     */
    @Test
    void theApiWorksWithAnUnresolvableBrokerAndDeliveryRecoversWithoutARestart() throws Exception {
        // ----- while the broker's name does not resolve -----

        // Readiness is about the payment path, which needs the database and not the broker.
        assertThat(health("/actuator/health/readiness").path("status").asText()).isEqualTo("UP");
        assertThat(health("/actuator/health/liveness").path("status").asText()).isEqualTo("UP");

        // The impairment is reported rather than hidden, and named: the consumers are not running.
        JsonNode async = health("/actuator/health/async");
        assertThat(async.path("status").asText()).isEqualTo("DEGRADED");
        JsonNode details = async.path("components").path("asyncDelivery").path("details");
        assertThat(details.path("consumersRunning").asBoolean()).isFalse();
        assertThat(details.path("paymentApiAffected").asBoolean()).isFalse();
        assertThat(listeners.allListenersRunning()).isFalse();

        // And money still moves: authorize, capture, refund, all committed with no broker in sight.
        UUID account = newAccount(500_000);
        UUID payment = authorize(account, 9_000);
        capture(payment);
        UUID refundId = refund(payment, 3_000);

        assertThat(jdbc.queryForObject("SELECT returned_amount_minor FROM payments WHERE id = ?", Long.class, payment))
                .isEqualTo(3_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_journals WHERE source_return_id = ?",
                Long.class, refundId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, account))
                .isEqualTo(500_000 - 9_000 + 3_000);

        // The event intent is durable and undelivered, which is the correct state, not a lost event.
        assertThat(jdbc.queryForObject("""
                SELECT status FROM outbox_events WHERE aggregate_id = ? AND event_type = 'payment.refunded.v1'
                """, String.class, payment)).isNotEqualTo("PUBLISHED");

        // ----- once the name resolves again -----

        // Nothing restarts the application and nothing starts the containers by hand: the retry that
        // was already scheduled is what picks them up.
        ((DefaultKafkaConsumerFactory<String, String>) paymentEventConsumerFactory).updateConfigs(
                Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, BrokerProbe.bootstrapServers()));
        producerFactory.updateConfigs(
                Map.of(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, BrokerProbe.bootstrapServers()));
        producerFactory.reset();

        Waits.until("the listeners start without the application restarting", BUDGET,
                () -> listeners.allListenersRunning());

        assertThat(health("/actuator/health/async")
                .path("components").path("asyncDelivery").path("details")
                .path("consumersRunning").asBoolean()).isTrue();
    }

    // ----- helpers -----

    private JsonNode health(String path) throws Exception {
        return json.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsString());
    }

    private UUID newAccount(long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,'demo-merchant','CAD',?,?)",
                id, balance, balance);
        return id;
    }

    private UUID authorize(UUID account, long amount) throws Exception {
        String body = json.writeValueAsString(
                Map.of("accountId", account, "amountMinor", amount, "currency", "CAD", "country", "CA"));
        var response = mvc.perform(post("/v1/payments/authorizations").header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("authorization must succeed with no broker").isEqualTo(201);
        return UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText());
    }

    private void capture(UUID payment) throws Exception {
        assertThat(mvc.perform(post("/v1/payments/{id}/capture", payment).header("Authorization", DEMO)
                .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    private UUID refund(UUID payment, long amount) throws Exception {
        String body = json.writeValueAsString(Map.of("amountMinor", amount, "reason", "no broker"));
        var response = mvc.perform(post("/v1/payments/{id}/refunds", payment).header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("a refund must commit with no broker").isEqualTo(201);
        return UUID.fromString(json.readTree(response.getContentAsString()).path("returnId").asText());
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
