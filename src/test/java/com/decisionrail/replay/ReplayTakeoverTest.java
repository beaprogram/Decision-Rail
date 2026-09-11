package com.decisionrail.replay;

import com.decisionrail.decision.DecisionEngine;
import com.decisionrail.events.DeliveryFaults;
import com.decisionrail.policy.PolicyService;
import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
 * Replay job completion when one worker's lease expires while its batch is still uncommitted.
 *
 * <p>The failure this guards against produced a job that looked finished and reported totals that
 * disagreed with its own stored results. Batch writes carried no check on the job's current lease,
 * and recomputing totals, counting remaining work, and marking the job complete happened in three
 * separate transactions, so a taking-over worker could compute totals, watch the previous owner
 * commit more results, then freeze the stale numbers by completing the job. Completed jobs are never
 * reclaimed, so nothing corrected them afterwards.
 *
 * <p>The interleaving is forced with gates, not timing, and the assertions check totals against the
 * durable result rows rather than against an expected constant.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReplayTakeoverTest {
    private static final Duration BUDGET = Duration.ofSeconds(60);
    private static final int INPUTS = 7;

    private static final String STRICT_CANDIDATE = """
            {"rules":[
              {"code":"STRICT_AMOUNT","description":"Candidate declines at or above 1000 minor units.",
               "scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,
               "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}
            ]}""";

    @DynamicPropertySource
    static void noListeners(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired ReplayWorker workerA;
    @Autowired ReplayStore store;
    @Autowired ReplayService replayService;
    @Autowired PolicyService policies;
    @Autowired DecisionEngine engine;
    @Autowired DeliveryFaults faults;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired MeterRegistry metrics;

    private ReplayWorker workerB;
    private String candidateVersion;
    private Instant windowFrom;
    private String merchantId;

    @BeforeEach
    void prepare() throws Exception {
        faults.clear();
        // Leftover unfinished jobs from other suites would be claimed ahead of this test's job,
        // because the claim takes the oldest runnable job. Parking them keeps this deterministic
        // without changing their status.
        jdbc.update("UPDATE replay_jobs SET lease_expires_at = ? WHERE status IN ('PENDING', 'RUNNING')",
                Timestamp.from(Instant.parse("2099-01-01T00:00:00Z")));
        workerB = newWorker();
        assertThat(workerB.workerId()).isNotEqualTo(workerA.workerId());
        candidateVersion = "takeover-candidate-" + UUID.randomUUID().toString().substring(0, 8);
        policies.createCandidate(candidateVersion, json.readTree(STRICT_CANDIDATE), "admin");
        // A merchant of its own makes membership exactly this test's payments, so the window can be
        // wide enough that job creation never races the payment timestamps.
        merchantId = "takeover-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO merchants (id) VALUES (?)", merchantId);
        windowFrom = clock.instant().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MICROS);
    }

    @AfterEach
    void disarm() {
        faults.clear();
    }

    @Test
    void aJobTakenOverMidRunStillCompletesWithTotalsThatMatchItsStoredResults() throws Exception {
        UUID jobId = createJobOverFreshPayments();
        // One input cannot be evaluated, so the job must end with both a success and a failure count.
        forceOneItemToFailEvaluation(jobId);
        assertThat(store.pendingItemCount(jobId)).isEqualTo(INPUTS);
        assertThat(workerA.batchSize()).isLessThan(INPUTS);

        // A claims the job, commits its first batch, and parks in the handover window: the only point
        // at which it holds no locks and another worker can legitimately take the job over.
        faults.hold(DeliveryFaults.replayHandoverGate(workerA.workerId()));
        CompletableFuture<ReplayWorker.Cycle> runA = CompletableFuture.supplyAsync(workerA::runOnce);
        Waits.until("worker A commits a batch and reaches the handover window", BUDGET,
                () -> faults.isHeld(DeliveryFaults.replayHandoverGate(workerA.workerId()))
                        && resultCount(jobId) == workerA.batchSize());

        // Its lease expires exactly as a stalled worker's would, and B takes the job over and
        // finishes the remaining inputs while A is still parked.
        jdbc.update("UPDATE replay_jobs SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minusSeconds(600)), jobId);
        Waits.until("worker B takes over and completes the job", BUDGET, () -> {
            workerB.runOnce();
            return "COMPLETED".equals(status(jobId));
        });

        // Totals on the completed job describe every durable result, including A's committed batch.
        assertTotalsAgreeWithStoredResults(jobId);
        Map<String, Object> afterCompletion = jobRow(jobId);

        // Now A resumes and tries to finalise a job it no longer owns. It must change nothing.
        faults.release(DeliveryFaults.replayHandoverGate(workerA.workerId()));
        ReplayWorker.Cycle staleCycle = runA.get(BUDGET.toSeconds(), TimeUnit.SECONDS);
        assertThat(staleCycle.jobCompleted()).as("an obsolete owner must not complete a job").isFalse();
        assertThat(jobRow(jobId)).isEqualTo(afterCompletion);

        // Membership was never altered, and every pinned input has exactly one terminal result.
        assertThat(itemCount(jobId)).isEqualTo(INPUTS);
        assertThat(resultCount(jobId)).isEqualTo(INPUTS);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM replay_job_items WHERE job_id = ? AND state = 'PENDING'", Long.class, jobId))
                .isZero();

        // Both a success and a failure are represented, and the failure is recorded as such.
        ReplayJobView finished = job(jobId);
        assertThat(finished.failedCount()).isEqualTo(1);
        assertThat(finished.completedCount()).isEqualTo(INPUTS - 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM replay_results WHERE job_id = ? AND error_code IS NOT NULL", Long.class, jobId))
                .isEqualTo(1);

        // Further cycles from either worker cannot change a completed report.
        for (int cycle = 0; cycle < 3; cycle++) {
            workerA.runOnce();
            workerB.runOnce();
        }
        assertThat(jobRow(jobId)).isEqualTo(afterCompletion);
        assertThat(resultCount(jobId)).isEqualTo(INPUTS);
    }

    @Test
    void aJobWithABatchInFlightIsNotHandedToASecondWorker() throws Exception {
        UUID jobId = createJobOverFreshPayments();

        // A worker whose lease is already expired the moment it claims, so the only thing that can
        // keep the job out of another worker's hands is the ownership lock its batch holds.
        ReplayWorker shortLeasedA = newWorker(Duration.ofMillis(1));
        faults.hold(DeliveryFaults.replayBatchCommitGate(shortLeasedA.workerId()));
        CompletableFuture<ReplayWorker.Cycle> runA = CompletableFuture.supplyAsync(shortLeasedA::runOnce);
        Waits.until("the short-leased worker reaches its uncommitted batch", BUDGET,
                () -> faults.isHeld(DeliveryFaults.replayBatchCommitGate(shortLeasedA.workerId()))
                        && shortLeasedA.workerId().equals(leaseOwner(jobId)));
        assertThat(leaseExpiry(jobId)).isBefore(clock.instant());

        // Despite the expired lease, the job must not be handed over while those writes are
        // outstanding: completion could otherwise be decided against totals about to change.
        ReplayWorker.Cycle contender = workerB.runOnce();
        assertThat(contender.didWork()).as("a job with a batch in flight must be skipped").isFalse();
        assertThat(leaseOwner(jobId)).isEqualTo(shortLeasedA.workerId());
        assertThat(resultCount(jobId)).as("the contender wrote nothing").isZero();

        faults.release(DeliveryFaults.replayBatchCommitGate(shortLeasedA.workerId()));
        runA.get(BUDGET.toSeconds(), TimeUnit.SECONDS);

        Waits.until("the job completes", BUDGET, () -> {
            workerB.runOnce();
            return "COMPLETED".equals(status(jobId));
        });
        assertTotalsAgreeWithStoredResults(jobId);
        assertThat(resultCount(jobId)).isEqualTo(INPUTS);
    }

    @Test
    void aStaleLeaseTokenAuthorisesNoReplayWriteAtAll() throws Exception {
        UUID jobId = createJobOverFreshPayments();

        // Park A in the handover window, where it holds no locks, and let B take the job over.
        faults.hold(DeliveryFaults.replayHandoverGate(workerA.workerId()));
        CompletableFuture<ReplayWorker.Cycle> runA = CompletableFuture.supplyAsync(workerA::runOnce);
        Waits.until("worker A commits a batch and reaches the handover window", BUDGET,
                () -> faults.isHeld(DeliveryFaults.replayHandoverGate(workerA.workerId()))
                        && resultCount(jobId) == workerA.batchSize());
        UUID staleToken = leaseToken(jobId);
        jdbc.update("UPDATE replay_jobs SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(clock.instant().minusSeconds(600)), jobId);
        Waits.until("worker B takes the job over", BUDGET, () -> {
            workerB.runOnce();
            return !staleToken.equals(leaseToken(jobId)) || "COMPLETED".equals(status(jobId));
        });

        // The token A is still carrying no longer authorises anything, which is what stops an
        // obsolete owner committing results or item transitions.
        Boolean stillOwned = transactions.execute(status -> store.lockOwnedJob(jobId, staleToken));
        assertThat(stillOwned).isFalse();

        long resultsBefore = resultCount(jobId);
        Map<String, Object> jobBefore = jobRow(jobId);
        faults.release(DeliveryFaults.replayHandoverGate(workerA.workerId()));
        ReplayWorker.Cycle staleCycle = runA.get(BUDGET.toSeconds(), TimeUnit.SECONDS);

        assertThat(staleCycle.jobCompleted()).isFalse();
        assertThat(resultCount(jobId)).isEqualTo(resultsBefore);
        assertThat(jobRow(jobId)).isEqualTo(jobBefore);

        Waits.until("the job completes under its current owner", BUDGET, () -> {
            workerB.runOnce();
            return "COMPLETED".equals(status(jobId));
        });
        assertTotalsAgreeWithStoredResults(jobId);
        assertThat(resultCount(jobId)).isEqualTo(INPUTS);
    }

    @Test
    void replayResultsStillCarryRawScoresAboveTheCap() throws Exception {
        // Three overlapping rules all match every input: 60 + 40 + 20 = 120 raw.
        String overflowing = "overflow-replay-" + UUID.randomUUID().toString().substring(0, 8);
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

        insertPayments(2);
        UUID jobId = createJob(overflowing);
        Waits.until("the overflow job completes", BUDGET, () -> {
            workerA.runOnce();
            return "COMPLETED".equals(status(jobId));
        });

        var results = replayService.results(merchantId, jobId, false, 50, 0);
        assertThat(results).isNotEmpty();
        for (ReplayResultView result : results) {
            // The excess above the scale is stored and returned, not discarded.
            assertThat(result.candidateScore()).isEqualTo(100);
            assertThat(result.candidateRawScore()).isEqualTo(120);
            assertThat(result.candidateScoreCapped()).isTrue();
            assertThat(result.candidateOutcome()).isEqualTo("DECLINE");
            assertThat(result.candidateReasons()).hasSize(3);
            int contributions = 0;
            for (var reason : result.candidateReasons()) {
                contributions += reason.path("scoreContribution").asInt();
            }
            assertThat(contributions).isEqualTo(120);
        }
    }

    // ----- helpers -----

    /** Recomputes every published total from the durable result rows and compares them. */
    private void assertTotalsAgreeWithStoredResults(UUID jobId) {
        ReplayJobView view = job(jobId);
        Map<String, Object> actual = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE error_code IS NULL)             AS completed,
                       count(*) FILTER (WHERE error_code IS NOT NULL)         AS failed,
                       count(*) FILTER (WHERE candidate_outcome = 'APPROVE')  AS approve,
                       count(*) FILTER (WHERE candidate_outcome = 'REVIEW')   AS review,
                       count(*) FILTER (WHERE candidate_outcome = 'DECLINE')  AS decline,
                       count(*) FILTER (WHERE diverged)                       AS diverged,
                       coalesce(sum(evaluation_nanos), 0)                     AS nanos
                FROM replay_results WHERE job_id = ?
                """, jobId);

        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.completedCount()).as("completedCount").isEqualTo(number(actual, "completed"));
        assertThat(view.failedCount()).as("failedCount").isEqualTo(number(actual, "failed"));
        assertThat(view.approveCount()).as("approveCount").isEqualTo(number(actual, "approve"));
        assertThat(view.reviewCount()).as("reviewCount").isEqualTo(number(actual, "review"));
        assertThat(view.declineCount()).as("declineCount").isEqualTo(number(actual, "decline"));
        assertThat(view.divergenceCount()).as("divergenceCount").isEqualTo(number(actual, "diverged"));
        assertThat(view.evaluationNanosTotal()).as("timing total")
                .isEqualTo(((Number) actual.get("nanos")).longValue());
        // Nothing is left unaccounted for against the fixed membership.
        assertThat(view.completedCount() + view.failedCount()).isEqualTo(view.inputCount());
        assertThat(view.pendingCount()).isZero();
    }

    private static int number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    private ReplayWorker newWorker() {
        return newWorker(Duration.ofSeconds(60));
    }

    private ReplayWorker newWorker(Duration leaseDuration) {
        return new ReplayWorker(store, policies, engine, faults, transactions, json, clock, metrics,
                3, leaseDuration);
    }

    private UUID createJobOverFreshPayments() {
        insertPayments(INPUTS);
        return createJob(candidateVersion);
    }

    private UUID createJob(String candidate) {
        ReplayService.CreatedJob created = replayService.create(merchantId, UUID.randomUUID().toString(),
                new ReplayService.CreateCommand(candidate, 500, windowFrom));
        return created.job().id();
    }

    /** Inserts payments directly: the subject here is the worker, not the payment command path. */
    private void insertPayments(int count) {
        UUID account = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                account, merchantId, 10_000_000L, 10_000_000L);
        for (int index = 0; index < count; index++) {
            // Firmly inside the window and firmly in the past, so membership never depends on how
            // long the inserts took.
            Instant createdAt = clock.instant().minus(Duration.ofMinutes(30)).plusMillis(index + 1);
            jdbc.update("""
                    INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                            decision, failure_code, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'CAD', 'CA', 'AUTHORIZED',
                            '{"outcome":"APPROVE","score":0,"ruleSetVersion":"demo-v1",
                              "reasons":[{"code":"NO_RISK_SIGNALS","description":"No synthetic demo risk rules matched.","scoreContribution":0}],
                              "flags":[]}'::jsonb, NULL, ?, ?)
                    """, UUID.randomUUID(), merchantId, account, 1_500L + index,
                    Timestamp.from(createdAt), Timestamp.from(createdAt));
        }
    }

    /**
     * Makes one pinned input unevaluable. A two-character non-letter country fits the snapshot column
     * but is rejected by the decision input, so the worker records an error for that item.
     */
    private void forceOneItemToFailEvaluation(UUID jobId) {
        UUID victim = jdbc.queryForObject(
                "SELECT payment_id FROM replay_job_items WHERE job_id = ? ORDER BY item_sequence DESC LIMIT 1",
                UUID.class, jobId);
        jdbc.update("UPDATE replay_job_items SET country = 'Z1' WHERE job_id = ? AND payment_id = ?", jobId, victim);
    }

    private ReplayJobView job(UUID jobId) {
        return replayService.job(merchantId, jobId);
    }

    private Map<String, Object> jobRow(UUID jobId) {
        return jdbc.queryForMap("""
                SELECT status, input_count, completed_count, failed_count, skipped_count, approve_count,
                       review_count, decline_count, divergence_count, evaluation_nanos_total, completed_at
                FROM replay_jobs WHERE id = ?
                """, jobId);
    }

    private String status(UUID jobId) {
        return jdbc.queryForObject("SELECT status FROM replay_jobs WHERE id = ?", String.class, jobId);
    }

    private String leaseOwner(UUID jobId) {
        return jdbc.queryForObject("SELECT lease_owner FROM replay_jobs WHERE id = ?", String.class, jobId);
    }

    private Instant leaseExpiry(UUID jobId) {
        Timestamp expiry = jdbc.queryForObject("SELECT lease_expires_at FROM replay_jobs WHERE id = ?", Timestamp.class, jobId);
        return expiry == null ? null : expiry.toInstant();
    }

    private UUID leaseToken(UUID jobId) {
        return jdbc.queryForObject("SELECT lease_token FROM replay_jobs WHERE id = ?", UUID.class, jobId);
    }

    /** The completed count currently published on the job row, as an operator would read it. */
    private int publishedCompletedCount(UUID jobId) {
        Integer count = jdbc.queryForObject("SELECT completed_count FROM replay_jobs WHERE id = ?", Integer.class, jobId);
        return count == null ? 0 : count;
    }

    private long resultCount(UUID jobId) {
        return jdbc.queryForObject("SELECT count(*) FROM replay_results WHERE job_id = ?", Long.class, jobId);
    }

    private long itemCount(UUID jobId) {
        return jdbc.queryForObject("SELECT count(*) FROM replay_job_items WHERE job_id = ?", Long.class, jobId);
    }
}
