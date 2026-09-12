import { useEffect, useId, useRef, type ReactNode } from 'react';
import { ApiError } from '../api/client';
import { formatMinorUnits } from '../lib/money';
import type { ReasonContribution, RiskOutcome } from '../api/types';
import type { SubmittedCommand } from '../lib/command';

/** Shared presentation pieces. Everything renders server strings as text, never as markup. */

type Tone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';

export function Badge({ tone, children, plain }: { tone: Tone; children: ReactNode; plain?: boolean }) {
  return <span className={`badge badge-${tone}${plain ? ' plain' : ''}`}>{children}</span>;
}

/** Payment lifecycle status. The word is always present, so colour is never the only signal. */
export function PaymentStatusBadge({ status }: { status: string }) {
  const tones: Record<string, Tone> = {
    AUTHORIZED: 'info',
    CAPTURED: 'success',
    VOIDED: 'neutral',
    DECLINED: 'danger',
    REVIEW: 'warning',
  };
  return <Badge tone={tones[status] ?? 'neutral'}>{status}</Badge>;
}

/**
 * Stored risk outcome.
 *
 * Deliberately a different component from the payment status badge. They are different facts and an
 * APPROVE risk decision sitting next to a DECLINED payment is a normal, correct combination that the
 * screen must not blur into one.
 */
export function RiskBadge({ outcome }: { outcome: RiskOutcome | string | null }) {
  if (!outcome) return <span className="id-short">not evaluated</span>;
  const tones: Record<string, Tone> = { APPROVE: 'success', REVIEW: 'warning', DECLINE: 'danger' };
  return <Badge tone={tones[outcome] ?? 'neutral'}>{outcome}</Badge>;
}

export function DeliveryBadge({ status }: { status: string }) {
  const tones: Record<string, Tone> = {
    PENDING: 'warning',
    CLAIMED: 'info',
    PUBLISHED: 'success',
    FAILED: 'danger',
  };
  return <Badge tone={tones[status] ?? 'neutral'}>{status}</Badge>;
}

export function HealthBadge({ status }: { status: string }) {
  const tones: Record<string, Tone> = {
    UP: 'success',
    DEGRADED: 'warning',
    DOWN: 'danger',
    OUT_OF_SERVICE: 'danger',
    UNKNOWN: 'neutral',
  };
  return <Badge tone={tones[status] ?? 'neutral'}>{status}</Badge>;
}

export function BreakerBadge({ state }: { state: string }) {
  const tones: Record<string, Tone> = { CLOSED: 'success', HALF_OPEN: 'warning', OPEN: 'danger' };
  return <Badge tone={tones[state] ?? 'neutral'}>{state.replace('_', ' ')}</Badge>;
}

export function Card({
  title,
  scope,
  actions,
  children,
  tight,
}: {
  title?: ReactNode;
  /** What the card's numbers cover. Stated so a figure is never read as more than it is. */
  scope?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  tight?: boolean;
}) {
  return (
    <section className="card">
      {(title || actions) && (
        <header className="card-header">
          <div className="card-title-group">
            {title && <h2>{title}</h2>}
            {scope && <span className="card-scope">{scope}</span>}
          </div>
          {actions && <div className="row">{actions}</div>}
        </header>
      )}
      <div className={tight ? 'card-body tight' : 'card-body'}>{children}</div>
    </section>
  );
}

export function Stat({
  label,
  value,
  note,
  unavailable,
}: {
  label: ReactNode;
  value?: ReactNode;
  note?: ReactNode;
  /** Shown instead of a value when the server did not provide one. Never substitute zero. */
  unavailable?: string;
}) {
  return (
    <div className="stat">
      <span className="stat-label">{label}</span>
      {unavailable ? (
        <span className="stat-value unavailable">{unavailable}</span>
      ) : (
        <span className="stat-value">{value}</span>
      )}
      {note && <span className="stat-note">{note}</span>}
    </div>
  );
}

export function Notice({
  tone,
  title,
  children,
}: {
  tone: 'danger' | 'warning' | 'info' | 'success';
  title?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <div className={`notice notice-${tone}`} role={tone === 'danger' ? 'alert' : 'status'}>
      {title && <span className="notice-title">{title}</span>}
      {children}
    </div>
  );
}

