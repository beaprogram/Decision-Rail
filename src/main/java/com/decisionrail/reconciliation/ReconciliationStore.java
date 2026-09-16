package com.decisionrail.reconciliation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The reads that reconciliation is built from. Nothing here writes, and nothing here is reachable
 * from a command path.
 *
 * <h2>What "independently derived" means here, and what it does not</h2>
 * Every number below is re-derived from a different record than the one it is checked against. An
 * account's expected balance comes from the ledger entries that name its wallet; the ledger is then
 * checked against the return operations and the capture amounts; those are checked against the
 * payment's own totals. Comparing a payment's returned total against the account balance alone would
 * be circular - both are written by the same statement in the same transaction - so the chain is
 * walked one link at a time and each link can break visibly.
 *
 * <p>What this cannot do is detect a corruption that changed every record consistently. All of this
 * evidence lives in one database, and a single mistaken transaction that wrote a wrong amount to the
 * payment, the journal and the balance together would reconcile perfectly. That limitation is
 * reported in the report rather than left implied.
 */
@Repository
public class ReconciliationStore {
    private final JdbcTemplate jdbc;

    public ReconciliationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Accounts in scope, with the balance the ledger implies and the holds the payments imply.
     *
     * <p>The wallet movement sums are deliberately not bounded by the payment limit. An expected
     * balance derived from part of an account's history is not a partial answer, it is a wrong one,
     * so this covers the account's whole history or the account is not reported on at all.
     *
     * <p>The two "wrong direction" columns exist because a balanced journal can still move value the
     * wrong way. A capture that credited the wallet and a return that debited it would both balance
     * and both be catastrophic, and neither would show up in a sum that only looked at the side it
     * expected.
     */
    public List<AccountRow> accounts(String merchant, UUID accountFilter, int limit) {
        StringBuilder scope = new StringBuilder("a.merchant_id = ?");
        Object[] arguments = accountFilter == null
                ? new Object[] {merchant, limit}
                : new Object[] {merchant, accountFilter, limit};
        if (accountFilter != null) scope.append(" AND a.id = ?");
        return jdbc.query("""
                WITH scoped AS (
                    SELECT a.id, a.currency, a.opening_balance_minor, a.balance_minor, a.held_minor
                    FROM accounts a WHERE %s ORDER BY a.created_at, a.id LIMIT ?
                )
                SELECT s.id, s.currency, s.opening_balance_minor, s.balance_minor, s.held_minor,
                       coalesce(w.captured_debits, 0)            AS captured_debits,
                       coalesce(w.returned_credits, 0)           AS returned_credits,
                       coalesce(w.reversed_direction_entries, 0) AS reversed_direction_entries,
                       coalesce(h.outstanding_authorizations, 0) AS outstanding_authorizations,
                       -- Currency agreement across everything the sums above draw on. Without this the
                       -- sums add minor units of one currency into an expectation denominated in
                       -- another, which produces a number that looks like a balance and means nothing.
                       (SELECT count(*) FROM payments p
                         WHERE p.account_id = s.id AND p.currency <> s.currency)   AS payments_in_other_currency,
                       (SELECT count(*) FROM payments p
                          JOIN ledger_journals j ON j.payment_id = p.id
                         WHERE p.account_id = s.id AND j.currency <> s.currency)   AS journals_in_other_currency,
                       (SELECT count(*) FROM payment_returns r
                         WHERE r.account_id = s.id AND r.currency <> s.currency)   AS returns_in_other_currency,
                       (SELECT string_agg(DISTINCT trim(p.currency), ',' ORDER BY trim(p.currency))
                          FROM payments p
                         WHERE p.account_id = s.id AND p.currency <> s.currency)   AS conflicting_currencies
                FROM scoped s
                LEFT JOIN LATERAL (
                    SELECT coalesce(sum(e.amount_minor) FILTER (WHERE j.journal_kind = 'CAPTURE' AND e.side = 'DEBIT'), 0)  AS captured_debits,
                           coalesce(sum(e.amount_minor) FILTER (WHERE j.journal_kind = 'RETURN'  AND e.side = 'CREDIT'), 0) AS returned_credits,
                           count(*) FILTER (WHERE (j.journal_kind = 'CAPTURE' AND e.side = 'CREDIT')
                                               OR (j.journal_kind = 'RETURN'  AND e.side = 'DEBIT'))                        AS reversed_direction_entries
                    FROM payments p
                    JOIN ledger_journals j ON j.payment_id = p.id
                    JOIN ledger_entries e ON e.journal_id = j.id AND e.ledger_account = 'wallet:' || s.id::text
                    WHERE p.account_id = s.id
                ) w ON true
                LEFT JOIN LATERAL (
                    SELECT coalesce(sum(p.amount_minor), 0) AS outstanding_authorizations
                    FROM payments p WHERE p.account_id = s.id AND p.status = 'AUTHORIZED'
                ) h ON true
                ORDER BY s.id
                """.formatted(scope), ReconciliationStore::mapAccount, arguments);
    }

