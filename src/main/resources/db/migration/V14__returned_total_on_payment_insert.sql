-- The third way into the returned-total rule: inserting the payment already inconsistent.
--
-- V13 closed the update side, after which ADR-0007 and the engineering guide described the equality as
-- holding whichever side was written. That was still one case short. The two triggers in place were:
--
--   * return_budget_on_insert           AFTER INSERT ON payment_returns
--   * returned_total_on_payment_change  AFTER UPDATE ON payments
--
-- A payment inserted with a returned total its (non-existent) return operations do not sum to is
-- neither of those events, so it passed. Reproduced against V1-V13 on PostgreSQL 16.15: a valid
-- account, a CAPTURED payment of 1000 captured 1000 recording 100 returned, a valid balanced
-- 1000-unit capture journal and no return operations at all committed cleanly, after which an UPDATE
-- of that same total to 101 was refused by V13 - the guard was live, and blind to the way the row
-- arrived.
--
-- As with V12 and V13 this is a schema-invariant gap, not a demonstrated loss. No API path inserts a
-- payment with a non-zero returned total: PaymentService writes returns and totals together under the
-- payment row lock, and an authorization inserts the column at its default of zero.

-- ---------------------------------------------------------------------------
-- 1. No incompatible write may slip between the check and the protection
-- ---------------------------------------------------------------------------

-- Validating first and installing afterwards leaves a window in which a transaction already in flight
-- commits exactly the row the validation just declared absent. SHARE ROW EXCLUSIVE conflicts with the
-- ROW EXCLUSIVE that INSERT, UPDATE and DELETE take, so writers wait from here until this migration
-- commits, while readers are unaffected. It is also the mode CREATE TRIGGER acquires, so taking it up
-- front is the lock the statement below needs anyway rather than an additional one.
--
-- This requires the migration to run inside a transaction, which is Flyway's default and this
-- project's configuration. Applied by hand in autocommit it fails immediately with "LOCK TABLE can
-- only be used in transaction blocks" rather than silently running unprotected, which is the right
-- way round.
LOCK TABLE payments, payment_returns IN SHARE ROW EXCLUSIVE MODE;

-- ---------------------------------------------------------------------------
-- 2. Existing rows, before the stronger invariant is declared
-- ---------------------------------------------------------------------------

-- A database upgraded to V13 can already hold a row inserted through the gap above: V13's own
-- pre-flight ran before this trigger existed, and nothing has checked an insert since. The upgrade
-- reports such rows and stops, exactly as V12 and V13 do. Repairing them here would destroy the only
-- evidence that something went wrong, using code whose output is in doubt.
DO $$
DECLARE offending bigint; sample text;
BEGIN
    SELECT count(*), min(p.id::text) INTO offending, sample
      FROM payments p
      LEFT JOIN (SELECT payment_id, sum(amount_minor) AS total
                   FROM payment_returns GROUP BY payment_id) r ON r.payment_id = p.id
     WHERE p.returned_amount_minor <> coalesce(r.total, 0);
    IF offending > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('Cannot enforce returned-total equality on insert: %s payment(s) record a returned total that disagrees with their return operations.', offending),
            DETAIL  = format('For example payment %s. Reconciliation reports these as RETURN_TOTAL_MISMATCH, with the expected and recorded amounts. A row like this can predate V13 or have been inserted directly after it, because until this migration no check ran when a payment was inserted.', sample),
            HINT    = 'Investigate those records before upgrading. This migration deliberately does not adjust totals, delete return operations or otherwise rewrite financial history.';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 3. The same rule, reached from the remaining side
-- ---------------------------------------------------------------------------

-- enforce_returned_total() is reused unchanged, so insert, update and return insertion are one rule
-- with one message rather than three implementations that could drift apart. It locks the payment
-- before summing, which is what makes concurrent writers validate one after another against committed
-- state; for an insert that row is one this transaction just created, so nothing new is contended and
-- the existing payment-then-account lock order is untouched.
--
-- Deferred, like its two siblings, so a transaction that inserts a payment together with the return
-- operations it already carries is judged once on the state that actually commits rather than on the
-- order its statements happened to run in.
--
-- The WHEN clause keeps the ordinary authorization path exactly as it was. A payment inserted with a
-- returned total of zero cannot be inconsistent at the moment it is inserted: a return operation
-- references its payment, so none can exist before the row does. One added later in the same
-- transaction is caught by return_budget_on_insert, which compares the payment's recorded total
-- against the operations - covered by a regression test rather than left as an assumption. What this
-- leaves out is a per-authorization aggregate at every commit on the hottest path in the system,
-- which would also invalidate the published throughput figures for no additional protection.
CREATE CONSTRAINT TRIGGER returned_total_on_payment_insert AFTER INSERT ON payments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.returned_amount_minor IS DISTINCT FROM 0)
    EXECUTE FUNCTION enforce_returned_total_on_payment();

-- The row-level cap (payments_returned_within_capture), the immutability of payment_returns and
-- ledger rows, and the currency agreement added in V12 are all unchanged. This adds the missing
-- entry point to an existing rule; it replaces nothing.