/**
 * Turns a failure into something an operator can act on.
 *
 * An indeterminate outcome is called out separately, because "we do not know whether this happened"
 * needs a different response from "this did not happen".
 */
export function ErrorNotice({ error, context }: { error: unknown; context?: string }) {
  if (error instanceof ApiError) {
    const title = error.indeterminate
      ? 'The outcome of this request is unknown'
      : context
        ? `${context} failed`
        : 'Request failed';
    return (
      <Notice tone={error.indeterminate ? 'warning' : 'danger'} title={title}>
        <span>{error.detail}</span>
        <span className="field-hint">
          {error.code}
          {error.requestId ? ` · request ${error.requestId}` : ''}
        </span>
      </Notice>
    );
  }
  return (
    <Notice tone="danger" title={context ? `${context} failed` : 'Something went wrong'}>
      <span>{error instanceof Error ? error.message : 'An unexpected error occurred.'}</span>
    </Notice>
  );
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty-state">
      <span className="empty-title">{title}</span>
      {children}
    </div>
  );
}

export function LoadingRows({ rows = 4, label = 'Loading' }: { rows?: number; label?: string }) {
  return (
    <div className="skeleton-rows" aria-busy="true" aria-live="polite">
      <span className="visually-hidden">{label}</span>
      {Array.from({ length: rows }, (_, index) => (
        <div className="skeleton" key={index} style={{ width: `${92 - index * 7}%` }} />
      ))}
    </div>
  );
}

/** A labelled input. The label is always a real label bound to the control. */
export function Field({
  label,
  hint,
  error,
  children,
}: {
  label: ReactNode;
  hint?: ReactNode;
  error?: string | null;
  children: (props: { id: string; 'aria-describedby': string | undefined; 'aria-invalid': boolean }) => ReactNode;
}) {
  const id = useId();
  const hintId = hint || error ? `${id}-hint` : undefined;
  return (
    <div className="field">
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      {children({ id, 'aria-describedby': hintId, 'aria-invalid': Boolean(error) })}
      {error ? (
        <span className="field-error" id={hintId}>
          {error}
        </span>
      ) : (
        hint && (
          <span className="field-hint" id={hintId}>
            {hint}
          </span>
        )
      )}
    </div>
  );
}

