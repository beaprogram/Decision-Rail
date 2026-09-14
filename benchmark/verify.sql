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
--    opening - captured + returned = balance, and held = the sum of still-authorized payments.
--
--    The returned term is new in checkpoint 9. The workloads here issue no refunds, so it is zero in
--    every run measured so far - which is exactly why it is written explicitly rather than omitted.
--    Left out, this check would silently pass for an account that had been credited back money the
--    run never accounted for, and would start failing the moment a future workload did issue one.
SELECT 'BALANCE_DOES_NOT_RECONCILE' AS failure, a.id, a.opening_balance_minor, a.balance_minor,
       coalesce(captured.total, 0) AS captured_total, coalesce(returned.total, 0) AS returned_total
FROM bench_accounts a
LEFT JOIN (
    SELECT account_id, sum(amount_minor) AS total FROM bench_payments WHERE status = 'CAPTURED' GROUP BY account_id
) captured ON captured.account_id = a.id
LEFT JOIN (
    SELECT p.account_id, sum(p.returned_amount_minor) AS total FROM bench_payments p GROUP BY p.account_id
) returned ON returned.account_id = a.id
WHERE a.balance_minor <> a.opening_balance_minor - coalesce(captured.total, 0) + coalesce(returned.total, 0);

-- Each payment's returned total agrees with its return operations and with the journals those wrote.
-- Three records of the same money, checked against each other rather than against one of themselves.
SELECT 'RETURN_TOTAL_DOES_NOT_RECONCILE' AS failure, p.id, p.returned_amount_minor,
       coalesce(operations.total, 0) AS operations_total, coalesce(journals.total, 0) AS journal_total
FROM bench_payments p
LEFT JOIN (
    SELECT payment_id, sum(amount_minor) AS total FROM payment_returns GROUP BY payment_id
) operations ON operations.payment_id = p.id
LEFT JOIN (
    SELECT j.payment_id, sum(e.amount_minor) AS total
    FROM ledger_journals j JOIN ledger_entries e ON e.journal_id = j.id
    WHERE j.journal_kind = 'RETURN' AND e.side = 'CREDIT'
    GROUP BY j.payment_id
) journals ON journals.payment_id = p.id
WHERE p.returned_amount_minor <> coalesce(operations.total, 0)
   OR p.returned_amount_minor <> coalesce(journals.total, 0)
   OR p.returned_amount_minor > coalesce(p.captured_amount_minor, 0);

SELECT 'HELD_DOES_NOT_RECONCILE' AS failure, a.id, a.held_minor, coalesce(authorized.total, 0) AS authorized_total
FROM bench_accounts a
LEFT JOIN (
    SELECT account_id, sum(amount_minor) AS total FROM bench_payments WHERE status = 'AUTHORIZED' GROUP BY account_id
) authorized ON authorized.account_id = a.id
WHERE a.held_minor <> coalesce(authorized.total, 0);

-- 3. Each captured payment has exactly one capture journal.
--    Qualified by journal_kind since checkpoint 9: a payment may now carry compensating journals as
--    well, and counting every journal would report a correctly refunded payment as a failure. The
--    guarantee is unchanged - one capture journal, never two - and return journals are counted
--    separately below rather than excused.
SELECT 'CAPTURE_WITHOUT_EXACTLY_ONE_JOURNAL' AS failure, p.id, count(j.id) AS capture_journals
FROM bench_payments p
LEFT JOIN ledger_journals j ON j.payment_id = p.id AND j.journal_kind = 'CAPTURE'
WHERE p.status = 'CAPTURED'
GROUP BY p.id
HAVING count(j.id) <> 1;

-- Every return operation has exactly one journal, and no journal claims a return that does not exist.
SELECT 'RETURN_WITHOUT_EXACTLY_ONE_JOURNAL' AS failure, r.id, count(j.id) AS journals
FROM payment_returns r
JOIN bench_payments p ON p.id = r.payment_id
LEFT JOIN ledger_journals j ON j.source_return_id = r.id
GROUP BY r.id
HAVING count(j.id) <> 1;

SELECT 'JOURNAL_NOT_BALANCED' AS failure, j.id,
       sum(CASE WHEN e.side = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END) AS imbalance
FROM ledger_journals j
JOIN bench_payments p ON p.id = j.payment_id
JOIN ledger_entries e ON e.journal_id = j.id
GROUP BY j.id
HAVING sum(CASE WHEN e.side = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END) <> 0;

-- 4. A void releases its hold and writes no journal. A void is an authorization reversal: it releases
--    a hold and moves no money, which is why it produces no journal of any kind, capture or return.
SELECT 'VOID_PRODUCED_A_JOURNAL' AS failure, p.id, j.journal_kind
FROM bench_payments p JOIN ledger_journals j ON j.payment_id = p.id
WHERE p.status = 'VOIDED';

-- And no payment that was never captured carries a return.
SELECT 'RETURN_ON_AN_UNCAPTURED_PAYMENT' AS failure, p.id, p.status, count(r.id) AS returns
FROM bench_payments p JOIN payment_returns r ON r.payment_id = p.id
WHERE p.status <> 'CAPTURED'
GROUP BY p.id, p.status;

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

--    A captured or voided payment has its authorization event and its lifecycle event, plus one
--    further event per return. Written as an expected count rather than a fixed 2, so a workload that
--    does issue refunds is checked rather than excused.
SELECT 'LIFECYCLE_EVENT_COUNT_WRONG' AS failure, p.id, p.status, count(o.id) AS events,
       2 + (SELECT count(*) FROM payment_returns r WHERE r.payment_id = p.id) AS expected
FROM bench_payments p
JOIN outbox_events o ON o.aggregate_id = p.id
WHERE p.status IN ('CAPTURED', 'VOIDED')
GROUP BY p.id, p.status
HAVING count(o.id) <> 2 + (SELECT count(*) FROM payment_returns r WHERE r.payment_id = p.id);

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
