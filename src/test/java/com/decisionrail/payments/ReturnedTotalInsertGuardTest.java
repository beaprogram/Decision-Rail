package com.decisionrail.payments;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The returned-total equality, reached from all three ways a transaction can break it.
 *
 * <h2>The gap this class exists for</h2>
 * V10 attached the equality check to inserts on {@code payment_returns} and V13 added the payment
 * update side, after which the rule was described as holding "whichever side is written". It did not:
 * a payment <em>inserted</em> already inconsistent is neither event. Reproduced against V1-V13 on
 * PostgreSQL 16.15 — a valid account, a CAPTURED payment of 1000 captured 1000 recording 100 returned,
 * a balanced 1000-unit capture journal and no return operations — which committed cleanly, while an
 * UPDATE of that same total was refused. V14 adds the missing deferred trigger.
 *
 * <p>These write through {@code JdbcTemplate} rather than the service, deliberately. No API path
 * produces any of this; what is under test is whether the database would accept it from anything.
 *
 * <p>Every fixture below is complete and valid apart from the one thing being tested — real merchant,
 * a funded account in the payment's own currency, a balanced capture journal naming the right ledger
 * accounts — so a rejection cannot come from an unrelated constraint and quietly pass for the right
 * reason. {@code aValidCapturedPaymentWithNothingReturnedCommits} is the control that proves it.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReturnedTotalInsertGuardTest {
    private static final String EQUALITY_MESSAGE = "return operations total";

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;

    private UUID accountId;

    @BeforeEach
    void createIsolatedAccount() {
        accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, merchant_id, currency, opening_balance_minor, balance_minor, held_minor)
                VALUES (?, 'demo-merchant', 'CAD', 100000, 100000, 0)
                """, accountId);
    }

    @Test
    void aPaymentInsertedClaimingMoneyItNeverReturnedIsRefusedAtCommit() {
        UUID payment = UUID.randomUUID();

        // Exactly the reproduction: the payment arrives already claiming 100 returned, with no return
        // operation anywhere and no follow-up UPDATE that could hand the work to V13's guard instead.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            insertCapturedPayment(payment, 1_000, 1_000, 100);
            insertCaptureJournal(payment, 1_000);
        }))
                .as("an inserted payment cannot claim a returned total its operations do not sum to")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(EQUALITY_MESSAGE)
                .hasStackTraceContaining("records 100 returned")
                .hasStackTraceContaining("total 0");

        // The whole transaction went back, not only the payment row.
        assertThat(count("SELECT count(*) FROM payments WHERE id = ?", payment)).isZero();
        assertThat(count("SELECT count(*) FROM ledger_journals WHERE payment_id = ?", payment)).isZero();
        assertThat(count("""
                SELECT count(*) FROM ledger_entries e JOIN ledger_journals j ON j.id = e.journal_id
                WHERE j.payment_id = ?
                """, payment)).isZero();
    }

    @Test
    void theRefusalIsTheEqualityRuleAndNotTheCapOrTheCaptureRules() {
        // Below the capture cap and on a properly CAPTURED payment, so neither
        // payments_returned_within_capture nor the "only a captured payment can be returned" rule can
        // be what answers. Asserted by message, because "some database error" would pass either way.
        UUID payment = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            insertCapturedPayment(payment, 1_000, 1_000, 1);
            insertCaptureJournal(payment, 1_000);
        }))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(EQUALITY_MESSAGE);

        // And the cap still answers for its own case, by its own name.
        UUID overCap = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                insertCapturedPayment(overCap, 1_000, 1_000, 1_001)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("payments_returned_within_capture");
    }

    @Test
    void aValidCapturedPaymentWithNothingReturnedCommits() {
        UUID payment = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            insertCapturedPayment(payment, 1_000, 1_000, 0);
            insertCaptureJournal(payment, 1_000);
        });

        assertThat(recordedReturnedTotal(payment)).isZero();
        assertThat(count("SELECT count(*) FROM ledger_journals WHERE payment_id = ?", payment)).isEqualTo(1);
    }

    @Test
    void aPaymentInsertedWithTheReturnEvidenceThatJustifiesItCommits() {
        UUID payment = UUID.randomUUID();

        // The payment is inconsistent at the moment it is inserted and consistent by the time the
        // transaction commits. A trigger checked immediately would reject this; the deferred one must
        // not, because judging a transaction on the order its statements happened to run in would make
        // the rule depend on the writer's sequencing rather than on what is true at the end.
        transactions.executeWithoutResult(status -> {
            insertCapturedPayment(payment, 1_000, 1_000, 250);
            insertCaptureJournal(payment, 1_000);
            insertReturn(payment, 250, 1);
        });

        assertThat(recordedReturnedTotal(payment)).isEqualTo(250);
        assertThat(operationsTotal(payment)).isEqualTo(250);
    }

    @Test
    void aPaymentInsertedAtZeroStillCannotGainAReturnWithoutItsTotal() {
        // The insert trigger deliberately does not run for a payment inserted with a returned total of
        // zero: a return operation references its payment, so none can exist before the row does, and
        // the ordinary authorization path would otherwise pay for an aggregate at every commit. This
        // is the case that keeps that reasoning honest - a return added afterwards in the same
        // transaction is caught by the return-side trigger instead.
        UUID payment = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            insertCapturedPayment(payment, 1_000, 1_000, 0);
            insertCaptureJournal(payment, 1_000);
            insertReturn(payment, 100, 1);
        }))
                .as("a return still has to agree with the total the payment records")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(EQUALITY_MESSAGE);

        assertThat(count("SELECT count(*) FROM payments WHERE id = ?", payment)).isZero();
    }

    @Test
    void theUpdateAndReturnInsertionSidesStillAnswerForTheirOwnCases() {
        UUID payment = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            insertCapturedPayment(payment, 1_000, 1_000, 0);
            insertCaptureJournal(payment, 1_000);
        });

        // The update side, as V13 added it.
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET returned_amount_minor = 100 WHERE id = ?", payment))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(EQUALITY_MESSAGE);

        // The return-insertion side, as V10 added it.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> insertReturn(payment, 100, 1)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(EQUALITY_MESSAGE);

        // Nothing above changed anything, and writing both sides together still commits.
        assertThat(recordedReturnedTotal(payment)).isZero();
        transactions.executeWithoutResult(status -> {
            insertReturn(payment, 100, 1);
            jdbc.update("UPDATE payments SET returned_amount_minor = 100 WHERE id = ?", payment);
        });
        assertThat(recordedReturnedTotal(payment)).isEqualTo(100);
        assertThat(operationsTotal(payment)).isEqualTo(100);
    }

    // ----- fixture -----

    private void insertCapturedPayment(UUID id, long amount, long captured, long returned) {
        jdbc.update("""
                INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                        decision, created_at, updated_at, captured_amount_minor, returned_amount_minor)
                VALUES (?, 'demo-merchant', ?, ?, 'CAD', 'CA', 'CAPTURED',
                        '{"outcome":"APPROVE","score":0,"ruleSetVersion":"demo-v1","reasons":[],"flags":[]}'::jsonb,
                        now(), now(), ?, ?)
                """, id, accountId, amount, captured, returned);
    }

    /** A journal the ledger rules accept: balanced, right amount, right accounts, right direction. */
    private void insertCaptureJournal(UUID payment, long amount) {
        UUID journal = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ledger_journals (id, payment_id, merchant_id, currency, journal_kind)
                VALUES (?, ?, 'demo-merchant', 'CAD', 'CAPTURE')
                """, journal, payment);
        jdbc.update("""
                INSERT INTO ledger_entries (id, journal_id, ledger_account, side, amount_minor) VALUES
                  (?, ?, ?, 'DEBIT', ?), (?, ?, 'merchant-clearing:demo-merchant', 'CREDIT', ?)
                """, UUID.randomUUID(), journal, "wallet:" + accountId, amount,
                UUID.randomUUID(), journal, amount);
    }

    private void insertReturn(UUID payment, long amount, int sequence) {
        jdbc.update("""
                INSERT INTO payment_returns (id, payment_id, merchant_id, account_id, return_type,
                        amount_minor, currency, sequence_number, created_at)
                VALUES (?, ?, 'demo-merchant', ?, 'REFUND', ?, 'CAD', ?, now())
                """, UUID.randomUUID(), payment, accountId, amount, sequence);
    }

    private long recordedReturnedTotal(UUID payment) {
        return jdbc.queryForObject("SELECT returned_amount_minor FROM payments WHERE id = ?", Long.class, payment);
    }

    private long operationsTotal(UUID payment) {
        return jdbc.queryForObject(
                "SELECT coalesce(sum(amount_minor), 0) FROM payment_returns WHERE payment_id = ?", Long.class, payment);
    }

    private long count(String sql, Object argument) {
        return jdbc.queryForObject(sql, Long.class, argument);
    }
}
