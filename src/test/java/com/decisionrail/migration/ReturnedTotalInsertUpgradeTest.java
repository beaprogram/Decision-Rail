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
 * Upgrading a V13 database to V14, with and without a row that only V14 would have prevented.
 *
 * <p>V14 declares an invariant about every payment row, so it has to establish that the rows already
 * there satisfy it. A V13 database can hold one that does not: until V14 nothing checked a payment at
 * insert time, so a row written directly — through a restore, a migration tool, a maintenance script,
 * or the reproduction this correction came from — could carry a returned total its operations never
 * justified, and V13's own pre-flight ran before any of that could happen.
 *
 * <p>Each case gets a database of its own on the disposable test server, created and dropped here.
 * Nothing shared is touched, and the fixtures are seeded at V13 rather than by disabling anything.
 */
class ReturnedTotalInsertUpgradeTest {
    private static final String DECISION = """
            {"outcome":"APPROVE","score":0,"ruleSetVersion":"demo-v1","reasons":[],"flags":[]}""";

    @Test
    void aV13DatabaseWhoseRowsAgreeUpgradesAndKeepsItsEvidence() throws Exception {
        try (ThrowawayDatabase database = ThrowawayDatabase.create("decisionrail_v14_valid")) {
            migrateTo(database, "13");
            Seeded seeded = seed(database, 250, true);

            migrateTo(database, null);

            try (Connection connection = database.open()) {
                // Read from the classpath rather than hard-coded, so adding a migration does not
                // break a check that is about the upgrade succeeding, not about a version number.
                assertThat(single(connection, "SELECT version FROM flyway_schema_history WHERE success"
                        + " ORDER BY installed_rank DESC LIMIT 1")).isEqualTo(latestMigrationVersion());
                assertThat(single(connection,
                        "SELECT returned_amount_minor::text FROM payments WHERE id = '" + seeded.payment() + "'"))
                        .as("the upgrade changes no money").isEqualTo("250");
                assertThat(single(connection,
                        "SELECT count(*)::text FROM payment_returns WHERE payment_id = '" + seeded.payment() + "'"))
                        .isEqualTo("1");
                assertThat(single(connection,
                        "SELECT count(*)::text FROM ledger_journals WHERE payment_id = '" + seeded.payment() + "'"))
                        .as("journals are untouched").isEqualTo("1");
                assertThat(single(connection, "SELECT count(*)::text FROM pg_trigger"
                        + " WHERE tgname = 'returned_total_on_payment_insert'"))
                        .as("and the new protection is installed").isEqualTo("1");

                // The upgraded database now refuses what it accepted before.
                assertThat(insertingAnInconsistentPaymentFails(connection, seeded.account()))
                        .as("the gap is closed on an upgraded database, not only on a fresh one").isTrue();
            }
        }
    }

    @Test
    void aV13DatabaseHoldingTheInconsistentInsertIsRefusedAndLeftExactlyAsItWas() throws Exception {
        try (ThrowawayDatabase database = ThrowawayDatabase.create("decisionrail_v14_invalid")) {
            migrateTo(database, "13");
            // The reproduction, committed at V13 where nothing stops it: 100 recorded returned, no
            // return operations, and a valid balanced capture journal beside it.
            Seeded seeded = seed(database, 100, false);

            assertThatThrownBy(() -> migrateTo(database, null))
                    .as("an upgrade must not declare an invariant the existing rows do not satisfy")
                    .hasStackTraceContaining("Cannot enforce returned-total equality on insert")
                    .hasStackTraceContaining("1 payment(s)")
                    .hasStackTraceContaining(seeded.payment().toString())
                    .hasStackTraceContaining("RETURN_TOTAL_MISMATCH")
                    .hasStackTraceContaining("does not adjust totals, delete return operations");

            try (Connection connection = database.open()) {
                assertThat(single(connection, "SELECT version FROM flyway_schema_history WHERE success"
                        + " ORDER BY installed_rank DESC LIMIT 1"))
                        .as("the schema stops at the last migration that applied").isEqualTo("13");
                assertThat(single(connection,
                        "SELECT returned_amount_minor::text FROM payments WHERE id = '" + seeded.payment() + "'"))
                        .as("the offending total is reported, not corrected").isEqualTo("100");
                assertThat(single(connection, "SELECT count(*)::text FROM payment_returns"))
                        .as("no return operation was invented to justify it").isEqualTo("0");
                assertThat(single(connection, "SELECT count(*)::text FROM payments"))
                        .as("and nothing was deleted to make the upgrade pass").isEqualTo("1");
                assertThat(single(connection, "SELECT count(*)::text FROM ledger_entries")).isEqualTo("2");
                assertThat(single(connection, "SELECT count(*)::text FROM pg_trigger"
                        + " WHERE tgname = 'returned_total_on_payment_insert'"))
                        .as("the protection is not half-installed").isEqualTo("0");
            }
        }
    }

