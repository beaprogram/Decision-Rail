package com.decisionrail.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upgrades a database that already contains payment, idempotency, ledger, and outbox records.
 *
 * <p>Applying V3 to V5 on an empty schema proves almost nothing: the interesting work is the
 * backfill. V1 wrote outbox rows with no sequence, no delivery state, and an envelope that lacked
 * routing identity, so the upgrade has to give those rows an order, a status, and a payload a
 * consumer will accept, without disturbing the financial records around them.
 *
 * <p>A throwaway database is created for this check rather than reusing the suite's schema, because
 * migrating to an intermediate version and back is not something to do to a database other tests
 * are using. It is dropped again afterwards.
 */
class MigrationUpgradeTest {
    private static final String ADMIN_DATABASE = "postgres";

    @Test
    void upgradingADatabaseWithExistingRecordsBackfillsDeliveryStateWithoutDisturbingMoney() throws Exception {
        Target target = Target.fromEnvironment();
        String database = "decisionrail_upgrade_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        createDatabase(target, database);
        try {
            String url = target.urlFor(database);

            // Stop at V2: exactly the schema the previous delivery left behind.
            Flyway atV2 = Flyway.configure()
                    .dataSource(url, target.username(), target.password())
                    .locations("classpath:db/migration")
                    .target("2")
                    .load();
            assertThat(atV2.migrate().migrationsExecuted).isEqualTo(2);

            Seeded seeded = seedRecordsAsV1WouldHaveWritten(url, target);

            // Now apply everything added after V2.
            Flyway toLatest = Flyway.configure()
                    .dataSource(url, target.username(), target.password())
                    .locations("classpath:db/migration")
                    .load();
            // Counted from the migrations on the classpath rather than hard-coded, so adding one does
            // not break this check. What the check is for is that every one of them applies cleanly to
            // a database that already holds real records, which the assertions below then inspect.
            int expectedUpgrades = migrationsAfterVersionTwo();
            assertThat(expectedUpgrades).as("there are upgrade migrations to apply").isGreaterThanOrEqualTo(4);
            assertThat(toLatest.migrate().migrationsExecuted).isEqualTo(expectedUpgrades);

            try (Connection connection = DriverManager.getConnection(url, target.username(), target.password())) {
                assertFinancialRecordsSurvived(connection, seeded);
                assertOutboxBackfill(connection, seeded);
                assertNewTablesExist(connection);
                assertSealedJournalStillRejectsLateEntries(connection, seeded);
            }
        } finally {
            dropDatabase(target, database);
        }
    }

