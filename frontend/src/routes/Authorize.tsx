import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { merchantApi } from '../api/endpoints';
import { useIdempotentCommand } from '../lib/command';
import { formatMinorUnits, parseMinorUnits } from '../lib/money';
import { PageHeader } from '../components/Shell';
import {
  Card,
  ConfirmDialog,
  ErrorNotice,
  Field,
  Identifier,
  LoadingRows,
  Money,
  Notice,
  PaymentStatusBadge,
  RiskBadge,
} from '../components/ui';

/**
 * Creates a synthetic authorization.
 *
 * The amount is typed as a decimal and converted to integer minor units exactly, never by multiplying
 * a float. The server remains the authority on whether the funds are actually available: this form
 * validates shape, not eligibility, and does not try to predict a decline.
 */
export function AuthorizePage() {
  const navigate = useNavigate();
  const queries = useQueryClient();

  const accounts = useQuery({ queryKey: ['accounts'], queryFn: ({ signal }) => merchantApi.accounts(signal) });

  const [accountId, setAccountId] = useState('');
  const [amountText, setAmountText] = useState('');
  const [country, setCountry] = useState('CA');
  const [confirming, setConfirming] = useState(false);

  const selectedAccount = accounts.data?.find((account) => account.id === accountId);
  const parsedAmount = useMemo(() => parseMinorUnits(amountText), [amountText]);
  const amountError = amountText.trim() === '' ? null : parsedAmount.ok ? null : parsedAmount.message;
  const countryError = /^[A-Za-z]{2}$/.test(country) ? null : 'Use a two-letter country code.';

  const authorize = useIdempotentCommand('authorize', (key) =>
    merchantApi.authorize(key, {
      accountId,
      amountMinor: parsedAmount.ok ? parsedAmount.minorUnits : 0,
      currency: selectedAccount?.currency ?? 'CAD',
      country: country.toUpperCase(),
    }),
  );

  const ready = Boolean(selectedAccount) && parsedAmount.ok && !countryError;
  const wouldExceedAvailable =
    selectedAccount && parsedAmount.ok && parsedAmount.minorUnits > selectedAccount.availableMinor;

  const submit = async () => {
    const created = await authorize.run(undefined);
    setConfirming(false);
    if (created) {
      void queries.invalidateQueries({ queryKey: ['accounts'] });
      void queries.invalidateQueries({ queryKey: ['payments'] });
    }
  };

  const created = authorize.state.phase === 'succeeded' ? authorize.state.result : null;

  return (
    <>
      <PageHeader
        title="New authorization"
        description="Evaluates the active policy and, if approved, reserves synthetic funds. No real money is involved."
        actions={
          <Link to="/payments">
            <button type="button">Back to payments</button>
          </Link>
        }
      />
      <div className="page-body">
        {authorize.state.phase === 'uncertain' && (
          <Notice tone="warning" title="The outcome of this authorization is unknown">
            <span>{authorize.state.error.detail}</span>
            <span>
              Retrying reuses the same idempotency key, so if the authorization did go through you will
              see its original result instead of a second reservation.
            </span>
            <div className="row">
              <button type="button" onClick={() => void authorize.retry()} disabled={authorize.busy}>
                Retry safely
              </button>
            </div>
          </Notice>
        )}
        {authorize.state.phase === 'failed' && <AuthorizationFailure error={authorize.state.error} />}

        {created && (
          <Card title="Authorization recorded" scope="The server's authoritative response.">
            <div className="row">
              <PaymentStatusBadge status={created.status} />
              <RiskBadge outcome={created.decision.outcome} />
              <span className="id-short">score {created.decision.score}</span>
              <Money minorUnits={created.amountMinor} currency={created.currency} />
            </div>
            {created.status === 'DECLINED' && created.decision.outcome === 'APPROVE' && (
              <Notice tone="info" title="Approved by policy, declined for funds">
                The risk decision was APPROVE. The decline came from available funds, recorded as{' '}
                {created.failureCode}.
              </Notice>
            )}
            <div className="row">
              <Identifier value={created.id} />
              <button type="button" className="primary" onClick={() => navigate(`/payments/${created.id}`)}>
                Open payment
              </button>
              <button
                type="button"
                onClick={() => {
                  // A new authorization is a new logical command, so the key is discarded.
                  authorize.reset();
                  setAmountText('');
                }}
              >
                Authorize another
              </button>
            </div>
          </Card>
        )}

        {!created && (
          <Card title="Authorization details" scope="Amounts are held as integer minor units throughout.">
            {accounts.isPending && <LoadingRows rows={3} label="Loading accounts" />}
            {accounts.error && <ErrorNotice error={accounts.error} context="Loading accounts" />}
            {accounts.data && accounts.data.length === 0 && (
              <Notice tone="warning" title="No accounts available">
                This merchant has no accounts, so there is nothing to authorize against.
              </Notice>
            )}
            {accounts.data && accounts.data.length > 0 && (
              <form
                className="stack"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (ready) setConfirming(true);
                }}
              >
                <div className="grid cols-2">
                  <Field label="Account" hint="Determines the currency of the authorization.">
                    {(props) => (
                      <select
                        {...props}
                        value={accountId}
                        onChange={(event) => setAccountId(event.target.value)}
                        required
                      >
                        <option value="">Select an account</option>
                        {accounts.data.map((account) => (
                          <option key={account.id} value={account.id}>
                            {account.id.slice(0, 8)} · {account.currency} · {formatMinorUnits(account.availableMinor)}{' '}
                            available
                          </option>
                        ))}
                      </select>
                    )}
                  </Field>
                  <Field
                    label={`Amount${selectedAccount ? ` (${selectedAccount.currency})` : ''}`}
                    hint="Up to two decimal places, for example 25.00."
                    error={amountError}
                  >
                    {(props) => (
                      <input
                        {...props}
                        type="text"
                        inputMode="decimal"
                        autoComplete="off"
                        value={amountText}
                        onChange={(event) => setAmountText(event.target.value)}
                        placeholder="25.00"
                        required
                      />
                    )}
                  </Field>
                  <Field label="Country" hint="Two-letter code. ZZ is a synthetic terminal-decline marker." error={country ? countryError : null}>
                    {(props) => (
                      <input
                        {...props}
                        type="text"
                        value={country}
                        maxLength={2}
                        autoCapitalize="characters"
                        onChange={(event) => setCountry(event.target.value.toUpperCase())}
                        required
                      />
                    )}
                  </Field>
                  <Field label="Converted amount" hint="What will be sent to the server.">
                    {(props) => (
                      <input
                        {...props}
                        type="text"
                        readOnly
                        value={parsedAmount.ok ? `${parsedAmount.minorUnits} minor units` : '—'}
                      />
                    )}
                  </Field>
                </div>

                {selectedAccount && (
                  <div className="row" style={{ gap: 'var(--space-5)' }}>
                    <span className="field-hint">
                      Balance <Money minorUnits={selectedAccount.balanceMinor} currency={selectedAccount.currency} />
                    </span>
                    <span className="field-hint">
                      Held <Money minorUnits={selectedAccount.heldMinor} currency={selectedAccount.currency} />
                    </span>
                    <span className="field-hint">
                      Available <Money minorUnits={selectedAccount.availableMinor} currency={selectedAccount.currency} />
                    </span>
                  </div>
                )}

                {wouldExceedAvailable && (
                  <Notice tone="info" title="This is more than the account currently has available">
                    <span>
                      The request will still be sent. The server decides whether funds are available, and
                      it may record an approved risk decision with an insufficient-funds decline.
                    </span>
                  </Notice>
                )}

                <div className="row">
                  <button type="submit" className="primary" disabled={!ready || authorize.busy}>
                    Review and authorize
                  </button>
                </div>
              </form>
            )}
          </Card>
        )}
      </div>

      <ConfirmDialog
        open={confirming}
        title="Authorize this payment?"
        confirmLabel="Authorize"
        confirming={authorize.busy}
        onCancel={() => setConfirming(false)}
        onConfirm={() => void submit()}
      >
        <div className="confirm-summary stack tight">
          <div className="row between">
            <span className="field-label">Account</span>
            <Identifier value={accountId} />
          </div>
          <div className="row between">
            <span className="field-label">Amount</span>
            <strong>
              {parsedAmount.ok ? formatMinorUnits(parsedAmount.minorUnits) : '—'} {selectedAccount?.currency}
            </strong>
          </div>
          <div className="row between">
            <span className="field-label">Sent as</span>
            <span className="mono">{parsedAmount.ok ? parsedAmount.minorUnits : '—'} minor units</span>
          </div>
          <div className="row between">
            <span className="field-label">Country</span>
            <span>{country.toUpperCase()}</span>
          </div>
        </div>
        <p>
          This reserves synthetic funds if the policy approves and the account has them available. It
          cannot be submitted twice by accident: the request carries one idempotency key for this
          command.
        </p>
      </ConfirmDialog>
    </>
  );
}

/** Distinguishes a rejected request from a server problem, since the operator's next step differs. */
function AuthorizationFailure({ error }: { error: unknown }) {
  if (error instanceof ApiError && error.status === 400) {
    return (
      <Notice tone="danger" title="The server rejected this authorization">
        <span>{error.detail}</span>
        <span className="field-hint">{error.code}</span>
      </Notice>
    );
  }
  if (error instanceof ApiError && error.conflict) {
    return (
      <Notice tone="warning" title="This command conflicted with an earlier one">
        <span>{error.detail}</span>
      </Notice>
    );
  }
  return <ErrorNotice error={error} context="Authorization" />;
}
