package com.decisionrail.migration;

import com.decisionrail.support.ThrowawayDatabase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upgrading a V14 database to V15, with and without the row the identity bypass could have left.
 *
 * <p>V15 cannot detect that an identity was changed in the past — the old value is gone and nothing
 * recorded it. What it can detect is the inconsistency such a change was used to introduce, so its
 * pre-flight is the returned-total equality check run against the rows as they stand. A V14 database
 * that holds one is refused; one that does not is upgraded without its evidence being touched.
 *
 * <p>Each case seeds at V14 through the bypass itself, which is the honest way to produce the fixture:
 * it is the transaction the review reported, run against the schema that still allowed it, rather than
 * a constraint disabled to make the row appear.
 */
class PaymentIdentityUpgradeTest {
    private static final String DECISION = """
            {"outcome":"APPROVE","score":0,"ruleSetVersion":"demo-v1","reasons":[],"flags":[]}""";

    @Test
    void aV14DatabaseWhoseRowsAgreeUpgradesWithoutChangingItsEvidence() throws Exception {
        try (ThrowawayDatabase database = ThrowawayDatabase.create("decisionrail_v15_valid")) {
            migrateTo(database, "14");
            Seeded seeded = seedConsistentPayment(database);

            migrateTo(database, null);

            try (Connection connection = database.open()) {
                assertThat(lastApplied(connection))
                        .isEqualTo(ReturnedTotalInsertUpgradeTest.latestMigrationVersion());
                assertThat(single(connection, "SELECT returned_amount_minor::text FROM payments WHERE id = '"
                        + seeded.payment() + "'")).as("no money changed").isEqualTo("250");
                assertThat(single(connection, "SELECT count(*)::text FROM payment_returns")).isEqualTo("1");
                assertThat(single(connection, "SELECT count(*)::text FROM ledger_entries")).isEqualTo("2");
                assertThat(single(connection, "SELECT count(*)::text FROM pg_trigger"
                        + " WHERE tgname = 'immutable_payment_identity'")).isEqualTo("1");

                // The payment kept its name, and now cannot lose it.
                assertThat(single(connection, "SELECT count(*)::text FROM payments WHERE id = '"
                        + seeded.payment() + "'")).as("nothing was renamed by the upgrade").isEqualTo("1");
                assertThat(renamingIsRefused(connection, seeded.payment()))
                        .as("the bypass is closed on an upgraded database, not only on a fresh one").isTrue();
            }
        }
    }

    @Test
    void aV14DatabaseHoldingTheBypassedRowIsRefusedWithItsEvidenceIntact() throws Exception {
        try (ThrowawayDatabase database = ThrowawayDatabase.create("decisionrail_v15_invalid")) {
            migrateTo(database, "14");
            // Seeded by running the bypass against the schema that still permits it.
            Bypassed bypassed = seedThroughTheIdentityBypass(database);
            assertThat(bypassed.committed()).as("the bypass really does commit at V14").isTrue();

            assertThatThrownBy(() -> migrateTo(database, null))
                    .as("an upgrade must not declare an invariant the existing rows contradict")
                    .hasStackTraceContaining("Cannot make payment identity immutable")
                    .hasStackTraceContaining("1 payment(s)")
                    .hasStackTraceContaining(bypassed.renamed().toString())
                    .hasStackTraceContaining("RETURN_TOTAL_MISMATCH")
                    .hasStackTraceContaining("does not adjust totals, invent return operations, delete records or rename payments");

            try (Connection connection = database.open()) {
                assertThat(lastApplied(connection)).as("the schema stops where it was").isEqualTo("14");
                assertThat(single(connection, "SELECT id::text FROM payments"))
                        .as("the payment keeps the name it was given, wrong as that history is")
                        .isEqualTo(bypassed.renamed().toString());
                assertThat(single(connection, "SELECT returned_amount_minor::text FROM payments"))
                        .as("the total is reported, not corrected").isEqualTo("100");
                assertThat(single(connection, "SELECT count(*)::text FROM payment_returns"))
                        .as("no return operation was invented to justify it").isEqualTo("0");
                assertThat(single(connection, "SELECT count(*)::text FROM ledger_entries"))
                        .as("the journal evidence is untouched").isEqualTo("2");
                assertThat(single(connection, "SELECT count(*)::text FROM pg_trigger"
                        + " WHERE tgname = 'immutable_payment_identity'"))
                        .as("no half-installed protection").isEqualTo("0");
                assertThat(single(connection, "SELECT count(*)::text FROM pg_proc"
                        + " WHERE proname = 'reject_payment_identity_change'")).isEqualTo("0");
            }
        }
    }

