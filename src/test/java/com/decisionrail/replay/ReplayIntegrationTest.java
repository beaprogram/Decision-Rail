package com.decisionrail.replay;

import com.decisionrail.support.Waits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Historical replay against a pinned immutable candidate policy, using real PostgreSQL.
 *
 * <p>The replay worker's timer is off in the test profile, so batches are driven explicitly. That
 * is what makes the resume checks meaningful: a test can stop a job mid-way, leave a dead worker's
 * lease behind, and verify that recovery happens through durable state rather than by calling the
 * same method twice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReplayIntegrationTest {
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final String OTHER = basic("other-merchant", "other-test-password-123");
    private static final String ADMIN = basic("admin", "admin-test-password-123");
    private static final Duration BUDGET = Duration.ofSeconds(30);

    /** Declines anything at or above 1000 minor units, so it diverges from demo-v1 on small amounts. */
    private static final String STRICT_CANDIDATE = """
            {"rules":[
              {"code":"STRICT_AMOUNT","description":"Candidate declines at or above 1000 minor units.",
               "scoreContribution":60,"flag":"HIGH_AMOUNT","terminal":false,
               "expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":1000}}
            ]}""";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReplayWorker worker;
    @Autowired Clock clock;

    private String candidateVersion;
    private Instant windowFrom;

    @BeforeEach
    void registerCandidateAndMarkWindowStart() throws Exception {
        candidateVersion = "replay-candidate-" + UUID.randomUUID().toString().substring(0, 8);
        createPolicy(candidateVersion, STRICT_CANDIDATE, 201);
        // Bounding the window to this test keeps membership assertions about payments this test
        // created, in a database deliberately shared with the other integration suites.
        windowFrom = clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void aJobReplaysPinnedInputsAndReportsDivergenceAgainstStoredRiskDecisions() throws Exception {
        UUID account = newAccount("demo-merchant", 2_000_000);
        UUID approved = authorize(account, 2_500, "CA", "AUTHORIZED");
        UUID review = authorize(account, 150_000, "CA", "REVIEW");
        UUID declined = authorize(account, 600_000, "CA", "DECLINED");

        UUID jobId = createJob(candidateVersion, 100, 201);
        ReplayJobView created = jobView(jobId);
        assertThat(created.inputCount()).isEqualTo(3);
        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.candidateVersion()).isEqualTo(candidateVersion);
        assertThat(created.candidateHash()).isNotBlank();
        assertThat(created.baselineSource()).isEqualTo("STORED_RISK_DECISION");

        runToCompletion(jobId);

        ReplayJobView finished = jobView(jobId);
        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.completedCount()).isEqualTo(3);
        assertThat(finished.failedCount()).isZero();
        assertThat(finished.pendingCount()).isZero();
        // The candidate declines all three amounts.
        assertThat(finished.declineCount()).isEqualTo(3);
        assertThat(finished.approveCount()).isZero();
        assertThat(finished.reviewCount()).isZero();
        // Divergence against stored risk outcomes: APPROVE and REVIEW differ, DECLINE agrees.
        assertThat(finished.divergenceCount()).isEqualTo(2);

        JsonNode report = readJson("/v1/replay-jobs/" + jobId + "/report", DEMO);
        assertThat(report.path("divergenceCount").asInt()).isEqualTo(2);
        assertThat(report.path("divergenceRate").asDouble()).isEqualTo(2.0 / 3.0, org.assertj.core.api.Assertions.within(1e-6));
        assertThat(report.path("divergenceDenominator").asText()).contains("completedCount");
        assertThat(report.path("baselineOutcomeCounts").path("APPROVE").asInt()).isEqualTo(1);
        assertThat(report.path("baselineOutcomeCounts").path("REVIEW").asInt()).isEqualTo(1);
        assertThat(report.path("baselineOutcomeCounts").path("DECLINE").asInt()).isEqualTo(1);
        assertThat(report.path("baselinePolicyVersion").asText()).isEqualTo("demo-v1");
        // Timings are labelled with how they were measured, and no fraud metrics are invented.
        assertThat(report.path("timingMethod").asText()).contains("System.nanoTime");
        assertThat(report.path("labelledOutcomeDataAvailable").asBoolean()).isFalse();
        assertThat(report.has("precision")).isFalse();
        assertThat(report.has("falsePositiveRate")).isFalse();
        assertThat(report.path("evaluationTimings").path("measuredEvaluations").asInt()).isEqualTo(3);

        JsonNode results = readJson("/v1/replay-jobs/" + jobId + "/results?limit=50", DEMO);
        assertThat(results).hasSize(3);
        for (JsonNode result : results) {
            // Both explanations are present per payment.
            assertThat(result.path("baselineReasons").isArray()).isTrue();
            assertThat(result.path("candidateReasons").isArray()).isTrue();
            assertThat(result.path("candidateOutcome").asText()).isEqualTo("DECLINE");
            assertThat(result.path("candidateScore").asInt()).isEqualTo(60);
            assertThat(result.path("errorCode").isNull()).isTrue();
        }
        Map<UUID, Boolean> divergenceByPayment = new LinkedHashMap<>();
        for (JsonNode result : results) {
            divergenceByPayment.put(UUID.fromString(result.path("paymentId").asText()), result.path("diverged").asBoolean());
        }
        assertThat(divergenceByPayment).containsEntry(approved, true).containsEntry(review, true).containsEntry(declined, false);

        JsonNode divergedOnly = readJson("/v1/replay-jobs/" + jobId + "/results?divergedOnly=true", DEMO);
        assertThat(divergedOnly).hasSize(2);
    }

    @Test
    void anInsufficientFundsDeclineIsComparedAsItsApproveRiskDecision() throws Exception {
        // Balance only covers the first authorization; the second is declined for funds, not risk.
        // Both amounts sit above the candidate's threshold, so the candidate declines both and the
        // funding decline is a genuine policy divergence rather than an accidental agreement.
        UUID account = newAccount("demo-merchant", 1_500);
        UUID held = authorize(account, 1_200, "CA", "AUTHORIZED");
        UUID fundsDeclined = authorize(account, 1_100, "CA", "DECLINED");

        assertThat(failureCode(fundsDeclined)).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(riskOutcome(fundsDeclined)).isEqualTo("APPROVE");

        UUID jobId = createJob(candidateVersion, 100, 201);
        runToCompletion(jobId);

        JsonNode results = readJson("/v1/replay-jobs/" + jobId + "/results?limit=50", DEMO);
        JsonNode declinedRow = rowFor(results, fundsDeclined);
        // The baseline is the stored risk decision, not the payment's lifecycle status.
        assertThat(declinedRow.path("baselineOutcome").asText()).isEqualTo("APPROVE");
        assertThat(declinedRow.path("paymentStatus").asText()).isEqualTo("DECLINED");
        assertThat(declinedRow.path("paymentFailureCode").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        // Candidate declines 500 on policy grounds, so this counts as a genuine divergence.
        assertThat(declinedRow.path("candidateOutcome").asText()).isEqualTo("DECLINE");
        assertThat(declinedRow.path("diverged").asBoolean()).isTrue();

        // And the funding decline was never counted as a baseline policy decline.
        JsonNode report = readJson("/v1/replay-jobs/" + jobId + "/report", DEMO);
        assertThat(report.path("baselineOutcomeCounts").path("DECLINE").asInt()).isZero();
        assertThat(report.path("baselineOutcomeCounts").path("APPROVE").asInt()).isEqualTo(2);
        assertThat(rowFor(results, held).path("baselineOutcome").asText()).isEqualTo("APPROVE");
    }

    @Test
    void membershipIsFixedAtCreationSoLaterPaymentsNeverJoinTheJob() throws Exception {
        UUID account = newAccount("demo-merchant", 2_000_000);
        authorize(account, 1_500, "CA", "AUTHORIZED");
        authorize(account, 2_500, "CA", "AUTHORIZED");

        UUID jobId = createJob(candidateVersion, 100, 201);
        assertThat(jobView(jobId).inputCount()).isEqualTo(2);
        List<UUID> membershipAtCreation = membership(jobId);

        // Payments committed after the job exists must not change its membership or totals, even
        // though they fall inside the same merchant and the same requested limit.
        UUID late = authorize(account, 3_500, "CA", "AUTHORIZED");
        authorize(account, 4_500, "CA", "AUTHORIZED");

        assertThat(membership(jobId)).containsExactlyElementsOf(membershipAtCreation).doesNotContain(late);
        runToCompletion(jobId);

        ReplayJobView finished = jobView(jobId);
        assertThat(finished.inputCount()).isEqualTo(2);
        assertThat(finished.completedCount()).isEqualTo(2);
        assertThat(resultCount(jobId)).isEqualTo(2);
        assertThat(membership(jobId)).doesNotContain(late);
    }

    @Test
    void anInterruptedJobResumesWithoutDuplicateResultsOrInflatedTotals() throws Exception {
        UUID account = newAccount("demo-merchant", 5_000_000);
        int inputs = 7;
        for (int index = 0; index < inputs; index++) {
            authorize(account, 1_100 + index, "CA", "AUTHORIZED");
        }
        UUID jobId = createJob(candidateVersion, 100, 201);
        assertThat(jobView(jobId).inputCount()).isEqualTo(inputs);
        assertThat(worker.batchSize()).isLessThan(inputs);

        ReplayWorker.Cycle first = worker.runOnce();
        assertThat(first.jobId()).isEqualTo(jobId);
        assertThat(first.evaluated()).isEqualTo(worker.batchSize());
        long afterFirstBatch = resultCount(jobId);
        assertThat(afterFirstBatch).isEqualTo(worker.batchSize());
        assertThat(jobView(jobId).status()).isEqualTo("RUNNING");

        // Durable recovery, not a repeated call: the job is left exactly as a worker that died
        // mid-run would leave it, still RUNNING and still holding a lease that has since expired.
        jdbc.update("""
                UPDATE replay_jobs SET status = 'RUNNING', lease_owner = 'worker-that-died',
                    lease_token = ?, lease_expires_at = ? WHERE id = ?
                """, UUID.randomUUID(), java.sql.Timestamp.from(clock.instant().minusSeconds(600)), jobId);
        assertThat(jdbc.queryForObject("SELECT lease_owner FROM replay_jobs WHERE id = ?", String.class, jobId))
                .isEqualTo("worker-that-died");

        runToCompletion(jobId);

        ReplayJobView finished = jobView(jobId);
        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(finished.inputCount()).isEqualTo(inputs);
        assertThat(finished.completedCount()).isEqualTo(inputs);
        assertThat(finished.pendingCount()).isZero();
        // One result per pinned input: resuming neither duplicated work nor inflated a total.
        assertThat(resultCount(jobId)).isEqualTo(inputs);
        assertThat(finished.divergenceCount()).isEqualTo(inputs);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM replay_job_items WHERE job_id = ? AND state = 'DONE'", Long.class, jobId))
                .isEqualTo(inputs);

        // Extra cycles after completion change nothing: the job is no longer claimable.
        for (int cycle = 0; cycle < 3; cycle++) worker.runOnce();
        assertThat(resultCount(jobId)).isEqualTo(inputs);
        assertThat(jobView(jobId).completedCount()).isEqualTo(inputs);
    }

    @Test
    void thesamePinnedInputsAndPolicyProduceIdenticalResults() throws Exception {
        UUID account = newAccount("demo-merchant", 3_000_000);
        authorize(account, 1_200, "CA", "AUTHORIZED");
        authorize(account, 250_000, "CA", "REVIEW");
        authorize(account, 700_000, "US", "DECLINED");

        UUID firstJob = createJob(candidateVersion, 100, 201);
        runToCompletion(firstJob);
        UUID secondJob = createJob(candidateVersion, 100, 201);
        runToCompletion(secondJob);

        List<String> firstResults = comparableResults(firstJob);
        List<String> secondResults = comparableResults(secondJob);
        assertThat(secondResults).isEqualTo(firstResults);
        assertThat(jobView(secondJob).divergenceCount()).isEqualTo(jobView(firstJob).divergenceCount());
        assertThat(jobView(secondJob).candidateHash()).isEqualTo(jobView(firstJob).candidateHash());
    }

    @Test
    void replayingHistoryLeavesTheOriginalDecisionsAndMoneyUntouched() throws Exception {
        UUID account = newAccount("demo-merchant", 2_000_000);
        UUID payment = authorize(account, 4_000, "CA", "AUTHORIZED");
        authorize(account, 320_000, "CA", "REVIEW");

        Map<String, Object> paymentBefore = paymentRow(payment);
        Map<String, Object> accountBefore = accountRow(account);
        long ledgerBefore = countWhere("ledger_entries", "1=1");
        long outboxBefore = countWhere("outbox_events", "aggregate_id = '" + payment + "'");

        UUID jobId = createJob(candidateVersion, 100, 201);
        runToCompletion(jobId);
        assertThat(jobView(jobId).completedCount()).isEqualTo(2);

        // Nothing about the original payment, its stored decision, or its money changed.
        assertThat(paymentRow(payment)).isEqualTo(paymentBefore);
        assertThat(accountRow(account)).isEqualTo(accountBefore);
        assertThat(countWhere("ledger_entries", "1=1")).isEqualTo(ledgerBefore);
        assertThat(countWhere("outbox_events", "aggregate_id = '" + payment + "'")).isEqualTo(outboxBefore);
        assertThat(riskOutcome(payment)).isEqualTo("APPROVE");
        assertThat(jdbc.queryForObject("SELECT decision ->> 'ruleSetVersion' FROM payments WHERE id = ?",
                String.class, payment)).isEqualTo("demo-v1");
    }

    @Test
    void anotherMerchantCannotReadOrCreateAgainstThisMerchantsJob() throws Exception {
        UUID account = newAccount("demo-merchant", 1_000_000);
        authorize(account, 2_200, "CA", "AUTHORIZED");
        UUID jobId = createJob(candidateVersion, 100, 201);
        runToCompletion(jobId);

        // A foreign job reads as absent rather than forbidden, so ids cannot be probed.
        assertThat(status(get("/v1/replay-jobs/" + jobId), OTHER)).isEqualTo(404);
        assertThat(status(get("/v1/replay-jobs/" + jobId + "/results"), OTHER)).isEqualTo(404);
        assertThat(status(get("/v1/replay-jobs/" + jobId + "/report"), OTHER)).isEqualTo(404);
        assertThat(readJson("/v1/replay-jobs", OTHER)).noneSatisfy(job ->
                assertThat(job.path("id").asText()).isEqualTo(jobId.toString()));

        // The other merchant's own job sees only its own payments.
        UUID otherAccount = newAccount("other-merchant", 1_000_000);
        authorizeAs(otherAccount, 2_200, "CA", OTHER);
        UUID otherJob = createJobAs(candidateVersion, OTHER);
        List<UUID> otherMembership = membership(otherJob);
        assertThat(otherMembership).isNotEmpty();
        for (UUID member : otherMembership) {
            assertThat(jdbc.queryForObject("SELECT merchant_id FROM payments WHERE id = ?", String.class, member))
                    .isEqualTo("other-merchant");
        }
        assertThat(status(get("/v1/replay-jobs/" + otherJob), DEMO)).isEqualTo(404);

        // Administrative routes stay closed to merchants, and merchant routes to the admin.
        assertThat(status(put("/v1/ops/shadow").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"), DEMO)).isEqualTo(403);
        assertThat(status(get("/v1/ops/outbox/backlog"), DEMO)).isEqualTo(403);
        assertThat(status(get("/v1/ops/outbox/backlog"),
                basic("operations", "operations-test-password-123"))).isEqualTo(403);
        assertThat(status(get("/v1/replay-jobs"), ADMIN)).isEqualTo(403);
    }

    @Test
    void aRepeatedJobRequestReturnsTheSameJobAndAConflictingKeyIsRejected() throws Exception {
        UUID account = newAccount("demo-merchant", 1_000_000);
        authorize(account, 1_700, "CA", "AUTHORIZED");
        String key = UUID.randomUUID().toString();

        JsonNode first = createJobResponse(candidateVersion, 100, key, 201);
        JsonNode repeat = createJobResponse(candidateVersion, 100, key, 200);
        assertThat(repeat.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(countWhere("replay_jobs", "merchant_id = 'demo-merchant'")).isGreaterThan(0);

        // A job response is the job document, not a payment document forced into this shape.
        assertThat(first.has("amountMinor")).isFalse();
        assertThat(first.has("decision")).isFalse();
        assertThat(first.path("inputCount").asInt()).isEqualTo(1);

        // Same key, different request: rejected instead of returning the wrong job.
        String body = json.writeValueAsString(Map.of("candidateVersion", candidateVersion, "limit", 7));
        assertThat(status(post("/v1/replay-jobs").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body), DEMO)).isEqualTo(409);
    }

    @Test
    void aPolicyVersionCannotBeRebound() throws Exception {
        String versionId = "rebind-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode created = createPolicy(versionId, STRICT_CANDIDATE, 201);
        assertThat(created.path("origin").asText()).isEqualTo("CANDIDATE");
        assertThat(created.path("definitionHash").asText()).isNotBlank();

        // Identical resubmission is an idempotent retry, not a conflict.
        JsonNode again = createPolicy(versionId, STRICT_CANDIDATE, 200);
        assertThat(again.path("definitionHash").asText()).isEqualTo(created.path("definitionHash").asText());

        // Reformatted but semantically identical: still the same version, same hash.
        JsonNode reformatted = createPolicy(versionId, STRICT_CANDIDATE.replace("\n", " ").replace("  ", " "), 200);
        assertThat(reformatted.path("definitionHash").asText()).isEqualTo(created.path("definitionHash").asText());

        // Different content under the same name is refused.
        String changed = STRICT_CANDIDATE.replace("\"scoreContribution\":60", "\"scoreContribution\":30");
        JsonNode conflict = createPolicyExpectingProblem(versionId, changed, 409);
        assertThat(conflict.path("code").asText()).isEqualTo("POLICY_VERSION_CONFLICT");
        assertThat(createPolicy(versionId, STRICT_CANDIDATE, 200).path("definitionHash").asText())
                .isEqualTo(created.path("definitionHash").asText());

        // The reserved built-in identifier cannot be claimed by a candidate.
        assertThat(createPolicyExpectingProblem("demo-v1", STRICT_CANDIDATE, 400).path("code").asText())
                .isEqualTo("INVALID_POLICY_DEFINITION");

        // The built-in policy is recorded, immutable, and distinguishable from candidates.
        JsonNode builtin = readJson("/v1/policies/demo-v1", DEMO);
        assertThat(builtin.path("origin").asText()).isEqualTo("BUILTIN");
        assertThat(builtin.path("definitionHash").asText()).hasSize(64);
    }

    // ----- helpers -----

    private void runToCompletion(UUID jobId) {
        Waits.until("replay job " + jobId + " completes", BUDGET, () -> {
            worker.runOnce();
            return "COMPLETED".equals(jobView(jobId).status());
        });
    }

    private ReplayJobView jobView(UUID jobId) {
        try {
            JsonNode node = readJson("/v1/replay-jobs/" + jobId, DEMO);
            return json.treeToValue(node, ReplayJobView.class);
        } catch (Exception failure) {
            throw new AssertionError("could not read replay job " + jobId, failure);
        }
    }

    private List<UUID> membership(UUID jobId) {
        return jdbc.queryForList("SELECT payment_id FROM replay_job_items WHERE job_id = ? ORDER BY item_sequence",
                UUID.class, jobId);
    }

    private long resultCount(UUID jobId) {
        return jdbc.queryForObject("SELECT count(*) FROM replay_results WHERE job_id = ?", Long.class, jobId);
    }

    private List<String> comparableResults(UUID jobId) {
        return jdbc.queryForList("""
                SELECT i.item_sequence || '|' || r.payment_id || '|' || r.baseline_outcome || '|' || r.baseline_score
                       || '|' || r.candidate_outcome || '|' || r.candidate_score || '|' || r.candidate_raw_score
                       || '|' || r.candidate_score_capped || '|' || r.diverged || '|' || r.candidate_reasons::text
                FROM replay_results r JOIN replay_job_items i ON i.job_id = r.job_id AND i.payment_id = r.payment_id
                WHERE r.job_id = ? ORDER BY i.item_sequence
                """, String.class, jobId);
    }

    private JsonNode rowFor(JsonNode results, UUID paymentId) {
        for (JsonNode row : results) {
            if (paymentId.toString().equals(row.path("paymentId").asText())) return row;
        }
        throw new AssertionError("no replay result for payment " + paymentId);
    }

    private String riskOutcome(UUID paymentId) {
        return jdbc.queryForObject("SELECT decision ->> 'outcome' FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String failureCode(UUID paymentId) {
        return jdbc.queryForObject("SELECT failure_code FROM payments WHERE id = ?", String.class, paymentId);
    }

    private Map<String, Object> paymentRow(UUID paymentId) {
        return jdbc.queryForMap("SELECT * FROM payments WHERE id = ?", paymentId);
    }

    private Map<String, Object> accountRow(UUID accountId) {
        return jdbc.queryForMap("SELECT * FROM accounts WHERE id = ?", accountId);
    }

    private long countWhere(String table, String predicate) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + predicate, Long.class);
    }

    private UUID createJob(String candidate, int limit, int expectedStatus) throws Exception {
        return UUID.fromString(createJobResponse(candidate, limit, UUID.randomUUID().toString(), expectedStatus)
                .path("id").asText());
    }

    private JsonNode createJobResponse(String candidate, int limit, String key, int expectedStatus) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidateVersion", candidate);
        body.put("limit", limit);
        body.put("from", windowFrom.toString());
        var result = mvc.perform(post("/v1/replay-jobs").header("Authorization", DEMO)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("create replay job: %s", result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID createJobAs(String candidate, String auth) throws Exception {
        Map<String, Object> body = Map.of("candidateVersion", candidate, "limit", 100, "from", windowFrom.toString());
        var result = mvc.perform(post("/v1/replay-jobs").header("Authorization", auth)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private JsonNode createPolicy(String versionId, String definition, int expectedStatus) throws Exception {
        var result = mvc.perform(post("/v1/policies").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"" + versionId + "\",\"definition\":" + definition + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("create policy: %s", result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode createPolicyExpectingProblem(String versionId, String definition, int expectedStatus) throws Exception {
        var result = mvc.perform(post("/v1/policies").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"" + versionId + "\",\"definition\":" + definition + "}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID newAccount(String merchant, long balance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                id, merchant, balance, balance);
        return id;
    }

    private UUID authorize(UUID account, long amount, String country, String expectedStatus) throws Exception {
        JsonNode node = authorizeAs(account, amount, country, DEMO);
        assertThat(node.path("status").asText()).isEqualTo(expectedStatus);
        return UUID.fromString(node.path("id").asText());
    }

    private JsonNode authorizeAs(UUID account, long amount, String country, String auth) throws Exception {
        String body = json.writeValueAsString(Map.of("accountId", account, "amountMinor", amount,
                "currency", "CAD", "country", country));
        var result = mvc.perform(post("/v1/payments/authorizations").header("Authorization", auth)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readJson(String path, String auth) throws Exception {
        var result = mvc.perform(get(path).header("Authorization", auth)).andReturn();
        assertThat(result.getResponse().getStatus()).as("GET %s", path).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String auth)
            throws Exception {
        return mvc.perform(request.header("Authorization", auth)).andReturn().getResponse().getStatus();
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