    /**
     * Payments in scope, each with the ledger and return evidence that should agree with it.
     *
     * <p>Counts as well as totals, because a payment with two capture journals whose amounts happen to
     * sum to the captured amount would pass a totals-only check while recording the money twice.
     */
    public List<PaymentRow> payments(String merchant, UUID accountFilter, int limit) {
        StringBuilder scope = new StringBuilder("p.merchant_id = ?");
        Object[] arguments = accountFilter == null
                ? new Object[] {merchant, limit}
                : new Object[] {merchant, accountFilter, limit};
        if (accountFilter != null) scope.append(" AND p.account_id = ?");
        return jdbc.query("""
                SELECT p.id, p.account_id, p.currency, p.status, p.amount_minor,
                       p.captured_amount_minor, p.returned_amount_minor,
                       -- The account boundary, which the per-payment checks did not cross. Journals and
                       -- returns are already compared against the payment; the payment was compared
                       -- against nothing above it.
                       (SELECT trim(a.currency) FROM accounts a WHERE a.id = p.account_id) AS account_currency,
                       (SELECT count(*) FROM ledger_journals j
                         WHERE j.payment_id = p.id AND j.journal_kind = 'CAPTURE')            AS capture_journals,
                       (SELECT coalesce(sum(e.amount_minor), 0)
                          FROM ledger_journals j JOIN ledger_entries e ON e.journal_id = j.id
                         WHERE j.payment_id = p.id AND j.journal_kind = 'CAPTURE'
                           AND e.side = 'DEBIT')                                             AS capture_debit_total,
                       (SELECT count(*) FROM ledger_journals j
                         WHERE j.payment_id = p.id
                           AND (j.merchant_id <> p.merchant_id OR j.currency <> p.currency)) AS journal_ownership_mismatches,
                       (SELECT count(*) FROM payment_returns r WHERE r.payment_id = p.id)    AS return_operations,
                       (SELECT coalesce(sum(r.amount_minor), 0) FROM payment_returns r
                         WHERE r.payment_id = p.id)                                          AS return_operations_total,
                       (SELECT count(*) FROM ledger_journals j
                         WHERE j.payment_id = p.id AND j.journal_kind = 'RETURN')            AS return_journals,
                       (SELECT coalesce(sum(e.amount_minor), 0)
                          FROM ledger_journals j JOIN ledger_entries e ON e.journal_id = j.id
                         WHERE j.payment_id = p.id AND j.journal_kind = 'RETURN'
                           AND e.side = 'CREDIT')                                            AS return_journal_total,
                       (SELECT count(*) FROM payment_returns r
                         WHERE r.payment_id = p.id
                           AND (r.merchant_id <> p.merchant_id OR r.currency <> p.currency
                                OR r.account_id <> p.account_id))                            AS return_ownership_mismatches
                FROM payments p
                WHERE %s
                ORDER BY p.created_at, p.id
                LIMIT ?
                """.formatted(scope), ReconciliationStore::mapPayment, arguments);
    }