    // ----- fixture -----

    private Seeded seedConsistentPayment(ThrowawayDatabase database) throws Exception {
        UUID account = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(account(account));
                statement.execute(capturedPayment(payment, account, 250));
                statement.execute(captureJournal(payment, account));
                statement.execute("""
                        INSERT INTO payment_returns (id, payment_id, merchant_id, account_id, return_type,
                                amount_minor, currency, sequence_number, created_at)
                        VALUES ('%s', '%s', 'demo-merchant', '%s', 'REFUND', 250, 'CAD', 1, now())
                        """.formatted(UUID.randomUUID(), payment, account));
            }
            connection.commit();
        }
        return new Seeded(account, payment);
    }

    /** The reported transaction, run against V14 where it still commits. */
    private Bypassed seedThroughTheIdentityBypass(ThrowawayDatabase database) throws Exception {
        UUID account = UUID.randomUUID();
        UUID inserted = UUID.randomUUID();
        UUID renamed = UUID.randomUUID();
        boolean committed;
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(account(account));
                statement.execute(capturedPayment(inserted, account, 100));
                statement.execute("UPDATE payments SET id = '%s' WHERE id = '%s'".formatted(renamed, inserted));
                statement.execute(captureJournal(renamed, account));
            }
            connection.commit();
            committed = true;
        }
        return new Bypassed(renamed, committed);
    }

    private record Seeded(UUID account, UUID payment) {}

    private record Bypassed(UUID renamed, boolean committed) {}

    private static String account(UUID id) {
        return """
                INSERT INTO accounts (id, merchant_id, currency, opening_balance_minor, balance_minor, held_minor)
                VALUES ('%s', 'demo-merchant', 'CAD', 100000, 99000, 0)
                """.formatted(id);
    }

    private static String capturedPayment(UUID id, UUID account, long returned) {
        return """
                INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                        decision, created_at, updated_at, captured_amount_minor, returned_amount_minor)
                VALUES ('%s', 'demo-merchant', '%s', 1000, 'CAD', 'CA', 'CAPTURED', '%s'::jsonb,
                        now(), now(), 1000, %d)
                """.formatted(id, account, DECISION, returned);
    }

    private static String captureJournal(UUID payment, UUID account) {
        UUID journal = UUID.randomUUID();
        return """
                INSERT INTO ledger_journals (id, payment_id, merchant_id, currency, journal_kind)
                VALUES ('%s', '%s', 'demo-merchant', 'CAD', 'CAPTURE');
                INSERT INTO ledger_entries (id, journal_id, ledger_account, side, amount_minor) VALUES
                  ('%s', '%s', 'wallet:%s', 'DEBIT', 1000),
                  ('%s', '%s', 'merchant-clearing:demo-merchant', 'CREDIT', 1000);
                """.formatted(journal, payment, UUID.randomUUID(), journal, account, UUID.randomUUID(), journal);
    }

    private boolean renamingIsRefused(Connection connection, UUID payment) throws Exception {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("UPDATE payments SET id = '%s' WHERE id = '%s'".formatted(UUID.randomUUID(), payment));
            connection.commit();
            return false;
        } catch (Exception refused) {
            connection.rollback();
            return refused.getMessage() != null && refused.getMessage().contains("identity is immutable");
        } finally {
            connection.setAutoCommit(true);
        }
    }

    // ----- infrastructure -----

    private static void migrateTo(ThrowawayDatabase database, String version) {
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target(version == null ? "latest" : version)
                .load()
                .migrate();
    }

    private static String lastApplied(Connection connection) throws Exception {
        return single(connection,
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1");
    }

    private static String single(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("a row for: %s", sql).isTrue();
            return rows.getString(1);
        }
    }
}
