-- A payment's recorded returned total must equal the sum of its committed return operations.
--
-- The equality was already checked, but only when a return was inserted, because that is the event the
-- constraint trigger was attached to. An update touching only the payment was not checked against the
-- return rows at all, so within the row-level cap the total could be moved to a value its operations
-- did not sum to. Documentation claimed the universal version; this makes the claim true instead.
--
-- Hardening, not an observed loss: no service path performs such an update. PaymentService writes the
-- return and the total together in one transaction, under the payment row lock.

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
            MESSAGE = format('Cannot enforce returned-total equality: %s payment(s) record a returned total that disagrees with their return operations.', offending),
            DETAIL  = format('For example payment %s. Reconciliation reports these as RETURN_TOTAL_MISMATCH, with the expected and recorded amounts.', sample),
            HINT    = 'Investigate those records before upgrading. This migration deliberately does not adjust totals, delete return operations or otherwise rewrite financial history.';
    END IF;
END $$;

-- One rule, reachable from either side that can break it.
--
-- The payment row is locked before the sum is read. Two transactions changing the same payment would
-- otherwise each be able to read a view that looks consistent while their combined effect is not; the
-- lock makes them validate one after the other against committed state. In ordinary operation the
-- transaction already holds this lock - PaymentService takes the payment row, then the account, and
-- this re-acquisition is a no-op that introduces no new lock order.
CREATE FUNCTION enforce_returned_total(target uuid) RETURNS void LANGUAGE plpgsql AS $$
DECLARE operations_total numeric; captured bigint; recorded bigint; payment_status varchar(16);
BEGIN
    SELECT p.captured_amount_minor, p.returned_amount_minor, p.status
      INTO captured, recorded, payment_status
      FROM payments p WHERE p.id = target FOR UPDATE;
    IF NOT FOUND THEN
        -- The payment no longer exists in this transaction's view. There is nothing left to be
        -- inconsistent with, and payments are never deleted in normal operation.
        RETURN;
    END IF;

    SELECT coalesce(sum(amount_minor::numeric), 0) INTO operations_total
      FROM payment_returns WHERE payment_id = target;

    IF operations_total > 0 AND (captured IS NULL OR payment_status <> 'CAPTURED') THEN
        RAISE EXCEPTION 'Only a captured payment can be returned' USING ERRCODE = '23514';
    END IF;
    IF operations_total > coalesce(captured, 0) THEN
        RAISE EXCEPTION 'Returns for payment % total % minor units, above the captured %',
            target, operations_total, coalesce(captured, 0) USING ERRCODE = '23514';
    END IF;
    IF recorded IS DISTINCT FROM operations_total::bigint THEN
        RAISE EXCEPTION 'Payment % records % returned but its return operations total %',
            target, recorded, operations_total USING ERRCODE = '23514';
    END IF;
END $$;

-- The existing trigger keeps its name and its deferred timing; only its body moves into the shared
-- rule above, so a return and the payment total it produces are still judged on the state that
-- actually commits rather than on the order the statements happened to run in.
CREATE OR REPLACE FUNCTION enforce_return_budget() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM enforce_returned_total(NEW.payment_id);
    RETURN NULL;
END $$;

CREATE FUNCTION enforce_returned_total_on_payment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM enforce_returned_total(NEW.id);
    RETURN NULL;
END $$;

-- Deferred, so a refund that writes its return operation and the payment's new total in one
-- transaction is validated once, on the final state, rather than rejected half way through whichever
-- statement happens to run first.
CREATE CONSTRAINT TRIGGER returned_total_on_payment_change AFTER UPDATE ON payments
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (OLD.returned_amount_minor IS DISTINCT FROM NEW.returned_amount_minor
          OR OLD.captured_amount_minor IS DISTINCT FROM NEW.captured_amount_minor)
    EXECUTE FUNCTION enforce_returned_total_on_payment();

-- The row-level cap (payments_returned_within_capture) and the immutability of payment_returns are
-- unchanged. This adds agreement; it does not replace either of them.
