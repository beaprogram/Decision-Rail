package com.decisionrail.reconciliation;

import com.decisionrail.support.ThrowawayDatabase;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The reconciliation detector, and the upgrade path, against evidence the production schema no longer
 * permits.
 *
 * <h2>Which boundary this class exercises, and which it does not</h2>
 * Three separate things are being claimed about currency agreement and returned-total equality, and
 * each is established somewhere different on purpose:
 *
 * <ol>
 *   <li><b>Production rejects the invalid write.</b> V12 and V13 make these rows impossible to
 *       create. That is asserted in {@link ReconciliationIntegrationTest} against the real schema, by
 *       attempting the writes and watching the database refuse them.</li>
 *   <li><b>Valid data reconciles.</b> Also {@link ReconciliationIntegrationTest}, over payments,
 *       captures, refunds and reversals made through the service.</li>
 *   <li><b>The detector still identifies damaged evidence.</b> This class. A database restored from a
 *       partial backup, or simply not yet upgraded, can hold rows nothing would write today, and a
 *       report that could not see them would be reassuring about exactly the state it exists to
 *       find.</li>
 * </ol>
 *
 * <p>The fixture for (3) is a <b>private database of its own</b>, migrated only as far as V11 - the
 * last revision before the constraints - and seeded there. No production migration is edited, no
 * constraint is dropped, and nothing shared is weakened: the absence of those constraints is confined
 * to a throwaway database that exists for the length of one test. None of the rows below is reachable
 * through any service path, before or after this change; what they demonstrate is the detector's
 * behaviour on legacy evidence, and nothing about what the current API can produce.
 *
 * <p>The service is wired by hand here rather than injected, so {@code @Transactional} does not apply
 * and the report runs without its REPEATABLE READ snapshot. That is deliberate - nothing writes to this
 * database concurrently - and the snapshot itself is covered against the real container elsewhere.
 */
class ReconciliationLegacyEvidenceTest {
    private static final String DECISION = """
            {"outcome":"APPROVE","score":0,"ruleSetVersion":"demo-v1",
             "reasons":[{"code":"NO_RISK_SIGNALS","description":"No synthetic demo risk rules matched.","scoreContribution":0}],
             "flags":[]}""";

