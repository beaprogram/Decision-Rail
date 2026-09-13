package com.decisionrail.resilience;

import com.decisionrail.events.DeliveryFaults;
import com.decisionrail.events.DeliveryProperties;
import com.decisionrail.events.EventPublisher;
import com.decisionrail.events.OutboxDispatcher;
import com.decisionrail.events.OutboxStore;
import com.decisionrail.support.BrokerProbe;
import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Resilience controls around the dependencies this phase introduced: bounded work, observable
 * breaker transitions, durable recovery across a worker restart, and the rule that neither a broker
 * failure nor a database failure may produce a wrong financial answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResilienceIntegrationTest {
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "test.resilience." + RUN;
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final String ADMIN = basic("admin", "admin-test-password-123");
    private static final int BATCH_SIZE = 4;
    private static final Duration BUDGET = Duration.ofSeconds(40);

    @DynamicPropertySource
    static void brokerAndBounds(DynamicPropertyRegistry registry) {
        BrokerProbe.requireReachable();
        registry.add("spring.kafka.bootstrap-servers", BrokerProbe::bootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("app.events.topic", () -> TOPIC);
        registry.add("app.events.projection-group", () -> "test-resilience-projection-" + RUN);
        registry.add("app.events.shadow-group", () -> "test-resilience-shadow-" + RUN);
        registry.add("app.events.dispatcher.batch-size", () -> Integer.toString(BATCH_SIZE));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxStore outbox;
    @Autowired EventPublisher publisher;
    @Autowired com.decisionrail.telemetry.DeliveryTracing tracing;
    @Autowired DeliveryProperties properties;
    @Autowired DeliveryFaults faults;
    @Autowired CircuitBreaker brokerBreaker;
    @Autowired TransactionTemplate transactions;
    @Autowired Clock clock;
    @Autowired MeterRegistry metrics;

    private UUID accountId;

    @BeforeEach
    void prepare() {
        faults.clear();
        brokerBreaker.reset();
        drainExistingBacklog();
        accountId = newAccount(10_000_000);
    }

    @AfterEach
    void disarm() {
        faults.clear();
        brokerBreaker.reset();
    }

    @Test
    void aDispatchCycleNeverClaimsMoreThanItsConfiguredBatch() throws Exception {
        List<UUID> payments = new ArrayList<>();
        for (int index = 0; index < BATCH_SIZE * 3; index++) {
            payments.add(authorize(1_000 + index));
        }
        // Every event is due, so only the batch bound can limit what a single cycle takes on.
        assertThat(pendingFor(payments)).isEqualTo(BATCH_SIZE * 3L);

        // Sends fail for every one of them, so nothing drains and the bound is what is observed.
        payments.forEach(faults::rejectSendsFor);
        OutboxDispatcher.Cycle cycle = dispatcher.dispatchOnce();
        payments.forEach(faults::allowSendsFor);

        assertThat(cycle.claimed()).isEqualTo(BATCH_SIZE);
        assertThat(cycle.claimed()).isLessThan(payments.size());
        // And no more than one batch is ever leased at a time by this worker.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = 'CLAIMED'", Long.class))
                .isLessThanOrEqualTo((long) BATCH_SIZE);

        brokerBreaker.reset();
        Waits.until("the whole backlog eventually drains in bounded batches", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return pendingFor(payments) == 0;
        });
    }

    @Test
    void pendingWorkSurvivesAWorkerProcessRestart() throws Exception {
        UUID payment = authorize(3_300);
        UUID eventId = eventId(payment);

        // A worker claims the event and then the process disappears: no completion, no release.
        var abandoned = transactions.execute(status -> outbox.claim("worker-before-restart", 10,
                clock.instant(), clock.instant().plus(properties.dispatcher().leaseDuration())));
        assertThat(abandoned).isNotEmpty();
        assertThat(status(eventId)).isEqualTo("CLAIMED");
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM outbox_events WHERE id = ?", String.class, eventId))
                .isEqualTo("worker-before-restart");

        // A genuinely different worker identity stands in for the restarted process.
        OutboxDispatcher restarted = new OutboxDispatcher(outbox, publisher, properties, faults, brokerBreaker,
                transactions, clock, metrics, tracing);
        assertThat(restarted.workerId()).isNotEqualTo("worker-before-restart");

        // It cannot simply steal the claim; the work becomes available when the lease expires.
        assertThat(restarted.dispatchOnce().claimed()).isZero();
        assertThat(status(eventId)).isEqualTo("CLAIMED");
        assertThat(restarted.reclaimExpiredLeases(
                clock.instant().plus(properties.dispatcher().leaseDuration()).plusSeconds(1))).isGreaterThanOrEqualTo(1);

        Waits.until("the restarted worker delivers the recovered event", BUDGET, () -> {
            restarted.dispatchOnce();
            return "PUBLISHED".equals(status(eventId));
        });
        // Recovered, not lost, and not duplicated in the outbox.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, payment))
                .isEqualTo(1);
    }

    @Test
    void breakerOpeningAndRecoveryAreObservableThroughMetricsAndTheOperatorApi() throws Exception {
        assertThat(gauge("decisionrail.broker.breaker.state")).isZero();
        assertThat(readJson("/v1/ops/outbox/backlog", ADMIN).path("breakerState").asText()).isEqualTo("CLOSED");

        UUID payment = authorize(2_700);
        faults.rejectSendsFor(payment);
        Waits.until("the breaker opens", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return brokerBreaker.state() == CircuitBreaker.State.OPEN;
        });

        // Observable in both places an operator would look.
        assertThat(gauge("decisionrail.broker.breaker.state")).isEqualTo(2.0);
        assertThat(readJson("/v1/ops/outbox/backlog", ADMIN).path("breakerState").asText()).isEqualTo("OPEN");
        JsonNode degraded = readJson("/actuator/health/async", null);
        assertThat(degraded.path("status").asText()).isEqualTo("DEGRADED");

        // While open, cycles cost no broker round trip at all.
        OutboxDispatcher.Cycle skipped = dispatcher.dispatchOnce();
        assertThat(skipped.skippedWhileBreakerOpen()).isTrue();
        assertThat(skipped.claimed()).isZero();

        // Recovery happens through the half-open probe, not by resetting anything by hand.
        faults.allowSendsFor(payment);
        Waits.until("the breaker closes again after a successful probe", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return brokerBreaker.state() == CircuitBreaker.State.CLOSED;
        });
        assertThat(gauge("decisionrail.broker.breaker.state")).isZero();
        Waits.until("delivery resumes", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "PUBLISHED".equals(status(eventId(payment)));
        });
        assertThat(readJson("/actuator/health/async", null).path("components").path("asyncDelivery")
                .path("details").path("brokerBreaker").asText()).isEqualTo("CLOSED");
    }

    @Test
    void theOperatorApiReportsDurableBacklogAgeAndBlockedStreams() throws Exception {
        UUID blocked = authorize(2_900);
        UUID eventId = eventId(blocked);

        faults.rejectSendsFor(blocked);
        Waits.until("the event exhausts its retry budget", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "FAILED".equals(status(eventId));
        });
        faults.allowSendsFor(blocked);
        brokerBreaker.reset();

        JsonNode backlog = readJson("/v1/ops/outbox/backlog", ADMIN);
        assertThat(backlog.path("countsByStatus").path("FAILED").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(backlog.path("blockedPaymentCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(backlog.path("oldestPendingAgeSeconds").asLong()).isGreaterThanOrEqualTo(0);

        // Terminal failures are surfaced as degraded asynchronous capability, never as an unready
        // instance: the payment API is still correct and must stay in rotation.
        assertThat(readJson("/actuator/health/async", null).path("status").asText()).isEqualTo("DEGRADED");
        assertThat(readJson("/actuator/health/readiness", null).path("status").asText()).isEqualTo("UP");

        // Redrive clears it, and the redriven event keeps its identity.
        String body = json.writeValueAsString(Map.of("paymentId", blocked.toString()));
        JsonNode redrive = json.readTree(mvc.perform(post("/v1/ops/outbox/redrive").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
        assertThat(redrive.path("redrivenCount").asInt()).isEqualTo(1);
        Waits.until("the redriven event is delivered", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return "PUBLISHED".equals(status(eventId));
        });
        assertThat(eventId(blocked)).isEqualTo(eventId);
    }

    @Test
    void aBrokerFailureNeverChangesADecisionOrFailsOpen() throws Exception {
        // Decisions are computed in process from committed state, so an unreachable broker cannot
        // turn a decline into an approval or release a hold.
        UUID approved = authorize(2_500);
        UUID declined = authorizeExpecting(600_000, "DECLINED");
        faults.rejectSendsFor(approved);
        faults.rejectSendsFor(declined);
        for (int cycle = 0; cycle < 5; cycle++) dispatcher.dispatchOnce();

        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, approved))
                .isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT decision ->> 'outcome' FROM payments WHERE id = ?", String.class, declined))
                .isEqualTo("DECLINE");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, declined))
                .isEqualTo("DECLINED");
        // The approval's hold is intact and the decline reserved nothing.
        assertThat(jdbc.queryForObject("SELECT held_minor FROM accounts WHERE id = ?", Long.class, accountId))
                .isEqualTo(2_500);

        // A further decline during the outage is still a decline.
        assertThat(authorizeExpecting(700_000, "DECLINED")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT held_minor FROM accounts WHERE id = ?", Long.class, accountId))
                .isEqualTo(2_500);
    }

    @Test
    void aDatabaseFailureNeverProducesASuccessfulFinancialResponse() throws Exception {
        long heldBefore = jdbc.queryForObject("SELECT held_minor FROM accounts WHERE id = ?", Long.class, accountId);
        long paymentsBefore = jdbc.queryForObject("SELECT count(*) FROM payments WHERE account_id = ?", Long.class, accountId);

        String name = "test_fail_insert_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.account_id = '" + accountId + "'::uuid THEN "
                + "RAISE EXCEPTION 'injected storage failure' USING ERRCODE = '23514'; END IF; RETURN NEW; END $$");
        int status;
        String bodyText;
        try {
            jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON payments FOR EACH ROW EXECUTE FUNCTION " + name + "()");
            try {
                var result = mvc.perform(post("/v1/payments/authorizations").header("Authorization", DEMO)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json.writeValueAsString(Map.of("accountId", accountId, "amountMinor", 1_900,
                                        "currency", "CAD", "country", "CA"))))
                        .andReturn();
                status = result.getResponse().getStatus();
                bodyText = result.getResponse().getContentAsString();
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS " + name + " ON payments");
            }
        } finally {
            jdbc.execute("DROP FUNCTION IF EXISTS " + name + "()");
        }

        // A storage failure is reported as a failure, never as a completed payment.
        assertThat(status).isEqualTo(503);
        assertThat(json.readTree(bodyText).path("code").asText()).isEqualTo("STORAGE_UNAVAILABLE");
        // And nothing was reserved, recorded, or queued for delivery.
        assertThat(jdbc.queryForObject("SELECT held_minor FROM accounts WHERE id = ?", Long.class, accountId))
                .isEqualTo(heldBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE account_id = ?", Long.class, accountId))
                .isEqualTo(paymentsBefore);

        // The account still works once storage recovers.
        UUID recovered = authorize(1_900);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, recovered))
                .isEqualTo("AUTHORIZED");
    }

    // ----- helpers -----

    /**
     * Publishes whatever earlier suites left undelivered, so this test's bounded-batch assertions
     * are about events it created rather than about a shared database's history.
     */
    private void drainExistingBacklog() {
        Waits.until("pre-existing deliverable backlog drains", BUDGET, () -> {
            OutboxDispatcher.Cycle cycle = dispatcher.dispatchOnce();
            return cycle.claimed() == 0;
        });
    }

    private long pendingFor(List<UUID> payments) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE aggregate_id = ANY (?::uuid[]) AND status <> 'PUBLISHED'
                """, Long.class, "{" + String.join(",", payments.stream().map(UUID::toString).toList()) + "}");
    }

    private UUID eventId(UUID payment) {
        return jdbc.queryForObject("SELECT id FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence LIMIT 1",
                UUID.class, payment);
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, eventId);
    }

    private double gauge(String name) {
        return metrics.find(name).gauges().stream().mapToDouble(io.micrometer.core.instrument.Gauge::value).sum();
    }

    private UUID newAccount(long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,'demo-merchant','CAD',?,?)",
                id, balance, balance);
        return id;
    }

    private UUID authorize(long amount) throws Exception {
        return authorizeExpecting(amount, "AUTHORIZED");
    }

    private UUID authorizeExpecting(long amount, String expectedStatus) throws Exception {
        String body = json.writeValueAsString(Map.of("accountId", accountId, "amountMinor", amount,
                "currency", "CAD", "country", "CA"));
        var result = mvc.perform(post("/v1/payments/authorizations").header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        assertThat(node.path("status").asText()).isEqualTo(expectedStatus);
        return UUID.fromString(node.path("id").asText());
    }

    private JsonNode readJson(String path, String auth) throws Exception {
        var request = get(path);
        if (auth != null) request.header("Authorization", auth);
        var result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as("GET %s", path).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
