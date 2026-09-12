import { act, renderHook, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { advanceIdentityGeneration } from '../api/client';
import { newIdempotencyKey, useIdempotentCommand } from './command';

/**
 * What a command sends, and what it sends again when its outcome is unknown.
 *
 * The interesting failures all look like ordinary ones. A retry that rebuilds its body from live form
 * state carries different content under the original key; the server refuses that as a conflicting
 * reuse, and the operator loses the only handle on a command that may already have moved money.
 */

const originalFetch = globalThis.fetch;
type FetchMock = ReturnType<typeof vi.fn>;

interface Sent {
  path: string;
  method: string;
  key: string | null;
  body: string | null;
}

function jsonResponse(status: number, body: string): Response {
  const headers = new Headers({ 'content-type': 'application/json' });
  return {
    status,
    ok: status < 400,
    statusText: 'test',
    headers,
    text: async () => body,
  } as unknown as Response;
}

describe('newIdempotencyKey', () => {
  it('produces keys the backend accepts', () => {
    for (let attempt = 0; attempt < 50; attempt += 1) {
      expect(newIdempotencyKey('authorize')).toMatch(/^[A-Za-z0-9._:-]{8,128}$/);
    }
  });

  it('produces a different key each time it is called', () => {
    const keys = new Set(Array.from({ length: 200 }, () => newIdempotencyKey('authorize')));
    expect(keys.size).toBe(200);
  });
});

describe('useIdempotentCommand', () => {
  let fetchMock: FetchMock;
  let sent: Sent[];

  beforeEach(() => {
    sent = [];
    fetchMock = vi.fn();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    advanceIdentityGeneration();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  /** Records each outgoing request, then answers with the queued outcomes in order. */
  function respondWith(outcomes: Array<Response | Error>) {
    let index = 0;
    fetchMock.mockImplementation(async (path: string, init: RequestInit) => {
      const headers = (init.headers ?? {}) as Record<string, string>;
      sent.push({
        path,
        method: init.method ?? 'GET',
        key: headers['Idempotency-Key'] ?? null,
        body: typeof init.body === 'string' ? init.body : null,
      });
      const outcome = outcomes[Math.min(index, outcomes.length - 1)];
      index += 1;
      if (outcome instanceof Error) throw outcome;
      return outcome;
    });
  }

  it('resends the submitted payload, not what the form holds at retry time', async () => {
    respondWith([
      // The server commits it, then the response is lost.
      new TypeError('connection reset'),
      jsonResponse(201, '{"id":"payment-1","status":"AUTHORIZED"}'),
    ]);

    const { result } = renderHook(() => {
      // Live form state, exactly as the authorize screen holds it.
      const [amountMinor, setAmountMinor] = useState(2500);
      const command = useIdempotentCommand<{ id: string }>('authorize');
      return { command, amountMinor, setAmountMinor };
    });

    await act(async () => {
      await result.current.command.submit({
        method: 'POST',
        path: '/ui/payments/authorizations',
        body: { accountId: 'account-1', amountMinor: result.current.amountMinor, currency: 'CAD', country: 'CA' },
      });
    });
    expect(result.current.command.state.phase).toBe('uncertain');

    // The operator edits the form while recovery is unresolved.
    act(() => result.current.setAmountMinor(9900));

    await act(async () => {
      await result.current.command.retry();
    });
    await waitFor(() => expect(result.current.command.state.phase).toBe('succeeded'));

    expect(sent).toHaveLength(2);
    // Same key, same method, same path, and the same bytes.
    expect(sent[1]?.key).toBe(sent[0]?.key);
    expect(sent[1]?.method).toBe(sent[0]?.method);
    expect(sent[1]?.path).toBe(sent[0]?.path);
    expect(sent[1]?.body).toBe(sent[0]?.body);
    expect(sent[1]?.body).toContain('"amountMinor":2500');
    expect(sent[1]?.body).not.toContain('9900');
  });

  it('keeps the submission through a replay-job retry after the form changes', async () => {
    respondWith([
      new TypeError('connection reset'),
      jsonResponse(201, '{"id":"job-1","status":"PENDING","inputCount":4}'),
    ]);

    const { result } = renderHook(() => {
      const [limit, setLimit] = useState(50);
      const [candidate, setCandidate] = useState('candidate-a');
      const command = useIdempotentCommand<{ id: string }>('replay');
      return { command, limit, candidate, setLimit, setCandidate };
    });

    await act(async () => {
      await result.current.command.submit({
        method: 'POST',
        path: '/ui/replay-jobs',
        body: { candidateVersion: result.current.candidate, limit: result.current.limit },
      });
    });
    expect(result.current.command.state.phase).toBe('uncertain');

    // Both the candidate and the membership bound are changed before retrying.
    act(() => {
      result.current.setCandidate('candidate-b');
      result.current.setLimit(5000);
    });

    await act(async () => {
      await result.current.command.retry();
    });
    await waitFor(() => expect(result.current.command.state.phase).toBe('succeeded'));

    expect(sent[1]?.body).toBe(sent[0]?.body);
    expect(sent[1]?.body).toContain('candidate-a');
    expect(sent[1]?.body).not.toContain('candidate-b');
    expect(sent[1]?.body).toContain('"limit":50');
  });

  it('exposes the submission so a screen can say which command it is recovering', async () => {
    respondWith([new TypeError('connection reset')]);
    const { result } = renderHook(() => useIdempotentCommand<{ id: string }>('authorize'));

    await act(async () => {
      await result.current.submit({
        method: 'POST',
        path: '/ui/payments/authorizations',
        body: { amountMinor: 2500 },
        summary: [{ label: 'Amount', value: '25.00 CAD' }],
      });
    });

    const submission = result.current.submitted;
    expect(submission).not.toBeNull();
    expect(submission?.method).toBe('POST');
    expect(submission?.path).toBe('/ui/payments/authorizations');
    expect(submission?.serializedBody).toContain('2500');
    expect(submission?.summary).toEqual([{ label: 'Amount', value: '25.00 CAD' }]);
    expect(submission?.idempotencyKey).toMatch(/^ui-authorize-/);
    expect(submission?.attempts).toBe(1);
  });

  it('refuses to start a second command while one is unresolved', async () => {
    respondWith([new TypeError('connection reset')]);
    const { result } = renderHook(() => useIdempotentCommand<{ id: string }>('authorize'));

    await act(async () => {
      await result.current.submit({ method: 'POST', path: '/ui/payments/authorizations', body: { amountMinor: 2500 } });
    });
    expect(result.current.unresolved).toBe(true);

    // A brand new submission while the first is unresolved would mint a second key for work that may
    // already exist.
    await act(async () => {
      await result.current.submit({ method: 'POST', path: '/ui/payments/authorizations', body: { amountMinor: 100 } });
    });
    expect(sent).toHaveLength(1);
  });

  it('ignores a second submission while one is in flight', async () => {
    let release!: (value: Response) => void;
    const held = new Promise<Response>((resolve) => {
      release = resolve;
    });
    fetchMock.mockImplementation(() => held);

    const { result } = renderHook(() => useIdempotentCommand<{ id: string }>('authorize'));
    act(() => {
      void result.current.submit({ method: 'POST', path: '/ui/payments/authorizations', body: { amountMinor: 2500 } });
      void result.current.submit({ method: 'POST', path: '/ui/payments/authorizations', body: { amountMinor: 2500 } });
    });

    await waitFor(() => expect(result.current.state.phase).toBe('running'));
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      release(jsonResponse(201, '{"id":"payment-1"}'));
      await held;
    });
    await waitFor(() => expect(result.current.state.phase).toBe('succeeded'));
  });

  it('treats a success whose body could not be read as unresolved and recovers it', async () => {
    respondWith([
      // Committed, but the body is unusable, so the outcome is unknown.
      jsonResponse(201, '{"id":"payment-1"'),
      jsonResponse(201, '{"id":"payment-1","status":"AUTHORIZED"}'),
    ]);
    const { result } = renderHook(() => useIdempotentCommand<{ id: string }>('authorize'));

    await act(async () => {
      await result.current.submit({ method: 'POST', path: '/ui/payments/authorizations', body: { amountMinor: 2500 } });
    });
    // The key must survive: it is the only way to learn what the first attempt actually did.
    expect(result.current.state.phase).toBe('uncertain');
    expect(result.current.submitted).not.toBeNull();

    await act(async () => {
      await result.current.retry();
    });
    await waitFor(() => expect(result.current.state.phase).toBe('succeeded'));
    expect(sent[1]?.key).toBe(sent[0]?.key);
  });

  it('keeps the submission for a server error and discards it for a rejection', async () => {
    respondWith([
      jsonResponse(503, '{"code":"STORAGE_UNAVAILABLE","detail":"try again"}'),
      jsonResponse(201, '{"id":"payment-1"}'),
    ]);
    const { result } = renderHook(() => useIdempotentCommand<{ id: string }>('authorize'));

    await act(async () => {
      await result.current.submit({ method: 'POST', path: '/ui/payments/authorizations', body: { amountMinor: 2500 } });
    });
    // A storage failure may have been applied, so the submission has to survive.
    expect(result.current.state.phase).toBe('uncertain');
    await act(async () => {
      await result.current.retry();
    });
    expect(sent[1]?.key).toBe(sent[0]?.key);

    // A validation rejection is definite: the next attempt is a different command with a new key.
    respondWith([
      jsonResponse(400, '{"code":"INVALID_REQUEST","detail":"amount is required"}'),
      jsonResponse(201, '{"id":"payment-2"}'),
    ]);
    sent = [];
    const second = renderHook(() => useIdempotentCommand<{ id: string }>('authorize'));
    await act(async () => {
      await second.result.current.submit({ method: 'POST', path: '/ui/payments/authorizations', body: { amountMinor: 0 } });
    });
    expect(second.result.current.state.phase).toBe('failed');
    expect(second.result.current.submitted).toBeNull();
    await act(async () => {
      await second.result.current.submit({ method: 'POST', path: '/ui/payments/authorizations', body: { amountMinor: 2500 } });
    });
    expect(sent[1]?.key).not.toBe(sent[0]?.key);
  });

  it('reports a conflict as a definite failure rather than an unknown outcome', async () => {
    respondWith([jsonResponse(409, '{"code":"INVALID_STATE","detail":"already captured"}')]);
    const { result } = renderHook(() => useIdempotentCommand<{ id: string }>('capture'));

    await act(async () => {
      await result.current.submit({ method: 'POST', path: '/ui/payments/p1/capture' });
    });

    expect(result.current.state.phase).toBe('failed');
    expect(result.current.submitted).toBeNull();
  });

  it('sends no body for a command that has none', async () => {
    respondWith([jsonResponse(200, '{"id":"payment-1","status":"CAPTURED"}')]);
    const { result } = renderHook(() => useIdempotentCommand<{ id: string }>('capture'));

    await act(async () => {
      await result.current.submit({ method: 'POST', path: '/ui/payments/p1/capture' });
    });

    expect(sent[0]?.body).toBeNull();
    expect(result.current.state.phase).toBe('succeeded');
  });

  it('forgets the command when reset, so a later attempt starts a new one', async () => {
    respondWith([new TypeError('connection reset'), jsonResponse(201, '{"id":"payment-1"}')]);
    const { result } = renderHook(() => useIdempotentCommand<{ id: string }>('authorize'));

    await act(async () => {
      await result.current.submit({ method: 'POST', path: '/ui/payments/authorizations', body: { amountMinor: 2500 } });
    });
    expect(result.current.state.phase).toBe('uncertain');

    act(() => result.current.reset());
    expect(result.current.state.phase).toBe('idle');
    expect(result.current.submitted).toBeNull();

    await act(async () => {
      await result.current.submit({ method: 'POST', path: '/ui/payments/authorizations', body: { amountMinor: 100 } });
    });
    expect(sent[1]?.key).not.toBe(sent[0]?.key);
  });
});
