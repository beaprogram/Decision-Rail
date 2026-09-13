-- Correlation for shadow evaluation, on the same terms as the outbox.
--
-- A shadow task is enqueued by a Kafka consumer and evaluated later, on a worker thread, possibly
-- after a restart and possibly by a different worker after a lease takeover. None of those can see the
-- context of the thread that enqueued it, so the trace is written with the task, in the same
-- transaction that creates it, and read back when the task is claimed.
--
-- Nullable, because tasks enqueued before this migration have no trace. Those evaluate normally and
-- start their own trace; a missing decision is treated as unsampled for the same reason as the outbox,
-- so nothing downstream promotes itself to recording on its own authority.

ALTER TABLE shadow_tasks
    ADD COLUMN origin_trace_id char(32),
    ADD COLUMN origin_span_id char(16),
    ADD COLUMN origin_trace_sampled boolean;

ALTER TABLE shadow_tasks
    ADD CONSTRAINT shadow_origin_trace_id_hex
        CHECK (origin_trace_id IS NULL OR origin_trace_id ~ '^[0-9a-f]{32}$'),
    ADD CONSTRAINT shadow_origin_span_id_hex
        CHECK (origin_span_id IS NULL OR origin_span_id ~ '^[0-9a-f]{16}$'),
    ADD CONSTRAINT shadow_origin_span_needs_trace
        CHECK (origin_span_id IS NULL OR origin_trace_id IS NOT NULL),
    ADD CONSTRAINT shadow_origin_sampled_needs_trace
        CHECK (origin_trace_sampled IS NULL OR origin_trace_id IS NOT NULL);
