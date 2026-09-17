-- A payment must be denominated in the currency its funding account holds.
--
-- This is a hardening decision, not a response to an observed loss. PaymentService has always refused
-- a currency that disagrees with the account, and no service path produces one; what was missing was
-- the schema saying so, which left the invariant true by convention rather than by construction.
--
-- What was already enforced, so that the resulting guarantee can be stated accurately:
--   * payment_returns -> payments on (payment_id, account_id, currency), so a return already had to
--     agree with its payment on both account and currency.
--   * enforce_balanced_journal() requires a journal's currency to equal its payment's, and a return
--     journal's to equal its return operation's.
-- The one relationship nothing checked was the payment against the account above it. With the
-- constraint below, currency agreement now holds transitively across account, payment, return and
-- journal.

-- Incompatible rows are found and reported before the constraint is validated, because a bare foreign
-- key violation names one row and explains nothing. Nothing here alters financial data: an upgrade
-- that found such rows would be telling an operator to investigate, not repairing balances on their
-- behalf.
DO $$
DECLARE offending bigint; sample text;
BEGIN
    SELECT count(*), min(p.id::text) INTO offending, sample
      FROM payments p JOIN accounts a ON a.id = p.account_id
     WHERE p.currency <> a.currency;
    IF offending > 0 THEN
        RAISE EXCEPTION USING ERRCODE = '23514',
            MESSAGE = format('Cannot enforce payment/account currency agreement: %s payment(s) are denominated differently from their funding account.', offending),
            DETAIL  = format('For example payment %s. Reconciliation reports these as PAYMENT_CURRENCY_MISMATCH and ACCOUNT_CURRENCY_MISMATCH, with the account and currencies involved.', sample),
            HINT    = 'Investigate those records and correct them with compensating operations before upgrading. This migration deliberately does not alter, delete or repair financial data.';
    END IF;
END $$;

-- The referenced key. accounts already carries UNIQUE (id, merchant_id) for ownership; this is the
-- currency counterpart, and the two are independent.
ALTER TABLE accounts ADD CONSTRAINT accounts_identity_currency_unique UNIQUE (id, currency);

-- No ON UPDATE action on purpose. Changing an account's currency while payments reference it is
-- refused rather than cascaded: a cascade would silently redenominate committed payments, which is
-- the corruption this constraint exists to prevent rather than a resolution of it.
ALTER TABLE payments ADD CONSTRAINT payments_currency_matches_account
    FOREIGN KEY (account_id, currency) REFERENCES accounts(id, currency);

-- Ownership is unchanged and still enforced separately by payments -> accounts (id, merchant_id).