    @Test
    void theDetectorStillFindsCurrencyAndTotalDisagreementsInASnapshotThatPredatesTheConstraints() throws Exception {
        try (ThrowawayDatabase database = ThrowawayDatabase.create("decisionrail_legacy")) {
            migrateTo(database, "11");
            Legacy seeded = seedEvidenceTheCurrentSchemaWouldRefuse(database, true);

            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            ReconciliationReport report = new ReconciliationService(new ReconciliationStore(jdbc), Clock.systemUTC())
                    .forMerchant("demo-merchant", new ReconciliationRequest(null, 25, 500));

            assertThat(report.status())
                    .as("legacy evidence that disagrees with itself is never a clean result")
                    .isEqualTo(ReconciliationReport.Status.DISCREPANCIES_FOUND);
            // Exactly the seeded damage and nothing else: the sound account, its captured payment, its
            // journals and its declined payment are left alone. A detector that flagged everything
            // would be no more useful than one that flagged nothing.
            assertThat(report.findings()).extracting(ReconciliationFinding::type)
                    .containsExactlyInAnyOrder("ACCOUNT_CURRENCY_MISMATCH", "ACCOUNT_TOTALS_NOT_DERIVABLE",
                            "PAYMENT_CURRENCY_MISMATCH", "RETURN_TOTAL_MISMATCH");

            ReconciliationFinding accountCurrency = finding(report, "ACCOUNT_CURRENCY_MISMATCH");
            assertThat(accountCurrency.severity()).isEqualTo(ReconciliationFinding.Severity.CRITICAL);
            assertThat(accountCurrency.resourceId()).isEqualTo(seeded.usdAccount());
            assertThat(accountCurrency.currency()).isEqualTo("USD");
            assertThat(accountCurrency.detail()).contains("CAD").contains("USD");
            assertThat(accountCurrency.references())
                    .contains(new ReconciliationFinding.Reference("ACCOUNT", seeded.usdAccount()));

            ReconciliationFinding paymentCurrency = finding(report, "PAYMENT_CURRENCY_MISMATCH");
            assertThat(paymentCurrency.resourceId()).isEqualTo(seeded.foreignPayment());

            // The arithmetic is refused rather than performed across currencies: no expected total is
            // stated for that account, and no balance figure is presented as reconciled.
            ReconciliationFinding refused = finding(report, "ACCOUNT_TOTALS_NOT_DERIVABLE");
            assertThat(refused.resourceId()).isEqualTo(seeded.usdAccount());
            assertThat(refused.expectedMinor()).isNull();
            assertThat(refused.actualMinor()).isNull();

            ReconciliationFinding drift = finding(report, "RETURN_TOTAL_MISMATCH");
            assertThat(drift.resourceId()).isEqualTo(seeded.driftedPayment());
            assertThat(drift.expectedMinor()).as("what the return operations sum to").isEqualTo(1_000L);
            assertThat(drift.actualMinor()).as("what the payment records").isEqualTo(900L);
            assertThat(drift.deltaMinor()).isEqualTo(-100L);
            assertThat(drift.references()).extracting(ReconciliationFinding.Reference::id)
                    .contains(seeded.driftedPayment());

            // Reading the report changes nothing. It reports; it does not convert, filter away or
            // repair the amounts it disagrees with.
            assertThat(unchangedBy(jdbc, () -> new ReconciliationService(new ReconciliationStore(jdbc), Clock.systemUTC())
                    .forMerchant("demo-merchant", new ReconciliationRequest(null, 25, 500))))
                    .as("reconciliation is read-only over legacy evidence too")
                    .isTrue();
        }
    }

