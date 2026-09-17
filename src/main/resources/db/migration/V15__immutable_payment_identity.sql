-- A payment's identity is fixed for its lifetime.
--
-- This closes the last way a transaction could leave a payment's recorded returned total disagreeing
-- with its return operations. V14 added a deferred trigger on payment insert, and it is correct, but a
-- deferred trigger captures its NEW row when the statement runs and validates at COMMIT:
--
--   1. INSERT payment A recording 100 returned          -- queues enforce_returned_total(A)
--   2. UPDATE payments SET id = B WHERE id = A          -- V13's trigger has WHEN (returned/captured
--                                                          changed), so this queues nothing
--   3. COMMIT                                           -- enforce_returned_total(A) finds no row,
--                                                          returns early, and B is never examined
--
-- Reproduced on PostgreSQL 16.15 against V1-V14 applied unchanged, and previously by review on 14.19:
-- the transaction committed, leaving a payment recording 100 returned with zero return operations. The
-- same transaction without step 2 was refused at COMMIT, which is what identifies the identity change
-- rather than the amounts as the way through.
--
-- This is a direct-SQL invariant defect. No API path reaches it and no money loss was demonstrated:
-- every UPDATE the application issues against payments sets status, captured_amount_minor,
-- returned_amount_minor or updated_at, and none of them names id.
--
-- The fix is the invariant itself rather than a wider trigger. A payment's id is referenced by its
-- journals, returns, outbox events, audit records and stored idempotency responses, and it is what a
-- merchant was told their payment is called; there is no operation in this system for which changing
-- it is the answer. Making it immutable also removes the whole class of "the row moved before the
-- deferred check looked at it" rather than the one instance found.

-- ---------------------------------------------------------------------------
-- 1. No incompatible write between the check and the protection
-- ---------------------------------------------------------------------------

-- As in V14, and for the same reason: validating first and installing afterwards leaves a window in
-- which a transaction already in flight commits exactly what the validation just declared absent.
-- SHARE ROW EXCLUSIVE conflicts with the ROW EXCLUSIVE that writes take and leaves readers alone.
-- Requires a transaction, which is Flyway's default and this project's configuration; applied by hand
-- in autocommit it fails loudly rather than running unprotected.
LOCK TABLE payments, payment_returns IN SHARE ROW EXCLUSIVE MODE;

-- ---------------------------------------------------------------------------
-- 2. Rows a V14 database may already hold
-- ---------------------------------------------------------------------------

-- A database already on V14 can contain the row the sequence above produces, because until now nothing
-- stopped it. That residue is a returned total disagreeing with its return operations, so it is the
-- same check V14 makes, run again here against the rows as they stand.
--
-- It is not possible to detect that an identity was changed in the past: the old value is gone and
-- nothing recorded it. What can be detected is the inconsistency such a change was used to smuggle in,
-- and that is what this reports. Nothing here repairs a total, invents a return operation, deletes a
-- record or renames a payment.
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
            MESSAGE = format('Cannot make payment identity immutable: %s payment(s) already record a returned total that disagrees with their return operations.', offending),
            DETAIL  = format('For example payment %s. Reconciliation reports these as RETURN_TOTAL_MISMATCH, with the expected and recorded amounts. A row like this can predate V13 or V14, or have been written by changing a payment''s id inside the transaction that inserted it, which is what this migration stops.', sample),
            HINT    = 'Investigate those records before upgrading. This migration deliberately does not adjust totals, invent return operations, delete records or rename payments.';
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 3. The invariant
-- ---------------------------------------------------------------------------

-- Immediate rather than deferred, and deliberately so. A deferred check asks "is the final state
-- consistent", which is the right question for a total that legitimately changes during a transaction.
-- An identity change has no legitimate intermediate form, so the statement that attempts it is the
-- thing to refuse, at the point it is attempted, where the error names what was tried.
CREATE FUNCTION reject_payment_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'A payment''s identity is immutable: % cannot become %. Journals, returns, events, audit records and stored idempotency responses all name it.',
        OLD.id, NEW.id USING ERRCODE = '23514';
END $$;

-- UPDATE OF id so the trigger is not even considered for the updates the application actually makes,
-- and the WHEN clause so an UPDATE that merely mentions id while leaving it alone is still allowed.
CREATE TRIGGER immutable_payment_identity BEFORE UPDATE OF id ON payments
    FOR EACH ROW
    WHEN (OLD.id IS DISTINCT FROM NEW.id)
    EXECUTE FUNCTION reject_payment_identity_change();

-- Deliberately not extended to DELETE. Payments are never deleted by this application, but a deletion
-- removes the row and its dependants rather than leaving a misattributed one, and the foreign keys
-- from journals, returns and audit records already refuse it while that evidence exists. Refusing
-- DELETE here as well would be a different decision from this one, and it is not the defect found.
