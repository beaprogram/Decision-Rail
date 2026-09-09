CREATE TABLE merchants (
    id varchar(64) PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO merchants (id) VALUES ('demo-merchant'), ('other-merchant');

CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    currency char(3) NOT NULL CHECK (currency IN ('CAD', 'USD')),
    opening_balance_minor bigint NOT NULL CHECK (opening_balance_minor >= 0),
    balance_minor bigint NOT NULL CHECK (balance_minor >= 0),
    held_minor bigint NOT NULL DEFAULT 0 CHECK (held_minor >= 0 AND held_minor <= balance_minor),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, merchant_id)
);

CREATE TABLE payments (
    id uuid PRIMARY KEY,
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    account_id uuid NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0 AND amount_minor <= 1000000000000),
    currency char(3) NOT NULL CHECK (currency IN ('CAD', 'USD')),
    country char(2) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('AUTHORIZED', 'CAPTURED', 'VOIDED', 'DECLINED', 'REVIEW')),
    decision jsonb NOT NULL,
    failure_code varchar(64),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (account_id, merchant_id) REFERENCES accounts(id, merchant_id),
    UNIQUE (id, merchant_id)
);
CREATE INDEX payments_account_status_idx ON payments(account_id, status);

CREATE TABLE idempotency_records (
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    response_body jsonb,
    http_status integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, idempotency_key),
    CHECK ((response_body IS NULL) = (http_status IS NULL))
);

CREATE TABLE ledger_journals (
    id uuid PRIMARY KEY,
    payment_id uuid NOT NULL UNIQUE,
    merchant_id varchar(64) NOT NULL,
    currency char(3) NOT NULL CHECK (currency IN ('CAD', 'USD')),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (payment_id, merchant_id) REFERENCES payments(id, merchant_id)
);
CREATE TABLE ledger_entries (
    id uuid PRIMARY KEY,
    journal_id uuid NOT NULL REFERENCES ledger_journals(id),
    ledger_account varchar(128) NOT NULL,
    side varchar(6) NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0)
);
CREATE INDEX ledger_entries_journal_idx ON ledger_entries(journal_id);

-- Every journal must have at least one debit and credit and balance at COMMIT.
-- Numeric accumulation avoids bigint overflow while summing entries.
CREATE FUNCTION enforce_balanced_journal() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_id uuid; net numeric; entry_count integer;
BEGIN
    IF TG_TABLE_NAME = 'ledger_journals' THEN target_id := NEW.id;
    ELSE target_id := NEW.journal_id; END IF;
    SELECT COALESCE(SUM(CASE WHEN side = 'DEBIT' THEN amount_minor::numeric ELSE -amount_minor::numeric END), 0), COUNT(*)
      INTO net, entry_count FROM ledger_entries WHERE journal_id = target_id;
    IF entry_count < 2 OR net <> 0 THEN
        RAISE EXCEPTION 'Ledger journal must contain balanced debit and credit entries' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER journal_balance_on_create AFTER INSERT ON ledger_journals
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_balanced_journal();
CREATE CONSTRAINT TRIGGER journal_balance_on_entry AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION enforce_balanced_journal();

CREATE FUNCTION reject_ledger_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Ledger history is append-only; use a new correcting journal' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER immutable_journals BEFORE UPDATE OR DELETE ON ledger_journals
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER immutable_entries BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    aggregate_id uuid NOT NULL,
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    event_type varchar(64) NOT NULL,
    schema_version integer NOT NULL DEFAULT 1,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox_events(occurred_at) WHERE published_at IS NULL;

CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    payment_id uuid NOT NULL REFERENCES payments(id),
    action varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
