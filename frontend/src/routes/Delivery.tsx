import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { opsApi } from '../api/endpoints';
import { PageHeader } from '../components/Shell';
import {
  Badge,
  BreakerBadge,
  Card,
  ConfirmDialog,
  EmptyState,
  ErrorNotice,
  HealthBadge,
  Identifier,
  LoadingRows,
  Notice,
  ShortIdentifier,
  Stat,
  TableScroll,
  Timestamp,
} from '../components/ui';
import type { FailedEvent, RedriveResult } from '../api/types';

const FAILED_PAGE = 25;

/**
 * The administrative delivery workspace.
 *
 * Liveness, readiness and asynchronous capability are presented as three separate signals because they
 * answer different questions. A broker outage degrades asynchronous delivery while readiness stays up,
 * and showing one rolled-up status would hide exactly the distinction that matters.
 */
export function DeliveryPage() {
  const queries = useQueryClient();
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [confirming, setConfirming] = useState(false);
  const [lastRedrive, setLastRedrive] = useState<RedriveResult | null>(null);

  const delivery = useQuery({
    queryKey: ['delivery'],
    queryFn: ({ signal }) => opsApi.delivery(signal),
    // A fixed, modest interval: this is a status board, and a tighter loop would add load without
    // telling an operator anything sooner than they can act on.
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  });

  const failed = useQuery({
    queryKey: ['failed-events', offset],
    queryFn: ({ signal }) => opsApi.failedEvents(FAILED_PAGE, offset, signal),
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  });

  const redrive = useMutation({
    mutationFn: (eventIds: string[]) => opsApi.redrive({ eventIds }),
    onSuccess: (result) => {
      setLastRedrive(result);
      setSelected(new Set());
      void queries.invalidateQueries({ queryKey: ['failed-events'] });
      void queries.invalidateQueries({ queryKey: ['delivery'] });
    },
  });

  const status = delivery.data;
  const events = failed.data?.events ?? [];
  const selectedEvents = events.filter((event) => selected.has(event.eventId));

  const toggle = (eventId: string) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(eventId)) next.delete(eventId);
      else next.add(eventId);
      return next;
    });
  };

  return (
    <>
      <PageHeader
        title="Event delivery"
        description="Durable delivery state for committed payment events. Payments keep working while delivery is impaired; this screen shows what has not yet been delivered and why."
      />
      <div className="page-body">
        {delivery.isPending && <LoadingRows rows={3} label="Loading delivery status" />}
        {delivery.error && <ErrorNotice error={delivery.error} context="Loading delivery status" />}

        {status && (
          <>
            <div className="grid cols-4">
              <Stat label="Liveness" value={<HealthBadge status={status.liveness} />} note="Is the process alive" />
              <Stat
                label="Readiness"
                value={<HealthBadge status={status.readiness} />}
                note="Should payment traffic arrive here. Excludes the broker."
              />
              <Stat
                label="Asynchronous delivery"
                value={<HealthBadge status={status.asyncDelivery} />}
                note="Degrades on its own without affecting the payment API"
              />
              <Stat
                label="Broker circuit"
                value={<BreakerBadge state={status.breakerState} />}
                note="Open means sends are being skipped while the broker recovers"
              />
            </div>

            {status.asyncDelivery === 'DEGRADED' && (
              <Notice tone="warning" title="Asynchronous delivery is degraded">
                <span>
                  Committed events are accumulating rather than being lost. Payment authorization,
                  capture and void continue to work, and readiness stays up on purpose so a broker
                  outage does not take a correct payment API out of rotation.
                </span>
              </Notice>
            )}

            <div className="grid cols-4">
              {(['PENDING', 'CLAIMED', 'PUBLISHED', 'FAILED'] as const).map((key) => (
                <Stat key={key} label={`${key} events`} value={status.countsByStatus[key] ?? 0} />
              ))}
            </div>

            <div className="grid cols-2">
              <Stat
                label="Oldest undelivered event"
                value={status.oldestPendingAt ? `${status.oldestPendingAgeSeconds}s` : '—'}
                note={
                  status.oldestPendingAt ? (
                    <>
                      committed <Timestamp value={status.oldestPendingAt} />
                    </>
                  ) : (
                    'Nothing is waiting.'
                  )
                }
              />
              <Stat
                label="Stalled payment streams"
                value={status.blockedPaymentCount}
                note="Payments whose later events cannot be delivered until an earlier failure is resolved"
              />
            </div>
          </>
        )}

        {lastRedrive && (
          <Notice
            tone={lastRedrive.redrivenCount > 0 ? 'success' : 'warning'}
            title={`Redrive returned ${lastRedrive.redrivenCount} event${lastRedrive.redrivenCount === 1 ? '' : 's'} to the pending pool`}
          >
            <span>
              {lastRedrive.remainingFailedCount} event
              {lastRedrive.remainingFailedCount === 1 ? '' : 's'} still failed,{' '}
              {lastRedrive.stillBlockedPaymentCount} payment stream
              {lastRedrive.stillBlockedPaymentCount === 1 ? '' : 's'} still stalled.
            </span>
            {lastRedrive.redrivenCount === 0 && (
              <span>
                Nothing matched. The events may have already been redriven or delivered since the list was
                loaded.
              </span>
            )}
            <span className="field-hint">
              Event identity and payload are unchanged, so consumers deduplicate a redriven event exactly
              like any other redelivery.
            </span>
          </Notice>
        )}

        <Card
          title="Failed events"
          scope="Events that exhausted their retry budget, oldest first. The oldest failure for a payment is the one blocking its stream."
          actions={
            <>
              <span className="field-hint">
                {selectedEvents.length > 0
                  ? `${selectedEvents.length} selected`
                  : `${failed.data?.totalFailed ?? 0} failed in total`}
              </span>
              <button
                type="button"
                className="primary"
                disabled={selectedEvents.length === 0 || redrive.isPending}
                onClick={() => setConfirming(true)}
              >
                Redrive selected
              </button>
            </>
          }
          tight
        >
          {failed.isPending && <LoadingRows rows={3} label="Loading failed events" />}
          {failed.error && (
            <div className="card-body">
              <ErrorNotice error={failed.error} context="Loading failed events" />
            </div>
          )}
          {failed.data && events.length === 0 && (
            <EmptyState title="No failed events">
              <span>Every committed event has either been delivered or is still being retried.</span>
            </EmptyState>
          )}
          {events.length > 0 && (
            <>
              <TableScroll>
                <table>
                  <caption className="visually-hidden">Terminally failed events available for redrive</caption>
                  <thead>
                    <tr>
                      <th scope="col">
                        <span className="visually-hidden">Select</span>
                      </th>
                      <th scope="col">Event</th>
                      <th scope="col">Payment</th>
                      <th scope="col" className="numeric">Seq</th>
                      <th scope="col" className="numeric">Attempts</th>
                      <th scope="col">Last failure</th>
                      <th scope="col">Stream</th>
                    </tr>
                  </thead>
                  <tbody>
                    {events.map((event) => (
                      <tr key={event.eventId}>
                        <td>
                          <input
                            type="checkbox"
                            checked={selected.has(event.eventId)}
                            onChange={() => toggle(event.eventId)}
                            aria-label={`Select event ${event.eventId} for redrive`}
                          />
                        </td>
                        <td>
                          <ShortIdentifier value={event.eventId} />
                          <div className="id-short">{event.eventType}</div>
                        </td>
                        <td>
                          <ShortIdentifier value={event.paymentId} />
                          <div className="id-short">{event.merchantId}</div>
                        </td>
                        <td className="numeric">{event.aggregateSequence}</td>
                        <td className="numeric">{event.attempts}</td>
                        <td>
                          <div className="reason-description">{event.lastError ?? 'not recorded'}</div>
                          <div className="id-short">
                            last attempt <Timestamp value={event.lastAttemptAt} />
                          </div>
                        </td>
                        <td>
                          {event.blocksLaterEvents ? (
                            <Badge tone="danger">blocking later events</Badge>
                          ) : (
                            <Badge tone="neutral">last in stream</Badge>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableScroll>
              <div
                className="row between"
                style={{ padding: 'var(--space-3) var(--space-4)', borderTop: '1px solid var(--border)' }}
              >
                <span className="field-hint">
                  Showing {offset + 1}–{offset + events.length} of {failed.data?.totalFailed ?? 0}
                </span>
                <div className="row">
                  <button type="button" onClick={() => setOffset(Math.max(0, offset - FAILED_PAGE))} disabled={offset === 0}>
                    Previous
                  </button>
                  <button type="button" onClick={() => setOffset(offset + FAILED_PAGE)} disabled={!failed.data?.hasMore}>
                    Next
                  </button>
                </div>
              </div>
            </>
          )}
        </Card>

        {redrive.error && <ErrorNotice error={redrive.error} context="Redrive" />}

        <Notice tone="info" title="Outages are produced by local tooling, not from this screen">
          There is deliberately no control here that stops the broker, edits database rows, or arms a
          failure hook. To see an outage, use the documented local demo script.
        </Notice>
      </div>

      <ConfirmDialog
        open={confirming}
        title="Redrive these events?"
        confirmLabel={`Redrive ${selectedEvents.length} event${selectedEvents.length === 1 ? '' : 's'}`}
        confirming={redrive.isPending}
        onCancel={() => setConfirming(false)}
        onConfirm={() => {
          setConfirming(false);
          redrive.mutate(selectedEvents.map((event) => event.eventId));
        }}
      >
        {/* The exact selection is restated, because "redrive selected" is otherwise an unbounded
            instruction if the list changed underneath. */}
        <div className="confirm-summary stack tight">
          {selectedEvents.map((event) => (
            <RedriveTarget key={event.eventId} event={event} />
          ))}
        </div>
        <p>
          Each event returns to the pending pool with its identity and payload unchanged. Per-payment
          ordering is preserved: a later event still waits for its predecessor to publish.
        </p>
      </ConfirmDialog>
    </>
  );
}

function RedriveTarget({ event }: { event: FailedEvent }) {
  return (
    <div className="row between">
      <div className="stack tight">
        <Identifier value={event.eventId} />
        <span className="id-short">
          {event.eventType} · sequence {event.aggregateSequence} · payment {event.paymentId.slice(0, 8)}
        </span>
      </div>
      {event.blocksLaterEvents && <Badge tone="danger">unblocks a stream</Badge>}
    </div>
  );
}
