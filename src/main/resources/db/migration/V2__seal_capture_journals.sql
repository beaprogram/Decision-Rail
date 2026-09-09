-- Entry inserts are allowed only in the transaction that creates the journal.
-- This makes an already committed journal immutable even to additional balanced entries.
ALTER TABLE ledger_journals ADD COLUMN created_transaction_id bigint NOT NULL DEFAULT txid_current();
CREATE FUNCTION reject_late_ledger_entry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE creator bigint;
BEGIN
    SELECT created_transaction_id INTO creator FROM ledger_journals WHERE id = NEW.journal_id;
    IF creator IS DISTINCT FROM txid_current() THEN
        RAISE EXCEPTION 'Ledger journal is sealed after its creating transaction' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER sealed_journal_entries BEFORE INSERT ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_late_ledger_entry();

CREATE OR REPLACE FUNCTION enforce_balanced_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_id uuid; debit_total numeric; credit_total numeric; entry_count integer;
        expected_amount bigint; expected_currency char(3); journal_currency char(3); payment_status varchar(16);
BEGIN
    IF TG_TABLE_NAME = 'ledger_journals' THEN target_id := NEW.id;
    ELSE target_id := NEW.journal_id; END IF;
    SELECT COALESCE(SUM(amount_minor::numeric) FILTER (WHERE side='DEBIT'),0),
           COALESCE(SUM(amount_minor::numeric) FILTER (WHERE side='CREDIT'),0), COUNT(*)
      INTO debit_total, credit_total, entry_count FROM ledger_entries WHERE journal_id=target_id;
    SELECT p.amount_minor,p.currency,p.status,j.currency INTO expected_amount,expected_currency,payment_status,journal_currency
      FROM ledger_journals j JOIN payments p ON p.id=j.payment_id WHERE j.id=target_id;
    IF entry_count <> 2 OR debit_total <> credit_total OR debit_total <> expected_amount
       OR expected_currency <> journal_currency OR payment_status <> 'CAPTURED' THEN
        RAISE EXCEPTION 'Capture journal must contain two balanced entries matching its captured payment' USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END $$;