    @Test
    void upgradingASnapshotWhosePaymentsDisagreeWithTheirAccountRefusesAndExplains() throws Exception {
        try (ThrowawayDatabase database = ThrowawayDatabase.create("decisionrail_legacy_currency")) {
            migrateTo(database, "11");
            Legacy seeded = seedEvidenceTheCurrentSchemaWouldRefuse(database, true);

            assertThatThrownBy(() -> migrateTo(database, null))
                    .as("an upgrade must not silently repair financial history")
                    .hasStackTraceContaining("Cannot enforce payment/account currency agreement")
                    .hasStackTraceContaining("1 payment(s)")
                    .hasStackTraceContaining(seeded.foreignPayment().toString())
                    .hasStackTraceContaining("PAYMENT_CURRENCY_MISMATCH")
                    .hasStackTraceContaining("deliberately does not alter, delete or repair");

            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            assertThat(lastAppliedVersion(jdbc)).as("the upgrade stopped at the migration that refused").isEqualTo("11");
            assertThat(jdbc.queryForObject("SELECT currency FROM payments WHERE id = ?", String.class,
                    seeded.foreignPayment()).trim())
                    .as("the offending payment is reported, not redenominated").isEqualTo("CAD");
            assertThat(jdbc.queryForObject("SELECT currency FROM accounts WHERE id = ?", String.class,
                    seeded.usdAccount()).trim()).isEqualTo("USD");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Long.class))
                    .as("nothing was deleted to make the upgrade pass").isEqualTo(3L);
        }
    }

    @Test
    void upgradingASnapshotWhoseReturnedTotalDriftedRefusesAndExplains() throws Exception {
        try (ThrowawayDatabase database = ThrowawayDatabase.create("decisionrail_legacy_total")) {
            migrateTo(database, "11");
            // Seeded without the currency disagreement, so this upgrade reaches V13 rather than
            // stopping at V12. The two pre-flights are isolated by seeding different snapshots, not by
            // deleting rows from one of them to get past a check.
            Legacy seeded = seedEvidenceTheCurrentSchemaWouldRefuse(database, false);

            assertThatThrownBy(() -> migrateTo(database, null))
                    .as("a drifted total is reported, not adjusted")
                    .hasStackTraceContaining("Cannot enforce returned-total equality")
                    .hasStackTraceContaining("1 payment(s)")
                    .hasStackTraceContaining(seeded.driftedPayment().toString())
                    .hasStackTraceContaining("RETURN_TOTAL_MISMATCH")
                    .hasStackTraceContaining("does not adjust totals, delete return operations");

            JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
            assertThat(lastAppliedVersion(jdbc))
                    .as("currency agreement applied; the returned-total migration refused").isEqualTo("12");
            assertThat(jdbc.queryForObject("SELECT returned_amount_minor FROM payments WHERE id = ?",
                    Long.class, seeded.driftedPayment()))
                    .as("the recorded total is left exactly as found").isEqualTo(900L);
            assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount_minor), 0) FROM payment_returns WHERE payment_id = ?",
                    Long.class, seeded.driftedPayment()))
                    .as("and so are the return operations").isEqualTo(1_000L);
        }
    }

    // ----- fixture -----

    /**
     * Rows the current schema refuses: a payment denominated differently from its funding account, and
     * a payment whose recorded returned total no longer equals its return operations.
     *
     * <p>The second is seeded the way it could actually have happened before V13 - the return and the
     * total were written together and agreed, and a later update moved the total on its own. Its
     * journals are correct throughout, which is what made the drift invisible to everything except a
     * check that compares the column against the rows it summarises.
     */
    private Legacy seedEvidenceTheCurrentSchemaWouldRefuse(ThrowawayDatabase database,
                                                           boolean withForeignCurrencyPayment) throws Exception {
        UUID usdAccount = UUID.randomUUID();
        UUID cadAccount = UUID.randomUUID();
        UUID foreignPayment = UUID.randomUUID();
        UUID driftedPayment = UUID.randomUUID();
        UUID declinedPayment = UUID.randomUUID();
        UUID returnId = UUID.randomUUID();
        UUID captureJournal = UUID.randomUUID();
        UUID returnJournal = UUID.randomUUID();

        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // Opening 100000, one capture of 5000 out, one return of 1000 back: the balance the
                // journals derive, so this account reconciles apart from the drift seeded below.
                statement.execute("""
                        INSERT INTO accounts (id, merchant_id, currency, opening_balance_minor, balance_minor, held_minor) VALUES
                          ('%s', 'demo-merchant', 'USD', 100000, 98000, 2000),
                          ('%s', 'demo-merchant', 'CAD', 100000, 96000, 0)
                        """.formatted(usdAccount, cadAccount));

                if (withForeignCurrencyPayment) {
                    // A CAD authorization against a USD account. The account's held funds even agree
                    // with it, which is what let the arithmetic look clean while the currencies did not.
                    statement.execute("""
                            INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                                    decision, created_at, updated_at)
                            VALUES ('%s', 'demo-merchant', '%s', 2000, 'CAD', 'CA', 'AUTHORIZED', '%s'::jsonb, now(), now())
                            """.formatted(foreignPayment, usdAccount, DECISION));
                }

                statement.execute("""
                        INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                                decision, created_at, updated_at, captured_amount_minor, returned_amount_minor)
                        VALUES ('%s', 'demo-merchant', '%s', 5000, 'CAD', 'CA', 'CAPTURED', '%s'::jsonb, now(), now(), 5000, 1000)
                        """.formatted(driftedPayment, cadAccount, DECISION));
                statement.execute("""
                        INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                                decision, created_at, updated_at)
                        VALUES ('%s', 'demo-merchant', '%s', 1000, 'CAD', 'CA', 'DECLINED', '%s'::jsonb, now(), now())
                        """.formatted(declinedPayment, cadAccount, DECISION));

                statement.execute("""
                        INSERT INTO ledger_journals (id, payment_id, merchant_id, currency, journal_kind)
                        VALUES ('%s', '%s', 'demo-merchant', 'CAD', 'CAPTURE')
                        """.formatted(captureJournal, driftedPayment));
                statement.execute("""
                        INSERT INTO ledger_entries (id, journal_id, ledger_account, side, amount_minor) VALUES
                          ('%s', '%s', 'wallet:%s', 'DEBIT', 5000),
                          ('%s', '%s', 'merchant-clearing:demo-merchant', 'CREDIT', 5000)
                        """.formatted(UUID.randomUUID(), captureJournal, cadAccount, UUID.randomUUID(), captureJournal));

                statement.execute("""
                        INSERT INTO payment_returns (id, payment_id, merchant_id, account_id, return_type,
                                amount_minor, currency, reason, sequence_number, created_at)
                        VALUES ('%s', '%s', 'demo-merchant', '%s', 'REFUND', 1000, 'CAD', 'customer request', 1, now())
                        """.formatted(returnId, driftedPayment, cadAccount));
                statement.execute("""
                        INSERT INTO ledger_journals (id, payment_id, merchant_id, currency, journal_kind, source_return_id)
                        VALUES ('%s', '%s', 'demo-merchant', 'CAD', 'RETURN', '%s')
                        """.formatted(returnJournal, driftedPayment, returnId));
                statement.execute("""
                        INSERT INTO ledger_entries (id, journal_id, ledger_account, side, amount_minor) VALUES
                          ('%s', '%s', 'merchant-clearing:demo-merchant', 'DEBIT', 1000),
                          ('%s', '%s', 'wallet:%s', 'CREDIT', 1000)
                        """.formatted(UUID.randomUUID(), returnJournal, UUID.randomUUID(), returnJournal, cadAccount));
            }
            // Everything above agrees, and commits under V10's return-budget trigger precisely because
            // it does.
            connection.commit();

            // The drift, as a later update touching only the payment. This is what V11 allowed and V13
            // now refuses; it is why the equality could be true at every insert and false afterwards.
            try (Statement statement = connection.createStatement()) {
                statement.execute("UPDATE payments SET returned_amount_minor = 900 WHERE id = '" + driftedPayment + "'");
            }
            connection.commit();
        }
        return new Legacy(usdAccount, withForeignCurrencyPayment ? foreignPayment : null, driftedPayment);
    }

    private record Legacy(UUID usdAccount, UUID foreignPayment, UUID driftedPayment) {}

    // ----- infrastructure -----

    private static void migrateTo(ThrowawayDatabase database, String version) {
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target(version == null ? "latest" : version)
                .load()
                .migrate();
    }

    private static String lastAppliedVersion(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }

    private static ReconciliationFinding finding(ReconciliationReport report, String type) {
        List<ReconciliationFinding> matching = report.findings().stream()
                .filter(candidate -> candidate.type().equals(type)).toList();
        assertThat(matching).as("exactly one %s finding", type).hasSize(1);
        return matching.getFirst();
    }

    /** Runs the action and answers whether the money and the evidence for it are byte-for-byte as before. */
    private static boolean unchangedBy(JdbcTemplate jdbc, Runnable action) {
        String before = financialState(jdbc);
        action.run();
        return financialState(jdbc).equals(before);
    }

    private static String financialState(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT (SELECT string_agg(a.id::text || a.currency || a.balance_minor || a.held_minor, ',' ORDER BY a.id)
                          FROM accounts a) AS accounts,
                       (SELECT string_agg(p.id::text || p.currency || p.status || p.returned_amount_minor, ',' ORDER BY p.id)
                          FROM payments p) AS payments,
                       (SELECT string_agg(r.id::text || r.amount_minor, ',' ORDER BY r.id) FROM payment_returns r) AS returns,
                       (SELECT string_agg(e.id::text || e.side || e.amount_minor, ',' ORDER BY e.id) FROM ledger_entries e) AS entries
                """).toString();
    }
}
