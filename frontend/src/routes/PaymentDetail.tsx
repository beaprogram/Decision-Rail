import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { merchantApi } from '../api/endpoints';
import { useSession } from '../auth/session';
import { useIdempotentCommand, type CommandHandle } from '../lib/command';
import { formatMinorUnits, parseMinorUnits } from '../lib/money';
import { fundingPresentation } from '../components/funding';
import { PageHeader } from '../components/Shell';
import {
  Badge,
  Card,
  ConfirmDialog,
  DeliveryBadge,
  EmptyState,
  ErrorNotice,
  Field,
  Identifier,
  KeyValues,
  LoadingRows,
  Money,
  Notice,
  PaymentStatusBadge,
  Reasons,
  RiskBadge,
  ScoreDisplay,
  ShortIdentifier,
  Stat,
  TableScroll,
  Timestamp,
  UnresolvedCommand,
} from '../components/ui';
import type { Payment, PaymentReturns, PaymentTimeline, ReturnReceipt } from '../api/types';

export function PaymentDetailPage() {
  const { paymentId = '' } = useParams();
  const { can } = useSession();
  const queries = useQueryClient();

  const payment = useQuery({
    queryKey: ['payment', paymentId],
    queryFn: ({ signal }) => merchantApi.payment(paymentId, signal),
  });
  const ledger = useQuery({
    queryKey: ['payment', paymentId, 'ledger'],
    queryFn: ({ signal }) => merchantApi.ledger(paymentId, signal),
    enabled: payment.data?.status === 'CAPTURED',
  });
  // One page of return history at a time. The cursor is component state rather than part of the query
  // key's identity being reset on every refresh: a new return must bring the reader back to the newest
  // page, because that is where it landed.
  const [returnsCursor, setReturnsCursor] = useState<string | null>(null);
  const returns = useQuery({
    queryKey: ['payment', paymentId, 'returns', returnsCursor],
    queryFn: ({ signal }) => merchantApi.returns(paymentId, returnsCursor, signal),
  });
  const timeline = useQuery({
    queryKey: ['payment', paymentId, 'timeline'],
    queryFn: ({ signal }) => merchantApi.timeline(paymentId, signal),
  });
  const shadow = useQuery({
    queryKey: ['payment', paymentId, 'shadow'],
    queryFn: ({ signal }) => merchantApi.paymentShadow(paymentId, signal),
    enabled: can.viewShadowComparisons,
  });

  /** After any command the authoritative state is re-read rather than patched from the response. */
  const refreshEverything = () => {
    void queries.invalidateQueries({ queryKey: ['payment', paymentId] });
    void queries.invalidateQueries({ queryKey: ['payments'] });
    void queries.invalidateQueries({ queryKey: ['accounts'] });
  };

  const capture = useIdempotentCommand<Payment>('capture');
  const voidCommand = useIdempotentCommand<Payment>('void');
  const refundCommand = useIdempotentCommand<ReturnReceipt>('refund');
  const reverseCommand = useIdempotentCommand<ReturnReceipt>('reverse');
  const [pending, setPending] = useState<'capture' | 'void' | null>(null);

  if (payment.isPending) {
    return (
      <>
        <PageHeader title="Payment" />
        <div className="page-body">
          <LoadingRows rows={6} label="Loading payment" />
        </div>
      </>
    );
  }

  if (payment.error) {
    const missing = payment.error instanceof ApiError && payment.error.status === 404;
    return (
      <>
        <PageHeader title="Payment" />
        <div className="page-body">
          {missing ? (
            <Notice tone="warning" title="No such payment">
              <span>
                This payment does not exist, or it belongs to another merchant. Both answer the same way
                on purpose, so identifiers cannot be probed.
              </span>
            </Notice>
          ) : (
            <ErrorNotice error={payment.error} context="Loading the payment" />
          )}
          <Link to="/payments">Back to payments</Link>
        </div>
      </>
    );
  }

  const record = payment.data;
  const funding = fundingPresentation(record.status, record.failureCode, record.decision.outcome);
  const fundingDecline = record.status === 'DECLINED' && record.decision.outcome === 'APPROVE';
  const canCapture = can.createPayments && record.status === 'AUTHORIZED';
  const canVoid = can.createPayments && record.status === 'AUTHORIZED';

  /**
   * Every attempt at a lifecycle command, first or retry, ends the same way.
   *
   * A retry used to call the command handle directly and skip this, so a capture that succeeded on its
   * second attempt left the screen showing the payment as it was before: still AUTHORIZED, still
   * offering Capture and Void. The command's own response cannot stand in for that read. Under a
   * replayed idempotency key the server returns the result as it stood when the command first ran,
   * which is a historical snapshot, not the payment's current state.
   */
  const attemptCommand = async (run: () => Promise<unknown>) => {
    try {
      await run();
    } finally {
      // A new return is the newest entry, so recovery from any attempt returns to the first page
      // rather than leaving the reader on a stale continuation that predates it.
      setReturnsCursor(null);
      // Whether it succeeded, was refused, or conflicted, the authoritative state is re-read rather
      // than inferred from the response.
      refreshEverything();
    }
  };

  /**
   * Sends a return, then re-reads authoritative state exactly as a lifecycle command does.
   *
   * The amount is taken from the caller as integer minor units that were already converted from the
   * digit string, and it is serialized once at submission. A retry replays those exact bytes: rebuilding
   * the body from the form would send a different amount under the original key, which the server
   * correctly refuses as conflicting reuse - destroying the one thing that could have recovered the
   * original command.
   */
  const runReturn = (kind: 'refund' | 'reverse', amountMinor: number | null, reason: string) =>
    attemptCommand(async () => {
      const handle = kind === 'refund' ? refundCommand : reverseCommand;
      const trimmed = reason.trim();
      await handle.submit({
        method: 'POST',
        path: `/ui/payments/${paymentId}/${kind === 'refund' ? 'refunds' : 'reversal'}`,
        body: {
          ...(amountMinor === null ? {} : { amountMinor }),
          ...(trimmed === '' ? {} : { reason: trimmed }),
        },
        summary: [
          { label: 'Payment', value: paymentId },
          { label: 'Command', value: kind === 'refund' ? 'Refund' : 'Reversal' },
          ...(amountMinor === null
            ? []
            : [{ label: 'Amount', value: `${formatMinorUnits(amountMinor)} ${record.currency}` }]),
        ],
      });
    });

  const retryReturn = (kind: 'refund' | 'reverse') =>
    attemptCommand(() => (kind === 'refund' ? refundCommand : reverseCommand).retry());

  const runCommand = (kind: 'capture' | 'void') =>
    attemptCommand(async () => {
      const handle = kind === 'capture' ? capture : voidCommand;
      // Captured once, so a retry replays the same command against the same payment.
      await handle.submit({
        method: 'POST',
        path: `/ui/payments/${paymentId}/${kind === 'capture' ? 'capture' : 'void'}`,
        summary: [
          { label: 'Payment', value: paymentId },
          { label: 'Command', value: kind === 'capture' ? 'Capture' : 'Void' },
        ],
      });
      setPending(null);
    });

  /** Replays the captured submission unchanged, then re-reads the payment exactly as a first attempt does. */
  const retryCommand = (kind: 'capture' | 'void') =>
    attemptCommand(() => (kind === 'capture' ? capture : voidCommand).retry());

  return (
    <>
      <PageHeader
        title="Payment"
        badge={<PaymentStatusBadge status={record.status} />}
        description={<Identifier value={record.id} />}
        actions={
          <>
            {canCapture && (
              <button
                type="button"
                className="primary"
                onClick={() => setPending('capture')}
                disabled={capture.unresolved || voidCommand.unresolved}
              >
                Capture
              </button>
            )}
            {canVoid && (
              <button
                type="button"
                onClick={() => setPending('void')}
                disabled={capture.unresolved || voidCommand.unresolved}
              >
                Void
              </button>
            )}
            <Link to="/payments">
              <button type="button">Back</button>
            </Link>
          </>
        }
      />
      <div className="page-body">
        <CommandOutcome kind="capture" handle={capture} onRetry={() => void retryCommand('capture')} />
        <CommandOutcome kind="void" handle={voidCommand} onRetry={() => void retryCommand('void')} />
        <ReturnOutcome kind="refund" handle={refundCommand} onRetry={() => void retryReturn('refund')} />
        <ReturnOutcome kind="reverse" handle={reverseCommand} onRetry={() => void retryReturn('reverse')} />

        {fundingDecline && (
          <Notice tone="info" title="Declined for funds, not by policy">
            <span>
              The risk decision for this payment was APPROVE. It was declined because the account did not
              have enough available funds, which the funding result below records separately. A policy
              decline would show a DECLINE risk outcome instead.
            </span>
          </Notice>
        )}

        <div className="grid cols-4">
          <Stat label="Amount" value={<Money minorUnits={record.amountMinor} currency={record.currency} />} />
          <Stat label="Risk outcome" value={<RiskBadge outcome={record.decision.outcome} />} note="Stored decision" />
          <Stat label="Risk score" value={record.decision.score} note={`Policy ${record.decision.ruleSetVersion}`} />
          <Stat
            label="Funding result"
            value={<Badge tone={funding.tone}>{funding.label}</Badge>}
            note={funding.note}
          />
        </div>

        <div className="grid cols-2">
          <Card title="Payment" scope="Authoritative record from the payments table.">
            <KeyValues
              entries={[
                ['Payment ID', <Identifier value={record.id} />],
                ['Account', <Link to={`/payments?accountId=${record.accountId}`}><Identifier value={record.accountId} /></Link>],
                ['Amount', <Money minorUnits={record.amountMinor} currency={record.currency} />],
                ['Country', record.country],
                ['Status', <PaymentStatusBadge status={record.status} />],
                ['Funding result', <Badge tone={funding.tone}>{funding.label}</Badge>],
                ['Funding failure', record.failureCode ?? '—'],
                ['Created', <Timestamp value={record.createdAt} />],
                ['Updated', <Timestamp value={record.updatedAt} />],
              ]}
            />
          </Card>

          <Card
            title="Stored decision"
            scope="Recorded when the payment was authorized. Not recomputed here."
          >
            <KeyValues
              entries={[
                ['Outcome', <RiskBadge outcome={record.decision.outcome} />],
                ['Score', <ScoreDisplay score={record.decision.score} />],
                ['Policy version', <Identifier value={record.decision.ruleSetVersion} />],
                [
                  'Flags',
                  record.decision.flags.length > 0 ? (
                    <div className="row" style={{ gap: 'var(--space-1)' }}>
                      {record.decision.flags.map((flag) => (
                        <Badge key={flag} tone="neutral" plain>
                          {flag}
                        </Badge>
                      ))}
                    </div>
                  ) : (
                    <span className="id-short">none</span>
                  ),
                ],
              ]}
            />
            <div className="stack tight">
              <span className="field-label">Reason contributions</span>
              <Reasons reasons={record.decision.reasons} />
            </div>
          </Card>
        </div>

        {record.status === 'CAPTURED' && (
          <Card title="Capture journal" scope="Balanced double-entry record, sealed after its transaction committed.">
            {ledger.isPending && <LoadingRows rows={2} label="Loading journal" />}
            {ledger.error && <ErrorNotice error={ledger.error} context="Loading the capture journal" />}
            {ledger.data && ledger.data.length === 0 && <EmptyState title="No journal entries recorded" />}
            {ledger.data && ledger.data.length > 0 && (
              <TableScroll>
                <table>
                  <thead>
                    <tr>
                      <th scope="col">Ledger account</th>
                      <th scope="col">Side</th>
                      <th scope="col" className="numeric">Amount</th>
                      <th scope="col">Journal</th>
                    </tr>
                  </thead>
                  <tbody>
                    {ledger.data.map((entry) => (
                      <tr key={entry.id}>
                        <td className="id-cell">{entry.ledgerAccount}</td>
                        <td>
                          <Badge tone={entry.side === 'DEBIT' ? 'info' : 'success'}>{entry.side}</Badge>
                        </td>
                        <td className="numeric">
                          <Money minorUnits={entry.amountMinor} currency={entry.currency} />
                        </td>
                        <td><ShortIdentifier value={entry.journalId} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableScroll>
            )}
          </Card>
        )}

        <ReturnsPanel
          query={returns}
          canCommand={can.createPayments}
          busy={refundCommand.busy || reverseCommand.busy}
          blocked={refundCommand.unresolved || reverseCommand.unresolved}
          onRefund={(amountMinor, reason) => void runReturn('refund', amountMinor, reason)}
          onReverse={(reason) => void runReturn('reverse', null, reason)}
          onPage={setReturnsCursor}
          paged={returnsCursor !== null}
        />

        <LifecycleTimeline query={timeline} />

        {can.viewShadowComparisons && (
          <Card
            title="Shadow comparisons"
            scope="What a candidate policy would have decided for this payment. Recorded only if shadow evaluation was enabled when it was authorized."
          >
            {shadow.isPending && <LoadingRows rows={2} label="Loading shadow comparisons" />}
            {shadow.error && <ErrorNotice error={shadow.error} context="Loading shadow comparisons" />}
            {shadow.data && shadow.data.length === 0 && (
              <EmptyState title="No shadow comparison for this payment">
                <span>
                  Shadow evaluation applies to authorizations made while it is enabled. It does not
                  backfill earlier payments; use a replay job for history.
                </span>
              </EmptyState>
            )}
            {shadow.data?.map((comparison) => (
              <div className="comparison" key={comparison.candidateVersion}>
                <div className="comparison-side">
                  <span className="comparison-label">Live decision</span>
                  <div className="row">
                    <RiskBadge outcome={comparison.baselineOutcome} />
                    <span className="id-short">score {comparison.baselineScore}</span>
                  </div>
                  <Reasons reasons={comparison.baselineReasons} />
                </div>
                <div className={comparison.diverged ? 'comparison-side diverged' : 'comparison-side'}>
                  <span className="comparison-label">
                    {/* "observation only" is stated here because this is the screen where a candidate
                        outcome sits next to the real one and could otherwise be mistaken for it. */}
                    Candidate {comparison.candidateVersion} · observation only
                    {comparison.diverged ? ' · diverged' : ' · agreed'}
                  </span>
                  {comparison.errorCode ? (
                    <Notice tone="warning" title="The candidate failed to evaluate">
                      <span>{comparison.errorCode}</span>
                    </Notice>
                  ) : (
                    <>
                      <div className="row">
                        <RiskBadge outcome={comparison.candidateOutcome} />
                        <ScoreDisplay
                          score={comparison.candidateScore}
                          rawScore={comparison.candidateRawScore}
                          capped={comparison.candidateScoreCapped}
                        />
                      </div>
                      <Reasons reasons={comparison.candidateReasons} />
                    </>
                  )}
                </div>
              </div>
            ))}
          </Card>
        )}
      </div>

      <ConfirmDialog
        open={pending !== null}
        title={pending === 'capture' ? 'Capture this payment?' : 'Void this payment?'}
        confirmLabel={pending === 'capture' ? 'Capture funds' : 'Release hold'}
        destructive={pending === 'void'}
        confirming={capture.busy || voidCommand.busy}
        onCancel={() => setPending(null)}
        onConfirm={() => pending && void runCommand(pending)}
      >
        {/* The concrete payment and amount are restated, so a confirmation is never about "the thing
            I clicked" but about a named payment for a specific amount. */}
        <div className="confirm-summary stack tight">
          <div className="row between">
            <span className="field-label">Payment</span>
            <Identifier value={record.id} />
          </div>
          <div className="row between">
            <span className="field-label">Amount</span>
            <strong>
              {formatMinorUnits(record.amountMinor)} {record.currency}
            </strong>
          </div>
          <div className="row between">
            <span className="field-label">Account</span>
            <ShortIdentifier value={record.accountId} />
          </div>
        </div>
        <p>
          {pending === 'capture'
            ? 'Capturing consumes the held funds and writes a balanced journal that cannot be edited afterwards.'
            : 'Voiding releases the authorization hold back to available funds. It cannot be captured afterwards.'}
        </p>
      </ConfirmDialog>
    </>
  );
}

/**
 * What was captured, what has come back, and what may still be returned.
 *
 * Every one of those numbers comes from the server, including whether a refund or a reversal is
 * available and why one is not. Recomputing eligibility here would be a second copy of a financial
 * rule living somewhere that cannot enforce it.
 */
function ReturnsPanel({
  query,
  canCommand,
  busy,
  blocked,
  onRefund,
  onReverse,
  onPage,
  paged,
}: {
  query: { isPending: boolean; error: unknown; data: PaymentReturns | undefined };
  canCommand: boolean;
  busy: boolean;
  /** True while a return's outcome is unknown: a new submission must not be offered until it resolves. */
  blocked: boolean;
  onRefund: (amountMinor: number, reason: string) => void;
  onReverse: (reason: string) => void;
  /** Moves to the page continuing from this cursor, or back to the newest page when null. */
  onPage: (cursor: string | null) => void;
  /** True while showing a continuation page rather than the newest one. */
  paged: boolean;
}) {
  const [amountText, setAmountText] = useState('');
  const [reason, setReason] = useState('');
  const [confirming, setConfirming] = useState<'refund' | 'reverse' | null>(null);

  if (query.isPending) {
    return (
      <Card title="Returns" scope="Refunds and reversals against this payment.">
        <LoadingRows rows={2} label="Loading returns" />
      </Card>
    );
  }
  if (query.error || !query.data) {
    return (
      <Card title="Returns" scope="Refunds and reversals against this payment.">
        <ErrorNotice error={query.error} context="Loading returns" />
      </Card>
    );
  }

  const summary = query.data;
  const parsed = parseMinorUnits(amountText);
  const typedTooMuch =
    parsed.ok && parsed.minorUnits > summary.remainingRefundableMinor
      ? `At most ${formatMinorUnits(summary.remainingRefundableMinor)} ${summary.currency} can still be returned.`
      : null;
  const amountError = amountText.trim() === '' ? null : parsed.ok ? typedTooMuch : parsed.message;
  const amountReady = parsed.ok && typedTooMuch === null;

  // The server's own words for why an action is unavailable, so the screen and the API never disagree.
  const unavailable: Record<string, string> = {
    NOT_CAPTURED:
      'Nothing has been captured on this payment, so there is nothing to return. An authorization that has not been captured is released with Void, which moves no money.',
    FULLY_RETURNED: 'Everything captured on this payment has already been returned.',
    PARTIALLY_RETURNED:
      'Part of this capture has already been returned, so it can no longer be reversed. A reversal means the whole capture is undone; returning what is left is a refund.',
  };

  return (
    <>
      <Card
        title="Returns"
        scope="Money returned after capture. Each return is its own operation with its own balanced journal; the original capture is never altered."
      >
        <div className="grid cols-3">
          <Stat
            label="Captured"
            value={
              summary.capturedAmountMinor === null ? (
                <span className="id-short">not captured</span>
              ) : (
                <Money minorUnits={summary.capturedAmountMinor} currency={summary.currency} />
              )
            }
            note="What the capture moved"
          />
          <Stat
            label="Returned"
            value={<Money minorUnits={summary.returnedAmountMinor} currency={summary.currency} />}
            /* The payment's total, not the page's length. Showing the page length here reported 200
               returns for a payment that had 201, while the amount beside it counted all of them. */
            note={`${summary.returnCount} return${summary.returnCount === 1 ? '' : 's'}`}
          />
          <Stat
            label="Remaining refundable"
            value={<Money minorUnits={summary.remainingRefundableMinor} currency={summary.currency} />}
            note="Shared by refunds and reversal"
          />
        </div>

        {summary.unavailableReason && (
          <Notice
            tone={summary.unavailableReason === 'NOT_CAPTURED' ? 'info' : 'warning'}
            title={summary.refundable ? 'Reversal is no longer available' : 'No further returns are possible'}
          >
            <span>{unavailable[summary.unavailableReason]}</span>
          </Notice>
        )}

        {canCommand && summary.refundable && (
          <div className="stack">
            <Field
              label="Refund amount"
              hint={`Up to ${formatMinorUnits(summary.remainingRefundableMinor)} ${summary.currency}. Converted exactly from what you type; excess decimals are refused rather than rounded.`}
              error={amountError}
            >
              {(fieldProps) => (
                <input
                  {...fieldProps}
                  type="text"
                  inputMode="decimal"
                  autoComplete="off"
                  placeholder="0.00"
                  value={amountText}
                  onChange={(event) => setAmountText(event.target.value)}
                />
              )}
            </Field>
            <Field label="Reason" hint="Optional, kept with the return operation as provenance. At most 140 characters.">
              {(fieldProps) => (
                <input
                  {...fieldProps}
                  type="text"
                  maxLength={140}
                  autoComplete="off"
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                />
              )}
            </Field>
            <div className="row">
              <button
                type="button"
                className="primary"
                disabled={!amountReady || busy || blocked}
                onClick={() => setConfirming('refund')}
              >
                Refund
              </button>
              <button
                type="button"
                disabled={busy || blocked}
                onClick={() => setAmountText(formatMinorUnits(summary.remainingRefundableMinor).replace(/,/g, ''))}
              >
                Refund everything remaining
              </button>
              {summary.reversible && (
                <button type="button" disabled={busy || blocked} onClick={() => setConfirming('reverse')}>
                  Reverse the capture
                </button>
              )}
            </div>
            {/* "Refund everything remaining" fills the exact amount rather than sending a request that
                means "whatever is left". The remainder changes as other refunds commit, so a key whose
                meaning depends on when it arrives would be a key whose meaning drifts. */}
          </div>
        )}

        {summary.returnCount === 0 ? (
          <EmptyState title="No returns on this payment" />
        ) : (
          <TableScroll>
            <table>
              <thead>
                <tr>
                  <th scope="col">#</th>
                  <th scope="col">Type</th>
                  <th scope="col" className="numeric">Amount</th>
                  <th scope="col">Reason</th>
                  <th scope="col">Journal</th>
                  <th scope="col">Recorded</th>
                </tr>
              </thead>
              <tbody>
                {summary.returns.map((entry) => (
                  <tr key={entry.id}>
                    <td>{entry.sequenceNumber}</td>
                    <td>
                      <Badge tone={entry.returnType === 'REVERSAL' ? 'warning' : 'info'}>{entry.returnType}</Badge>
                    </td>
                    <td className="numeric">
                      <Money minorUnits={entry.amountMinor} currency={entry.currency} />
                    </td>
                    <td>{entry.reason ?? '—'}</td>
                    <td>{entry.journalId ? <ShortIdentifier value={entry.journalId} /> : '—'}</td>
                    <td><Timestamp value={entry.createdAt} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableScroll>
        )}

        {summary.returnCount > 0 && (
          <div className="row between">
            {/* Which of the total is on screen, stated rather than implied by the row count. */}
            <span className="field-hint">
              Showing {summary.returns.length} of {summary.returnCount} return
              {summary.returnCount === 1 ? '' : 's'}, newest first.
              {summary.nextCursor === null && !paged ? '' : ' Older operations continue below.'}
            </span>
            <div className="row">
              {paged && (
                <button type="button" onClick={() => onPage(null)}>
                  Newest
                </button>
              )}
              {summary.nextCursor !== null && (
                <button type="button" onClick={() => onPage(summary.nextCursor)}>
                  Older returns
                </button>
              )}
            </div>
          </div>
        )}
      </Card>

      <ConfirmDialog
        open={confirming !== null}
        title={confirming === 'reverse' ? 'Reverse this capture?' : 'Refund this amount?'}
        confirmLabel={confirming === 'reverse' ? 'Reverse the capture' : 'Return the funds'}
        destructive={confirming === 'reverse'}
        confirming={busy}
        onCancel={() => setConfirming(null)}
        onConfirm={() => {
          if (confirming === 'reverse') {
            onReverse(reason);
          } else if (parsed.ok) {
            onRefund(parsed.minorUnits, reason);
          }
          setConfirming(null);
          setAmountText('');
          setReason('');
        }}
      >
        <div className="confirm-summary stack tight">
          <div className="row between">
            <span className="field-label">Payment</span>
            <Identifier value={summary.paymentId} />
          </div>
          <div className="row between">
            <span className="field-label">Amount returned</span>
            <strong>
              {confirming === 'reverse'
                ? `${formatMinorUnits(summary.capturedAmountMinor ?? 0)} ${summary.currency}`
                : `${parsed.ok ? formatMinorUnits(parsed.minorUnits) : '—'} ${summary.currency}`}
            </strong>
          </div>
          <div className="row between">
            <span className="field-label">Account credited</span>
            <ShortIdentifier value={summary.accountId} />
          </div>
        </div>
        <p>
          {confirming === 'reverse'
            ? 'A reversal returns the whole captured amount in one operation and cannot be undone. The original capture and its journal stay exactly as they are; this adds a new balanced journal recording the money coming back.'
            : 'The funds are credited back to the account and recorded in a new balanced journal. The original capture is not altered. Holds belonging to other authorizations on this account are untouched.'}
        </p>
      </ConfirmDialog>
    </>
  );
}

/** Reports what a return did, including the case where its outcome is unknown. */
function ReturnOutcome({
  kind,
  handle,
  onRetry,
}: {
  kind: 'refund' | 'reverse';
  handle: CommandHandle<ReturnReceipt>;
  onRetry: () => void;
}) {
  const label = kind === 'refund' ? 'Refund' : 'Reversal';
  if (handle.state.phase === 'uncertain' && handle.submitted) {
    return (
      <UnresolvedCommand
        title={`${label} may or may not have been applied`}
        detail={handle.state.error.detail}
        submitted={handle.submitted}
        busy={handle.busy}
        onRetry={onRetry}
      />
    );
  }
  if (handle.state.phase === 'failed') {
    const error = handle.state.error;
    if (error instanceof ApiError && error.conflict) {
      return (
        <Notice tone="warning" title={`${label} was refused`}>
          <span>{error.detail}</span>
          <span>The returns shown below have been re-read from the server.</span>
        </Notice>
      );
    }
    return <ErrorNotice error={error} context={label} />;
  }
  if (handle.state.phase === 'succeeded') {
    const receipt = handle.state.result;
    return (
      <Notice tone="success" title={`${label} recorded`}>
        {/* The receipt's own numbers are shown, because they describe this operation. What is left to
            return comes from the fresh read below, not from here: a replayed key returns the receipt as
            it stood when the command first ran. */}
        <span>
          {formatMinorUnits(receipt.amountMinor)} {receipt.currency} was returned to the account, recorded
          in journal {receipt.journalId}. The totals below have been re-read from the server.
        </span>
      </Notice>
    );
  }
  return null;
}

/** Reports what a command did, including the case where its outcome is unknown. */
function CommandOutcome({
  kind,
  handle,
  onRetry,
}: {
  kind: 'capture' | 'void';
  handle: CommandHandle<Payment>;
  /** Supplied by the page so a retry re-reads the payment, exactly as a first attempt does. */
  onRetry: () => void;
}) {
  const label = kind === 'capture' ? 'Capture' : 'Void';
  if (handle.state.phase === 'uncertain' && handle.submitted) {
    return (
      <UnresolvedCommand
        title={`${label} may or may not have been applied`}
        detail={handle.state.error.detail}
        submitted={handle.submitted}
        busy={handle.busy}
        onRetry={onRetry}
      />
    );
  }
  if (handle.state.phase === 'failed') {
    const error = handle.state.error;
    if (error instanceof ApiError && error.conflict) {
      return (
        <Notice tone="warning" title={`${label} was refused because the payment changed`}>
          <span>{error.detail}</span>
          <span>The payment state shown below has been re-read from the server.</span>
        </Notice>
      );
    }
    return <ErrorNotice error={error} context={label} />;
  }
  if (handle.state.phase === 'succeeded') {
    return (
      <Notice tone="success" title={`${label} completed`}>
        {/* Deliberately not "the payment is now X" taken from the command's response. Under a replayed
            key that response is the result as it stood when the command first ran. The payment shown
            below is a fresh read, so that is what the operator is pointed at. */}
        <span>The server recorded this command. The payment below has been re-read from the server.</span>
      </Notice>
    );
  }
  return null;
}

/**
 * The lifecycle, with the three kinds of evidence kept apart.
 *
 * A published event is not a processed event, so publication and per-consumer-group consumption are
 * shown as separate facts rather than rolled into one "delivered" state.
 */
function LifecycleTimeline({ query }: { query: { isPending: boolean; error: unknown; data?: PaymentTimeline } }) {
  return (
    <Card
      title="Lifecycle"
      scope="Commands from the payment transaction, event publication, and what each consumer group has recorded."
    >
      {query.isPending && <LoadingRows rows={4} label="Loading lifecycle" />}
      {query.error ? <ErrorNotice error={query.error} context="Loading the lifecycle" /> : null}
      {query.data && (
        <>
          <div className="timeline">
            {query.data.commands.map((command, index) => (
              <div className="timeline-item" key={`command-${index}`}>
                <span className="timeline-marker done" aria-hidden="true">
                  ✓
                </span>
                <div className="timeline-content">
                  <div className="timeline-heading">
                    <strong>{command.action}</strong>
                    <Badge tone="success" plain>
                      committed
                    </Badge>
                  </div>
                  <span className="timeline-meta">
                    Payment transaction · <Timestamp value={command.occurredAt} />
                  </span>
                </div>
              </div>
            ))}
          </div>

          <div className="stack tight">
            <span className="field-label">Event delivery</span>
            {query.data.events.length === 0 ? (
              <span className="field-hint">No events recorded for this payment.</span>
            ) : (
              <TableScroll>
                <table>
                  <thead>
                    <tr>
                      <th scope="col" className="numeric">Seq</th>
                      <th scope="col">Event</th>
                      <th scope="col">Delivery</th>
                      <th scope="col">Published</th>
                      <th scope="col" className="numeric">Attempts</th>
                      <th scope="col">Consumed by</th>
                    </tr>
                  </thead>
                  <tbody>
                    {query.data.events.map((event) => (
                      <tr key={event.eventId}>
                        <td className="numeric">{event.sequence}</td>
                        <td>
                          <div>{event.eventType}</div>
                          <span className="id-short">committed <Timestamp value={event.committedAt} /></span>
                        </td>
                        <td>
                          <DeliveryBadge status={event.deliveryStatus} />
                          {event.lastFailureKind && (
                            <div className="id-short">{event.lastFailureKind}</div>
                          )}
                        </td>
                        <td>
                          {event.publishedAt ? (
                            <>
                              <Timestamp value={event.publishedAt} />
                              <div className="id-short">
                                partition {event.brokerPartition} · offset {event.brokerOffset}
                              </div>
                            </>
                          ) : (
                            <span className="id-short">not yet published</span>
                          )}
                        </td>
                        <td className="numeric">{event.attempts}</td>
                        <td>
                          {event.consumers.length === 0 ? (
                            <span className="id-short">no consumer has recorded it</span>
                          ) : (
                            <div className="stack tight">
                              {event.consumers.map((consumer) => (
                                <span key={consumer.consumerGroup} className="id-short">
                                  {consumer.consumerGroup} · <Timestamp value={consumer.consumedAt} />
                                </span>
                              ))}
                            </div>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableScroll>
            )}
          </div>

          <div className="stack tight">
            <span className="field-label">Activity projection</span>
            {query.data.projection ? (
              <KeyValues
                entries={[
                  ['Projected status', query.data.projection.lastStatus],
                  ['Last applied event', `${query.data.projection.lastEventType} (sequence ${query.data.projection.lastSequence})`],
                  ['Events applied', query.data.projection.appliedEventCount],
                  ['First projected', <Timestamp value={query.data.projection.firstEventAt} />],
                  ['Last projected', <Timestamp value={query.data.projection.lastEventAt} />],
                ]}
              />
            ) : (
              <span className="field-hint">
                No event for this payment has been projected yet. The payment record above is still
                authoritative and complete; only the read model is behind.
              </span>
            )}
          </div>
        </>
      )}
    </Card>
  );
}