    /** Counts V3 and later on the classpath, so this check follows the migrations rather than a constant. */
    private int migrationsAfterVersionTwo() throws Exception {
        java.net.URL location = getClass().getClassLoader().getResource("db/migration");
        assertThat(location).as("migrations are on the classpath").isNotNull();
        java.io.File[] files = new java.io.File(location.toURI()).listFiles();
        assertThat(files).isNotNull();
        int count = 0;
        for (java.io.File file : files) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^V(\\d+)__.*\\.sql$")
                    .matcher(file.getName());
            if (matcher.matches() && Integer.parseInt(matcher.group(1)) > 2) count++;
        }
        return count;
    }

    private record Seeded(UUID accountId, UUID capturedPayment, UUID authorizedPayment,
                          List<UUID> capturedEvents, UUID authorizedEvent) {}

    /**
     * Writes rows in the shape V1 and V2 produced: outbox rows with no sequence, no status, and an
     * envelope that carries only the original five fields.
     */
    private Seeded seedRecordsAsV1WouldHaveWritten(String url, Target target) throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID capturedPayment = UUID.randomUUID();
        UUID authorizedPayment = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        List<UUID> capturedEvents = List.of(UUID.randomUUID(), UUID.randomUUID());
        UUID authorizedEvent = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(url, target.username(), target.password())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        INSERT INTO accounts (id, merchant_id, currency, opening_balance_minor, balance_minor, held_minor)
                        VALUES ('%s', 'demo-merchant', 'CAD', 1000000, 997500, 0)
                        """.formatted(accountId));
                statement.execute("""
                        INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                                decision, failure_code, created_at, updated_at)
                        VALUES ('%s', 'demo-merchant', '%s', 2500, 'CAD', 'CA', 'CAPTURED',
                                '{"outcome":"APPROVE","score":0,"ruleSetVersion":"demo-v1",
                                  "reasons":[{"code":"NO_RISK_SIGNALS","description":"No synthetic demo risk rules matched.","scoreContribution":0}],
                                  "flags":[]}'::jsonb,
                                NULL, '2026-09-08 23:00:00+00', '2026-09-08 23:00:05+00')
                        """.formatted(capturedPayment, accountId));
                statement.execute("""
                        INSERT INTO payments (id, merchant_id, account_id, amount_minor, currency, country, status,
                                decision, failure_code, created_at, updated_at)
                        VALUES ('%s', 'demo-merchant', '%s', 4000, 'CAD', 'US', 'AUTHORIZED',
                                '{"outcome":"APPROVE","score":20,"ruleSetVersion":"demo-v1",
                                  "reasons":[{"code":"CROSS_BORDER","description":"Country differs from the demo home country CA; no currency conversion is applied.","scoreContribution":20}],
                                  "flags":["CROSS_BORDER"]}'::jsonb,
                                NULL, '2026-09-08 23:01:00+00', '2026-09-08 23:01:00+00')
                        """.formatted(authorizedPayment, accountId));
                statement.execute("""
                        INSERT INTO idempotency_records (merchant_id, idempotency_key, request_hash, response_body, http_status)
                        VALUES ('demo-merchant', 'legacy-key-00000001',
                                repeat('a', 64), '{"id":"%s"}'::jsonb, 201)
                        """.formatted(capturedPayment));
                // A balanced capture journal, created in the same transaction as its entries.
                statement.execute("""
                        INSERT INTO ledger_journals (id, payment_id, merchant_id, currency) VALUES ('%s', '%s', 'demo-merchant', 'CAD')
                        """.formatted(journalId, capturedPayment));
                statement.execute("""
                        INSERT INTO ledger_entries (id, journal_id, ledger_account, side, amount_minor) VALUES
                          ('%s', '%s', 'wallet:%s', 'DEBIT', 2500),
                          ('%s', '%s', 'merchant-clearing:demo-merchant', 'CREDIT', 2500)
                        """.formatted(UUID.randomUUID(), journalId, accountId, UUID.randomUUID(), journalId));
                // Two lifecycle events for one payment, in the old envelope shape. The second is
                // already marked delivered, which V1 allowed even though nothing delivered it.
                statement.execute(legacyEvent(capturedEvents.get(0), capturedPayment, "payment.authorized.v1",
                        "2026-09-08 23:00:00+00", null));
                statement.execute(legacyEvent(capturedEvents.get(1), capturedPayment, "payment.captured.v1",
                        "2026-09-08 23:00:05+00", "2026-09-08 23:00:06+00"));
                statement.execute(legacyEvent(authorizedEvent, authorizedPayment, "payment.authorized.v1",
                        "2026-09-08 23:01:00+00", null));
                statement.execute("""
                        INSERT INTO audit_events (id, merchant_id, payment_id, action)
                        VALUES ('%s', 'demo-merchant', '%s', 'payment.captured.v1')
                        """.formatted(UUID.randomUUID(), capturedPayment));
            }
            connection.commit();
        }
        return new Seeded(accountId, capturedPayment, authorizedPayment, capturedEvents, authorizedEvent);
    }

    private static String legacyEvent(UUID eventId, UUID paymentId, String type, String occurredAt, String publishedAt) {
        return """
                INSERT INTO outbox_events (id, aggregate_id, merchant_id, event_type, schema_version, payload,
                        occurred_at, published_at)
                VALUES ('%s', '%s', 'demo-merchant', '%s', 1,
                        '{"eventId":"%s","eventType":"%s","schemaVersion":1,"occurredAt":"2026-09-08T23:00:00Z",
                          "payment":{"id":"%s","amountMinor":2500}}'::jsonb,
                        '%s', %s)
                """.formatted(eventId, paymentId, type, eventId, type, paymentId, occurredAt,
                publishedAt == null ? "NULL" : "'" + publishedAt + "'");
    }

    private void assertFinancialRecordsSurvived(Connection connection, Seeded seeded) throws Exception {
        assertThat(single(connection, "SELECT balance_minor FROM accounts WHERE id = '" + seeded.accountId() + "'"))
                .isEqualTo("997500");
        assertThat(single(connection, "SELECT count(*) FROM payments")).isEqualTo("2");
        assertThat(single(connection, "SELECT status FROM payments WHERE id = '" + seeded.capturedPayment() + "'"))
                .isEqualTo("CAPTURED");
        // The stored decision is untouched by the upgrade.
        assertThat(single(connection, "SELECT decision ->> 'ruleSetVersion' FROM payments WHERE id = '"
                + seeded.capturedPayment() + "'")).isEqualTo("demo-v1");
        assertThat(single(connection, "SELECT decision ->> 'outcome' FROM payments WHERE id = '"
                + seeded.authorizedPayment() + "'")).isEqualTo("APPROVE");
        assertThat(single(connection, "SELECT http_status FROM idempotency_records WHERE idempotency_key = 'legacy-key-00000001'"))
                .isEqualTo("201");
        assertThat(single(connection, "SELECT count(*) FROM ledger_entries")).isEqualTo("2");
        assertThat(single(connection, "SELECT sum(amount_minor) FROM ledger_entries WHERE side = 'DEBIT'"))
                .isEqualTo("2500");
        assertThat(single(connection, "SELECT count(*) FROM audit_events")).isEqualTo("1");
    }

    private void assertOutboxBackfill(Connection connection, Seeded seeded) throws Exception {
        // Lifecycle order reconstructed deterministically from (occurred_at, id).
        assertThat(single(connection, "SELECT aggregate_sequence FROM outbox_events WHERE id = '"
                + seeded.capturedEvents().get(0) + "'")).isEqualTo("1");
        assertThat(single(connection, "SELECT aggregate_sequence FROM outbox_events WHERE id = '"
                + seeded.capturedEvents().get(1) + "'")).isEqualTo("2");
        // Sequences are per payment, so a different payment restarts at 1.
        assertThat(single(connection, "SELECT aggregate_sequence FROM outbox_events WHERE id = '"
                + seeded.authorizedEvent() + "'")).isEqualTo("1");

        // Delivery status agrees with whether the row had a published timestamp.
        assertThat(single(connection, "SELECT status FROM outbox_events WHERE id = '"
                + seeded.capturedEvents().get(0) + "'")).isEqualTo("PENDING");
        assertThat(single(connection, "SELECT status FROM outbox_events WHERE id = '"
                + seeded.capturedEvents().get(1) + "'")).isEqualTo("PUBLISHED");
        assertThat(single(connection, "SELECT count(*) FROM outbox_events WHERE attempts <> 0")).isEqualTo("0");
        assertThat(single(connection, "SELECT count(*) FROM outbox_events WHERE partition_key IS NULL")).isEqualTo("0");
        assertThat(single(connection, "SELECT partition_key FROM outbox_events WHERE id = '"
                + seeded.authorizedEvent() + "'")).isEqualTo(seeded.authorizedPayment().toString());

        // Payloads gained the routing identity the envelope contract requires, so committed history
        // stays deliverable instead of being quarantined as malformed.
        assertThat(single(connection, "SELECT payload ->> 'aggregateId' FROM outbox_events WHERE id = '"
                + seeded.authorizedEvent() + "'")).isEqualTo(seeded.authorizedPayment().toString());
        assertThat(single(connection, "SELECT payload ->> 'merchantId' FROM outbox_events WHERE id = '"
                + seeded.authorizedEvent() + "'")).isEqualTo("demo-merchant");
        assertThat(single(connection, "SELECT payload ->> 'aggregateType' FROM outbox_events WHERE id = '"
                + seeded.authorizedEvent() + "'")).isEqualTo("payment");
        assertThat(single(connection, "SELECT payload ->> 'aggregateSequence' FROM outbox_events WHERE id = '"
                + seeded.capturedEvents().get(1) + "'")).isEqualTo("2");
        // The backfilled timestamp parses as the ISO-8601 instant shape the application emits.
        String recordedAt = single(connection, "SELECT payload ->> 'recordedAt' FROM outbox_events WHERE id = '"
                + seeded.authorizedEvent() + "'");
        assertThat(java.time.Instant.parse(recordedAt)).isEqualTo(java.time.Instant.parse("2026-09-08T23:01:00Z"));
        // Event identity is preserved: the backfill rewrote no ids.
        assertThat(single(connection, "SELECT payload ->> 'eventId' FROM outbox_events WHERE id = '"
                + seeded.authorizedEvent() + "'")).isEqualTo(seeded.authorizedEvent().toString());

        // The claim predicate can now pick the right row: the earliest unpublished sequence.
        assertThat(single(connection, """
                SELECT id FROM outbox_events candidate
                WHERE candidate.aggregate_id = '%s' AND candidate.status = 'PENDING'
                  AND candidate.aggregate_sequence = (
                        SELECT min(earlier.aggregate_sequence) FROM outbox_events earlier
                        WHERE earlier.aggregate_id = candidate.aggregate_id AND earlier.status <> 'PUBLISHED')
                """.formatted(seeded.capturedPayment()))).isEqualTo(seeded.capturedEvents().get(0).toString());
    }

    private void assertNewTablesExist(Connection connection) throws Exception {
        for (String table : List.of("consumed_events", "payment_activity", "consumer_quarantine", "policy_versions",
                "replay_jobs", "replay_job_requests", "replay_job_items", "replay_results",
                "shadow_settings", "shadow_tasks", "shadow_comparisons")) {
            assertThat(single(connection, "SELECT count(*) FROM information_schema.tables WHERE table_name = '" + table + "'"))
                    .as("table %s exists after upgrade", table).isEqualTo("1");
        }
        // The single shadow settings row starts disabled: an upgrade never switches anything on.
        // PostgreSQL renders booleans as f and t through getString.
        assertThat(single(connection, "SELECT enabled FROM shadow_settings WHERE id = 1")).isEqualTo("f");
        assertThat(single(connection, "SELECT candidate_version IS NULL FROM shadow_settings WHERE id = 1")).isEqualTo("t");
    }

    private void assertSealedJournalStillRejectsLateEntries(Connection connection, Seeded seeded) throws Exception {
        // The upgrade must not have weakened the V2 guarantees around committed journals.
        String journalId = single(connection, "SELECT id FROM ledger_journals WHERE payment_id = '"
                + seeded.capturedPayment() + "'");
        boolean rejected = false;
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO ledger_entries (id, journal_id, ledger_account, side, amount_minor) VALUES
                      ('%s', '%s', 'wallet:late', 'DEBIT', 1), ('%s', '%s', 'clearing:late', 'CREDIT', 1)
                    """.formatted(UUID.randomUUID(), journalId, UUID.randomUUID(), journalId));
        } catch (Exception expected) {
            rejected = true;
        }
        assertThat(rejected).as("a committed journal is still sealed after the upgrade").isTrue();
    }

    // ----- infrastructure -----

    private record Target(String host, int port, String username, String password) {
        static Target fromEnvironment() {
            String url = System.getenv("JDBC_URL");
            if (url == null || url.isBlank()) url = "jdbc:postgresql://127.0.0.1:55433/decisionrail_test";
            String withoutScheme = url.substring("jdbc:postgresql://".length());
            String authority = withoutScheme.substring(0, withoutScheme.indexOf('/'));
            String host = authority.contains(":") ? authority.substring(0, authority.indexOf(':')) : authority;
            int port = authority.contains(":") ? Integer.parseInt(authority.substring(authority.indexOf(':') + 1)) : 5432;
            String username = orDefault(System.getenv("JDBC_USERNAME"), "decisionrail");
            String password = orDefault(System.getenv("JDBC_PASSWORD"), "local-test-only");
            return new Target(host, port, username, password);
        }

        String urlFor(String database) {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database;
        }

        private static String orDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private void createDatabase(Target target, String database) throws Exception {
        try (Connection connection = DriverManager.getConnection(target.urlFor(ADMIN_DATABASE), target.username(), target.password());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
    }

    private void dropDatabase(Target target, String database) {
        try (Connection connection = DriverManager.getConnection(target.urlFor(ADMIN_DATABASE), target.username(), target.password());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        } catch (Exception cleanupFailure) {
            // A leaked throwaway database is untidy, not a test failure worth masking a real one with.
            System.err.println("Could not drop upgrade-check database " + database + ": " + cleanupFailure.getMessage());
        }
    }

    private String single(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) values.add(rows.getString(1));
            assertThat(values).as("query returns exactly one row: %s", sql).hasSize(1);
            return values.getFirst();
        }
    }
}