    // ----- fixture -----

    /**
     * A captured payment recording {@code returned} minor units, with its capture journal, and with
     * the return operation that justifies it only when {@code withMatchingReturn} is set.
     */
    private Seeded seed(ThrowawayDatabase database, long returned, boolean withMatchingReturn) throws Exception {
        UUID account = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID journal = UUID.randomUUID();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO accounts (id, merchant_id, currency, opening_balance_minor, balance_minor, held_minor)
                        VALUES ('%s', 'demo-merchant', 'CAD', 100000, 99000, 0)
                        """.formatted(account));
                statement.execute("""
                        INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                                decision, created_at, updated_at, captured_amount_minor, returned_amount_minor)
                        VALUES ('%s', 'demo-merchant', '%s', 1000, 'CAD', 'CA', 'CAPTURED', '%s'::jsonb,
                                now(), now(), 1000, %d)
                        """.formatted(payment, account, DECISION, returned));
                statement.execute("""
                        INSERT INTO ledger_journals (id, payment_id, merchant_id, currency, journal_kind)
                        VALUES ('%s', '%s', 'demo-merchant', 'CAD', 'CAPTURE')
                        """.formatted(journal, payment));
                statement.execute("""
                        INSERT INTO ledger_entries (id, journal_id, ledger_account, side, amount_minor) VALUES
                          ('%s', '%s', 'wallet:%s', 'DEBIT', 1000),
                          ('%s', '%s', 'merchant-clearing:demo-merchant', 'CREDIT', 1000)
                        """.formatted(UUID.randomUUID(), journal, account, UUID.randomUUID(), journal));
                if (withMatchingReturn) {
                    statement.execute("""
                            INSERT INTO payment_returns (id, payment_id, merchant_id, account_id, return_type,
                                    amount_minor, currency, sequence_number, created_at)
                            VALUES ('%s', '%s', 'demo-merchant', '%s', 'REFUND', %d, 'CAD', 1, now())
                            """.formatted(UUID.randomUUID(), payment, account, returned));
                }
            }
            connection.commit();
        }
        return new Seeded(account, payment);
    }

    private record Seeded(UUID account, UUID payment) {}

    /** Attempts the reproduced insert and answers whether the database refused it for the right reason. */
    private boolean insertingAnInconsistentPaymentFails(Connection connection, UUID account) throws Exception {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                            decision, created_at, updated_at, captured_amount_minor, returned_amount_minor)
                    VALUES ('%s', 'demo-merchant', '%s', 1000, 'CAD', 'CA', 'CAPTURED', '%s'::jsonb,
                            now(), now(), 1000, 100)
                    """.formatted(UUID.randomUUID(), account, DECISION));
            connection.commit();
            return false;
        } catch (Exception refused) {
            connection.rollback();
            return refused.getMessage() != null && refused.getMessage().contains("return operations total");
        } finally {
            connection.setAutoCommit(true);
        }
    }

    // ----- infrastructure -----

    /** The highest migration version on the classpath, as Flyway records it. */
    static String latestMigrationVersion() throws Exception {
        java.net.URL location = ReturnedTotalInsertUpgradeTest.class.getClassLoader().getResource("db/migration");
        assertThat(location).as("migrations are on the classpath").isNotNull();
        java.io.File[] files = new java.io.File(location.toURI()).listFiles();
        assertThat(files).isNotNull();
        int highest = 0;
        for (java.io.File file : files) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("^V(\\d+)__.*\\.sql$").matcher(file.getName());
            if (matcher.matches()) highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        }
        assertThat(highest).as("at least one migration was found").isGreaterThan(0);
        return String.valueOf(highest);
    }

    private static void migrateTo(ThrowawayDatabase database, String version) {
        Flyway.configure()
                .dataSource(database.url(), database.username(), database.password())
                .locations("classpath:db/migration")
                .target(version == null ? "latest" : version)
                .load()
                .migrate();
    }

    private static String single(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as("a row for: %s", sql).isTrue();
            return rows.getString(1);
        }
    }
}