/** Key/value detail list. */
export function KeyValues({ entries }: { entries: Array<[ReactNode, ReactNode]> }) {
  return (
    <dl className="kv">
      {entries.map(([key, value], index) => (
        <div key={index} style={{ display: 'contents' }}>
          <dt>{key}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** Full identifier, monospaced and selectable. Never truncated in a detail view. */
export function Identifier({ value }: { value: string }) {
  return <span className="id-cell">{value}</span>;
}

/** Shortened identifier for dense tables, with the full value available to assistive technology. */
export function ShortIdentifier({ value }: { value: string }) {
  return (
    <span className="id-cell" title={value}>
      {value.slice(0, 8)}
      <span className="visually-hidden">{value.slice(8)}</span>
    </span>
  );
}

export function Timestamp({ value }: { value: string | null | undefined }) {
  if (!value) return <span className="id-short">—</span>;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return <span className="id-short">{value}</span>;
  return (
    <time dateTime={value} title={value}>
      {parsed.toLocaleString()}
    </time>
  );
}

/** Reason contributions, rendered as text with their numeric contribution aligned. */
export function Reasons({ reasons }: { reasons: ReasonContribution[] | null | undefined }) {
  if (!reasons || reasons.length === 0) {
    return <span className="field-hint">No reasons recorded.</span>;
  }
  return (
    <ul className="reasons">
      {reasons.map((reason) => (
        <li className="reason" key={reason.code}>
          <span>
            <span className="reason-code">{reason.code}</span>
            <br />
            <span className="reason-description">{reason.description}</span>
          </span>
          <span className="reason-contribution">
            {reason.scoreContribution > 0 ? `+${reason.scoreContribution}` : reason.scoreContribution}
          </span>
        </li>
      ))}
    </ul>
  );
}

/**
 * A score and how it was bounded.
 *
 * When a candidate's contributions add up past the top of the scale the bar fills completely, and the
 * raw total is stated alongside. Showing only the capped number would make two very different policies
 * look identical.
 */
export function ScoreDisplay({
  score,
  rawScore,
  capped,
}: {
  score: number | null;
  rawScore?: number | null;
  capped?: boolean;
}) {
  if (score === null || score === undefined) return <span className="id-short">not scored</span>;
  const tone = score >= 60 ? 'var(--danger)' : score >= 30 ? 'var(--warning)' : 'var(--success)';
  return (
    <div className="stack tight">
      <div className="row" style={{ gap: 'var(--space-2)' }}>
        <strong style={{ fontVariantNumeric: 'tabular-nums' }}>{score}</strong>
        {capped && rawScore != null && (
          <Badge tone="warning">capped from {rawScore}</Badge>
        )}
      </div>
      <div className="score-bar" role="img" aria-label={`Score ${score} of 100${capped && rawScore != null ? `, capped from a raw total of ${rawScore}` : ''}`}>
        <div className="score-bar-fill" style={{ width: `${Math.min(100, score)}%`, background: tone }} />
      </div>
    </div>
  );
}

export function Money({ minorUnits, currency }: { minorUnits: number; currency: string }) {
  return (
    <span style={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
      {formatMinorUnits(minorUnits)} <span className="id-short">{currency}</span>
    </span>
  );
}

/**
 * A modal confirmation.
 *
 * Uses the native dialog element so focus trapping, Escape, and the backdrop come from the platform
 * rather than from hand-written key handling.
 */
export function ConfirmDialog({
  open,
  title,
  confirmLabel,
  confirming,
  destructive,
  onConfirm,
  onCancel,
  children,
}: {
  open: boolean;
  title: string;
  confirmLabel: string;
  confirming?: boolean;
  destructive?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  return (
    <dialog ref={ref} onCancel={(event) => { event.preventDefault(); onCancel(); }} aria-labelledby="confirm-title">
      <div className="dialog-header">
        <h2 id="confirm-title">{title}</h2>
      </div>
      <div className="dialog-body">{children}</div>
      <div className="dialog-footer">
        <button type="button" onClick={onCancel} disabled={confirming}>
          Cancel
        </button>
        <button
          type="button"
          className={destructive ? 'danger' : 'primary'}
          onClick={onConfirm}
          disabled={confirming}
        >
          {confirming ? 'Working…' : confirmLabel}
        </button>
      </div>
    </dialog>
  );
}

/**
 * A command whose outcome the server never confirmed.
 *
 * Shows exactly what was submitted, because "retry safely" is only trustworthy if the operator can see
 * which command is being retried. Retrying replays the captured submission under its original
 * idempotency key, so if the command did land the server returns its original result rather than
 * performing it a second time.
 */
export function UnresolvedCommand({
  title,
  detail,
  submitted,
  busy,
  onRetry,
}: {
  title: string;
  detail: string;
  submitted: SubmittedCommand;
  busy: boolean;
  onRetry: () => void;
}) {
  return (
    <Notice tone="warning" title={title}>
      <span>{detail}</span>
      <span>
        Retrying resends exactly this command under its original key, so if it did go through you will
        see its original result rather than a second one.
      </span>
      <div className="confirm-summary stack tight" style={{ marginTop: 'var(--space-2)' }}>
        <span className="comparison-label">Submitted command</span>
        {submitted.summary.map((entry) => (
          <div className="row between" key={entry.label}>
            <span className="field-label">{entry.label}</span>
            <span className="mono">{entry.value}</span>
          </div>
        ))}
        <div className="row between">
          <span className="field-label">Request</span>
          <span className="mono">
            {submitted.method} {submitted.path}
          </span>
        </div>
        <div className="row between">
          <span className="field-label">Idempotency key</span>
          <span className="mono">{submitted.idempotencyKey}</span>
        </div>
        <div className="row between">
          <span className="field-label">Attempts</span>
          <span className="mono">{submitted.attempts}</span>
        </div>
      </div>
      <div className="row">
        <button type="button" onClick={onRetry} disabled={busy}>
          {busy ? 'Retrying…' : 'Retry safely'}
        </button>
      </div>
    </Notice>
  );
}

/** A table whose horizontal overflow scrolls inside itself rather than the page. */
export function TableScroll({ children }: { children: ReactNode }) {
  return <div className="table-scroll">{children}</div>;
}
