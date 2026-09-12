-- Checkpoint 7: indexes for the operator dashboard's read paths.
--
-- No table, column, or constraint changes. The dashboard needs authoritative, merchant-scoped
-- search over payments, an account list, a per-payment lifecycle timeline, and an administrative
-- list of failed events. Every one of those is a read against existing evidence; what they lacked
-- was an index that keeps them bounded as history grows.

-- Keyset pagination for merchant payment search orders by (created_at DESC, id DESC) so a page
-- boundary is stable even when several payments share a timestamp. Matching the index to that exact
-- ordering is what lets a page be fetched without scanning the merchant's whole history.
CREATE INDEX payments_merchant_recent_idx ON payments (merchant_id, created_at DESC, id DESC);

-- Account-scoped search within a merchant, same ordering.
CREATE INDEX payments_account_recent_idx ON payments (account_id, created_at DESC, id DESC);

-- A foreign key does not create an index in PostgreSQL, so listing a merchant's accounts was a
-- sequential scan over every tenant's accounts.
CREATE INDEX accounts_merchant_idx ON accounts (merchant_id, created_at, id);

-- The timeline reads one payment's command history in order.
CREATE INDEX audit_events_payment_idx ON audit_events (payment_id, occurred_at, id);

-- The timeline also reads which consumer groups have recorded each of a payment's events. The
-- existing index leads with consumer_group, which cannot serve a lookup across all groups for one
-- payment.
CREATE INDEX consumed_events_payment_idx ON consumed_events (aggregate_id, aggregate_sequence);

-- The administrative failed-event list is ordered oldest first, because the oldest terminal failure
-- is the one blocking a payment's stream.
CREATE INDEX outbox_failed_idx ON outbox_events (occurred_at, id) WHERE status = 'FAILED';
