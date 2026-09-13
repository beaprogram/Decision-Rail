-- The sampling decision, kept with the trace it belongs to.
--
-- V7 stored a trace and span id and nothing else, and everything downstream then assumed the span had
-- been sampled: the traceparent was emitted with flags 01 and delivery rebuilt its parent with
-- sampled(true). Valid identifiers do not mean a span was recorded. Under a sampling probability below
-- 1 that turned an unsampled request into a sampled publication, which re-samples work the deployment
-- had already decided not to record, and produces traces whose first span does not exist.
--
-- Nullable, because rows written by V7 carry no decision. Those are treated as unsampled rather than
-- assumed sampled: an absent decision is not a decision to record, and the alternative would recreate
-- exactly the behaviour this migration exists to stop. A row written before correlation existed has no
-- trace at all and keeps starting its own.

ALTER TABLE outbox_events
    ADD COLUMN origin_trace_sampled boolean;

-- A decision without a trace to apply it to identifies nothing.
ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_origin_sampled_needs_trace
        CHECK (origin_trace_sampled IS NULL OR origin_trace_id IS NOT NULL);
