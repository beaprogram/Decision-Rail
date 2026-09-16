package com.decisionrail.payments;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * All SQL for return operations and the compensating journals that record them.
 *
 * <p>Separate from {@link PaymentStore} because the two answer different questions - one owns the
 * payment, its account and its capture evidence, the other owns what was given back - but both run
 * inside the same transaction opened by {@link PaymentService}. There is no second transaction
 * boundary here and no path that commits a return without its journal.
 *
 * <p>Nothing in this class updates or deletes a return or a journal. A return is corrected by
 * recording another return, and the database rejects any attempt to do otherwise.
 */
@Repository
public class ReturnStore {
    /**
     * Page size bounds for return history.
     *
     * <p>Still bounded, because one payment's history must never produce an unbounded response. What
     * changed is that the bound is now a page rather than a ceiling: everything past it was previously
     * unreachable, so a payment with 201 returns had a newest operation that no caller could see while
     * its totals counted it.
     */
    public static final int DEFAULT_PAGE = 50;
    public static final int MAX_PAGE = 200;

    private final JdbcTemplate jdbc;

    public ReturnStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What has already been returned, read while the caller holds the payment row.
     *
     * <p>Deliberately summed from the return operations rather than read from the payment's
     * denormalised total. The two must agree - a deferred database trigger rejects the transaction if
     * they do not - and deciding a new return's budget from the rows means the decision rests on the
     * operations themselves, not on a cached number that a defect could have left stale.
     */
    public Totals totals(UUID paymentId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(amount_minor), 0), count(*), coalesce(max(sequence_number), 0)
                FROM payment_returns WHERE payment_id = ?
                """, (rs, n) -> new Totals(rs.getLong(1), rs.getInt(2), rs.getInt(3)), paymentId);
    }

    /**
     * Writes the return operation and its balanced compensating journal.
     *
     * <p>Both the operation and the journal are created here, in the caller's transaction, and the
     * journal names the return it records. The database checks that link: a return journal whose
     * amount, currency, merchant, payment or ledger accounts disagree with its return operation is
     * refused, so a balanced pair carrying the wrong amount cannot be written.
     *
     * <p>Entry direction is the exact reverse of a capture. A capture debits the wallet and credits
     * merchant clearing; a return debits merchant clearing and credits the wallet back.
     */
    public UUID record(String merchant, PaymentView payment, UUID returnId, ReturnType type,
                       long amountMinor, String reason, int sequenceNumber, Instant createdAt) {
        jdbc.update("""
                INSERT INTO payment_returns
                    (id, payment_id, merchant_id, account_id, return_type, amount_minor, currency,
                     reason, sequence_number, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, returnId, payment.id(), merchant, payment.accountId(), type.name(), amountMinor,
                payment.currency(), reason, sequenceNumber, Timestamp.from(createdAt));

        UUID journal = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ledger_journals (id, payment_id, merchant_id, currency, journal_kind, source_return_id)
                VALUES (?, ?, ?, ?, 'RETURN', ?)
                """, journal, payment.id(), merchant, payment.currency(), returnId);
        jdbc.update("INSERT INTO ledger_entries(id,journal_id,ledger_account,side,amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journal, "merchant-clearing:" + merchant, "DEBIT", amountMinor);
        jdbc.update("INSERT INTO ledger_entries(id,journal_id,ledger_account,side,amount_minor) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), journal, "wallet:" + payment.accountId(), "CREDIT", amountMinor);
        return journal;
    }

    /**
     * Moves the returned funds back into the account balance.
     *
     * <p>Only {@code balance_minor} changes. Holds belong to other payments' authorizations and are
     * not this payment's to touch: adding to {@code held_minor} here would silently reserve the
     * refunded money against work nobody requested, and leaving {@code held_minor} alone is what keeps
     * unrelated authorizations on the same account exactly as they were.
     */
    public void creditBack(UUID accountId, long amountMinor) {
        jdbc.update("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?", amountMinor, accountId);
    }

    /** Records the new returned total on the payment. The deferred trigger checks it against the rows. */
    public void recordReturnedTotal(UUID paymentId, long returnedAmountMinor, Instant updatedAt) {
        jdbc.update("UPDATE payments SET returned_amount_minor = ?, updated_at = ? WHERE id = ?",
                returnedAmountMinor, Timestamp.from(updatedAt), paymentId);
    }

    /**
     * One page of a payment's returns, newest first, each with the journal that recorded it.
     *
     * <p>Newest first so the most recent operation is always on the first page, and keyset paged on the
     * sequence number so the rest stays reachable. Ownership is in the query rather than applied to the
     * result: the merchant comes from authentication, so a return belonging to anyone else is never
     * selected instead of being filtered out afterwards.
     *
     * <p>One row beyond the page size is read to decide whether another page exists, which avoids a
     * second query and avoids reporting a next page that turns out to be empty.
     */
    public Page forPayment(String merchant, UUID paymentId, ReturnCursor cursor, int limit) {
        List<PaymentReturnView> rows = jdbc.query("""
                SELECT r.id, r.payment_id, r.account_id, r.return_type, r.amount_minor, r.currency,
                       r.reason, r.sequence_number, j.id AS journal_id, r.created_at
                FROM payment_returns r
                LEFT JOIN ledger_journals j ON j.source_return_id = r.id
                WHERE r.merchant_id = ? AND r.payment_id = ? AND (? = 0 OR r.sequence_number < ?)
                ORDER BY r.sequence_number DESC
                LIMIT ?
                """, ReturnStore::map, merchant, paymentId,
                cursor == null ? 0 : cursor.sequenceNumber(),
                cursor == null ? 0 : cursor.sequenceNumber(),
                limit + 1);
        boolean more = rows.size() > limit;
        List<PaymentReturnView> page = more ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String next = more
                ? new ReturnCursor(paymentId, page.getLast().sequenceNumber()).encode()
                : null;
        return new Page(page, next);
    }

    /** One page of return history, and where to continue from. */
    public record Page(List<PaymentReturnView> returns, String nextCursor) {}

    private static PaymentReturnView map(ResultSet rs, int n) throws SQLException {
        return new PaymentReturnView(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getObject("account_id", UUID.class), ReturnType.valueOf(rs.getString("return_type")),
                rs.getLong("amount_minor"), rs.getString("currency").trim(), rs.getString("reason"),
                rs.getInt("sequence_number"), rs.getObject("journal_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    /** @param highestSequence the largest sequence number used so far, so the next one is dense. */
    public record Totals(long returnedMinor, int count, int highestSequence) {}
}
