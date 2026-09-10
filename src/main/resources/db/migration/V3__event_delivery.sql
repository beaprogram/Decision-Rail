-- Checkpoint 4: reliable outbox delivery with per-payment ordering and an idempotent consumer.
--
-- V1 created outbox_events as durable intent only: no sequence, no delivery state, no
-- retry bookkeeping. This migration adds the columns a dispatcher needs and backfills
-- rows written before any dispatcher existed.

ALTER TABLE outbox_events
    ADD COLUMN aggregate_sequence bigint,
    ADD COLUMN aggregate_type varchar(32) NOT NULL DEFAULT 'payment',
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN last_error varchar(500),
    ADD COLUMN last_attempt_at timestamptz,
    ADD COLUMN lease_owner varchar(64),
    ADD COLUMN lease_token uuid,
    ADD COLUMN lease_expires_at timestamptz,
    ADD COLUMN partition_key varchar(128),
    ADD COLUMN broker_partition integer,
    ADD COLUMN broker_offset bigint;

-- Backfill order for pre-existing rows. No delivery had happened yet, so lifecycle
-- order is reconstructed from (occurred_at, id). occurred_at is the payment's
-- updated_at at write time, and id is a stable deterministic tie-break when two
-- events share a microsecond. This approximation applies ONLY to rows that predate
-- this migration; every new row is assigned its sequence while the transaction holds
-- the payment row, so the live path does not depend on timestamp resolution.
WITH ordered AS (
    SELECT id, row_number() OVER (PARTITION BY aggregate_id ORDER BY occurred_at, id) AS sequence_number
    FROM outbox_events
)
UPDATE outbox_events target
SET aggregate_sequence = ordered.sequence_number
FROM ordered
WHERE target.id = ordered.id;

UPDATE outbox_events SET status = 'PUBLISHED' WHERE published_at IS NOT NULL;
UPDATE outbox_events SET partition_key = aggregate_id::text WHERE partition_key IS NULL;

-- Pre-existing payloads were written before the envelope carried routing identity.
-- Backfilling them from authoritative columns keeps every committed event deliverable
-- and contract-valid instead of stranding history in the consumer's quarantine.
UPDATE outbox_events
SET payload = payload || jsonb_build_object(
        'aggregateId', aggregate_id,
        'aggregateType', aggregate_type,
        'aggregateSequence', aggregate_sequence,
        'merchantId', merchant_id,
        -- Written in the same ISO-8601 UTC shape the application serializer emits.
        'recordedAt', to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
WHERE NOT (payload ? 'aggregateSequence');

ALTER TABLE outbox_events
    ALTER COLUMN aggregate_sequence SET NOT NULL,
    ALTER COLUMN partition_key SET NOT NULL,
    ADD CONSTRAINT outbox_sequence_positive CHECK (aggregate_sequence > 0),
    ADD CONSTRAINT outbox_status_known CHECK (status IN ('PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED')),
    ADD CONSTRAINT outbox_published_has_timestamp CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
    ADD CONSTRAINT outbox_attempts_bounded CHECK (attempts >= 0 AND attempts <= 10000),
    ADD CONSTRAINT outbox_lease_is_whole CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)),
    ADD CONSTRAINT outbox_claimed_needs_lease CHECK (status <> 'CLAIMED' OR lease_token IS NOT NULL),
    ADD CONSTRAINT outbox_aggregate_sequence_unique UNIQUE (aggregate_id, aggregate_sequence);

DROP INDEX outbox_pending_idx;
CREATE INDEX outbox_claimable_idx ON outbox_events (next_attempt_at, aggregate_sequence) WHERE status = 'PENDING';
-- Supports the "no earlier unfinished event for this payment" predicate.
CREATE INDEX outbox_unfinished_idx ON outbox_events (aggregate_id, aggregate_sequence) WHERE status <> 'PUBLISHED';
CREATE INDEX outbox_expired_lease_idx ON outbox_events (lease_expires_at) WHERE status = 'CLAIMED';

-- Delivery evidence and event identity are not rewritable. A published row is terminal;
-- retry, redrive, and lease changes may only touch rows that are not yet published.
CREATE FUNCTION reject_outbox_rewrite() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'PUBLISHED' THEN
        RAISE EXCEPTION 'A published outbox event is immutable delivery evidence' USING ERRCODE = '23514';
    END IF;
    IF NEW.id <> OLD.id OR NEW.aggregate_id <> OLD.aggregate_id
       OR NEW.aggregate_sequence <> OLD.aggregate_sequence OR NEW.merchant_id <> OLD.merchant_id
       OR NEW.event_type <> OLD.event_type OR NEW.schema_version <> OLD.schema_version
       OR NEW.payload::text <> OLD.payload::text THEN
        RAISE EXCEPTION 'Outbox event identity and payload are immutable across retries' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER outbox_identity_immutable BEFORE UPDATE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION reject_outbox_rewrite();

-- Consumer deduplication record. One row per (consumer group, event identity).
CREATE TABLE consumed_events (
    consumer_group varchar(64) NOT NULL,
    event_id uuid NOT NULL,
    aggregate_id uuid NOT NULL,
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    aggregate_sequence bigint NOT NULL CHECK (aggregate_sequence > 0),
    event_type varchar(64) NOT NULL,
    payload_fingerprint char(64) NOT NULL,
    topic varchar(128) NOT NULL,
    kafka_partition integer NOT NULL,
    kafka_offset bigint NOT NULL,
    consumed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
CREATE INDEX consumed_events_aggregate_idx ON consumed_events (consumer_group, aggregate_id, aggregate_sequence);

-- Merchant-scoped read model. It deliberately holds no balance, hold, or ledger amount:
-- the authoritative financial record stays in accounts, payments, and ledger_entries.
CREATE TABLE payment_activity (
    payment_id uuid PRIMARY KEY,
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    account_id uuid NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency char(3) NOT NULL CHECK (currency IN ('CAD', 'USD')),
    country char(2) NOT NULL,
    last_status varchar(16) NOT NULL,
    last_event_type varchar(64) NOT NULL,
    last_sequence bigint NOT NULL CHECK (last_sequence > 0),
    risk_outcome varchar(16) NOT NULL CHECK (risk_outcome IN ('APPROVE', 'REVIEW', 'DECLINE')),
    risk_score integer NOT NULL CHECK (risk_score BETWEEN 0 AND 100),
    policy_version varchar(64) NOT NULL,
    failure_code varchar(64),
    applied_event_count integer NOT NULL DEFAULT 1 CHECK (applied_event_count > 0),
    first_event_at timestamptz NOT NULL,
    last_event_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_activity_merchant_idx ON payment_activity (merchant_id, last_event_at DESC);

-- Events the consumer refuses to apply, kept for operator inspection instead of
-- being dropped silently or blocking the partition forever.
CREATE TABLE consumer_quarantine (
    id uuid PRIMARY KEY,
    consumer_group varchar(64) NOT NULL,
    reason varchar(32) NOT NULL CHECK (reason IN (
        'MALFORMED', 'UNSUPPORTED_SCHEMA', 'UNSUPPORTED_TYPE', 'IDENTITY_CONFLICT', 'UNKNOWN_MERCHANT')),
    event_id uuid,
    topic varchar(128) NOT NULL,
    kafka_partition integer NOT NULL,
    kafka_offset bigint NOT NULL,
    detail varchar(500) NOT NULL,
    quarantined_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (consumer_group, topic, kafka_partition, kafka_offset)
);
CREATE INDEX consumer_quarantine_recent_idx ON consumer_quarantine (quarantined_at DESC);
