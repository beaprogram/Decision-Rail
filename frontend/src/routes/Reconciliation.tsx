import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { merchantApi } from '../api/endpoints';
import { formatMinorUnits } from '../lib/money';
import { PageHeader } from '../components/Shell';
import {
  Badge,
  Card,
  EmptyState,
  ErrorNotice,
  Field,
  Identifier,
  KeyValues,
  LoadingRows,
  Notice,
  ShortIdentifier,
  Stat,
  TableScroll,
  Timestamp,
} from '../components/ui';
import type { ReconciliationReport, ReconciliationStatus } from '../api/types';

/**
 * Whether recorded money agrees with the evidence for it.
 *
 * Read-only, and the screen says so rather than implying it. There is no control here that repairs a
 * balance, writes a journal or creates a refund: a discrepancy is something an operator investigates,
 * and correcting it means a new compensating operation with its own evidence, not an edit.
 */
export function ReconciliationPage() {
  const [accountFilter, setAccountFilter] = useState('');
  const [applied, setApplied] = useState('');

  const report = useQuery({
    queryKey: ['reconciliation', applied],
    queryFn: ({ signal }) =>
      merchantApi.reconciliation(applied === '' ? {} : { accountId: applied }, signal),
  });

  return (
    <>
      <PageHeader
        title="Reconciliation"
        description="Derived from the ledger and the operations that wrote it, not from any single field."
        actions={
          <button type="button" onClick={() => void report.refetch()} disabled={report.isFetching}>
            {report.isFetching ? 'Running…' : 'Run again'}
          </button>
        }
      />
      <div className="page-body">
        <form
          className="filters"
          onSubmit={(event) => {
            event.preventDefault();
            setApplied(accountFilter.trim());
          }}
        >
          <Field label="Account" hint="Optional. Leave empty to cover every account you own.">
            {(fieldProps) => (
              <input
                {...fieldProps}
                type="text"
                autoComplete="off"
                placeholder="All accounts"
                value={accountFilter}
                onChange={(event) => setAccountFilter(event.target.value)}
              />
            )}
          </Field>
          <button type="submit" className="primary">
            Reconcile
          </button>
        </form>

        {report.isPending && <LoadingRows rows={5} label="Reconciling" />}
        {report.error && <ErrorNotice error={report.error} context="Running reconciliation" />}
        {report.data && <ReportBody report={report.data} />}
      </div>
    </>
  );
}

