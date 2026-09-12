import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { merchantApi, type PaymentSearchParams } from '../api/endpoints';
import { useSession } from '../auth/session';
import { PageHeader } from '../components/Shell';
import {
  Card,
  EmptyState,
  ErrorNotice,
  Field,
  LoadingRows,
  Money,
  Notice,
  PaymentStatusBadge,
  RiskBadge,
  ShortIdentifier,
  Stat,
  TableScroll,
  Timestamp,
} from '../components/ui';

const STATUSES = ['AUTHORIZED', 'CAPTURED', 'VOIDED', 'DECLINED', 'REVIEW'] as const;
const RISK_OUTCOMES = ['APPROVE', 'REVIEW', 'DECLINE'] as const;
const PAGE_SIZE = 25;

/** Reads filters from the URL, so a filtered view can be shared and survives a refresh. */
function filtersFromUrl(params: URLSearchParams): PaymentSearchParams {
  return {
    paymentId: params.get('paymentId') ?? undefined,
    accountId: params.get('accountId') ?? undefined,
    status: params.get('status') ?? undefined,
    riskOutcome: params.get('riskOutcome') ?? undefined,
    currency: params.get('currency') ?? undefined,
    createdFrom: params.get('createdFrom') ?? undefined,
    createdTo: params.get('createdTo') ?? undefined,
    cursor: params.get('cursor') ?? undefined,
    limit: PAGE_SIZE,
  };
}

/** A datetime-local control gives "2026-09-11T10:30"; the API wants an instant. */
function toInstant(localValue: string): string | undefined {
  if (!localValue) return undefined;
  const parsed = new Date(localValue);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed.toISOString();
}

function toLocalInput(instant: string | undefined): string {
  if (!instant) return '';
  const parsed = new Date(instant);
  if (Number.isNaN(parsed.getTime())) return '';
  const offset = parsed.getTimezoneOffset() * 60_000;
  return new Date(parsed.getTime() - offset).toISOString().slice(0, 16);
}

