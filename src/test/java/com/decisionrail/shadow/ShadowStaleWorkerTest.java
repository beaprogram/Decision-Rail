package com.decisionrail.shadow;

import com.decisionrail.decision.DecisionEngine;
import com.decisionrail.events.DeliveryFaults;
import com.decisionrail.events.EventEnvelope;
import com.decisionrail.policy.PolicyService;
import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a shadow worker may write after its claim has been taken over by another worker.
 *
 * <p>The failure this guards against is not a lost update but a contradictory record. The
 * comparison insert carried no ownership check, and the lease-fenced task update's false result was
 * discarded, so a stale worker could commit a terminal-failure comparison while the new owner went
 * on to evaluate successfully and mark the task DONE. The task then claimed success while its only
 * stored comparison recorded failure.
 *
 * <p>The interleaving is forced with gates rather than timing: worker A is held inside its
 * evaluation step, its lease is expired, worker B reclaims and is held in turn, and only then is A
 * released so it attempts its write first.
 */
@SpringBootTest
@ActiveProfiles("test")
class ShadowStaleWorkerTest {
    private static final Duration BUDGET = Duration.ofSeconds(30);

    /** Declines at or above 1000 minor units, so a 2500 payment diverges from the demo baseline. */
    private static final String STRICT_CANDIDATE = """
            {"rules":[
              {"code":"STRICT_AMOUNT","description":"Candidate declines at or above 1000 minor units.",
               "scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,
               "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}
            ]}""";

