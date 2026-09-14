-- Checkpoint 9: idempotency records that can hold more than one kind of response, and a projection
-- that can show what a refund event changed.
--
-- Until now every stored idempotent response was a PaymentView, and the decoder simply assumed that.
-- A refund returns a different shape. Decoding a historical authorize response as a refund, or the
-- reverse, would either throw or - worse - succeed against a lenient reader and hand a caller a
-- half-populated object under the impression it was the original result.
--
-- So the kind is recorded rather than inferred. Every row written before this migration is a payment
-- response, which the backfill states explicitly instead of leaving the decoder to guess from the
-- JSON keys present.

ALTER TABLE idempotency_records ADD COLUMN response_kind varchar(32);

UPDATE idempotency_records SET response_kind = 'PAYMENT' WHERE response_body IS NOT NULL;

ALTER TABLE idempotency_records
    -- A kind exists exactly when a response does. A claimed but uncompleted key has neither, which is
    -- the in-flight state the recovery path depends on.
    ADD CONSTRAINT idempotency_kind_with_response CHECK ((response_body IS NULL) = (response_kind IS NULL)),
    ADD CONSTRAINT idempotency_kind_known CHECK (response_kind IS NULL OR response_kind IN ('PAYMENT', 'RETURN'));

-- ---------------------------------------------------------------------------
-- The activity projection
-- ---------------------------------------------------------------------------

-- A refund does not change a payment's status, so last_status alone cannot show that one happened.
-- Without these columns two partial refunds would advance last_sequence and applied_event_count and
-- change nothing else a reader could see, which reads as a projection that ignored two events.
--
-- These are still projection columns, not financial ones. The authoritative returned total lives on
-- payments and is reconciled against payment_returns and the ledger; this is the read model catching
-- up to it, and it is allowed to lag.
ALTER TABLE payment_activity
    ADD COLUMN returned_amount_minor bigint NOT NULL DEFAULT 0 CHECK (returned_amount_minor >= 0),
    ADD COLUMN captured_amount_minor bigint CHECK (captured_amount_minor IS NULL OR captured_amount_minor > 0),
    ADD COLUMN last_return_at timestamptz,
    ADD COLUMN return_event_count integer NOT NULL DEFAULT 0 CHECK (return_event_count >= 0);

-- Historical projection rows describe payments whose events carried no return information at all.
-- They are left at zero rather than backfilled from the payments table: this table is built only
-- from delivered events, and filling it from the authoritative record would make the projection agree
-- with its source by copying rather than by consuming, hiding exactly the delivery gaps it exists to
-- reveal. The reconciliation report reads authoritative tables and never this one.
