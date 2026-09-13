package com.decisionrail.telemetry;

import com.decisionrail.events.OutboxDispatcher;
import com.decisionrail.shadow.ShadowWorker;
import com.decisionrail.support.BrokerProbe;
import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Correlation through shadow evaluation, which is the hardest case in this system.
 *
 * <p>A shadow task is enqueued by a Kafka consumer and evaluated later by a worker, on a pooled thread,
 * possibly in another process and possibly after another worker's lease expired. Three separate pieces
 * of thread context are gone by then. The only thing that survives is the row, so the trace is written
 * into the row and read back out of it.
 *
 * <p>A non-empty trace id would prove nothing here — every span has one. These assertions compare
 * identity: the evaluation's trace must be the same trace as the command that caused the work, and the
 * evaluation span's parent must be the span recorded with the task.
 */
@SpringBootTest(classes = {com.decisionrail.DecisionRailApplication.class,
        ShadowCorrelationTest.RecordingExporter.class})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "management.tracing.sampling.probability=1.0",
        "app.events.dispatcher.enabled=false",
        "app.shadow.enabled=false",
})
class ShadowCorrelationTest {
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final String ADMIN = basic("admin", "admin-test-password-123");
    private static final Duration BUDGET = Duration.ofSeconds(30);
    private static final String STRICT_CANDIDATE = """
            {"rules":[{"code":"STRICT_AMOUNT","description":"Declines at or above 1000 minor units.",
              "scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,
              "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}]}""";

    @TestConfiguration
    static class RecordingExporter {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    private static String topic;

    @DynamicPropertySource
    static void topics(DynamicPropertyRegistry registry) {
        BrokerProbe.requireReachable();
        topic = "test.shadowtrace." + UUID.randomUUID().toString().substring(0, 8);
        BrokerProbe.ensureTopic(topic, 1);
        registry.add("spring.kafka.bootstrap-servers", BrokerProbe::bootstrapServers);
        registry.add("app.events.topic", () -> topic);
        registry.add("app.events.projection-group", () -> topic + "-projection");
        registry.add("app.events.shadow-group", () -> topic + "-shadow");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired ShadowWorker worker;
    @Autowired InMemorySpanExporter spans;
    @Autowired SdkTracerProvider tracerProvider;

    private String candidate;

    @BeforeEach
    void enableShadow() throws Exception {
        spans.reset();
        candidate = "shadow-trace-" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/v1/policies").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"versionId\":\"" + candidate + "\",\"definition\":" + STRICT_CANDIDATE + "}"));
        configureShadow(true, candidate);
    }

    @AfterEach
    void disableShadow() throws Exception {
        configureShadow(false, null);
    }

