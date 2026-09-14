package com.decisionrail.reconciliation;

import com.decisionrail.payments.AuthorizationCommand;
import com.decisionrail.payments.PaymentService;
import com.decisionrail.payments.ReturnCommand;
import com.decisionrail.payments.ReturnType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Reconciliation against real state: a correct population, a deliberately corrupted one, and both
 * while ordinary financial work is committing.
 *
 * <p>The corruption is applied to accounts this test created and to nothing else. The suite shares a
 * database, so a fixture broken globally would make every other test's reconciliation fail and would
 * make this one's success meaningless.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReconciliationIntegrationTest {
    private static final String DEMO = basic("demo-merchant", "demo-test-password-123");
    private static final String OTHER = basic("other-merchant", "other-test-password-123");
    private static final String OPERATIONS = basic("operations", "operations-test-password-123");
    private static final String ADMIN = basic("admin", "admin-test-password-123");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired ReconciliationService reconciliation;

    private UUID accountId;

    @BeforeEach
    void createIsolatedAccount() {
        accountId = newAccount("demo-merchant", 80_000);
    }

    @Test
    void aCorrectPopulationReconcilesAndSaysWhatItExamined() {
        UUID captured = capture(authorize(6_000));
        refund(captured, 2_500);
        authorize(1_200);

        ReconciliationReport report = report();
        assertThat(report.findings()).isEmpty();
        assertThat(report.status()).isEqualTo(ReconciliationReport.Status.CLEAN);
        assertThat(report.scope().complete()).isTrue();
        assertThat(report.scope().accountsExamined()).isEqualTo(1);
        assertThat(report.scope().paymentsExamined()).isEqualTo(2);
        assertThat(report.scope().returnsExamined()).isEqualTo(1);
        assertThat(report.scope().currencies()).containsExactly("CAD");
        assertThat(report.scope().snapshot()).contains("REPEATABLE READ");
        assertThat(report.scope().checks()).isNotEmpty();
        assertThat(report.limitations()).isNotEmpty();
    }

    @Test
    void aBalanceThatDisagreesWithTheLedgerIsReportedWithTheEvidenceForTheExpectation() {
        UUID captured = capture(authorize(6_000));
        refund(captured, 1_000);
        // Opening 80000, captured 6000, returned 1000 -> the ledger implies 75000.

        // A disposable fixture made inconsistent on purpose: the balance moves, the ledger does not.
        jdbc.update("UPDATE accounts SET balance_minor = balance_minor - 250 WHERE id = ?", accountId);

        ReconciliationFinding finding = onlyFinding("ACCOUNT_BALANCE_MISMATCH");
        assertThat(finding.severity()).isEqualTo(ReconciliationFinding.Severity.CRITICAL);
        assertThat(finding.resourceType()).isEqualTo("ACCOUNT");
        assertThat(finding.resourceId()).isEqualTo(accountId);
        assertThat(finding.currency()).isEqualTo("CAD");
        assertThat(finding.expectedMinor()).isEqualTo(75_000);
        assertThat(finding.actualMinor()).isEqualTo(74_750);
        assertThat(finding.deltaMinor()).isEqualTo(-250);
        assertThat(finding.detail()).contains("80000").contains("6000").contains("1000");
        assertThat(finding.references()).contains(new ReconciliationFinding.Reference("ACCOUNT", accountId));
        assertThat(report().status()).isEqualTo(ReconciliationReport.Status.DISCREPANCIES_FOUND);
    }

    @Test
    void aReturnedTotalThatDisagreesWithItsOperationsIsReported() {
        UUID captured = capture(authorize(6_000));
        refund(captured, 1_000);

        // The payment's own total is moved without touching the operations or the journals, which is
        // exactly the divergence a check comparing only two fields of the same write would miss.
        jdbc.update("UPDATE payments SET returned_amount_minor = 900 WHERE id = ?", captured);

        List<ReconciliationFinding> findings = report().findings();
        assertThat(findings).extracting(ReconciliationFinding::type).contains("RETURN_TOTAL_MISMATCH");
        ReconciliationFinding mismatch = findings.stream()
                .filter(finding -> finding.type().equals("RETURN_TOTAL_MISMATCH")).findFirst().orElseThrow();
        assertThat(mismatch.resourceId()).isEqualTo(captured);
        assertThat(mismatch.expectedMinor()).isEqualTo(1_000);
        assertThat(mismatch.actualMinor()).isEqualTo(900);
        assertThat(mismatch.deltaMinor()).isEqualTo(-100);
    }

    @Test
    void aHoldThatDisagreesWithTheOutstandingAuthorizationsIsReported() {
        authorize(2_000);
        jdbc.update("UPDATE accounts SET held_minor = held_minor + 75 WHERE id = ?", accountId);

        ReconciliationFinding finding = onlyFinding("ACCOUNT_HELD_MISMATCH");
        assertThat(finding.expectedMinor()).isEqualTo(2_000);
        assertThat(finding.actualMinor()).isEqualTo(2_075);
        assertThat(finding.deltaMinor()).isEqualTo(75);
    }

    @Test
    void aBoundedReportIsNeverDescribedAsClean() {
        authorize(100);
        authorize(100);
        authorize(100);

        ReconciliationReport bounded = reconciliation.forMerchant("demo-merchant",
                new ReconciliationRequest(accountId, 25, 2));
        assertThat(bounded.findings()).isEmpty();
        assertThat(bounded.status())
                .as("no findings in a partial population is not a clean result")
                .isEqualTo(ReconciliationReport.Status.INCOMPLETE);
        assertThat(bounded.scope().complete()).isFalse();
        assertThat(bounded.scope().incompleteReason()).contains("more than 2 payments match");
        assertThat(bounded.scope().paymentsExamined()).isEqualTo(2);
    }

    @Test
    void anIncompleteReportThatAlsoFoundSomethingSaysBoth() {
        authorize(100);
        authorize(100);
        authorize(100);
        jdbc.update("UPDATE accounts SET held_minor = held_minor + 5 WHERE id = ?", accountId);

        ReconciliationReport bounded = reconciliation.forMerchant("demo-merchant",
                new ReconciliationRequest(accountId, 25, 2));
        assertThat(bounded.status()).isEqualTo(ReconciliationReport.Status.INCOMPLETE_WITH_DISCREPANCIES);
        assertThat(bounded.findings()).isNotEmpty();
    }

    @Test
    void reconciliationStaysConsistentWhileFinancialTransactionsCommit() throws Exception {
        UUID captured = capture(authorize(20_000));
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch running = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            worker.submit(() -> {
                running.countDown();
                while (!stop.get()) {
                    // Ordinary, valid work on the same account: a refund and a fresh capture, each
                    // committing between the report's queries if the snapshot does not hold.
                    refund(captured, 10);
                    capture(authorize(15));
                }
            });
            assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();

            for (int attempt = 0; attempt < 12; attempt++) {
                assertThat(report().findings())
                        .as("valid concurrent activity must not produce findings")
                        .isEmpty();
            }
        } finally {
            stop.set(true);
            worker.shutdown();
            assertThat(worker.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        // And the settled state still reconciles once everything has committed.
        assertThat(report().findings()).isEmpty();
    }

    @Test
    void oneMerchantsReportNeverContainsAnotherMerchantsAccounts() throws Exception {
        UUID theirs = newAccount("other-merchant", 40_000);
        jdbc.update("UPDATE accounts SET balance_minor = balance_minor - 500 WHERE id = ?", theirs);

        // Their account is broken; ours is not. Our report must not mention theirs at all.
        assertThat(report().findings()).isEmpty();

        // Nor can we ask for it by id: the merchant predicate is part of the query, not a later filter.
        ReconciliationReport targeted = reconciliation.forMerchant("demo-merchant",
                new ReconciliationRequest(theirs, 25, 500));
        assertThat(targeted.scope().accountsExamined()).isZero();
        assertThat(targeted.findings()).isEmpty();

        Reply overHttp = read("/v1/reconciliation?accountId=" + theirs, DEMO);
        assertThat(overHttp.status()).isEqualTo(200);
        assertThat(overHttp.body().path("scope").path("accountsExamined").asInt()).isZero();

        // The same credential buys nothing on the browser chain, which is session-authenticated and
        // CSRF-protected. The two chains stay separate for reconciliation exactly as for everything
        // else; the browser route is exercised with a real session in the Playwright suite.
        assertThat(read("/ui/reconciliation?accountId=" + theirs, DEMO).status()).isEqualTo(401);

        // The owner does see it.
        ReconciliationReport ownersView = reconciliation.forMerchant("other-merchant",
                new ReconciliationRequest(theirs, 25, 500));
        assertThat(ownersView.findings()).extracting(ReconciliationFinding::type)
                .contains("ACCOUNT_BALANCE_MISMATCH");
    }

    @Test
    void theBroaderAdministratorViewIsAuthorisedOnTheServerNotByHidingIt() throws Exception {
        assertThat(read("/v1/ops/reconciliation?merchantId=demo-merchant", ADMIN).status()).isEqualTo(200);
        // A merchant identity cannot reach the administrative route even though it exists.
        assertThat(read("/v1/ops/reconciliation?merchantId=other-merchant", DEMO).status()).isEqualTo(403);
        assertThat(read("/v1/ops/reconciliation?merchantId=demo-merchant", OTHER).status()).isEqualTo(403);
        // OPERATIONS stays metrics-only.
        assertThat(read("/v1/ops/reconciliation?merchantId=demo-merchant", OPERATIONS).status()).isEqualTo(403);
    }

    @Test
    void reconciliationDoesNotRepairAnythingItFinds() {
        UUID captured = capture(authorize(6_000));
        refund(captured, 1_000);
        jdbc.update("UPDATE accounts SET balance_minor = balance_minor - 250 WHERE id = ?", accountId);

        long balanceBefore = balance();
        long returnsBefore = count("SELECT count(*) FROM payment_returns WHERE account_id = ?", accountId);
        String journalSql = "SELECT count(*) FROM ledger_journals j JOIN payments p ON p.id = j.payment_id WHERE p.account_id = ?";
        long journalsBefore = count(journalSql, accountId);

        assertThat(report().findings()).isNotEmpty();
        report();
        report();

        assertThat(balance()).as("a report must not correct the balance it complains about").isEqualTo(balanceBefore);
        assertThat(count("SELECT count(*) FROM payment_returns WHERE account_id = ?", accountId)).isEqualTo(returnsBefore);
        assertThat(count(journalSql, accountId)).isEqualTo(journalsBefore);
    }

    // ----- helpers -----

    private ReconciliationReport report() {
        return reconciliation.forMerchant("demo-merchant", new ReconciliationRequest(accountId, 25, 500));
    }

    private ReconciliationFinding onlyFinding(String type) {
        List<ReconciliationFinding> findings = report().findings();
        assertThat(findings).as("findings: %s", findings).hasSize(1);
        assertThat(findings.getFirst().type()).isEqualTo(type);
        return findings.getFirst();
    }

    private UUID authorize(long amount) {
        return payments.authorize("demo-merchant", key(),
                new AuthorizationCommand(accountId, amount, "CAD", "CA")).body().id();
    }

    private UUID capture(UUID payment) {
        payments.capture("demo-merchant", key(), payment);
        return payment;
    }

    private void refund(UUID payment, long amount) {
        payments.returnFunds("demo-merchant", key(), new ReturnCommand(payment, ReturnType.REFUND, amount, null));
    }

    private UUID newAccount(String merchant, long openingBalance) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id,merchant_id,currency,opening_balance_minor,balance_minor) VALUES (?,?,'CAD',?,?)",
                id, merchant, openingBalance, openingBalance);
        return id;
    }

    private long balance() {
        return jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long.class, accountId);
    }

    private long count(String sql, Object argument) {
        return jdbc.queryForObject(sql, Long.class, argument);
    }

    private Reply read(String path, String auth) throws Exception {
        MvcResult result = mvc.perform(get(path).header("Authorization", auth)).andReturn();
        String body = result.getResponse().getContentAsString();
        return new Reply(result.getResponse().getStatus(), body.isBlank() ? json.nullNode() : json.readTree(body));
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private record Reply(int status, JsonNode body) {}
}
