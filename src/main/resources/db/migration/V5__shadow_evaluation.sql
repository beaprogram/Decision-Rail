-- Checkpoint 5, part 2: asynchronous shadow evaluation of a pinned candidate policy.
--
-- Shadow evaluation observes authorizations and records what a candidate policy would have
-- decided. It is strictly read-only with respect to money: nothing in this schema holds a
-- balance, a hold, a ledger amount, or a payment status it could write back.

-- Exactly one row. A single authoritative switch is easier to reason about than per-merchant
-- toggles, and this phase has one candidate at a time by design.
CREATE TABLE shadow_settings (
    id integer PRIMARY KEY CHECK (id = 1),
    enabled boolean NOT NULL,
    candidate_version varchar(64) REFERENCES policy_versions(version_id),
    updated_by varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- Enabling without a candidate would silently evaluate nothing.
    CHECK (NOT enabled OR candidate_version IS NOT NULL)
);
INSERT INTO shadow_settings (id, enabled, candidate_version, updated_by) VALUES (1, false, NULL, 'migration');

-- Durable work derived from delivered authorization events, not an in-process callback. The
-- event id is the primary key, so a redelivered event, a payment retry, and a restarted consumer
-- all converge on one task. The candidate version is pinned at enqueue time: changing the
-- setting later does not retroactively change what an already queued task evaluates.
CREATE TABLE shadow_tasks (
    event_id uuid PRIMARY KEY,
    payment_id uuid NOT NULL,
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    aggregate_sequence bigint NOT NULL CHECK (aggregate_sequence > 0),
    candidate_version varchar(64) NOT NULL REFERENCES policy_versions(version_id),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    currency char(3) NOT NULL CHECK (currency IN ('CAD', 'USD')),
    country char(2) NOT NULL,
    baseline_outcome varchar(16) NOT NULL CHECK (baseline_outcome IN ('APPROVE', 'REVIEW', 'DECLINE')),
    baseline_score integer NOT NULL CHECK (baseline_score BETWEEN 0 AND 100),
    baseline_policy_version varchar(64) NOT NULL,
    baseline_reasons jsonb NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'CLAIMED', 'DONE', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0 AND attempts <= 1000),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_owner varchar(64),
    lease_token uuid,
    lease_expires_at timestamptz,
    last_error varchar(500),
    enqueued_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK (state <> 'CLAIMED' OR lease_token IS NOT NULL)
);
CREATE INDEX shadow_tasks_claimable_idx ON shadow_tasks (next_attempt_at) WHERE state = 'PENDING';
CREATE INDEX shadow_tasks_expired_lease_idx ON shadow_tasks (lease_expires_at) WHERE state = 'CLAIMED';
CREATE INDEX shadow_tasks_payment_idx ON shadow_tasks (payment_id);

-- One comparison per payment per candidate. This primary key is what makes repeated deliveries
-- and payment retries produce one comparison rather than several, and it is deliberately keyed
-- on the candidate so a later candidate can be compared without erasing an earlier one.
--
-- Kept separate from replay_results on purpose: a shadow comparison is a live observation of one
-- authorization, while a replay result belongs to a bounded historical job with a fixed
-- membership snapshot and a divergence denominator. Merging them would make a report unable to
-- say which population it described.
CREATE TABLE shadow_comparisons (
    candidate_version varchar(64) NOT NULL REFERENCES policy_versions(version_id),
    payment_id uuid NOT NULL,
    merchant_id varchar(64) NOT NULL REFERENCES merchants(id),
    source_event_id uuid NOT NULL,
    baseline_outcome varchar(16) NOT NULL CHECK (baseline_outcome IN ('APPROVE', 'REVIEW', 'DECLINE')),
    baseline_score integer NOT NULL CHECK (baseline_score BETWEEN 0 AND 100),
    baseline_reasons jsonb NOT NULL,
    candidate_outcome varchar(16) CHECK (candidate_outcome IN ('APPROVE', 'REVIEW', 'DECLINE')),
    candidate_score integer CHECK (candidate_score BETWEEN 0 AND 100),
    candidate_raw_score integer CHECK (candidate_raw_score >= 0),
    candidate_score_capped boolean NOT NULL DEFAULT false,
    candidate_reasons jsonb,
    diverged boolean NOT NULL,
    evaluation_nanos bigint NOT NULL DEFAULT 0 CHECK (evaluation_nanos >= 0),
    error_code varchar(64),
    evaluated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (candidate_version, payment_id),
    CHECK ((error_code IS NULL) = (candidate_outcome IS NOT NULL))
);
CREATE INDEX shadow_comparisons_merchant_idx ON shadow_comparisons (merchant_id, evaluated_at DESC);
CREATE INDEX shadow_comparisons_divergence_idx ON shadow_comparisons (candidate_version, diverged);
