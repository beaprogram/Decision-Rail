-- Checkpoint 9: refunds, post-capture reversal, and the compensating journals that record them.
--
-- Two constraints from earlier milestones stand directly in the way, and both are here on purpose:
--
--   1. V1 declared ledger_journals.payment_id UNIQUE, which permitted exactly one journal per
--      payment. That was right when capture was the only money movement. A return is a second one.
--   2. V2 made enforce_balanced_journal() require a full capture journal matching the payment's
--      amount, currency and CAPTURED status, and sealed entries after the creating transaction.
--
-- Neither is weakened into "any balanced pair is acceptable". A balanced journal can still record the
-- wrong amount, the wrong direction, the wrong account or an operation that does not exist, and each
-- of those is a way to lose money while every debit still equals a credit. What replaces them is a
-- per-kind rule: a payment keeps exactly one CAPTURE journal, and every RETURN journal must match an
-- existing return operation on amount, currency, merchant, payment, and the two ledger accounts in
-- the correct direction.
--
-- Returns do not change a payment's status. The capture happened; it stays a historical fact, its
-- journal stays sealed and unmodified, and what a return changes is the payment's returned total and
-- the account balance. That also keeps V2's "payment must be CAPTURED" rule true for the capture
-- journal for the whole life of the payment, rather than making an old sealed journal retroactively
-- invalid the moment a refund lands.

-- ---------------------------------------------------------------------------
-- 1. What a payment now records about money returned
-- ---------------------------------------------------------------------------

ALTER TABLE payments
    -- What was actually captured, recorded separately from what was authorized. Today capture is
    -- always the full authorized amount, so the backfill below is exact; keeping it as its own column
    -- means the return budget is capped by what moved, not by what was once reserved.
    ADD COLUMN captured_amount_minor bigint,
    ADD COLUMN returned_amount_minor bigint NOT NULL DEFAULT 0;

UPDATE payments SET captured_amount_minor = amount_minor WHERE status = 'CAPTURED';

ALTER TABLE payments
    ADD CONSTRAINT payments_capture_amount_present
        CHECK ((status = 'CAPTURED') = (captured_amount_minor IS NOT NULL)),
    ADD CONSTRAINT payments_capture_amount_bounded
        CHECK (captured_amount_minor IS NULL
               OR (captured_amount_minor > 0 AND captured_amount_minor <= amount_minor)),
    -- The cap, enforced on the row itself. Concurrent returns serialise on the payment row lock that
    -- the service takes before reading the remaining budget; this is what makes a code path that
    -- forgot the lock fail loudly instead of over-crediting.
    ADD CONSTRAINT payments_returned_within_capture
        CHECK (returned_amount_minor >= 0
               AND returned_amount_minor <= coalesce(captured_amount_minor, 0));

-- Lets a return row prove, declaratively, that it belongs to the same account and currency as its
-- payment. Without it that agreement would be something the application asserts about itself.
ALTER TABLE payments ADD CONSTRAINT payments_identity_unique UNIQUE (id, account_id, currency);

-- ---------------------------------------------------------------------------
-- 2. Return operations
-- ---------------------------------------------------------------------------

-- REFUND    a merchant returning captured funds, in full or in part.
-- REVERSAL  the capture itself being undone. All or nothing, and only while nothing has been
--           returned yet; see docs/adr/0007 for why it is refused after a partial refund rather than
--           silently downgraded to a refund of the remainder.
CREATE TABLE payment_returns (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL,
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    account_id uuid NOT NULL,
    return_type varchar(16) NOT NULL CHECK (return_type IN ('REFUND', 'REVERSAL')),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 1000000000000),
    currency char(3) NOT NULL CHECK (currency IN ('CAD', 'USD')),
    reason varchar(140),
    -- Dense per-payment ordering of returns, assigned inside the financial transaction while the
    -- payment row is held. Distinct from the outbox aggregate sequence, which orders every event.
    sequence_number integer NOT NULL CHECK (sequence_number > 0),
    created_at timestamptz NOT NULL,
    -- Ownership is structural: a return cannot name a payment belonging to another merchant.
    FOREIGN KEY (payment_id, merchant_id) REFERENCES payments(id, merchant_id),
    -- And cannot name a different account or currency than the payment it returns.
    FOREIGN KEY (payment_id, account_id, currency) REFERENCES payments(id, account_id, currency),
    UNIQUE (payment_id, sequence_number),
    UNIQUE (id, merchant_id)
);
CREATE INDEX payment_returns_payment_idx ON payment_returns (payment_id, sequence_number);
CREATE INDEX payment_returns_account_idx ON payment_returns (account_id, created_at);

