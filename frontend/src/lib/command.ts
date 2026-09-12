import { useCallback, useRef, useState } from 'react';
import { ApiError } from '../api/client';

/**
 * Runs a money-moving command with a correctly managed idempotency key.
 *
 * Two rules drive the whole design.
 *
 * **One key per logical command, kept across retries.** The key identifies the operation, not the HTTP
 * attempt. A fresh key on retry would make the server treat a retry as a brand new authorization and
 * reserve funds twice, which is exactly what idempotency exists to prevent.
 *
 * **An unknown outcome is not a failure.** If a request times out or the network drops, the command may
 * well have been applied. Reporting "failed" would invite the operator to try again as if nothing had
 * happened. Those cases become an `uncertain` state whose only safe next step is retrying with the same
 * key, which returns the original result if the command did land.
 */

export type CommandState<TResult> =
  | { phase: 'idle' }
  | { phase: 'running' }
  | { phase: 'succeeded'; result: TResult }
  | { phase: 'failed'; error: unknown }
  /** The server's answer is unknown. Retrying with the same key is the only safe resolution. */
  | { phase: 'uncertain'; error: ApiError };

export interface CommandHandle<TArgs, TResult> {
  state: CommandState<TResult>;
  /** Starts the command. Ignored while one is already in flight, so a double click cannot double submit. */
  run: (args: TArgs) => Promise<TResult | undefined>;
  /** Retries the same logical command, reusing its key. */
  retry: () => Promise<TResult | undefined>;
  reset: () => void;
  busy: boolean;
}

/** Matches the backend's accepted key shape: 8 to 128 of letters, digits, dot, underscore, colon, hyphen. */
export function newIdempotencyKey(purpose: string): string {
  const unique =
    typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  return `ui-${purpose}-${unique}`.slice(0, 128);
}

/** True when the outcome is unknown or the server may recover, so the same key must be reused. */
function mustKeepKey(error: unknown): error is ApiError {
  if (!(error instanceof ApiError)) return false;
  // Indeterminate: no response at all. 5xx: the server failed after possibly having applied the write,
  // and a storage failure in particular is documented as retryable with the same key.
  return error.indeterminate || error.status >= 500;
}

export function useIdempotentCommand<TArgs, TResult>(
  purpose: string,
  perform: (idempotencyKey: string, args: TArgs) => Promise<TResult>,
): CommandHandle<TArgs, TResult> {
  const keyRef = useRef<string | null>(null);
  const argsRef = useRef<TArgs | null>(null);
  const inFlight = useRef(false);
  const [state, setState] = useState<CommandState<TResult>>({ phase: 'idle' });

  const execute = useCallback(
    async (args: TArgs): Promise<TResult | undefined> => {
      // A second submission while the first is outstanding is a mistake, not a second command.
      if (inFlight.current) return undefined;
      inFlight.current = true;
      // Only mint a key when there is no command in progress: a retry keeps the existing one.
      keyRef.current ??= newIdempotencyKey(purpose);
      argsRef.current = args;
      setState({ phase: 'running' });
      try {
        const result = await perform(keyRef.current, args);
        // The server answered definitively, so this logical command is finished.
        keyRef.current = null;
        setState({ phase: 'succeeded', result });
        return result;
      } catch (error) {
        if (mustKeepKey(error)) {
          setState({ phase: 'uncertain', error });
        } else {
          // A definite rejection. Whatever the operator does next is a new command.
          keyRef.current = null;
          setState({ phase: 'failed', error });
        }
        return undefined;
      } finally {
        inFlight.current = false;
      }
    },
    [perform, purpose],
  );

  const retry = useCallback(async (): Promise<TResult | undefined> => {
    if (argsRef.current === null) return undefined;
    return execute(argsRef.current);
  }, [execute]);

  const reset = useCallback(() => {
    keyRef.current = null;
    argsRef.current = null;
    setState({ phase: 'idle' });
  }, []);

  return { state, run: execute, retry, reset, busy: state.phase === 'running' };
}