export function PaymentsPage() {
  const { can } = useSession();
  const [params, setParams] = useSearchParams();
  const filters = filtersFromUrl(params);
  // Cursor paging is forward-only by design, so going back means remembering where we came from.
  const [cursorTrail, setCursorTrail] = useState<string[]>([]);

  const accounts = useQuery({
    queryKey: ['accounts'],
    queryFn: ({ signal }) => merchantApi.accounts(signal),
    enabled: can.viewAccounts,
  });

  const search = useQuery({
    queryKey: ['payments', filters],
    queryFn: ({ signal }) => merchantApi.searchPayments(filters, signal),
  });

  const applyFilters = (next: Record<string, string>) => {
    const updated = new URLSearchParams();
    for (const [key, value] of Object.entries(next)) {
      if (value) updated.set(key, value);
    }
    // A changed filter always starts a new result set; keeping a cursor would page into the old one.
    setCursorTrail([]);
    setParams(updated, { replace: false });
  };

  const goToNextPage = (nextCursor: string) => {
    const current = params.get('cursor');
    setCursorTrail((trail) => [...trail, current ?? '']);
    const updated = new URLSearchParams(params);
    updated.set('cursor', nextCursor);
    setParams(updated);
  };

  const goToPreviousPage = () => {
    const trail = [...cursorTrail];
    const previous = trail.pop();
    setCursorTrail(trail);
    const updated = new URLSearchParams(params);
    if (previous) updated.set('cursor', previous);
    else updated.delete('cursor');
    setParams(updated);
  };

  const page = search.data;
  const invalidFilter = search.error instanceof ApiError && search.error.status === 400 ? search.error : null;

  return (
    <>
      <PageHeader
        title="Payments"
        description="Authoritative search over committed payments. A payment appears here as soon as its transaction commits, including while event delivery is unavailable."
        actions={
          can.createPayments ? (
            <Link className="button-link" to="/payments/new">
              <button type="button" className="primary">
                New authorization
              </button>
            </Link>
          ) : null
        }
      />
      <div className="page-body">
        <Card title="Filters" scope="All filters are applied by the server against your own payments only.">
          <form
            className="filters"
            onSubmit={(event) => {
              event.preventDefault();
              const form = new FormData(event.currentTarget);
              applyFilters({
                paymentId: String(form.get('paymentId') ?? '').trim(),
                accountId: String(form.get('accountId') ?? ''),
                status: String(form.get('status') ?? ''),
                riskOutcome: String(form.get('riskOutcome') ?? ''),
                currency: String(form.get('currency') ?? ''),
                createdFrom: toInstant(String(form.get('createdFrom') ?? '')) ?? '',
                createdTo: toInstant(String(form.get('createdTo') ?? '')) ?? '',
              });
            }}
          >
            <Field label="Payment ID" hint="Exact match">
              {(props) => (
                <input {...props} type="search" name="paymentId" defaultValue={filters.paymentId ?? ''} placeholder="UUID" />
              )}
            </Field>
            <Field label="Account">
              {(props) => (
                <select {...props} name="accountId" defaultValue={filters.accountId ?? ''}>
                  <option value="">Any account</option>
                  {(accounts.data ?? []).map((account) => (
                    <option key={account.id} value={account.id}>
                      {account.id.slice(0, 8)} · {account.currency}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Payment status">
              {(props) => (
                <select {...props} name="status" defaultValue={filters.status ?? ''}>
                  <option value="">Any status</option>
                  {STATUSES.map((status) => (
                    <option key={status} value={status}>
                      {status}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Risk outcome" hint="The stored decision">
              {(props) => (
                <select {...props} name="riskOutcome" defaultValue={filters.riskOutcome ?? ''}>
                  <option value="">Any outcome</option>
                  {RISK_OUTCOMES.map((outcome) => (
                    <option key={outcome} value={outcome}>
                      {outcome}
                    </option>
                  ))}
                </select>
              )}
            </Field>
            <Field label="Currency">
              {(props) => (
                <select {...props} name="currency" defaultValue={filters.currency ?? ''}>
                  <option value="">Any currency</option>
                  <option value="CAD">CAD</option>
                  <option value="USD">USD</option>
                </select>
              )}
            </Field>
            <Field label="Created from">
              {(props) => (
                <input {...props} type="datetime-local" name="createdFrom" defaultValue={toLocalInput(filters.createdFrom)} />
              )}
            </Field>
            <Field label="Created to">
              {(props) => (
                <input {...props} type="datetime-local" name="createdTo" defaultValue={toLocalInput(filters.createdTo)} />
              )}
            </Field>
            <div className="row">
              <button type="submit" className="primary">
                Apply
              </button>
              <button
                type="button"
                onClick={() => {
                  setCursorTrail([]);
                  setParams(new URLSearchParams());
                }}
              >
                Clear
              </button>
            </div>
          </form>
        </Card>

        {invalidFilter && (
          <Notice tone="danger" title="The server rejected these filters">
            <span>{invalidFilter.detail}</span>
          </Notice>
        )}

        {page && (
          <div className="grid cols-3">
            <Stat
              label="Matching payments"
              value={page.matchedCountCapped ? `${page.matchedCount}+` : page.matchedCount}
              note={
                page.matchedCountCapped
                  ? `Counting stops at ${page.matchedCountLimit}, so the real total is at least this.`
                  : 'Exact count for the current filters.'
              }
            />
            <Stat label="Shown on this page" value={page.payments.length} note={`Page size ${PAGE_SIZE}`} />
            <Stat
              label="Ordering"
              value="Newest first"
              note="By creation time, then id, so pages never overlap."
            />
          </div>
        )}

        <Card
          title="Results"
          scope={page ? 'Your own payments only. Ownership is enforced by the server, not by this filter.' : undefined}
          tight
        >
          {search.isPending && <LoadingRows rows={5} label="Searching payments" />}
          {search.error && !invalidFilter && <div className="card-body"><ErrorNotice error={search.error} context="Payment search" /></div>}
          {page && page.payments.length === 0 && (
            <EmptyState title="No payments match these filters">
              <span>Try widening the time range, or clear the filters to see recent payments.</span>
            </EmptyState>
          )}
          {page && page.payments.length > 0 && (
            <>
              <TableScroll>
                <table>
                  <caption className="visually-hidden">
                    Payments matching the current filters, newest first
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Payment</th>
                      <th scope="col" className="numeric">Amount</th>
                      <th scope="col">Status</th>
                      <th scope="col">Risk</th>
                      <th scope="col" className="numeric">Score</th>
                      <th scope="col">Funding</th>
                      <th scope="col" className="nowrap">Created</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.payments.map((payment) => (
                      <tr key={payment.id}>
                        <td>
                          <Link to={`/payments/${payment.id}`}>
                            <ShortIdentifier value={payment.id} />
                          </Link>
                          <div className="id-short">account {payment.accountId.slice(0, 8)}</div>
                        </td>
                        <td className="numeric">
                          <Money minorUnits={payment.amountMinor} currency={payment.currency} />
                        </td>
                        <td><PaymentStatusBadge status={payment.status} /></td>
                        <td><RiskBadge outcome={payment.riskOutcome} /></td>
                        <td className="numeric">{payment.riskScore}</td>
                        <td>
                          {payment.failureCode ? (
                            <span className="id-short">{payment.failureCode}</span>
                          ) : (
                            <span className="id-short">—</span>
                          )}
                        </td>
                        <td className="nowrap"><Timestamp value={payment.createdAt} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableScroll>
              <div className="card-footer row between" style={{ padding: 'var(--space-3) var(--space-4)', borderTop: '1px solid var(--border)' }}>
                <span className="field-hint">
                  {search.isFetching ? 'Refreshing…' : `Page ${cursorTrail.length + 1}`}
                </span>
                <div className="row">
                  <button type="button" onClick={goToPreviousPage} disabled={cursorTrail.length === 0}>
                    Previous
                  </button>
                  <button
                    type="button"
                    onClick={() => page.nextCursor && goToNextPage(page.nextCursor)}
                    disabled={!page.nextCursor}
                  >
                    Next
                  </button>
                </div>
              </div>
            </>
          )}
        </Card>
      </div>
    </>
  );
}