function ReportBody({ report }: { report: ReconciliationReport }) {
  const { scope } = report;
  return (
    <>
      <StatusNotice status={report.status} findings={report.findings.length} incompleteReason={scope.incompleteReason} />

      <div className="grid cols-4">
        <Stat label="Accounts examined" value={scope.accountsExamined} note={`limit ${scope.accountLimit}`} />
        <Stat label="Payments examined" value={scope.paymentsExamined} note={`limit ${scope.paymentLimit}`} />
        <Stat label="Returns examined" value={scope.returnsExamined} note={`limit ${scope.paymentLimit}`} />
        <Stat label="Findings" value={report.findings.length} note={report.findings.length === 0 ? 'none' : 'see below'} />
      </div>

      <Card
        title="Findings"
        scope="Each one names the resource, what the evidence says the value should be, and what is actually recorded."
      >
        {report.findings.length === 0 ? (
          <EmptyState title="Nothing disagreed in what was examined">
            <span>
              {scope.complete
                ? 'Every account, payment and return in scope was checked and agreed.'
                : 'Part of the population was checked. That is not the same as nothing being wrong; see the scope below.'}
            </span>
          </EmptyState>
        ) : (
          <TableScroll>
            <table>
              <thead>
                <tr>
                  <th scope="col">Finding</th>
                  <th scope="col">Resource</th>
                  <th scope="col" className="numeric">Expected</th>
                  <th scope="col" className="numeric">Actual</th>
                  <th scope="col" className="numeric">Difference</th>
                  <th scope="col">Evidence</th>
                </tr>
              </thead>
              <tbody>
                {report.findings.map((finding, index) => (
                  <tr key={`${finding.type}-${finding.resourceId}-${index}`}>
                    <td>
                      <div className="stack tight">
                        <Badge tone={finding.severity === 'CRITICAL' ? 'danger' : 'warning'}>{finding.type}</Badge>
                        <span className="id-short">{finding.detail}</span>
                      </div>
                    </td>
                    <td>
                      <div className="stack tight">
                        <span className="field-label">{finding.resourceType}</span>
                        <ShortIdentifier value={finding.resourceId} />
                      </div>
                    </td>
                    <td className="numeric">{money(finding.expectedMinor, finding.currency)}</td>
                    <td className="numeric">{money(finding.actualMinor, finding.currency)}</td>
                    <td className="numeric">{money(finding.deltaMinor, finding.currency, true)}</td>
                    <td>
                      <div className="stack tight">
                        {finding.references.map((reference) => (
                          <span key={`${reference.kind}-${reference.id}`} className="id-short">
                            {reference.kind} <ShortIdentifier value={reference.id} />
                          </span>
                        ))}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableScroll>
        )}
      </Card>

      <div className="grid cols-2">
        <Card title="Scope" scope="What this report covers, so an absent check is visible rather than mistaken for a passing one.">
          <KeyValues
            entries={[
              ['Merchant', <Identifier value={report.merchantId} />],
              ['Generated', <Timestamp value={report.generatedAt} />],
              ['Account filter', scope.accountFilter ? <ShortIdentifier value={scope.accountFilter} /> : 'every account you own'],
              ['Currencies', scope.currencies.length > 0 ? scope.currencies.join(', ') : '—'],
              ['Snapshot', scope.snapshot],
              ['Complete', scope.complete ? 'yes' : 'no'],
            ]}
          />
          <div className="stack tight">
            <span className="field-label">Checks performed</span>
            <ul className="prose-list">
              {scope.checks.map((check) => (
                <li key={check}>{check}</li>
              ))}
            </ul>
          </div>
        </Card>

        <Card title="What this cannot establish" scope="Stated in the report itself, not only in documentation.">
          <ul className="prose-list">
            {report.limitations.map((limitation) => (
              <li key={limitation}>{limitation}</li>
            ))}
          </ul>
        </Card>
      </div>
    </>
  );
}

function StatusNotice({
  status,
  findings,
  incompleteReason,
}: {
  status: ReconciliationStatus;
  findings: number;
  incompleteReason: string | null;
}) {
  if (status === 'CLEAN') {
    return (
      <Notice tone="success" title="Everything in scope reconciles">
        <span>Every account, payment and return examined agreed with the evidence recorded for it.</span>
      </Notice>
    );
  }
  if (status === 'INCOMPLETE') {
    return (
      <Notice tone="warning" title="Nothing disagreed, but not everything was examined">
        <span>{incompleteReason}</span>
        <span>This is deliberately not reported as clean: an unexamined population has not been cleared.</span>
      </Notice>
    );
  }
  return (
    <Notice tone="danger" title={`${findings} discrepanc${findings === 1 ? 'y' : 'ies'} found`}>
      <span>
        Recorded money disagrees with the evidence for it. Nothing here has been changed: correcting a
        discrepancy means a new compensating operation with its own journal, never an edit to history.
      </span>
      {status === 'INCOMPLETE_WITH_DISCREPANCIES' && <span>{incompleteReason}</span>}
    </Notice>
  );
}

/** Money or an em dash. A missing number is not zero, and showing 0.00 for one would be a claim. */
function money(minorUnits: number | null, currency: string | null, signed = false): string {
  if (minorUnits === null) return '—';
  const rendered = `${formatMinorUnits(minorUnits)} ${currency ?? ''}`.trim();
  return signed && minorUnits > 0 ? `+${rendered}` : rendered;
}
