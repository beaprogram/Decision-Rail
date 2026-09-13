-- Correlation that survives a restart.
--
-- A trace lives in a thread's context, and delivery happens minutes later, on another thread, often
-- in another process. The only way an event can still be attributed to the command that produced it
-- is to write that command's trace identity down beside the event, in the same transaction that
-- commits the event itself.
--
-- These columns are metadata about how the row came to exist. They are deliberately NOT part of the
-- published payload: the payload bytes are what a consumer fingerprints for deduplication, and
-- changing them would invalidate every previously recorded fingerprint. Trace context travels to the
-- broker as a record header instead, where it cannot affect event identity or the fingerprint.
--
-- Both are nullable. Rows written before this migration have no trace context and must stay
-- deliverable; a missing parent means delivery simply starts its own trace.

ALTER TABLE outbox_events
    ADD COLUMN origin_trace_id char(32),
    ADD COLUMN origin_span_id char(16);

-- Validated on the way in rather than trusted. These values reach the broker as a traceparent
-- header, and a header assembled from unchecked text is an injection surface.
ALTER TABLE outbox_events
    ADD CONSTRAINT outbox_origin_trace_id_hex
        CHECK (origin_trace_id IS NULL OR origin_trace_id ~ '^[0-9a-f]{32}$'),
    ADD CONSTRAINT outbox_origin_span_id_hex
        CHECK (origin_span_id IS NULL OR origin_span_id ~ '^[0-9a-f]{16}$'),
    -- A span id without its trace id identifies nothing.
    ADD CONSTRAINT outbox_origin_span_needs_trace
        CHECK (origin_span_id IS NULL OR origin_trace_id IS NOT NULL);

-- The trace of the request that first performed a command, kept so an idempotent replay can point at
-- the operation it is replaying instead of looking like the original. The stored response and the
-- fingerprint are untouched: this records who asked first, never what the answer was.
ALTER TABLE idempotency_records
    ADD COLUMN origin_trace_id char(32);

ALTER TABLE idempotency_records
    ADD CONSTRAINT idempotency_origin_trace_id_hex
        CHECK (origin_trace_id IS NULL OR origin_trace_id ~ '^[0-9a-f]{32}$');
