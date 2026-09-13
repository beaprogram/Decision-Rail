package com.decisionrail.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * An exporter that is switched on and cannot reach anything.
 *
 * <p>This is a different condition from export being disabled, and the two are easy to conflate. With
 * export off, nothing is ever handed to an exporter. Here the exporter exists, is enabled, accepts
 * every span, and fails on each attempt to send: there is a queue that can fill, a timeout that can be
 * waited on, and retries that can pile up behind a dead endpoint. That is the arrangement in which
 * telemetry could plausibly take a payment down with it.
 *
 * <p>The endpoint points at a port on loopback with nothing listening, which fails fast and locally
 * rather than depending on a DNS timeout or an outbound network the build may not have.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "management.otlp.tracing.export.enabled=true",
        "management.otlp.tracing.endpoint=http://127.0.0.1:9/v1/traces",
        "management.otlp.tracing.timeout=1s",
        "management.tracing.sampling.probability=1.0",
})
class UnreachableCollectorTest {
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");

    @DynamicPropertySource
    static void noListeners(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void paymentsCommitNormallyWhileEveryExportAttemptFails() throws Exception {
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                account, "demo-merchant", 500_000, 500_000);

        // Several in a row, so any queueing or per-export timeout would accumulate across them rather
        // than being absorbed once.
        for (int attempt = 0; attempt < 5; attempt++) {
            UUID payment = authorize(account, 1_100);
            assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment))
                    .isEqualTo("AUTHORIZED");
            // The financial effect is durable and complete, exporter or no exporter.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, payment))
                    .isEqualTo(1L);
        }

        assertThat(jdbc.queryForObject("SELECT held_minor FROM accounts WHERE id = ?", Long.class, account))
                .as("every authorization reserved its funds exactly once")
                .isEqualTo(5_500L);
    }

    @Test
    void readinessIsUnaffectedByAnUnreachableCollector() throws Exception {
        // Telemetry is not a dependency of serving payments, so it must not appear in readiness.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/actuator/health/readiness"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    private UUID authorize(UUID account, long amount) throws Exception {
        String body = json.writeValueAsString(
                Map.of("accountId", account, "amountMinor", amount, "currency", "CAD", "country", "CA"));
        String response = mvc.perform(post("/v1/payments/authorizations").header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        assertThat(node.path("status").asText()).isEqualTo("AUTHORIZED");
        return UUID.fromString(node.path("id").asText());
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
