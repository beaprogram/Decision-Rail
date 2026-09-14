package com.decisionrail.payments;

import com.decisionrail.decision.DecisionResult;
import com.decisionrail.events.EventEnvelope;
import com.decisionrail.events.OutboxStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.decisionrail.telemetry.Correlation;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OutboxStore outbox;
    private final Correlation correlation;

    public PaymentStore(JdbcTemplate jdbc, ObjectMapper json, OutboxStore outbox, Correlation correlation) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
        this.correlation = correlation;
    }

    public IdempotencyRecord claimKey(String merchant, String key, String hash) {
        // PostgreSQL waits for a competing insert to commit before resolving ON CONFLICT. The trace is
        // written only by the insert that wins, so it records the request that first performed this
        // command; ON CONFLICT DO NOTHING leaves an existing row's provenance alone, which is what lets
        // a later replay point at the original instead of overwriting it.
        jdbc.update("""
                INSERT INTO idempotency_records(merchant_id,idempotency_key,request_hash,origin_trace_id)
                VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                """, merchant, key, hash, correlation.currentTraceId().orElse(null));
        return jdbc.queryForObject("""
                SELECT request_hash,response_body,http_status,origin_trace_id,response_kind
                FROM idempotency_records WHERE merchant_id=? AND idempotency_key=? FOR UPDATE
                """,
                (rs, n) -> new IdempotencyRecord(rs.getString(1), rs.getString(2), rs.getObject(3, Integer.class),
                        rs.getString(4),
                        rs.getString(5) == null ? null : StoredResponseKind.valueOf(rs.getString(5))),
                merchant, key);
    }

    public void completeKey(String merchant, String key, StoredResponseKind kind, CommandResult<?> result) {
        jdbc.update("""
                UPDATE idempotency_records SET response_body=?::jsonb,http_status=?,response_kind=?
                WHERE merchant_id=? AND idempotency_key=?
                """, encode(result.body()), result.httpStatus(), kind.name(), merchant, key);
    }

    public AccountView account(String merchant, UUID id, boolean lock) {
        List<AccountView> values = jdbc.query("SELECT id,currency,balance_minor,held_minor FROM accounts WHERE merchant_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new AccountView(rs.getObject(1, UUID.class), rs.getString(2).trim(), rs.getLong(3), rs.getLong(4), rs.getLong(3) - rs.getLong(4)), merchant, id);
        if (values.isEmpty()) throw new PaymentException("ACCOUNT_NOT_FOUND", 404, "Account was not found.");
        return values.getFirst();
    }

    public PaymentView payment(String merchant, UUID id, boolean lock) {
        List<PaymentView> values = jdbc.query("SELECT * FROM payments WHERE merchant_id=? AND id=?" + (lock ? " FOR UPDATE" : ""), this::mapPayment, merchant, id);
        if (values.isEmpty()) throw new PaymentException("PAYMENT_NOT_FOUND", 404, "Payment was not found.");
        return values.getFirst();
    }

    /**
     * The merchant's accounts, newest first, bounded. Ownership is part of the query.
     *
     * <p>Ordered newest first rather than oldest first, which is what it was. Under a bounded list the
     * ordering decides which accounts become invisible, and hiding the newest is the harmful direction:
     * an account someone has just created is the one they are about to use, and it was falling off the
     * end. Hiding the oldest is survivable, and matches how payments are already listed.
     *
     * <p>The bound is still a bound. A merchant with more accounts than the limit sees only this page
     * of them, and the caller is expected to say so rather than present a truncated list as complete.
     */
    public List<AccountView> accounts(String merchant, int limit) {
        return jdbc.query("""
                SELECT id, currency, balance_minor, held_minor FROM accounts
                WHERE merchant_id = ? ORDER BY created_at DESC, id DESC LIMIT ?
                """, (rs, n) -> new AccountView(rs.getObject(1, UUID.class), rs.getString(2).trim(),
                        rs.getLong(3), rs.getLong(4), rs.getLong(3) - rs.getLong(4)), merchant, limit);
    }

    /**
     * Authoritative, merchant-scoped payment search.
     *
     * <p>Reads the {@code payments} table, not the activity projection, so a payment is findable the
     * moment its transaction commits and stays findable while the broker is unreachable and nothing has
     * been delivered.
     *
     * <p>Every filter value is bound as a parameter; only the number of placeholders varies with how
     * many enumerated values were supplied. The merchant predicate is always present and is not
     * derived from anything the caller sent.
     */
    public PaymentSearchPage search(String merchant, PaymentSearchQuery query) {
        List<Object> filters = new ArrayList<>();
        StringBuilder where = new StringBuilder("merchant_id = ?");
        filters.add(merchant);
        if (query.paymentId() != null) {
            where.append(" AND id = ?");
            filters.add(query.paymentId());
        }
        if (query.accountId() != null) {
            where.append(" AND account_id = ?");
            filters.add(query.accountId());
        }
        if (!query.statuses().isEmpty()) {
            where.append(" AND status IN (").append(placeholders(query.statuses().size())).append(')');
            query.statuses().forEach(status -> filters.add(status.name()));
        }
        if (!query.riskOutcomes().isEmpty()) {
            where.append(" AND (decision ->> 'outcome') IN (").append(placeholders(query.riskOutcomes().size())).append(')');
            filters.addAll(query.riskOutcomes());
        }
        if (query.currency() != null) {
            where.append(" AND currency = ?");
            filters.add(query.currency());
        }
        if (query.createdFrom() != null) {
            where.append(" AND created_at >= ?");
            filters.add(Timestamp.from(query.createdFrom()));
        }
        if (query.createdTo() != null) {
            where.append(" AND created_at <= ?");
            filters.add(Timestamp.from(query.createdTo()));
        }

        // Counted separately from the page, and without the cursor, so it describes the filters rather
        // than the remaining rows. Bounded by its own limit: an exact total would mean scanning every
        // match on every page request.
        Long counted = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT 1 FROM payments WHERE " + where + " LIMIT " + (MATCH_COUNT_LIMIT + 1) + ") AS bounded",
                Long.class, filters.toArray());
        long matched = counted == null ? 0 : counted;
        boolean capped = matched > MATCH_COUNT_LIMIT;

        List<Object> pageArguments = new ArrayList<>(filters);
        StringBuilder pageWhere = new StringBuilder(where);
        if (query.cursor() != null) {
            // Row comparison against the full ordering tuple, so a page boundary is stable even when
            // several payments share a created_at.
            pageWhere.append(" AND (created_at, id) < (?, ?)");
            pageArguments.add(Timestamp.from(query.cursor().createdAt()));
            pageArguments.add(query.cursor().id());
        }
        pageArguments.add(query.limit() + 1);
        List<PaymentSummaryView> rows = jdbc.query("""
                SELECT id, account_id, amount_minor, currency, country, status,
                       decision ->> 'outcome'          AS risk_outcome,
                       (decision ->> 'score')::integer AS risk_score,
                       decision ->> 'ruleSetVersion'   AS policy_version,
                       failure_code, created_at, updated_at
                FROM payments WHERE %s
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """.formatted(pageWhere), PaymentStore::mapSummary, pageArguments.toArray());

        // One row beyond the page size proves there is a next page without a second query.
        boolean more = rows.size() > query.limit();
        List<PaymentSummaryView> page = more ? List.copyOf(rows.subList(0, query.limit())) : List.copyOf(rows);
        String nextCursor = more
                ? new PaymentCursor(page.getLast().createdAt(), page.getLast().id()).encode()
                : null;
        return new PaymentSearchPage(page, nextCursor, capped ? MATCH_COUNT_LIMIT : matched, capped, MATCH_COUNT_LIMIT);
    }

    /** Accepted commands for one payment, from the audit record written inside each transaction. */
    public List<PaymentTimelineView.CommandEntry> commands(String merchant, UUID paymentId, int limit) {
        return jdbc.query("""
                SELECT action, occurred_at FROM audit_events
                WHERE merchant_id = ? AND payment_id = ? ORDER BY occurred_at, id LIMIT ?
                """, (rs, n) -> new PaymentTimelineView.CommandEntry(rs.getString(1), rs.getTimestamp(2).toInstant()),
                merchant, paymentId, limit);
    }

    private static final int MATCH_COUNT_LIMIT = 1_000;

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static PaymentSummaryView mapSummary(ResultSet rs, int n) throws SQLException {
        return new PaymentSummaryView(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getLong("amount_minor"), rs.getString("currency").trim(), rs.getString("country").trim(),
                PaymentStatus.valueOf(rs.getString("status")), rs.getString("risk_outcome"), rs.getInt("risk_score"),
                rs.getString("policy_version"), rs.getString("failure_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    public void insert(String merchant, PaymentView payment) {
        jdbc.update("""
                INSERT INTO payments(id,merchant_id,account_id,amount_minor,currency,country,status,decision,failure_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?::jsonb,?,?,?)
                """, payment.id(), merchant, payment.accountId(), payment.amountMinor(), payment.currency(), payment.country(),
                payment.status().name(), encode(payment.decision()), payment.failureCode(), Timestamp.from(payment.createdAt()), Timestamp.from(payment.updatedAt()));
    }

    public void reserve(UUID account, long amount) {
        jdbc.update("UPDATE accounts SET held_minor=held_minor+? WHERE id=?", amount, account);
    }

    public void release(UUID account, long amount) {
        jdbc.update("UPDATE accounts SET held_minor=held_minor-? WHERE id=?", amount, account);
    }

    public void captureBalance(UUID account, long amount) {
        jdbc.update("UPDATE accounts SET balance_minor=balance_minor-?,held_minor=held_minor-? WHERE id=?", amount, amount, account);
    }

    /**
     * Moves a payment to CAPTURED and records what the capture moved, in one statement.
     *
     * <p>The captured amount is what caps everything that can be returned later, and it is written
     * here rather than re-derived from the authorized amount whenever a refund asks. One statement
     * because the two are one fact: a CHECK constraint requires a CAPTURED payment to state its
     * captured amount and every other status not to, so writing them separately would leave the row
     * momentarily invalid and be rejected outright.
     */
    public void capture(UUID payment, long capturedAmountMinor, Instant updatedAt) {
        jdbc.update("UPDATE payments SET status='CAPTURED',captured_amount_minor=?,updated_at=? WHERE id=?",
                capturedAmountMinor, Timestamp.from(updatedAt), payment);
    }

    /** What a capture moved, or null when this payment was never captured. */
    public Long capturedAmount(UUID payment) {
        return jdbc.queryForObject("SELECT captured_amount_minor FROM payments WHERE id=?", Long.class, payment);
    }

    public void transition(UUID payment, PaymentStatus status, Instant updatedAt) {
        jdbc.update("UPDATE payments SET status=?,updated_at=? WHERE id=?", status.name(), Timestamp.from(updatedAt), payment);
    }

    public void recordJournal(String merchant, PaymentView payment) {
        UUID journal = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_journals(id,payment_id,merchant_id,currency) VALUES (?,?,?,?)", journal, payment.id(), merchant, payment.currency());
        jdbc.update("INSERT INTO ledger_entries(id,journal_id,ledger_account,side,amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journal, "wallet:" + payment.accountId(), "DEBIT", payment.amountMinor());
        jdbc.update("INSERT INTO ledger_entries(id,journal_id,ledger_account,side,amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journal, "merchant-clearing:" + merchant, "CREDIT", payment.amountMinor());
    }

    public List<LedgerEntryView> ledger(String merchant, UUID payment) {
        // Prevent a missing or foreign payment from being indistinguishable from an empty own ledger.
        payment(merchant, payment, false);
        return jdbc.query("""
                SELECT e.id,e.journal_id,e.ledger_account,e.side,e.amount_minor,j.currency
                FROM ledger_entries e JOIN ledger_journals j ON j.id=e.journal_id
                WHERE j.merchant_id=? AND j.payment_id=? ORDER BY e.side,e.id
                """, (rs, n) -> new LedgerEntryView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6).trim()), merchant, payment);
    }

    /**
     * Writes durable event intent and the audit row inside the caller's payment transaction.
     *
     * <p>The per-payment sequence is assigned here, while the transaction owns the payment row:
     * newly inserted for an authorization, locked FOR UPDATE for capture and void. That makes
     * lifecycle order a committed fact rather than something a dispatcher has to infer from
     * timestamps, random event ids, or partition assignment.
     *
     * <p>The event id is generated once and never regenerated, so every retry and every
     * operator redrive of this event carries the same identity a consumer deduplicates on.
     */
    public void recordEvent(String merchant, PaymentView payment) {
        // A capture moves the full authorized amount, so that is what the snapshot records as captured.
        // Nothing has been returned at the moment any lifecycle event is written: a return is its own
        // event, recorded by recordReturnEvent below.
        Long captured = payment.status() == PaymentStatus.CAPTURED ? payment.amountMinor() : null;
        recordEvent(merchant, payment, "payment." + payment.status().name().toLowerCase(java.util.Locale.ROOT) + ".v1",
                null, captured, 0L);
    }

    /**
     * Records a return's event intent alongside the lifecycle events of the same payment.
     *
     * <p>A return needs its own event even though the payment's status does not move: two partial
     * refunds are two distinct operations, and an event stream that showed only status changes would
     * report nothing at all for either. The return block carries which operation this was, so a
     * consumer can tell them apart without comparing snapshots.
     *
     * <p>It shares the payment's aggregate sequence, so a refund cannot be delivered before the
     * capture it compensates.
     */
    public void recordReturnEvent(String merchant, PaymentView payment, ReturnReceiptView receipt) {
        String eventType = switch (receipt.returnType()) {
            case REFUND -> "payment.refunded.v1";
            case REVERSAL -> "payment.reversed.v1";
        };
        EventEnvelope.Return operation = new EventEnvelope.Return(receipt.returnId(), receipt.returnType().name(),
                receipt.amountMinor(), receipt.currency(), receipt.reason(), receipt.sequenceNumber(),
                receipt.createdAt());
        recordEvent(merchant, payment, eventType, operation, receipt.capturedAmountMinor(),
                receipt.returnedAmountMinor());
    }

    private void recordEvent(String merchant, PaymentView payment, String eventType,
                             EventEnvelope.Return operation, Long capturedAmountMinor, long returnedAmountMinor) {
        UUID eventId = UUID.randomUUID();
        long sequence = outbox.nextSequence(payment.id());
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, EventEnvelope.SUPPORTED_SCHEMA_VERSION,
                payment.id(), EventEnvelope.PAYMENT_AGGREGATE, sequence, merchant,
                payment.updatedAt(), payment.updatedAt(),
                snapshot(payment, capturedAmountMinor, returnedAmountMinor), operation);
        // The trace of the command being committed, captured while the thread that ran it still exists.
        outbox.append(eventId, payment.id(), sequence, merchant, eventType,
                EventEnvelope.SUPPORTED_SCHEMA_VERSION, encode(envelope), payment.updatedAt(),
                correlation.current());
        jdbc.update("INSERT INTO audit_events(id,merchant_id,payment_id,action) VALUES (?,?,?,?)", UUID.randomUUID(), merchant, payment.id(), eventType);
    }

    private static EventEnvelope.Payment snapshot(PaymentView payment, Long capturedAmountMinor,
                                                  long returnedAmountMinor) {
        DecisionResult decision = payment.decision();
        return new EventEnvelope.Payment(payment.id(), payment.accountId(), payment.amountMinor(),
                payment.currency(), payment.country(), payment.status().name(),
                new EventEnvelope.Decision(decision.outcome().name(), decision.score(), decision.ruleSetVersion(),
                        decision.reasons().stream()
                                .map(reason -> new EventEnvelope.Reason(reason.code(), reason.description(), reason.scoreContribution()))
                                .toList(),
                        decision.flags().stream().map(Enum::name).toList()),
                payment.failureCode(), payment.createdAt(), payment.updatedAt(),
                capturedAmountMinor, returnedAmountMinor);
    }

    /**
     * Decodes a stored idempotent response as the kind it was written as.
     *
     * <p>The expected kind is what the command being replayed produces. A mismatch means the same key
     * was accepted for two different commands, which the request fingerprint should already have
     * refused; failing here rather than decoding anyway keeps that a loud error instead of a silently
     * wrong response.
     */
    public Object decodeResponse(IdempotencyRecord record, StoredResponseKind expected) {
        StoredResponseKind stored = record.responseKind() == null ? StoredResponseKind.PAYMENT : record.responseKind();
        if (stored != expected) {
            throw new IllegalStateException("Stored idempotent response is a " + stored
                    + " but this command produces a " + expected);
        }
        return switch (stored) {
            case PAYMENT -> decode(record.responseBody(), PaymentView.class);
            case RETURN -> decode(record.responseBody(), ReturnReceiptView.class);
        };
    }

    public PaymentView decodePayment(String value) { return decode(value, PaymentView.class); }

    private PaymentView mapPayment(ResultSet rs, int n) throws SQLException {
        return new PaymentView(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getLong("amount_minor"),
                rs.getString("currency").trim(), rs.getString("country").trim(), PaymentStatus.valueOf(rs.getString("status")),
                decode(rs.getString("decision"), DecisionResult.class), rs.getString("failure_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode persisted payment data", e); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot decode persisted payment data", e); }
    }

    /**
     * @param originTraceId the trace of the request that first performed this command, or null when it
     *                      ran untraced. A replay points at it rather than claiming to be it.
     */
    public record IdempotencyRecord(String requestHash, String responseBody, Integer httpStatus,
                                    String originTraceId, StoredResponseKind responseKind) {}
}
