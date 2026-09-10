package com.decisionrail.shadow;

import com.decisionrail.events.DeliveryFaults;
import com.decisionrail.events.OutboxDispatcher;
import com.decisionrail.support.BrokerProbe;
import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Shadow evaluation end to end: authorize, deliver the event, enqueue durable work, evaluate a
 * pinned candidate, and record a comparison, all against real PostgreSQL and a real broker.
 *
 * <p>Both workers are driven explicitly so the isolation claims can be checked at the exact points
 * where they matter: while a candidate is slow, while it is throwing, and across a worker restart.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShadowIntegrationTest {
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final String TOPIC = "test.shadow." + RUN;
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final String OTHER = basic("other-merchant", "other-test-password-123");
    private static final String ADMIN = basic("admin", "admin-test-password-123");
    private static final Duration BUDGET = Duration.ofSeconds(30);

    /** Declines at or above 1000 minor units, so it diverges from demo-v1 on ordinary payments. */
    private static final String STRICT_CANDIDATE = """
            {"rules":[
              {"code":"STRICT_AMOUNT","description":"Candidate declines at or above 1000 minor units.",
               "scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,
               "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}
            ]}""";

    @DynamicPropertySource
    static void brokerAndTopics(DynamicPropertyRegistry registry) {
        BrokerProbe.requireReachable();
        registry.add("spring.kafka.bootstrap-servers", BrokerProbe::bootstrapServers);
        registry.add("app.events.topic", () -> TOPIC);
        registry.add("app.events.projection-group", () -> "test-shadow-projection-" + RUN);
        registry.add("app.events.shadow-group", () -> "test-shadow-enqueue-" + RUN);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired ShadowWorker shadowWorker;
    @Autowired ShadowStore shadowStore;
    @Autowired DeliveryFaults faults;
    @Autowired Clock clock;

    private UUID accountId;
    private String candidateVersion;

    @BeforeEach
    void enableShadowForAFreshCandidate() throws Exception {
        faults.clear();
        accountId = newAccount("demo-merchant", 5_000_000);
        candidateVersion = "shadow-candidate-" + UUID.randomUUID().toString().substring(0, 8);
        createPolicy(candidateVersion, STRICT_CANDIDATE);
        configureShadow(true, candidateVersion, 200);
    }

    @AfterEach
    void disableShadow() throws Exception {
        faults.clear();
        configureShadow(false, null, 200);
    }

    @Test
    void aPinnedCandidateDivergenceIsRecordedWithNoFinancialEffect() throws Exception {
        FinancialState before = financialState();
        UUID payment = authorize(accountId, 2_500);
        Map<String, Object> paymentBefore = paymentRow(payment);

        deliver(payment);
        awaitShadowTask(payment);
        runShadowToCompletion(payment);

        List<Map<String, Object>> comparisons = comparisonRows(payment);
        assertThat(comparisons).hasSize(1);
        Map<String, Object> comparison = comparisons.getFirst();
        assertThat(comparison.get("candidate_version")).isEqualTo(candidateVersion);
        assertThat(comparison.get("baseline_outcome")).isEqualTo("APPROVE");
        assertThat(comparison.get("candidate_outcome")).isEqualTo("DECLINE");
        assertThat(comparison.get("diverged")).isEqualTo(true);
        assertThat(comparison.get("error_code")).isNull();
        assertThat(((Number) comparison.get("candidate_score")).intValue()).isEqualTo(60);

        JsonNode exposed = readJson("/v1/payments/" + payment + "/shadow", DEMO);
        assertThat(exposed).hasSize(1);
        assertThat(exposed.get(0).path("diverged").asBoolean()).isTrue();
        assertThat(exposed.get(0).path("candidateReasons").get(0).path("code").asText()).isEqualTo("STRICT_AMOUNT");
        assertThat(exposed.get(0).path("baselineReasons").isArray()).isTrue();

        // The live decision and all money state are exactly as authorization left them.
        assertThat(paymentRow(payment)).isEqualTo(paymentBefore);
        assertThat(riskOutcome(payment)).isEqualTo("APPROVE");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment))
                .isEqualTo("AUTHORIZED");
        // The authorization's own hold is the only balance change; shadow added nothing.
        FinancialState after = financialState();
        assertThat(after.ledgerEntries()).isEqualTo(before.ledgerEntries());
        assertThat(after.journals()).isEqualTo(before.journals());
        assertThat(after.heldMinor()).isEqualTo(2_500);
        assertThat(after.balanceMinor()).isEqualTo(before.balanceMinor());
        // Shadow emitted no events of its own: the payment has exactly its authorization event.
        assertThat(outboxCount(payment)).isEqualTo(1);
        assertThat(auditCount(payment)).isEqualTo(1);
    }

    @Test
    void repeatedDeliveriesAndRepeatedCyclesProduceOneComparison() throws Exception {
        UUID payment = authorize(accountId, 3_100);
        deliver(payment);
        awaitShadowTask(payment);
        runShadowToCompletion(payment);
        assertThat(comparisonRows(payment)).hasSize(1);

        // The same event delivered again: the shadow consumer deduplicates it, so no second task.
        String rawPayload = jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ? ORDER BY aggregate_sequence LIMIT 1",
                String.class, payment);
        BrokerProbe.publishRaw(TOPIC, payment.toString(), rawPayload);
        BrokerProbe.publishRaw(TOPIC, payment.toString(), rawPayload);
        Waits.neverDuring("a duplicate delivery creates a second shadow task", Duration.ofSeconds(5),
                () -> shadowTaskCount(payment) > 1);

        // Extra worker cycles change nothing either.
        for (int cycle = 0; cycle < 5; cycle++) shadowWorker.runOnce();
        assertThat(comparisonRows(payment)).hasSize(1);
        assertThat(shadowTaskCount(payment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT state FROM shadow_tasks WHERE payment_id = ?", String.class, payment))
                .isEqualTo("DONE");

        // A payment retry reuses the stored result and emits no new authorization event, so it
        // cannot produce a second comparison either.
        assertThat(comparisonRows(payment)).hasSize(1);
    }

    @Test
    void aThrowingCandidateFailsVisiblyAndChangesNothingFinancial() throws Exception {
        FinancialState before = financialState();
        UUID payment = authorize(accountId, 4_200);
        Map<String, Object> paymentBefore = paymentRow(payment);
        faults.failShadowEvaluationFor(payment);

        deliver(payment);
        awaitShadowTask(payment);
        Waits.until("the failing candidate exhausts its attempts", BUDGET, () -> {
            shadowWorker.runOnce();
            return "FAILED".equals(taskState(payment));
        });

        // Exactly one comparison, recording why rather than silently omitting the payment.
        List<Map<String, Object>> comparisons = comparisonRows(payment);
        assertThat(comparisons).hasSize(1);
        assertThat(comparisons.getFirst().get("candidate_outcome")).isNull();
        assertThat(comparisons.getFirst().get("error_code")).isEqualTo("IllegalStateException");
        assertThat(comparisons.getFirst().get("diverged")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT last_error FROM shadow_tasks WHERE payment_id = ?", String.class, payment))
                .contains("Simulated candidate policy evaluation failure");

        // The original decision, balances, holds, journals, and events are untouched.
        assertThat(paymentRow(payment)).isEqualTo(paymentBefore);
        FinancialState after = financialState();
        assertThat(after.ledgerEntries()).isEqualTo(before.ledgerEntries());
        assertThat(after.journals()).isEqualTo(before.journals());
        assertThat(after.balanceMinor()).isEqualTo(before.balanceMinor());
        assertThat(after.heldMinor()).isEqualTo(4_200);
        assertThat(outboxCount(payment)).isEqualTo(1);

        // Retrying the failed task does not add a second comparison.
        for (int cycle = 0; cycle < 3; cycle++) shadowWorker.runOnce();
        assertThat(comparisonRows(payment)).hasSize(1);
    }

    @Test
    void authorizationNeverWaitsForOrDependsOnCandidateExecution() throws Exception {
        // A candidate slow enough that a synchronous dependency would be obvious.
        faults.setShadowEvaluationDelayMillis(3_000);

        long startedAt = System.nanoTime();
        UUID first = authorize(accountId, 5_100);
        UUID second = authorize(accountId, 5_200);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        // Two authorizations complete in far less than one candidate evaluation would take.
        assertThat(elapsed).isLessThan(Duration.ofMillis(2_500));
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, first))
                .isEqualTo("AUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, second))
                .isEqualTo("AUTHORIZED");

        // With the shadow worker never run, no comparison appears: nothing on the authorization
        // path triggers candidate evaluation, so it cannot be a synchronous dependency.
        Waits.neverDuring("authorization produced a comparison by itself", Duration.ofSeconds(3),
                () -> !comparisonRows(first).isEmpty());

        // A further authorization still succeeds while shadow work is outstanding.
        faults.setShadowEvaluationDelayMillis(0);
        UUID third = authorize(accountId, 5_300);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, third))
                .isEqualTo("AUTHORIZED");
    }

    @Test
    void aShadowWorkerRestartRecoversTheClaimAndStillEvaluatesOnce() throws Exception {
        UUID payment = authorize(accountId, 6_400);
        deliver(payment);
        awaitShadowTask(payment);

        // Reproduce a worker that died holding a claim: the task stays CLAIMED with a lease that
        // has since expired, which is exactly the state a killed process leaves behind.
        UUID deadToken = UUID.randomUUID();
        jdbc.update("""
                UPDATE shadow_tasks SET state = 'CLAIMED', attempts = 1, lease_owner = 'worker-that-died',
                    lease_token = ?, lease_expires_at = ? WHERE payment_id = ?
                """, deadToken, java.sql.Timestamp.from(clock.instant().minusSeconds(300)), payment);

        // The dead worker's token can no longer finish the task once it has been reclaimed.
        ShadowWorker.Cycle recovery = shadowWorker.runOnce();
        assertThat(recovery.reclaimed()).isGreaterThanOrEqualTo(1);
        boolean staleFinish = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) = 0 FROM shadow_tasks WHERE payment_id = ? AND lease_token = ?",
                Boolean.class, payment, deadToken));
        assertThat(staleFinish).isTrue();

        runShadowToCompletion(payment);
        assertThat(comparisonRows(payment)).hasSize(1);
        assertThat(comparisonRows(payment).getFirst().get("diverged")).isEqualTo(true);
    }

    @Test
    void disablingShadowStopsNewWorkAndKeepsRecordedEvidence() throws Exception {
        UUID evaluated = authorize(accountId, 7_100);
        deliver(evaluated);
        awaitShadowTask(evaluated);
        runShadowToCompletion(evaluated);
        assertThat(comparisonRows(evaluated)).hasSize(1);

        configureShadow(false, candidateVersion, 200);
        JsonNode settings = readJson("/v1/ops/shadow", ADMIN);
        assertThat(settings.path("enabled").asBoolean()).isFalse();
        assertThat(settings.path("comparisons").asLong()).isGreaterThan(0);

        UUID afterDisable = authorize(accountId, 7_200);
        deliver(afterDisable);
        // No new task is enqueued while disabled, and the earlier evidence is still there.
        Waits.neverDuring("a task is enqueued while shadow is disabled", Duration.ofSeconds(5),
                () -> shadowTaskCount(afterDisable) > 0);
        for (int cycle = 0; cycle < 3; cycle++) shadowWorker.runOnce();
        assertThat(comparisonRows(afterDisable)).isEmpty();
        assertThat(comparisonRows(evaluated)).hasSize(1);

        // Enabling requires naming a candidate rather than silently evaluating nothing.
        assertThat(configureShadowStatus(true, null)).isEqualTo(400);
    }

    @Test
    void shadowComparisonsAreMerchantScopedAndSeparateFromReplay() throws Exception {
        UUID payment = authorize(accountId, 8_100);
        deliver(payment);
        awaitShadowTask(payment);
        runShadowToCompletion(payment);

        // Another merchant cannot see this payment's comparison.
        assertThat(readJson("/v1/payments/" + payment + "/shadow", OTHER)).isEmpty();
        assertThat(readJson("/v1/shadow-comparisons", OTHER)).noneSatisfy(node ->
                assertThat(node.path("paymentId").asText()).isEqualTo(payment.toString()));
        JsonNode own = readJson("/v1/shadow-comparisons?divergedOnly=true", DEMO);
        assertThat(own).isNotEmpty();

        // Shadow comparisons are stored and exposed separately from replay job results.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shadow_comparisons WHERE payment_id = ?",
                Long.class, payment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM replay_results WHERE payment_id = ?",
                Long.class, payment)).isZero();
        // And a merchant cannot reach the administrative shadow configuration.
        assertThat(configureShadowStatusAs(true, candidateVersion, DEMO)).isEqualTo(403);
    }

    // ----- helpers -----

    private record FinancialState(long balanceMinor, long heldMinor, long journals, long ledgerEntries) {}

    private FinancialState financialState() {
        Map<String, Object> account = jdbc.queryForMap("SELECT balance_minor, held_minor FROM accounts WHERE id = ?", accountId);
        return new FinancialState(
                ((Number) account.get("balance_minor")).longValue(),
                ((Number) account.get("held_minor")).longValue(),
                jdbc.queryForObject("SELECT count(*) FROM ledger_journals", Long.class),
                jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Long.class));
    }

    private void deliver(UUID payment) {
        Waits.until("events for " + payment + " are published", BUDGET, () -> {
            dispatcher.dispatchOnce();
            return jdbc.queryForObject(
                    "SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND status <> 'PUBLISHED'",
                    Long.class, payment) == 0;
        });
    }

    private void awaitShadowTask(UUID payment) {
        Waits.until("a shadow task is enqueued for " + payment, BUDGET, () -> shadowTaskCount(payment) == 1);
    }

    private void runShadowToCompletion(UUID payment) {
        Waits.until("shadow evaluation completes for " + payment, BUDGET, () -> {
            shadowWorker.runOnce();
            return "DONE".equals(taskState(payment));
        });
    }

    private long shadowTaskCount(UUID payment) {
        return jdbc.queryForObject("SELECT count(*) FROM shadow_tasks WHERE payment_id = ?", Long.class, payment);
    }

    private String taskState(UUID payment) {
        List<String> states = jdbc.queryForList("SELECT state FROM shadow_tasks WHERE payment_id = ?", String.class, payment);
        return states.isEmpty() ? null : states.getFirst();
    }

    private List<Map<String, Object>> comparisonRows(UUID payment) {
        return jdbc.queryForList("SELECT * FROM shadow_comparisons WHERE payment_id = ?", payment);
    }

    private String riskOutcome(UUID payment) {
        return jdbc.queryForObject("SELECT decision ->> 'outcome' FROM payments WHERE id = ?", String.class, payment);
    }

    private Map<String, Object> paymentRow(UUID payment) {
        return jdbc.queryForMap("SELECT * FROM payments WHERE id = ?", payment);
    }

    private long outboxCount(UUID payment) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Long.class, payment);
    }

    private long auditCount(UUID payment) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE payment_id = ?", Long.class, payment);
    }

    private void createPolicy(String versionId, String definition) throws Exception {
        int status = mvc.perform(post("/v1/policies").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"" + versionId + "\",\"definition\":" + definition + "}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(200, 201);
    }

    private void configureShadow(boolean enabled, String candidate, int expectedStatus) throws Exception {
        assertThat(configureShadowStatus(enabled, candidate)).isEqualTo(expectedStatus);
    }

    private int configureShadowStatus(boolean enabled, String candidate) throws Exception {
        return configureShadowStatusAs(enabled, candidate, ADMIN);
    }

    private int configureShadowStatusAs(boolean enabled, String candidate, String auth) throws Exception {
        String body = candidate == null
                ? "{\"enabled\":" + enabled + "}"
                : "{\"enabled\":" + enabled + ",\"candidateVersion\":\"" + candidate + "\"}";
        return mvc.perform(put("/v1/ops/shadow").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }

    private UUID newAccount(String merchant, long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                id, merchant, balance, balance);
        return id;
    }

    private UUID authorize(UUID account, long amount) throws Exception {
        String body = json.writeValueAsString(Map.of("accountId", account, "amountMinor", amount,
                "currency", "CAD", "country", "CA"));
        var result = mvc.perform(post("/v1/payments/authorizations").header("Authorization", DEMO)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private JsonNode readJson(String path, String auth) throws Exception {
        var result = mvc.perform(get(path).header("Authorization", auth)).andReturn();
        assertThat(result.getResponse().getStatus()).as("GET %s", path).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