-- A completed return is evidence, like a journal. Correcting one means another return, not an edit.
CREATE TRIGGER immutable_returns BEFORE UPDATE OR DELETE ON payment_returns
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

-- The budget, checked against the return rows themselves rather than only the denormalised total.
-- It asserts two separate things, and the second is the one that matters for reconciliation: the
-- payment's returned_amount_minor must equal the sum of its return operations. A column and the rows
-- it summarises can only disagree if something wrote one without the other, which is exactly the
-- corruption an independent check has to be able to see.
CREATE FUNCTION enforce_return_budget() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE operations_total numeric; captured bigint; recorded bigint; payment_status varchar(16);
BEGIN
    SELECT coalesce(sum(amount_minor::numeric), 0) INTO operations_total
      FROM payment_returns WHERE payment_id = NEW.payment_id;
    SELECT p.captured_amount_minor, p.returned_amount_minor, p.status
      INTO captured, recorded, payment_status
      FROM payments p WHERE p.id = NEW.payment_id;
    IF captured IS NULL OR payment_status <> 'CAPTURED' THEN
        RAISE EXCEPTION 'Only a captured payment can be returned' USING ERRCODE = '23514';
    END IF;
    IF operations_total > captured THEN
        RAISE EXCEPTION 'Returns for payment % total % minor units, above the captured %',
            NEW.payment_id, operations_total, captured USING ERRCODE = '23514';
    END IF;
    IF recorded IS DISTINCT FROM operations_total::bigint THEN
        RAISE EXCEPTION 'Payment % records % returned but its return operations total %',
            NEW.payment_id, recorded, operations_total USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;

-- Deferred so the application may write the return row and the payment total in either order, and so
-- several returns in one transaction are judged on the state that actually commits.
CREATE CONSTRAINT TRIGGER return_budget_on_insert AFTER INSERT ON payment_returns
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_return_budget();

-- ---------------------------------------------------------------------------
-- 3. Journals gain a kind and a link to the operation they record
-- ---------------------------------------------------------------------------

ALTER TABLE ledger_journals
    ADD COLUMN journal_kind varchar(16) NOT NULL DEFAULT 'CAPTURE'
        CHECK (journal_kind IN ('CAPTURE', 'RETURN')),
    ADD COLUMN source_return_id uuid REFERENCES payment_returns(id),
    ADD CONSTRAINT ledger_journal_source_matches_kind
        CHECK ((journal_kind = 'RETURN') = (source_return_id IS NOT NULL));

-- Every journal that existed before this migration records a capture, and the NOT NULL DEFAULT above
-- is what gives them that value. Deliberately not followed by a backfilling UPDATE: ledger_journals
-- carries an immutability trigger that rejects UPDATE outright, so a redundant restatement of the
-- default would fail the migration on any database that already holds journals.

-- The one-journal-per-payment rule becomes one CAPTURE journal per payment. Dropped by lookup rather
-- than by guessing PostgreSQL's generated constraint name.
DO $$
DECLARE constraint_name text;
BEGIN
    SELECT c.conname INTO constraint_name
      FROM pg_constraint c
      JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
     WHERE c.conrelid = 'ledger_journals'::regclass
       AND c.contype = 'u'
       AND array_length(c.conkey, 1) = 1
       AND a.attname = 'payment_id';
    IF constraint_name IS NULL THEN
        RAISE EXCEPTION 'Expected a single-column unique constraint on ledger_journals.payment_id';
    END IF;
    EXECUTE format('ALTER TABLE ledger_journals DROP CONSTRAINT %I', constraint_name);
END $$;

CREATE UNIQUE INDEX ledger_journals_one_capture_per_payment
    ON ledger_journals (payment_id) WHERE journal_kind = 'CAPTURE';
-- One journal per return operation. NULLs do not conflict, so capture journals are unaffected.
CREATE UNIQUE INDEX ledger_journals_one_per_return ON ledger_journals (source_return_id);
CREATE INDEX ledger_journals_payment_idx ON ledger_journals (payment_id, created_at);

-- ---------------------------------------------------------------------------
-- 4. What a balanced journal is allowed to say
-- ---------------------------------------------------------------------------

