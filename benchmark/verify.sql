-- Correctness of the workload that was just measured.
--
-- The integration suite proves these properties against constructed scenarios. It does not prove them
-- about this run: a benchmark drives far more concurrency through the same code, and "the historical
-- tests pass" is not a statement about the hundred thousand rows that were just written. Every check
-- below is scoped to the accounts this run created, by the marker in their merchant_id-independent
-- opening balance tag, so a shared database cannot make a failure look like a pass or the reverse.
--
-- Any row returned by this script is a failure. Silence is the expected output.

\set ON_ERROR_STOP on

-- The accounts this run owns.
CREATE TEMP VIEW bench_accounts AS
    SELECT * FROM accounts WHERE id IN (SELECT unnest(string_to_array(:'accounts', ',')::uuid[]));

CREATE TEMP VIEW bench_payments AS
    SELECT p.* FROM payments p JOIN bench_accounts a ON a.id = p.account_id;

-- 1. Available funds were never exceeded. held never exceeds balance, and neither goes negative.
--    The database also enforces this with CHECK constraints; asserting it here proves the constraints
--    were actually exercised rather than assumed.
SELECT 'AVAILABLE_FUNDS_EXCEEDED' AS failure, id, balance_minor, held_minor
FROM bench_accounts
WHERE held_minor > balance_minor OR held_minor < 0 OR balance_minor < 0;

-- 2. Balances reconcile to the operations this run performed.
--    opening - captured = balance, and held = the sum of still-authorized payments.
SELECT 'BALANCE_DOES_NOT_RECONCILE' AS failure, a.id, a.opening_balance_minor, a.balance_minor,
       coalesce(captured.total, 0) AS captured_total
FROM bench_accounts a
LEFT JOIN (
    SELECT account_id, sum(amount_minor) AS total FROM bench_payments WHERE status = 'CAPTURED' GROUP BY account_id
) captured ON captured.account_id = a.id
WHERE a.balance_minor <> a.opening_balance_minor - coalesce(captured.total, 0);

SELECT 'HELD_DOES_NOT_RECONCILE' AS failure, a.id, a.held_minor, coalesce(authorized.total, 0) AS authorized_total
FROM bench_accounts a
LEFT JOIN (
    SELECT account_id, sum(amount_minor) AS total FROM bench_payments WHERE status = 'AUTHORIZED' GROUP BY account_id
) authorized ON authorized.account_id = a.id
WHERE a.held_minor <> coalesce(authorized.total, 0);

-- 3. Each captured payment has exactly one balanced journal.
SELECT 'CAPTURE_WITHOUT_EXACTLY_ONE_JOURNAL' AS failure, p.id, count(j.id) AS journals
FROM bench_payments p
LEFT JOIN ledger_journals j ON j.payment_id = p.id
WHERE p.status = 'CAPTURED'
GROUP BY p.id
HAVING count(j.id) <> 1;

SELECT 'JOURNAL_NOT_BALANCED' AS failure, j.id,
       sum(CASE WHEN e.side = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END) AS imbalance
FROM ledger_journals j
JOIN bench_payments p ON p.id = j.payment_id
JOIN ledger_entries e ON e.journal_id = j.id
GROUP BY j.id
HAVING sum(CASE WHEN e.side = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END) <> 0;

-- 4. A void releases its hold and writes no journal.
SELECT 'VOID_PRODUCED_A_JOURNAL' AS failure, p.id
FROM bench_payments p JOIN ledger_journals j ON j.payment_id = p.id
WHERE p.status = 'VOIDED';

-- 5. A duplicate command created no additional financial effect: one payment per idempotency key.
SELECT 'IDEMPOTENCY_KEY_PRODUCED_MORE_THAN_ONE_PAYMENT' AS failure, r.idempotency_key, count(*) AS payments
FROM idempotency_records r
JOIN bench_payments p ON p.id = (r.response_body ->> 'id')::uuid
WHERE r.idempotency_key LIKE 'bench-%'
GROUP BY r.idempotency_key
HAVING count(*) <> 1;

-- 6. No committed payment lost its durable event intent, and every event has a sequence.
SELECT 'PAYMENT_WITHOUT_EVENT_INTENT' AS failure, p.id, p.status
FROM bench_payments p
WHERE NOT EXISTS (SELECT 1 FROM outbox_events o WHERE o.aggregate_id = p.id);

SELECT 'CAPTURED_OR_VOIDED_WITHOUT_ITS_SECOND_EVENT' AS failure, p.id, p.status, count(o.id) AS events
FROM bench_payments p
JOIN outbox_events o ON o.aggregate_id = p.id
WHERE p.status IN ('CAPTURED', 'VOIDED')
GROUP BY p.id, p.status
HAVING count(o.id) <> 2;

-- 7. Per-payment event order is a dense sequence starting at 1: no gaps, no duplicates.
SELECT 'EVENT_SEQUENCE_NOT_DENSE' AS failure, o.aggregate_id, count(*) AS events,
       min(o.aggregate_sequence) AS lowest, max(o.aggregate_sequence) AS highest
FROM outbox_events o JOIN bench_payments p ON p.id = o.aggregate_id
GROUP BY o.aggregate_id
HAVING min(o.aggregate_sequence) <> 1
    OR max(o.aggregate_sequence) <> count(*)
    OR count(DISTINCT o.aggregate_sequence) <> count(*);

-- 8. The projection caught up, in per-payment order, with one effect per event.
SELECT 'PROJECTION_BEHIND_THE_PAYMENT' AS failure, p.id, p.status, a.last_status
FROM bench_payments p
LEFT JOIN payment_activity a ON a.payment_id = p.id
WHERE a.payment_id IS NULL OR a.last_status <> p.status;

-- The projection applied every event it received, and in order: the last sequence it applied is the
-- highest this payment produced, and it applied one effect per event rather than collapsing them.
SELECT 'PROJECTION_APPLIED_OUT_OF_ORDER' AS failure, p.id, a.last_sequence, events.total
FROM bench_payments p
JOIN payment_activity a ON a.payment_id = p.id
JOIN (SELECT aggregate_id, count(*) AS total FROM outbox_events GROUP BY aggregate_id) events
     ON events.aggregate_id = p.id
WHERE a.last_sequence <> events.total OR a.applied_event_count <> events.total;

-- 9. Redelivery created no duplicate consumer effect: one consumed_events row per event per group.
SELECT 'DUPLICATE_CONSUMER_EFFECT' AS failure, c.event_id, c.consumer_group, count(*) AS records
FROM consumed_events c
JOIN bench_payments p ON p.id = c.aggregate_id
GROUP BY c.event_id, c.consumer_group
HAVING count(*) <> 1;

-- 10. Nothing this run produced was refused by the consumer.
SELECT 'QUARANTINED_RECORD_FROM_THIS_RUN' AS failure, q.event_id, q.reason
FROM consumer_quarantine q
WHERE q.event_id IN (SELECT o.id FROM outbox_events o JOIN bench_payments p ON p.id = o.aggregate_id);

-- 11. Delivery finished. A terminally failed event means an operator would have to redrive it.
SELECT 'EVENT_NOT_DELIVERED' AS failure, o.id, o.status, o.attempts, o.last_error
FROM outbox_events o JOIN bench_payments p ON p.id = o.aggregate_id
WHERE o.status <> 'PUBLISHED';