    @DynamicPropertySource
    static void noListeners(DynamicPropertyRegistry registry) {
        // This test drives the worker and the store directly; a broker would add nothing.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired ShadowWorker workerA;
    @Autowired com.decisionrail.telemetry.DeliveryTracing tracing;
    @Autowired ShadowStore store;
    @Autowired ShadowService shadowService;
    @Autowired PolicyService policies;
    @Autowired DecisionEngine engine;
    @Autowired DeliveryFaults faults;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired MeterRegistry metrics;

    private ShadowWorker workerB;
    private String candidateVersion;

    @BeforeEach
    void prepare() throws Exception {
        faults.clear();
        // This suite shares a disposable database with the others, and cycle counts here must mean
        // "this test's task". Parking whatever they left pending keeps the one backdated task below
        // the only claimable one, without deleting or completing rows this test does not own.
        jdbc.update("UPDATE shadow_tasks SET next_attempt_at = ? WHERE state = 'PENDING'",
                java.sql.Timestamp.from(java.time.Instant.parse("2099-01-01T00:00:00Z")));
        workerB = newWorker();
        assertThat(workerB.workerId()).isNotEqualTo(workerA.workerId());
        candidateVersion = "stale-candidate-" + UUID.randomUUID().toString().substring(0, 8);
        policies.createCandidate(candidateVersion, json.readTree(STRICT_CANDIDATE), "admin");
    }

    @AfterEach
    void disarm() {
        faults.clear();
    }

    @Test
    void aStaleWorkerCannotRecordATerminalFailureOverTheNewOwnersClaim() throws Exception {
        UUID payment = enqueueTask();
        // Put the task at its attempt ceiling so worker A's injected failure is terminal, which is
        // the path that wrote a comparison without checking ownership.
        jdbc.update("UPDATE shadow_tasks SET attempts = 5 WHERE payment_id = ?", payment);
        faults.failShadowEvaluationFor(payment);

        UUID staleToken = handOverFromAToB(payment);

        // A resumes first and takes its terminal-failure path while B still holds the claim.
        faults.release(DeliveryFaults.shadowGate(workerA.workerId()));
        Waits.until("worker A finishes its stale attempt", BUDGET, () -> !faults.isHeld(DeliveryFaults.shadowGate(workerA.workerId())));
        ShadowWorker.Cycle staleCycle = awaitCycle(staleRun);

        // Nothing of A's reached the database.
        assertThat(comparisons(payment)).isEmpty();
        assertThat(taskState(payment)).isEqualTo("CLAIMED");
        assertThat(leaseOwner(payment)).isEqualTo(workerB.workerId());
        assertThat(leaseToken(payment)).isNotEqualTo(staleToken);
        assertThat(staleCycle.evaluated()).isZero();
        assertThat(staleCycle.failed()).isZero();

        // Now let the real owner finish successfully.
        faults.clear();
        faults.release(DeliveryFaults.shadowGate(workerB.workerId()));
        ShadowWorker.Cycle ownerCycle = awaitCycle(ownerRun);

        assertThat(ownerCycle.evaluated()).isEqualTo(1);
        List<Map<String, Object>> recorded = comparisons(payment);
        assertThat(recorded).hasSize(1);
        Map<String, Object> comparison = recorded.getFirst();
        // The surviving comparison is the owner's successful evaluation, not the stale failure.
        assertThat(comparison.get("candidate_outcome")).isEqualTo("DECLINE");
        assertThat(comparison.get("error_code")).isNull();
        assertThat(comparison.get("diverged")).isEqualTo(true);
        assertThat(taskState(payment)).isEqualTo("DONE");
        // No trace of the stale worker's injected failure survives on the task either.
        assertThat(jdbc.queryForObject("SELECT last_error FROM shadow_tasks WHERE payment_id = ?", String.class, payment))
                .isNull();
    }

    @Test
    void aStaleWorkerCannotRecordASuccessfulComparisonOverTheNewOwnersClaim() throws Exception {
        UUID payment = enqueueTask();
        UUID staleToken = handOverFromAToB(payment);

        // A resumes and evaluates successfully, but it no longer owns the task.
        faults.release(DeliveryFaults.shadowGate(workerA.workerId()));
        ShadowWorker.Cycle staleCycle = awaitCycle(staleRun);

        assertThat(comparisons(payment)).isEmpty();
        assertThat(taskState(payment)).isEqualTo("CLAIMED");
        assertThat(leaseOwner(payment)).isEqualTo(workerB.workerId());
        assertThat(leaseToken(payment)).isNotEqualTo(staleToken);
        assertThat(staleCycle.evaluated()).as("a replaced claim must not count as an evaluation").isZero();

        faults.release(DeliveryFaults.shadowGate(workerB.workerId()));
        assertThat(awaitCycle(ownerRun).evaluated()).isEqualTo(1);
        assertThat(comparisons(payment)).hasSize(1);
        assertThat(comparisons(payment).getFirst().get("error_code")).isNull();
        assertThat(taskState(payment)).isEqualTo("DONE");
    }

    @Test
    void anOrdinaryDuplicateIsStillDistinguishedFromLostOwnership() throws Exception {
        UUID payment = enqueueTask();

        // First pass: the owner records its comparison normally.
        ShadowWorker.Cycle first = workerA.runOnce();
        assertThat(first.evaluated()).isEqualTo(1);
        assertThat(comparisons(payment)).hasSize(1);
        assertThat(taskState(payment)).isEqualTo("DONE");

        // Re-open the same task as if it had been redelivered, then process it again. The comparison
        // already exists, so this is a duplicate rather than a takeover: one comparison, task DONE.
        jdbc.update("""
                UPDATE shadow_tasks SET state = 'PENDING', next_attempt_at = ?, completed_at = NULL
                WHERE payment_id = ?
                """, java.sql.Timestamp.from(clock.instant()), payment);
        ShadowWorker.Cycle second = workerA.runOnce();

        assertThat(second.alreadyRecorded()).isEqualTo(1);
        assertThat(second.evaluated()).isZero();
        assertThat(comparisons(payment)).hasSize(1);
        assertThat(taskState(payment)).isEqualTo("DONE");
    }

    @Test
    void shadowEvaluationStillRecordsRawScoresAboveTheCap() throws Exception {
        // Three overlapping rules all match one input: 60 + 40 + 20 = 120 raw.
        String overflowing = "overflow-candidate-" + UUID.randomUUID().toString().substring(0, 8);
        policies.createCandidate(overflowing, json.readTree("""
                {"rules":[
                  {"code":"BAND_A","description":"At least 100 minor units.","scoreContribution":60,
                   "flag":"HIGH_AMOUNT","terminal":false,
                   "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":100}},
                  {"code":"BAND_B","description":"At least 200 minor units.","scoreContribution":40,
                   "flag":"ELEVATED_AMOUNT","terminal":false,
                   "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":200}},
                  {"code":"CURRENCY","description":"Canadian dollars.","scoreContribution":20,
                   "flag":"CROSS_BORDER","terminal":false,
                   "expression":{"operator":"CURRENCY_IN","currencies":["CAD"]}}
                ]}"""), "admin");

        UUID payment = enqueueTask(overflowing);
        assertThat(workerA.runOnce().evaluated()).isEqualTo(1);

        Map<String, Object> stored = comparisons(payment).getFirst();
        // The excess above the scale is preserved, not discarded.
        assertThat(((Number) stored.get("candidate_score")).intValue()).isEqualTo(100);
        assertThat(((Number) stored.get("candidate_raw_score")).intValue()).isEqualTo(120);
        assertThat(stored.get("candidate_score_capped")).isEqualTo(true);
        assertThat(stored.get("candidate_outcome")).isEqualTo("DECLINE");

        List<ShadowComparisonView> exposed = shadowService.comparisons("demo-merchant", payment);
        assertThat(exposed).hasSize(1);
        ShadowComparisonView view = exposed.getFirst();
        assertThat(view.candidateScore()).isEqualTo(100);
        assertThat(view.candidateRawScore()).isEqualTo(120);
        assertThat(view.candidateScoreCapped()).isTrue();
        // Every contributing rule is still explained, and the contributions sum to the raw score.
        assertThat(view.candidateReasons()).hasSize(3);
        int contributions = 0;
        for (var reason : view.candidateReasons()) {
            contributions += reason.path("scoreContribution").asInt();
        }
        assertThat(contributions).isEqualTo(120);
    }

    // ----- helpers -----

    private CompletableFuture<ShadowWorker.Cycle> staleRun;
    private CompletableFuture<ShadowWorker.Cycle> ownerRun;

    /**
     * Drives the takeover: A claims and is held, its lease is expired, then B reclaims and is held.
     *
     * @return the lease token A is holding, which must no longer authorise anything
     */
    private UUID handOverFromAToB(UUID payment) {
        faults.hold(DeliveryFaults.shadowGate(workerA.workerId()));
        staleRun = CompletableFuture.supplyAsync(workerA::runOnce);
        Waits.until("worker A claims the task", BUDGET,
                () -> "CLAIMED".equals(taskState(payment)) && workerA.workerId().equals(leaseOwner(payment)));
        UUID staleToken = leaseToken(payment);
        assertThat(staleToken).isNotNull();

        // Expire A's lease exactly as a dead worker would leave it.
        jdbc.update("UPDATE shadow_tasks SET lease_expires_at = ? WHERE payment_id = ?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(600)), payment);

        faults.hold(DeliveryFaults.shadowGate(workerB.workerId()));
        ownerRun = CompletableFuture.supplyAsync(workerB::runOnce);
        Waits.until("worker B reclaims and takes the task", BUDGET,
                () -> "CLAIMED".equals(taskState(payment)) && workerB.workerId().equals(leaseOwner(payment)));
        return staleToken;
    }

    private ShadowWorker.Cycle awaitCycle(CompletableFuture<ShadowWorker.Cycle> run) throws Exception {
        return run.get(BUDGET.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
    }

    private ShadowWorker newWorker() {
        return new ShadowWorker(store, policies, engine, faults, transactions, json, clock, metrics, tracing,
                5, 2, 2, Duration.ofSeconds(30), Duration.ofMillis(10));
    }

    private UUID enqueueTask() {
        return enqueueTask(candidateVersion);
    }

    private UUID enqueueTask(String candidate) {
        UUID payment = UUID.randomUUID();
        EventEnvelope envelope = envelopeFor(payment);
        Boolean enqueued = transactions.execute(status -> store.enqueue(envelope, candidate, java.util.Optional.empty()));
        assertThat(enqueued).isTrue();
        // The insert takes its due time from the database clock while the worker compares it against
        // the JVM clock, and the two differ by a few milliseconds in a container. Backdating the due
        // time removes that skew from the test and also puts this task ahead of any left over by
        // another suite, so a single cycle deterministically claims exactly this task.
        jdbc.update("UPDATE shadow_tasks SET next_attempt_at = ? WHERE payment_id = ?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(60)), payment);
        return payment;
    }

    private EventEnvelope envelopeFor(UUID payment) {
        Instant now = clock.instant();
        EventEnvelope.Decision decision = new EventEnvelope.Decision("APPROVE", 0, "demo-v1",
                List.of(new EventEnvelope.Reason("NO_RISK_SIGNALS", "No synthetic demo risk rules matched.", 0)),
                List.of());
        EventEnvelope.Payment snapshot = new EventEnvelope.Payment(payment, UUID.randomUUID(), 2_500,
                "CAD", "CA", "AUTHORIZED", decision, null, now, now);
        return new EventEnvelope(UUID.randomUUID(), "payment.authorized.v1", 1, payment, "payment", 1L,
                "demo-merchant", now, now, snapshot);
    }

    private List<Map<String, Object>> comparisons(UUID payment) {
        return jdbc.queryForList("SELECT * FROM shadow_comparisons WHERE payment_id = ?", payment);
    }

    private String taskState(UUID payment) {
        List<String> states = jdbc.queryForList("SELECT state FROM shadow_tasks WHERE payment_id = ?", String.class, payment);
        return states.isEmpty() ? null : states.getFirst();
    }

    private String leaseOwner(UUID payment) {
        List<String> owners = jdbc.queryForList("SELECT lease_owner FROM shadow_tasks WHERE payment_id = ?", String.class, payment);
        return owners.isEmpty() ? null : owners.getFirst();
    }

    private UUID leaseToken(UUID payment) {
        List<UUID> tokens = jdbc.queryForList("SELECT lease_token FROM shadow_tasks WHERE payment_id = ?", UUID.class, payment);
        return tokens.isEmpty() ? null : tokens.getFirst();
    }
}
