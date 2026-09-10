-- Checkpoint 5, part 1: immutable policy versions and durable historical replay jobs.

-- A policy version is content-addressed evidence. version_id is the identity and the primary
-- key; definition_hash is the canonical content. Together they make rebinding detectable: an
-- attempt to reuse a version id with different content collides on the primary key, and the
-- service compares hashes to tell an idempotent retry from a genuine conflict.
CREATE TABLE policy_versions (
    version_id varchar(64) PRIMARY KEY,
    definition jsonb NOT NULL,
    definition_hash char(64) NOT NULL,
    origin varchar(16) NOT NULL CHECK (origin IN ('BUILTIN', 'CANDIDATE')),
    rule_count integer NOT NULL CHECK (rule_count BETWEEN 1 AND 32),
    created_by varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (version_id ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$')
);
CREATE INDEX policy_versions_hash_idx ON policy_versions (definition_hash);

-- Immutable by construction. A historical decision names its policy version, so allowing an
-- update here would silently rewrite the meaning of decisions already made.
CREATE FUNCTION reject_policy_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'A policy version is immutable; create a new version instead' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER policy_versions_immutable BEFORE UPDATE OR DELETE ON policy_versions
    FOR EACH ROW EXECUTE FUNCTION reject_policy_mutation();

-- A replay job pins its candidate version and records progress durably so an interrupted run
-- resumes instead of restarting. Count columns are recomputed from replay_results rather than
-- incremented, so resuming cannot inflate a total.
CREATE TABLE replay_jobs (
    id uuid PRIMARY KEY,
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    candidate_version varchar(64) NOT NULL REFERENCES policy_versions(version_id),
    candidate_hash char(64) NOT NULL,
    -- Comparison is always against the risk decision stored with the original payment, never
    -- against the payment's lifecycle status and never against a re-evaluation of the baseline.
    baseline_source varchar(32) NOT NULL DEFAULT 'STORED_RISK_DECISION'
        CHECK (baseline_source = 'STORED_RISK_DECISION'),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    requested_limit integer NOT NULL CHECK (requested_limit BETWEEN 1 AND 5000),
    input_count integer NOT NULL CHECK (input_count >= 0),
    window_from timestamptz,
    window_to timestamptz NOT NULL,
    completed_count integer NOT NULL DEFAULT 0 CHECK (completed_count >= 0),
    failed_count integer NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    skipped_count integer NOT NULL DEFAULT 0 CHECK (skipped_count >= 0),
    approve_count integer NOT NULL DEFAULT 0 CHECK (approve_count >= 0),
    review_count integer NOT NULL DEFAULT 0 CHECK (review_count >= 0),
    decline_count integer NOT NULL DEFAULT 0 CHECK (decline_count >= 0),
    divergence_count integer NOT NULL DEFAULT 0 CHECK (divergence_count >= 0),
    evaluation_nanos_total bigint NOT NULL DEFAULT 0 CHECK (evaluation_nanos_total >= 0),
    lease_owner varchar(64),
    lease_token uuid,
    lease_expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    failure_detail varchar(500)
);
CREATE INDEX replay_jobs_merchant_idx ON replay_jobs (merchant_id, created_at DESC);
CREATE INDEX replay_jobs_runnable_idx ON replay_jobs (created_at) WHERE status IN ('PENDING', 'RUNNING');

-- Retry safety for job creation. Deliberately separate from idempotency_records: that table's
-- stored response is a payment document, and a replay job is not a payment.
CREATE TABLE replay_job_requests (
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    idempotency_key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    job_id uuid NOT NULL REFERENCES replay_jobs(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, idempotency_key)
);

-- The materialised membership and input snapshot, written in the transaction that creates the
-- job. Membership is a fixed row set, not a timestamp cursor re-queried per batch: a payment
-- committing after job creation cannot join, and late commits cannot change totals partway
-- through. The original inputs and the baseline decision are copied here so evaluation never
-- reads today's account balances, holds, or any other present-day state to reconstruct a
-- historical feature.
CREATE TABLE replay_job_items (
    job_id uuid NOT NULL REFERENCES replay_jobs(id),
    payment_id uuid NOT NULL,
    item_sequence integer NOT NULL CHECK (item_sequence > 0),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency char(3) NOT NULL CHECK (currency IN ('CAD', 'USD')),
    country char(2) NOT NULL,
    baseline_outcome varchar(16) NOT NULL CHECK (baseline_outcome IN ('APPROVE', 'REVIEW', 'DECLINE')),
    baseline_score integer NOT NULL CHECK (baseline_score BETWEEN 0 AND 100),
    baseline_policy_version varchar(64) NOT NULL,
    baseline_reasons jsonb NOT NULL,
    -- Recorded for reporting only. A DECLINED payment whose risk outcome was APPROVE was
    -- declined for insufficient funds, and must never be counted as a policy decline.
    payment_status varchar(16) NOT NULL,
    payment_failure_code varchar(64),
    payment_created_at timestamptz NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'DONE', 'FAILED')),
    PRIMARY KEY (job_id, payment_id),
    UNIQUE (job_id, item_sequence)
);
CREATE INDEX replay_job_items_pending_idx ON replay_job_items (job_id, item_sequence) WHERE state = 'PENDING';

-- One row per payment per job. The primary key is what makes resuming safe: a re-processed item
-- cannot produce a second result, so totals derived from this table stay correct.
CREATE TABLE replay_results (
    job_id uuid NOT NULL REFERENCES replay_jobs(id),
    payment_id uuid NOT NULL,
    baseline_outcome varchar(16) NOT NULL,
    baseline_score integer NOT NULL,
    baseline_reasons jsonb NOT NULL,
    candidate_outcome varchar(16) CHECK (candidate_outcome IN ('APPROVE', 'REVIEW', 'DECLINE')),
    candidate_score integer CHECK (candidate_score BETWEEN 0 AND 100),
    candidate_raw_score integer CHECK (candidate_raw_score >= 0),
    candidate_score_capped boolean NOT NULL DEFAULT false,
    candidate_reasons jsonb,
    diverged boolean NOT NULL,
    evaluation_nanos bigint NOT NULL DEFAULT 0 CHECK (evaluation_nanos >= 0),
    error_code varchar(64),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, payment_id),
    FOREIGN KEY (job_id, payment_id) REFERENCES replay_job_items (job_id, payment_id),
    -- A result either evaluated the candidate or recorded why it could not.
    CHECK ((error_code IS NULL) = (candidate_outcome IS NOT NULL))
);
CREATE INDEX replay_results_divergence_idx ON replay_results (job_id, diverged);

-- A recorded comparison is evidence of what a candidate produced for a pinned input. Resuming
-- a job inserts missing results; it never rewrites one that already exists.
CREATE FUNCTION reject_replay_result_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'A recorded replay result is immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER replay_results_immutable BEFORE UPDATE OR DELETE ON replay_results
    FOR EACH ROW EXECUTE FUNCTION reject_replay_result_mutation();