-- Replaces V2's capture-only rule with a per-kind one. The checks common to both kinds - exactly two
-- entries, debits equal credits, the journal's currency and merchant equal the payment's - are kept,
-- and each kind adds the amount it must equal and the two ledger accounts it must name, in the
-- direction that kind moves money. Accounts are checked because a balanced pair naming the wrong
-- account moves real value to the wrong place while passing every arithmetic test.
CREATE OR REPLACE FUNCTION enforce_balanced_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target_id uuid;
    kind varchar(16); journal_currency char(3); journal_merchant varchar(64);
    journal_payment uuid; source_return uuid;
    debit_total numeric; credit_total numeric; entry_count integer;
    debit_account varchar(128); credit_account varchar(128);
    payment_account uuid; payment_merchant varchar(64); payment_currency char(3);
    payment_status varchar(16); captured bigint;
    expected_amount bigint; expected_debit varchar(128); expected_credit varchar(128);
    return_payment uuid; return_currency char(3); return_merchant varchar(64); return_account uuid;
BEGIN
    IF TG_TABLE_NAME = 'ledger_journals' THEN target_id := NEW.id;
    ELSE target_id := NEW.journal_id; END IF;

    SELECT j.journal_kind, j.currency, j.merchant_id, j.payment_id, j.source_return_id
      INTO kind, journal_currency, journal_merchant, journal_payment, source_return
      FROM ledger_journals j WHERE j.id = target_id;
    IF kind IS NULL THEN
        RAISE EXCEPTION 'Ledger entry references a journal that does not exist' USING ERRCODE = '23514';
    END IF;

    SELECT coalesce(sum(amount_minor::numeric) FILTER (WHERE side = 'DEBIT'), 0),
           coalesce(sum(amount_minor::numeric) FILTER (WHERE side = 'CREDIT'), 0),
           count(*),
           max(ledger_account) FILTER (WHERE side = 'DEBIT'),
           max(ledger_account) FILTER (WHERE side = 'CREDIT')
      INTO debit_total, credit_total, entry_count, debit_account, credit_account
      FROM ledger_entries WHERE journal_id = target_id;

    SELECT p.account_id, p.merchant_id, p.currency, p.status, p.captured_amount_minor
      INTO payment_account, payment_merchant, payment_currency, payment_status, captured
      FROM payments p WHERE p.id = journal_payment;

    IF kind = 'CAPTURE' THEN
        IF payment_status <> 'CAPTURED' OR captured IS NULL THEN
            RAISE EXCEPTION 'A capture journal requires a captured payment' USING ERRCODE = '23514';
        END IF;
        expected_amount := captured;
        expected_debit := 'wallet:' || payment_account::text;
        expected_credit := 'merchant-clearing:' || payment_merchant;
    ELSE
        SELECT r.payment_id, r.currency, r.merchant_id, r.account_id, r.amount_minor
          INTO return_payment, return_currency, return_merchant, return_account, expected_amount
          FROM payment_returns r WHERE r.id = source_return;
        IF expected_amount IS NULL THEN
            RAISE EXCEPTION 'A return journal must reference an existing return operation'
                USING ERRCODE = '23514';
        END IF;
        IF return_payment IS DISTINCT FROM journal_payment
           OR return_merchant IS DISTINCT FROM journal_merchant
           OR return_currency IS DISTINCT FROM journal_currency
           OR return_account IS DISTINCT FROM payment_account THEN
            RAISE EXCEPTION 'A return journal must match the return operation it records'
                USING ERRCODE = '23514';
        END IF;
        -- The reverse of a capture: value leaves merchant clearing and returns to the wallet.
        expected_debit := 'merchant-clearing:' || payment_merchant;
        expected_credit := 'wallet:' || payment_account::text;
    END IF;

    IF entry_count <> 2
       OR debit_total <> credit_total
       OR debit_total <> expected_amount
       OR journal_currency IS DISTINCT FROM payment_currency
       OR journal_merchant IS DISTINCT FROM payment_merchant
       OR debit_account IS DISTINCT FROM expected_debit
       OR credit_account IS DISTINCT FROM expected_credit THEN
        RAISE EXCEPTION
            'Journal % must contain two balanced entries matching its % operation: expected % % debiting % and crediting %',
            target_id, kind, expected_amount, journal_currency, expected_debit, expected_credit
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
