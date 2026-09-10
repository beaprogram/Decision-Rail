package com.decisionrail.payments;

import com.decisionrail.decision.DecisionResult;
import com.decisionrail.events.EventEnvelope;
import com.decisionrail.events.OutboxStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OutboxStore outbox;

    public PaymentStore(JdbcTemplate jdbc, ObjectMapper json, OutboxStore outbox) {
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
    }

    public IdempotencyRecord claimKey(String merchant, String key, String hash) {
        // PostgreSQL waits for a competing insert to commit before resolving ON CONFLICT.
        jdbc.update("INSERT INTO idempotency_records(merchant_id,idempotency_key,request_hash) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                merchant, key, hash);
        return jdbc.queryForObject("SELECT request_hash,response_body,http_status FROM idempotency_records WHERE merchant_id=? AND idempotency_key=? FOR UPDATE",
                (rs, n) -> new IdempotencyRecord(rs.getString(1), rs.getString(2), rs.getObject(3, Integer.class)), merchant, key);
    }

    public void completeKey(String merchant, String key, CommandResult result) {
        jdbc.update("UPDATE idempotency_records SET response_body=?::jsonb,http_status=? WHERE merchant_id=? AND idempotency_key=?",
                encode(result.payment()), result.httpStatus(), merchant, key);
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
        String eventType = "payment." + payment.status().name().toLowerCase(java.util.Locale.ROOT) + ".v1";
        UUID eventId = UUID.randomUUID();
        long sequence = outbox.nextSequence(payment.id());
        EventEnvelope envelope = new EventEnvelope(eventId, eventType, EventEnvelope.SUPPORTED_SCHEMA_VERSION,
                payment.id(), EventEnvelope.PAYMENT_AGGREGATE, sequence, merchant,
                payment.updatedAt(), payment.updatedAt(), snapshot(payment));
        outbox.append(eventId, payment.id(), sequence, merchant, eventType,
                EventEnvelope.SUPPORTED_SCHEMA_VERSION, encode(envelope), payment.updatedAt());
        jdbc.update("INSERT INTO audit_events(id,merchant_id,payment_id,action) VALUES (?,?,?,?)", UUID.randomUUID(), merchant, payment.id(), eventType);
    }

    private static EventEnvelope.Payment snapshot(PaymentView payment) {
        DecisionResult decision = payment.decision();
        return new EventEnvelope.Payment(payment.id(), payment.accountId(), payment.amountMinor(),
                payment.currency(), payment.country(), payment.status().name(),
                new EventEnvelope.Decision(decision.outcome().name(), decision.score(), decision.ruleSetVersion(),
                        decision.reasons().stream()
                                .map(reason -> new EventEnvelope.Reason(reason.code(), reason.description(), reason.scoreContribution()))
                                .toList(),
                        decision.flags().stream().map(Enum::name).toList()),
                payment.failureCode(), payment.createdAt(), payment.updatedAt());
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

    public record IdempotencyRecord(String requestHash, String responseBody, Integer httpStatus) {}
}
