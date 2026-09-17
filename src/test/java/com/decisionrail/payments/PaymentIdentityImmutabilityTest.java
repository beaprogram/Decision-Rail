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
 * A payment's identity cannot change, and the deferred returned-total check can no longer be walked
 * away from.
 *
 * <h2>What this closes</h2>
 * V14 refuses a payment inserted with a returned total its operations do not sum to. A deferred
 * trigger captures its {@code NEW} row when the statement runs, though, and validates at COMMIT — so
 * moving the row out from under it defeated the check:
 *
 * <pre>
 *   INSERT payment A recording 100 returned   -- queues enforce_returned_total(A)
 *   UPDATE payments SET id = B WHERE id = A   -- V13's trigger watches returned/captured, not id
 *   COMMIT                                    -- enforce_returned_total(A) finds no row and returns;
 *                                                B is never examined
 * </pre>
 *
 * Reproduced on PostgreSQL 16.15 against V1–V14 applied unchanged: it committed, leaving a payment
 * recording 100 returned against zero return operations. The same transaction without the identity
 * change was refused. V15 makes the identity immutable, which removes the class rather than the case.
 *
 * <p>A direct-SQL defect: no API path changes a payment's id. The three UPDATEs the application issues
 * against {@code payments} set status, captured and returned amounts and {@code updated_at}, and
 * {@link #theUpdatesTheApplicationActuallyMakesAreUnaffected()} is what keeps that claim honest.
 */
@SpringBootTest
@ActiveProfiles("test")
class PaymentIdentityImmutabilityTest {
    private static final String IMMUTABLE = "identity is immutable";

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired PaymentService payments;

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
    void renamingAPaymentBeforeTheDeferredCheckRunsIsRefusedAndLeavesNothing() {
        UUID inserted = UUID.randomUUID();
        UUID renamed = UUID.randomUUID();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            insertCapturedPayment(inserted, 1_000, 1_000, 100);
            jdbc.update("UPDATE payments SET id = ? WHERE id = ?", renamed, inserted);
            insertCaptureJournal(renamed, 1_000);
        }))
                .as("the reproduced bypass")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(IMMUTABLE)
                .hasStackTraceContaining(inserted.toString())
                .hasStackTraceContaining(renamed.toString());

        // Neither identity survives, and nothing partial is left under either of them.
        for (UUID id : new UUID[] {inserted, renamed}) {
            assertThat(count("SELECT count(*) FROM payments WHERE id = ?", id)).isZero();
            assertThat(count("SELECT count(*) FROM ledger_journals WHERE payment_id = ?", id)).isZero();
            assertThat(count("""
                    SELECT count(*) FROM ledger_entries e JOIN ledger_journals j ON j.id = e.journal_id
                    WHERE j.payment_id = ?
                    """, id)).isZero();
        }
    }

    @Test
    void aCommittedPaymentCannotBeRenamedAfterwardsEither() {
        UUID payment = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            insertCapturedPayment(payment, 1_000, 1_000, 0);
            insertCaptureJournal(payment, 1_000);
        });

        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET id = ? WHERE id = ?", UUID.randomUUID(), payment))
                .as("a payment already carrying journals and evidence keeps its name")
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(IMMUTABLE);

        assertThat(count("SELECT count(*) FROM payments WHERE id = ?", payment)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_journals WHERE payment_id = ?", payment)).isEqualTo(1);
    }

    @Test
    void theUpdatesTheApplicationActuallyMakesAreUnaffected() {
        UUID payment = UUID.randomUUID();
        transactions.executeWithoutResult(status -> insertAuthorizedPayment(payment, 1_000));

        // Exactly the three the application issues: capture, a status transition, and a returned total.
        jdbc.update("UPDATE payments SET status = 'CAPTURED', captured_amount_minor = 1000, updated_at = now() WHERE id = ?", payment);
        assertThat(statusOf(payment)).isEqualTo("CAPTURED");

        transactions.executeWithoutResult(status -> {
            insertCaptureJournal(payment, 1_000);
            insertReturn(payment, 250, 1);
            jdbc.update("UPDATE payments SET returned_amount_minor = 250, updated_at = now() WHERE id = ?", payment);
        });
        assertThat(recordedReturnedTotal(payment)).isEqualTo(250);

        // And an UPDATE that names id while leaving it alone is still allowed: the rule is about the
        // identity changing, not about the column being mentioned.
        assertThat(jdbc.update("UPDATE payments SET id = ?, updated_at = now() WHERE id = ?", payment, payment))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments WHERE id = ?", payment)).isEqualTo(1);
    }

    @Test
    void theDeferredPositiveCaseStillCommits() {
        // V14's whole point: a payment inserted inconsistent and made consistent before COMMIT is
        // legitimate, and V15 must not have turned the deferred check into an immediate one.
        UUID payment = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            insertCapturedPayment(payment, 1_000, 1_000, 250);
            insertCaptureJournal(payment, 1_000);
            insertReturn(payment, 250, 1);
        });

        assertThat(recordedReturnedTotal(payment)).isEqualTo(250);
        assertThat(operationsTotal(payment)).isEqualTo(250);

        // And the insert-side refusal it added still fires.
        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                insertCapturedPayment(UUID.randomUUID(), 1_000, 1_000, 100)))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("return operations total");
    }

    @Test
    void theOrdinaryLifecycleStillWorksThroughTheService() {
        // The service path, not direct SQL: authorize, capture, refund, and a reversal on its own
        // payment. If identity immutability disagreed with how the application writes, this is where
        // it would show.
        UUID authorized = payments.authorize("demo-merchant", key(),
                new AuthorizationCommand(accountId, 4_000, "CAD", "CA")).body().id();
        payments.capture("demo-merchant", key(), authorized);
        payments.returnFunds("demo-merchant", key(),
                new ReturnCommand(authorized, ReturnType.REFUND, 1_500L, "partial"));
        assertThat(recordedReturnedTotal(authorized)).isEqualTo(1_500);
        assertThat(operationsTotal(authorized)).isEqualTo(1_500);

        UUID voided = payments.authorize("demo-merchant", key(),
                new AuthorizationCommand(accountId, 1_000, "CAD", "CA")).body().id();
        payments.voidPayment("demo-merchant", key(), voided);
        assertThat(statusOf(voided)).isEqualTo("VOIDED");

        UUID reversed = payments.authorize("demo-merchant", key(),
                new AuthorizationCommand(accountId, 2_000, "CAD", "CA")).body().id();
        payments.capture("demo-merchant", key(), reversed);
        payments.returnFunds("demo-merchant", key(),
                new ReturnCommand(reversed, ReturnType.REVERSAL, null, null));
        assertThat(recordedReturnedTotal(reversed)).isEqualTo(2_000);
        assertThat(statusOf(reversed)).as("a reversal does not change the status").isEqualTo("CAPTURED");
    }

    // ----- fixture -----

    private void insertAuthorizedPayment(UUID id, long amount) {
        jdbc.update("""
                INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                        decision, created_at, updated_at)
                VALUES (?, 'demo-merchant', ?, ?, 'CAD', 'CA', 'AUTHORIZED',
                        '{"outcome":"APPROVE","score":0,"ruleSetVersion":"demo-v1","reasons":[],"flags":[]}'::jsonb,
                        now(), now())
                """, id, accountId, amount);
    }

    private void insertCapturedPayment(UUID id, long amount, long captured, long returned) {
        jdbc.update("""
                INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                        decision, created_at, updated_at, captured_amount_minor, returned_amount_minor)
                VALUES (?, 'demo-merchant', ?, ?, 'CAD', 'CA', 'CAPTURED',
                        '{"outcome":"APPROVE","score":0,"ruleSetVersion":"demo-v1","reasons":[],"flags":[]}'::jsonb,
                        now(), now(), ?, ?)
                """, id, accountId, amount, captured, returned);
    }

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

    private String statusOf(UUID payment) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, payment);
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

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
