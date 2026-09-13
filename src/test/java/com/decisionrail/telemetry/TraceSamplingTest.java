package com.decisionrail.telemetry;

import com.decisionrail.events.OutboxDispatcher;
import com.decisionrail.support.BrokerProbe;
import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

/**
 * The sampling decision, followed through the real SDK rather than assumed.
 *
 * <p>Valid trace and span identifiers exist whether or not a span was recorded, so nothing here
 * asserts that an id is non-empty. These tests read the decision out of the database, out of the Kafka
 * header, and out of spans the SDK actually exported, at sampling probability 1 and 0, and for an
 * explicitly unsampled parent arriving from outside.
 *
 * <p>Before this contract existed, an unsampled request produced a sampled publication: the origin was
 * stored without its decision, the traceparent was emitted with flags {@code 01}, and delivery rebuilt
 * its parent with {@code sampled(true)}.
 */
class TraceSamplingTest {
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final Duration BUDGET = Duration.ofSeconds(30);

    /** Collects the spans the SDK really produced, so "recorded" is observed rather than inferred. */
    @TestConfiguration
    static class RecordingExporter {
        // One bean. InMemorySpanExporter is itself a SpanExporter, so Spring Boot's tracing
        // autoconfiguration picks it up; declaring it twice made the concrete type ambiguous.
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Nested
    @SpringBootTest(classes = {com.decisionrail.DecisionRailApplication.class, RecordingExporter.class})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "management.tracing.sampling.probability=1.0",
            "app.events.dispatcher.enabled=false",
    })
    class WhenEverythingIsSampled extends Fixture {
        @Test
        void theDecisionIsStored_propagated_andTheSpansAreRecorded() throws Exception {
            UUID payment = authorize(4_100);

            Map<String, Object> row = outboxRow(payment);
            assertThat((Boolean) row.get("origin_trace_sampled"))
                    .as("a sampled request records its decision beside the event")
                    .isTrue();

            publish(payment);
            String traceParent = header(recordFor(payment));
            assertThat(traceParent).endsWith("-01");
            assertThat(traceParent).startsWith("00-" + row.get("origin_trace_id"));

            // The publication really was recorded, in the same trace as the command.
            List<SpanData> published = spansNamed("outbox.publish");
            assertThat(published).isNotEmpty();
            assertThat(published).anySatisfy(span ->
                    assertThat(span.getTraceId()).isEqualTo(row.get("origin_trace_id")));
            assertThat(published).allSatisfy(span -> assertThat(span.getSpanContext().isSampled()).isTrue());
        }
    }

    @Nested
    @SpringBootTest(classes = {com.decisionrail.DecisionRailApplication.class, RecordingExporter.class})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "management.tracing.sampling.probability=0.0",
            "app.events.dispatcher.enabled=false",
    })
    class WhenNothingIsSampled extends Fixture {
        @Test
        void theDecisionSurvivesInsteadOfBeingReversedDownstream() throws Exception {
            UUID payment = authorize(2_600);

            Map<String, Object> row = outboxRow(payment);
            String storedTrace = (String) row.get("origin_trace_id");
            // An unsampled span still has valid identifiers. That is exactly why the flag is stored:
            // the identifiers alone would have looked identical to a sampled request.
            assertThat(storedTrace).as("an unsampled request still has a usable trace id").matches("[0-9a-f]{32}");
            assertThat((Boolean) row.get("origin_trace_sampled"))
                    .as("and the decision not to record it is kept")
                    .isFalse();

            publish(payment);
            String traceParent = header(recordFor(payment));
            assertThat(traceParent)
                    .as("the consumer must inherit the decision, not take a fresh one")
                    .endsWith("-00");
            assertThat(traceParent)
                    .as("continuity is preserved rather than the context being dropped, which would make a new root")
                    .startsWith("00-" + storedTrace);

            // Nothing from this run was exported, because nothing was sampled.
            assertThat(spansNamed("outbox.publish"))
                    .as("an unsampled trace records no publication span")
                    .noneSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(storedTrace));
        }
    }

    @Nested
    @SpringBootTest(classes = {com.decisionrail.DecisionRailApplication.class, RecordingExporter.class})
    @AutoConfigureMockMvc
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "management.tracing.sampling.probability=1.0",
            "app.events.dispatcher.enabled=false",
    })
    class WhenAnUpstreamParentSaysDoNotRecord extends Fixture {
        @Test
        void thatDecisionIsHonouredEvenThoughThisDeploymentWouldSample() {
            String upstreamTrace = "4bf92f3577b34da6a3ce929d0e0e4736";
            String upstreamSpan = "00f067aa0ba902b7";

            // A consume span parented to an explicitly unsampled remote context.
            org.apache.kafka.common.header.Headers headers = new org.apache.kafka.common.header.internals.RecordHeaders();
            headers.add(DeliveryTracing.TRACE_PARENT,
                    ("00-" + upstreamTrace + "-" + upstreamSpan + "-00").getBytes(StandardCharsets.UTF_8));
            try (DeliveryTracing.Scope scope = tracing.consume(headers, "test-group", "consumer.project")) {
                assertThat(scope).isNotNull();
            }

            // The trace continues, and this hop did not promote itself to sampled despite a
            // probability of 1 for traces it starts itself.
            assertThat(spansNamed("consumer.project"))
                    .as("an unsampled upstream decision must not be re-decided here")
                    .noneSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(upstreamTrace));
        }
    }

    // ----- shared fixture -----

    abstract static class Fixture {
        @Autowired MockMvc mvc;
        @Autowired ObjectMapper json;
        @Autowired JdbcTemplate jdbc;
        @Autowired OutboxDispatcher dispatcher;
        @Autowired InMemorySpanExporter spans;
        @Autowired DeliveryTracing tracing;
        @Autowired io.opentelemetry.sdk.trace.SdkTracerProvider tracerProvider;

        /**
         * Assigned once for the whole class. Each nested case starts its own context and runs this
         * hook, and a mutable field would leave the last one to initialise deciding which topic every
         * reader drains — including readers belonging to a context that published somewhere else.
         */
        static final String topic = "test.sampling." + UUID.randomUUID().toString().substring(0, 8);

        @DynamicPropertySource
        static void topics(DynamicPropertyRegistry registry) {
            BrokerProbe.requireReachable();
            BrokerProbe.ensureTopic(topic, 1);
            registry.add("spring.kafka.bootstrap-servers", BrokerProbe::bootstrapServers);
            registry.add("app.events.topic", () -> topic);
            registry.add("app.events.projection-group", () -> topic + "-projection");
            registry.add("app.events.shadow-group", () -> topic + "-shadow");
        }

        @BeforeEach
        void clearRecordedSpans() {
            spans.reset();
        }

        /**
         * Flushes before reading. Spans reach an exporter through a batch processor, so asserting
         * straight after the work is done reads an empty list and every "none of these spans exist"
         * assertion passes for the wrong reason.
         */
        List<SpanData> spansNamed(String name) {
            tracerProvider.forceFlush().join(5, java.util.concurrent.TimeUnit.SECONDS);
            return spans.getFinishedSpanItems().stream().filter(span -> span.getName().equals(name)).toList();
        }

        Map<String, Object> outboxRow(UUID payment) {
            return jdbc.queryForMap("""
                    SELECT origin_trace_id, origin_span_id, origin_trace_sampled, status
                    FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence LIMIT 1
                    """, payment);
        }

        void publish(UUID payment) {
            Waits.until("the event for " + payment + " is published", BUDGET, () -> {
                dispatcher.dispatchOnce();
                return "PUBLISHED".equals(outboxRow(payment).get("status"));
            });
        }

        ConsumerRecord<String, String> recordFor(UUID payment) {
            return Waits.value("the published record for " + payment, BUDGET,
                    () -> BrokerProbe.drain(topic, Duration.ofSeconds(2)).stream()
                            .filter(record -> payment.toString().equals(record.key()))
                            .toList()).getFirst();
        }

        static String header(ConsumerRecord<String, String> record) {
            var header = record.headers().lastHeader(DeliveryTracing.TRACE_PARENT);
            return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        }

        UUID authorize(long amount) throws Exception {
            UUID account = UUID.randomUUID();
            jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                    account, "demo-merchant", amount * 4, amount * 4);
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
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