    /** Return operations in scope, each beside the journal that is supposed to record it. */
    public List<ReturnRow> returns(String merchant, UUID accountFilter, int limit) {
        StringBuilder scope = new StringBuilder("r.merchant_id = ?");
        Object[] arguments = accountFilter == null
                ? new Object[] {merchant, limit}
                : new Object[] {merchant, accountFilter, limit};
        if (accountFilter != null) scope.append(" AND r.account_id = ?");
        return jdbc.query("""
                SELECT r.id, r.payment_id, r.return_type, r.amount_minor, r.currency, j.id AS journal_id,
                       coalesce((SELECT sum(e.amount_minor) FROM ledger_entries e
                                  WHERE e.journal_id = j.id AND e.side = 'CREDIT'), 0) AS journal_credit_total,
                       coalesce((SELECT sum(e.amount_minor) FROM ledger_entries e
                                  WHERE e.journal_id = j.id AND e.side = 'DEBIT'), 0)  AS journal_debit_total
                FROM payment_returns r
                LEFT JOIN ledger_journals j ON j.source_return_id = r.id
                WHERE %s
                ORDER BY r.created_at, r.id
                LIMIT ?
                """.formatted(scope), ReconciliationStore::mapReturn, arguments);
    }

    private static AccountRow mapAccount(ResultSet rs, int row) throws SQLException {
        return new AccountRow(rs.getObject("id", UUID.class), rs.getString("currency").trim(),
                rs.getLong("opening_balance_minor"), rs.getLong("balance_minor"), rs.getLong("held_minor"),
                rs.getLong("captured_debits"), rs.getLong("returned_credits"),
                rs.getLong("reversed_direction_entries"), rs.getLong("outstanding_authorizations"),
                rs.getLong("payments_in_other_currency"), rs.getLong("journals_in_other_currency"),
                rs.getLong("returns_in_other_currency"), rs.getString("conflicting_currencies"));
    }

    private static PaymentRow mapPayment(ResultSet rs, int row) throws SQLException {
        return new PaymentRow(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getString("currency").trim(), rs.getString("account_currency"),
                rs.getString("status"), rs.getLong("amount_minor"),
                rs.getObject("captured_amount_minor", Long.class), rs.getLong("returned_amount_minor"),
                rs.getLong("capture_journals"), rs.getLong("capture_debit_total"),
                rs.getLong("journal_ownership_mismatches"), rs.getLong("return_operations"),
                rs.getLong("return_operations_total"), rs.getLong("return_journals"),
                rs.getLong("return_journal_total"), rs.getLong("return_ownership_mismatches"));
    }

    private static ReturnRow mapReturn(ResultSet rs, int row) throws SQLException {
        return new ReturnRow(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getString("return_type"), rs.getLong("amount_minor"), rs.getString("currency").trim(),
                rs.getObject("journal_id", UUID.class), rs.getLong("journal_credit_total"),
                rs.getLong("journal_debit_total"));
    }

    /**
     * @param reversedDirectionEntries wallet entries on the wrong side for their journal's kind
     * @param conflictingCurrencies    the distinct currencies found on this account's payments that are
     *                                 not the account's own, or null when there are none
     */
    public record AccountRow(UUID id, String currency, long openingBalanceMinor, long balanceMinor,
                             long heldMinor, long capturedDebits, long returnedCredits,
                             long reversedDirectionEntries, long outstandingAuthorizations,
                             long paymentsInOtherCurrency, long journalsInOtherCurrency,
                             long returnsInOtherCurrency, String conflictingCurrencies) {

        /** True when the sums above draw on records denominated in more than one currency. */
        boolean hasCurrencyConflict() {
            return paymentsInOtherCurrency > 0 || journalsInOtherCurrency > 0 || returnsInOtherCurrency > 0;
        }
    }

    public record PaymentRow(UUID id, UUID accountId, String currency, String accountCurrency,
                             String status, long amountMinor,
                             Long capturedAmountMinor, long returnedAmountMinor, long captureJournals,
                             long captureDebitTotal, long journalOwnershipMismatches, long returnOperations,
                             long returnOperationsTotal, long returnJournals, long returnJournalTotal,
                             long returnOwnershipMismatches) {}

    public record ReturnRow(UUID id, UUID paymentId, String returnType, long amountMinor, String currency,
                            UUID journalId, long journalCreditTotal, long journalDebitTotal) {}
}
