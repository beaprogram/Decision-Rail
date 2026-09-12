import { useCallback, useRef, useState } from 'react';
import { ApiError, apiFetch } from '../api/client';

/**
 * Runs a money-moving command as an immutable submission.
 *
 * Three rules drive the design, and all three exist because of what happens when an outcome is
 * unknown.
 *
 * **One key per logical command, kept across retries.** The key identifies the operation, not the HTTP
 * attempt. A fresh key on retry would make the server treat it as a brand new authorization and reserve
 * funds twice, which is exactly what idempotency exists to prevent.
 *
 * **The whole request is captured, not just the key.** Method, path, and the serialized body are frozen
 * at submission and replayed verbatim. Rebuilding a retry from live form state is worse than useless:
 * it sends different content under the original key, and the server correctly refuses that as a
 * conflicting reuse, destroying the one thing that could have recovered the original command.
 *
 * **An unknown outcome is not a failure.** A timeout, a dropped connection, or a success whose body
 * never arrived all mean the command may well have been applied. Reporting "failed" would invite the
 * operator to try again as if nothing had happened. Those become an `uncertain` state whose only safe
 * resolution is retrying the captured submission, which returns the original result if it did land.
 */

/** What the caller wants to send. Serialized once, at submission. */
export interface CommandRequest {
  method: 'POST' | 'PUT' | 'DELETE';
  path: string;
  /** Serialized once at submission; never re-derived. Omit for a command with no body. */
  body?: unknown;
  /** What to show the operator about this submission while its outcome is unresolved. */
  summary?: Array<{ label: string; value: string }>;
}

/** Exactly what was sent, frozen. A retry replays this and nothing else. */
export interface SubmittedCommand {
  readonly idempotencyKey: string;
  readonly method: string;
  readonly path: string;
  /** The exact bytes sent, or null for a bodyless command. */
  readonly serializedBody: string | null;
  readonly summary: ReadonlyArray<{ label: string; value: string }>;
  readonly submittedAt: string;
  readonly attempts: number;
}

export type CommandState<TResult> =
  | { phase: 'idle' }
  | { phase: 'running' }
  | { phase: 'succeeded'; result: TResult }
  | { phase: 'failed'; error: unknown }
  /** The server's answer is unknown. Retrying the captured submission is the only safe resolution. */
  | { phase: 'uncertain'; error: ApiError };

export interface CommandHandle<TResult> {
  state: CommandState<TResult>;
  /** The submission being tracked, so the screen can show which command it is recovering. */
  submitted: SubmittedCommand | null;
  /** Sends a new command. Ignored while one is in flight, so a double click cannot double submit. */
  submit: (request: CommandRequest) => Promise<TResult | undefined>;
  /** Replays the captured submission byte for byte, under its original key. */
  retry: () => Promise<TResult | undefined>;
  reset: () => void;
  busy: boolean;
  /** True while an outcome is unknown, so the screen can stop offering a fresh submission. */
  unresolved: boolean;
}

/** Matches the backend's accepted key shape: 8 to 128 of letters, digits, dot, underscore, colon, hyphen. */
export function newIdempotencyKey(purpose: string): string {
  const unique =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  return `ui-${purpose}-${unique}`.slice(0, 128);
}

/** True when the outcome is unknown or the server may recover, so the same submission must be reused. */
function mustKeepSubmission(error: unknown): error is ApiError {
  if (!(error instanceof ApiError)) return false;
  // Indeterminate: no response, or none that could be read. 5xx: the server failed after possibly
  // having applied the write, and a storage failure in particular is documented as retryable with the
  // same key.
  return error.indeterminate || error.status >= 500;
}

export function useIdempotentCommand<TResult>(purpose: string): CommandHandle<TResult> {
  const submittedRef = useRef<SubmittedCommand | null>(null);
  const inFlight = useRef(false);
  const [submitted, setSubmitted] = useState<SubmittedCommand | null>(null);
  const [state, setState] = useState<CommandState<TResult>>({ phase: 'idle' });

  const send = useCallback(async (command: SubmittedCommand): Promise<TResult | undefined> => {
    if (inFlight.current) return undefined;
    inFlight.current = true;
    const attempt: SubmittedCommand = { ...command, attempts: command.attempts + 1 };
    submittedRef.current = attempt;
    setSubmitted(attempt);
    setState({ phase: 'running' });
    try {
      const result = await apiFetch<TResult>(attempt.path, {
        method: attempt.method,
        idempotencyKey: attempt.idempotencyKey,
        ...(attempt.serializedBody === null ? {} : { rawBody: attempt.serializedBody }),
      });
      // The server answered definitively, so this logical command is finished.
      submittedRef.current = null;
      setSubmitted(null);
      setState({ phase: 'succeeded', result });
      return result;
    } catch (error) {
      if (mustKeepSubmission(error)) {
        // The submission stays captured so the retry is the same command, not a new one.
        setState({ phase: 'uncertain', error });
      } else {
        // A definite rejection. Whatever the operator does next is a new command, so nothing is held
        // back for recovery.
        submittedRef.current = null;
        setSubmitted(null);
        setState({ phase: 'failed', error });
      }
      return undefined;
    } finally {
      inFlight.current = false;
    }
  }, []);

  const submit = useCallback(
    async (request: CommandRequest): Promise<TResult | undefined> => {
      // An unresolved command must be retried or abandoned deliberately, never quietly replaced by a
      // new one: minting a second key for work that may already exist is how a payment gets made twice.
      if (submittedRef.current !== null) return undefined;
      return send({
        idempotencyKey: newIdempotencyKey(purpose),
        method: request.method,
        path: request.path,
        serializedBody: request.body === undefined ? null : JSON.stringify(request.body),
        summary: Object.freeze([...(request.summary ?? [])]),
        submittedAt: new Date().toISOString(),
        attempts: 0,
      });
    },
    [purpose, send],
  );

  const retry = useCallback(async (): Promise<TResult | undefined> => {
    const command = submittedRef.current;
    if (command === null) return undefined;
    return send(command);
  }, [send]);

  const reset = useCallback(() => {
    submittedRef.current = null;
    setSubmitted(null);
    setState({ phase: 'idle' });
  }, []);

  return {
    state,
    submitted,
    submit,
    retry,
    reset,
    busy: state.phase === 'running',
    unresolved: state.phase === 'uncertain',
  };
}
