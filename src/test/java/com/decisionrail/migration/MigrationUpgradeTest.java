package com.decisionrail.migration;

import com.decisionrail.support.ThrowawayDatabase;
import java.sql.Connection;
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

    @Test
    void upgradingADatabaseWithExistingRecordsBackfillsDeliveryStateWithoutDisturbingMoney() throws Exception {
        try (ThrowawayDatabase database = ThrowawayDatabase.create("decisionrail_upgrade")) {
            String url = database.url();

            // Stop at V2: exactly the schema the previous delivery left behind.
            Flyway atV2 = Flyway.configure()
                    .dataSource(url, database.username(), database.password())
                    .locations("classpath:db/migration")
                    .target("2")
                    .load();
            assertThat(atV2.migrate().migrationsExecuted).isEqualTo(2);

            Seeded seeded = seedRecordsAsV1WouldHaveWritten(database);

            // Now apply everything added after V2.
            Flyway toLatest = Flyway.configure()
                    .dataSource(url, database.username(), database.password())
                    .locations("classpath:db/migration")
                    .load();
            // Counted from the migrations on the classpath rather than hard-coded, so adding one does
            // not break this check. What the check is for is that every one of them applies cleanly to
            // a database that already holds real records, which the assertions below then inspect.
            int expectedUpgrades = migrationsAfterVersionTwo();
            assertThat(expectedUpgrades).as("there are upgrade migrations to apply").isGreaterThanOrEqualTo(4);
            assertThat(toLatest.migrate().migrationsExecuted).isEqualTo(expectedUpgrades);

            try (Connection connection = database.open()) {
                assertFinancialRecordsSurvived(connection, seeded);
                assertOutboxBackfill(connection, seeded);
                assertNewTablesExist(connection);
                assertSealedJournalStillRejectsLateEntries(connection, seeded);
                assertCorrelationColumnsAreOptionalForOlderRows(connection, seeded);
                assertReturnBudgetBackfilledFromExistingCaptures(connection, seeded);
                assertHistoricalResponsesAreTypedAndUnchanged(connection, seeded);
                assertHistoricalEventsAreNotRewrittenForReturns(connection, seeded);
                assertReturnsWorkAgainstAnUpgradedCapture(connection, seeded);
            }
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

    /**
     * V7 adds correlation columns and backfills nothing, which is the point: every row written before
     * it has no trace, and an upgraded deployment must still deliver those events rather than treating
     * missing telemetry as a defect. The constraints are checked here too, because they are what stops
     * unvalidated text reaching an outbound header later.
     */
    private void assertCorrelationColumnsAreOptionalForOlderRows(Connection connection, Seeded seeded)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT origin_trace_id, origin_span_id, status FROM outbox_events WHERE id = ?")) {
            statement.setObject(1, seeded.authorizedEvent());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).as("a pre-upgrade row carries no trace").isNull();
                assertThat(rows.getString(2)).isNull();
                assertThat(rows.getString(3)).as("and is still claimable for delivery").isEqualTo("PENDING");
            }
        }
        // A span without its trace identifies nothing, and malformed hex must not reach a header.
        assertThat(rejected(connection, "UPDATE outbox_events SET origin_span_id = '00f067aa0ba902b7' WHERE id = '"
                + seeded.authorizedEvent() + "'"))
                .as("a span id without a trace id is refused").isTrue();
        assertThat(rejected(connection, "UPDATE outbox_events SET origin_trace_id = 'not-hex' WHERE id = '"
                + seeded.authorizedEvent() + "'"))
                .as("a non-hex trace id is refused").isTrue();
    }

    /**
     * V10 has to give a capture that predates it a return budget, or every historical captured payment
     * becomes unrefundable after the upgrade.
     *
     * <p>The backfill is exact rather than approximate: capture always moved the full authorized
     * amount, so captured_amount_minor is amount_minor for CAPTURED rows and must stay null for
     * everything else. A payment that was never captured with a captured amount set would offer a
     * refund against money that never moved.
     */
    private void assertReturnBudgetBackfilledFromExistingCaptures(Connection connection, Seeded seeded)
            throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT captured_amount_minor, returned_amount_minor FROM payments WHERE id = ?")) {
            statement.setObject(1, seeded.capturedPayment());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).as("a pre-upgrade capture keeps its full amount as its budget").isEqualTo(2500);
                assertThat(rows.getLong(2)).as("and nothing has been returned").isZero();
            }
        }
        try (var statement = connection.prepareStatement("SELECT captured_amount_minor FROM payments WHERE id = ?")) {
            statement.setObject(1, seeded.authorizedPayment());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                rows.getLong(1);
                assertThat(rows.wasNull()).as("a payment that was never captured has no captured amount").isTrue();
            }
        }
        assertThat(rejected(connection, "UPDATE payments SET returned_amount_minor = 2501 WHERE id = '"
                + seeded.capturedPayment() + "'"))
                .as("the cap applies to upgraded rows too").isTrue();
        assertThat(rejected(connection, "UPDATE payments SET captured_amount_minor = 4000 WHERE id = '"
                + seeded.authorizedPayment() + "'"))
                .as("an uncaptured payment cannot acquire a captured amount").isTrue();
    }

    /**
     * A stored idempotent response written before refunds existed is a payment, and says so after the
     * upgrade. Its bytes are unchanged: the response a caller was given years ago is what they get on
     * a replay, not a re-rendering of it.
     */
    private void assertHistoricalResponsesAreTypedAndUnchanged(Connection connection, Seeded seeded)
            throws Exception {
        try (var statement = connection.prepareStatement("""
                SELECT response_kind, response_body::text, http_status FROM idempotency_records
                WHERE merchant_id = 'demo-merchant' AND idempotency_key = 'legacy-key-00000001'
                """)) {
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("PAYMENT");
                assertThat(rows.getString(2)).isEqualTo("{\"id\": \"" + seeded.capturedPayment() + "\"}");
                assertThat(rows.getInt(3)).isEqualTo(201);
            }
        }
        assertThat(rejected(connection, """
                UPDATE idempotency_records SET response_kind = 'GUESS'
                WHERE idempotency_key = 'legacy-key-00000001'
                """)).as("only known response kinds are accepted").isTrue();
    }

    /**
     * The new envelope fields are absent from historical events, and must stay absent.
     *
     * <p>Backfilling them would change bytes a consumer has already fingerprinted and deduplicated on,
     * which would make a redelivered historical event look like identity reuse. V3 backfilled payloads
     * because those rows had never been delivered; these have, so the contract tolerates the absence
     * instead.
     */
    private void assertHistoricalEventsAreNotRewrittenForReturns(Connection connection, Seeded seeded)
            throws Exception {
        try (var statement = connection.prepareStatement("SELECT payload::text FROM outbox_events WHERE id = ?")) {
            statement.setObject(1, seeded.authorizedEvent());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                String payload = rows.getString(1);
                assertThat(payload).doesNotContain("capturedAmountMinor")
                        .doesNotContain("returnedAmountMinor")
                        .doesNotContain("returnOperation");
            }
        }
    }

    /**
     * The upgraded capture is genuinely refundable: a return operation and its compensating journal
     * can be written against a payment whose capture journal was created by V1.
     *
     * <p>That original journal is sealed and untouched afterwards, which is the property a
     * compensating-entry ledger exists to provide.
     */
    private void assertReturnsWorkAgainstAnUpgradedCapture(Connection connection, Seeded seeded) throws Exception {
        UUID returnId = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO payment_returns (id, payment_id, merchant_id, account_id, return_type,
                            amount_minor, currency, reason, sequence_number, created_at)
                    VALUES ('%s', '%s', 'demo-merchant', '%s', 'REFUND', 1000, 'CAD', 'upgrade check', 1, now())
                    """.formatted(returnId, seeded.capturedPayment(), seeded.accountId()));
            statement.execute("UPDATE payments SET returned_amount_minor = 1000 WHERE id = '"
                    + seeded.capturedPayment() + "'");
            statement.execute("""
                    INSERT INTO ledger_journals (id, payment_id, merchant_id, currency, journal_kind, source_return_id)
                    VALUES ('%s', '%s', 'demo-merchant', 'CAD', 'RETURN', '%s')
                    """.formatted(journalId, seeded.capturedPayment(), returnId));
            statement.execute("""
                    INSERT INTO ledger_entries (id, journal_id, ledger_account, side, amount_minor) VALUES
                      ('%s', '%s', 'merchant-clearing:demo-merchant', 'DEBIT', 1000),
                      ('%s', '%s', 'wallet:%s', 'CREDIT', 1000)
                    """.formatted(UUID.randomUUID(), journalId, UUID.randomUUID(), journalId, seeded.accountId()));
            statement.execute("UPDATE accounts SET balance_minor = balance_minor + 1000 WHERE id = '"
                    + seeded.accountId() + "'");
        }
        connection.commit();
        connection.setAutoCommit(true);

        try (var statement = connection.prepareStatement("""
                SELECT journal_kind, count(*) FROM ledger_journals WHERE payment_id = ? GROUP BY journal_kind ORDER BY 1
                """)) {
            statement.setObject(1, seeded.capturedPayment());
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("CAPTURE");
                assertThat(rows.getInt(2)).as("the original capture journal is still there, exactly once").isEqualTo(1);
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("RETURN");
                assertThat(rows.getInt(2)).isEqualTo(1);
            }
        }
        // The V1 journal is still sealed against additions, and the capture evidence is unchanged.
        assertThat(rejected(connection, """
                INSERT INTO ledger_entries (id, journal_id, ledger_account, side, amount_minor)
                SELECT gen_random_uuid(), j.id, 'wallet:late', 'DEBIT', 1
                FROM ledger_journals j WHERE j.payment_id = '%s' AND j.journal_kind = 'CAPTURE'
                """.formatted(seeded.capturedPayment())))
                .as("the original capture journal stays sealed after compensation").isTrue();
    }

    /** Runs a statement expected to violate a constraint, and reports whether the database refused it. */
    private boolean rejected(Connection connection, String sql) {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            return false;
        } catch (java.sql.SQLException refused) {
            return true;
        }
    }

    private record Seeded(UUID accountId, UUID capturedPayment, UUID authorizedPayment,
                          List<UUID> capturedEvents, UUID authorizedEvent) {}

    /**
     * Writes rows in the shape V1 and V2 produced: outbox rows with no sequence, no status, and an
     * envelope that carries only the original five fields.
     */
    private Seeded seedRecordsAsV1WouldHaveWritten(ThrowawayDatabase database) throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID capturedPayment = UUID.randomUUID();
        UUID authorizedPayment = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        List<UUID> capturedEvents = List.of(UUID.randomUUID(), UUID.randomUUID());
        UUID authorizedEvent = UUID.randomUUID();

        try (Connection connection = database.open()) {
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

    private String single(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) values.add(rows.getString(1));
            assertThat(values).as("query returns exactly one row: %s", sql).hasSize(1);
            return values.getFirst();
        }
    }
}