    @Test
    void anEvaluationOnAWorkerThreadStillBelongsToTheCommandThatCausedIt() throws Exception {
        UUID payment = authorize(2_500);
        String commandTrace = (String) jdbc.queryForMap(
                "SELECT origin_trace_id FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence LIMIT 1",
                payment).get("origin_trace_id");
        assertThat(commandTrace).matches("[0-9a-f]{32}");

        publish(payment);

        // The consumer enqueues durable work, carrying the trace into the row.
        Map<String, Object> task = Waits.value("the shadow task is enqueued", BUDGET, () -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT origin_trace_id, origin_span_id, origin_trace_sampled FROM shadow_tasks WHERE payment_id = ?",
                    payment);
            return rows.isEmpty() ? null : rows;
        }).getFirst();

        assertThat((String) task.get("origin_trace_id"))
                .as("the task belongs to the trace of the command whose event produced it")
                .isEqualTo(commandTrace);
        assertThat((Boolean) task.get("origin_trace_sampled")).isTrue();
        String enqueueSpanId = (String) task.get("origin_span_id");
        assertThat(enqueueSpanId).matches("[0-9a-f]{16}");

        // Evaluated on a thread with no ambient context at all, which is what a worker in a restarted
        // process has. Anything inherited from this test's thread would make the assertion meaningless.
        runWorkerUntilDone(payment);

        List<SpanData> evaluations = spansNamed("shadow.evaluate");
        assertThat(evaluations)
                .as("the evaluation must be recorded")
                .isNotEmpty();
        assertThat(evaluations)
                .as("and it must be in the command's trace, parented to the span that enqueued it")
                .anySatisfy(span -> {
                    assertThat(span.getTraceId()).isEqualTo(commandTrace);
                    assertThat(span.getParentSpanId()).isEqualTo(enqueueSpanId);
                });

        // The comparison really was produced, so this is correlation over work that happened.
        Waits.until("the comparison is recorded", BUDGET, () -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM shadow_comparisons WHERE payment_id = ?", Boolean.class, payment)));
    }

    @Test
    void aTaskEnqueuedBeforeCorrelationExistedStillEvaluates() throws Exception {
        UUID payment = authorize(3_100);
        publish(payment);
        Waits.until("the shadow task is enqueued", BUDGET, () -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM shadow_tasks WHERE payment_id = ?", Boolean.class, payment)));

        // Exactly the shape a task written before V9 has.
        jdbc.update("""
                UPDATE shadow_tasks SET origin_trace_id = NULL, origin_span_id = NULL, origin_trace_sampled = NULL
                WHERE payment_id = ?
                """, payment);

        runWorkerUntilDone(payment);

        assertThat(jdbc.queryForObject("SELECT state FROM shadow_tasks WHERE payment_id = ?", String.class, payment))
                .as("missing correlation must not stop the work being done")
                .isEqualTo("DONE");
        Waits.until("the comparison is recorded", BUDGET, () -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM shadow_comparisons WHERE payment_id = ?", Boolean.class, payment)));
    }

    // ----- helpers -----

    /**
     * Runs worker cycles on a thread that has never seen this test's tracing context, until this
     * test's own task is done.
     *
     * <p>Scoped to one payment deliberately. The suite shares a database and the worker claims whatever
     * is pending, so a fixed number of cycles can spend itself entirely on another test's leftover
     * tasks and leave this one untouched — which looks identical to correlation being broken.
     */
    private void runWorkerUntilDone(UUID payment) throws Exception {
        var pool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "detached-shadow-worker");
            thread.setDaemon(true);
            return thread;
        });
        try {
            pool.submit(() -> {
                Waits.until("the shadow task for " + payment + " is evaluated", BUDGET, () -> {
                    worker.runOnce();
                    return "DONE".equals(jdbc.queryForObject(
                            "SELECT state FROM shadow_tasks WHERE payment_id = ?", String.class, payment));
                });
            }).get(BUDGET.toSeconds() + 5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private List<SpanData> spansNamed(String name) {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        return spans.getFinishedSpanItems().stream().filter(span -> span.getName().equals(name)).toList();
    }

    private void publish(UUID payment) {
        Waits.until("the event for " + payment + " is published", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT count(*) = 0 FROM outbox_events WHERE aggregate_id = ? AND status <> 'PUBLISHED'",
                    Boolean.class, payment));
        });
    }

    private void configureShadow(boolean enabled, String version) throws Exception {
        mvc.perform(put("/v1/ops/shadow").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(version == null
                        ? Map.of("enabled", enabled)
                        : Map.of("enabled", enabled, "candidateVersion", version))));
    }

    private UUID authorize(long amount) throws Exception {
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                account, "demo-merchant", amount * 4, amount * 4);
        String body = json.writeValueAsString(
                Map.of("accountId", account, "amountMinor", amount, "currency", "CAD", "country", "CA"));
        JsonNode node = json.readTree(mvc.perform(post("/v1/payments/authorizations").header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
        assertThat(node.path("status").asText()).isEqualTo("AUTHORIZED");
        return UUID.fromString(node.path("id").asText());
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
