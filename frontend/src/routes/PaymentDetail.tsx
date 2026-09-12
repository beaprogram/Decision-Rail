import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api/client';
import { merchantApi } from '../api/endpoints';
import { useSession } from '../auth/session';
import { useIdempotentCommand } from '../lib/command';
import { formatMinorUnits } from '../lib/money';
import { PageHeader } from '../components/Shell';
import {
  Badge,
  Card,
  ConfirmDialog,
  DeliveryBadge,
  EmptyState,
  ErrorNotice,
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
} from '../components/ui';
import type { Payment, PaymentTimeline } from '../api/types';

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

  const capture = useIdempotentCommand('capture', (key) => merchantApi.capture(key, paymentId));
  const voidCommand = useIdempotentCommand('void', (key) => merchantApi.voidPayment(key, paymentId));
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
  const fundingDecline = record.status === 'DECLINED' && record.decision.outcome === 'APPROVE';
  const canCapture = can.createPayments && record.status === 'AUTHORIZED';
  const canVoid = can.createPayments && record.status === 'AUTHORIZED';

  const runCommand = async (kind: 'capture' | 'void') => {
    const handle = kind === 'capture' ? capture : voidCommand;
    const result = await handle.run(undefined);
    setPending(null);
    if (result) refreshEverything();
    else refreshEverything(); // A rejection or conflict also means re-reading the real state.
  };

  return (
    <>
      <PageHeader
        title="Payment"
        badge={<PaymentStatusBadge status={record.status} />}
        description={<Identifier value={record.id} />}
        actions={
          <>
            {canCapture && (
              <button type="button" className="primary" onClick={() => setPending('capture')}>
                Capture
              </button>
            )}
            {canVoid && (
              <button type="button" onClick={() => setPending('void')}>
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
        <CommandOutcome kind="capture" handle={capture} />
        <CommandOutcome kind="void" handle={voidCommand} />

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
            value={record.failureCode ? <Badge tone="danger">{record.failureCode}</Badge> : <Badge tone="success">Funds reserved</Badge>}
            note={record.status === 'VOIDED' ? 'Hold released' : undefined}
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

/** Reports what a command did, including the case where its outcome is unknown. */
function CommandOutcome({
  kind,
  handle,
}: {
  kind: 'capture' | 'void';
  handle: ReturnType<typeof useIdempotentCommand<undefined, Payment>>;
}) {
  const label = kind === 'capture' ? 'Capture' : 'Void';
  if (handle.state.phase === 'uncertain') {
    return (
      <Notice tone="warning" title={`${label} may or may not have been applied`}>
        <span>{handle.state.error.detail}</span>
        <span>
          Retrying uses the same idempotency key, so if the command did go through you will get its
          original result rather than a second one.
        </span>
        <div className="row">
          <button type="button" onClick={() => void handle.retry()} disabled={handle.busy}>
            Retry safely
          </button>
        </div>
      </Notice>
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
        <span>The payment is now {handle.state.result.status}.</span>
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
