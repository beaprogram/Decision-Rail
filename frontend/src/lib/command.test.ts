import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { newIdempotencyKey, useIdempotentCommand } from './command';

const deferred = <T,>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

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
  it('sends one key for a logical command and the same key on retry after an unknown outcome', async () => {
    const keys: string[] = [];
    const perform = vi.fn(async (key: string) => {
      keys.push(key);
      if (keys.length === 1) {
        throw new ApiError(0, 'REQUEST_TIMED_OUT', 'unknown', null);
      }
      return { id: 'payment-1' };
    });

    const { result } = renderHook(() => useIdempotentCommand('authorize', perform));

    await act(async () => {
      await result.current.run({ amountMinor: 2500 });
    });
    // An unknown outcome is never reported as a failure.
    expect(result.current.state.phase).toBe('uncertain');

    await act(async () => {
      await result.current.retry();
    });

    await waitFor(() => expect(result.current.state.phase).toBe('succeeded'));
    expect(keys).toHaveLength(2);
    // The retry is the same logical command, so it must carry the same key.
    expect(keys[0]).toBe(keys[1]);
  });

  it('uses a new key for the next command after one completes', async () => {
    const keys: string[] = [];
    const perform = vi.fn(async (key: string) => {
      keys.push(key);
      return { id: keys.length };
    });
    const { result } = renderHook(() => useIdempotentCommand('capture', perform));

    await act(async () => {
      await result.current.run({});
    });
    await act(async () => {
      await result.current.run({});
    });

    expect(keys).toHaveLength(2);
    expect(keys[0]).not.toBe(keys[1]);
  });

  it('ignores a second submission while one is already in flight', async () => {
    const gate = deferred<{ id: string }>();
    const perform = vi.fn(async () => gate.promise);
    const { result } = renderHook(() => useIdempotentCommand('authorize', perform));

    act(() => {
      void result.current.run({});
      // An impatient second click must not become a second authorization.
      void result.current.run({});
      void result.current.run({});
    });

    await waitFor(() => expect(result.current.state.phase).toBe('running'));
    expect(perform).toHaveBeenCalledTimes(1);

    await act(async () => {
      gate.resolve({ id: 'payment-1' });
      await gate.promise;
    });
    await waitFor(() => expect(result.current.state.phase).toBe('succeeded'));
  });

  it('treats a server error as recoverable with the same key but a rejection as a new command', async () => {
    const keys: string[] = [];
    let nextError: ApiError | null = null;
    const perform = vi.fn(async (key: string) => {
      keys.push(key);
      if (nextError) throw nextError;
      return { id: keys.length };
    });
    const { result } = renderHook(() => useIdempotentCommand('authorize', perform));

    // A storage failure may have been applied, so the key has to survive for the retry.
    nextError = new ApiError(503, 'STORAGE_UNAVAILABLE', 'try again', null);
    await act(async () => {
      await result.current.run({});
    });
    expect(result.current.state.phase).toBe('uncertain');

    nextError = null;
    await act(async () => {
      await result.current.retry();
    });
    expect(keys[0]).toBe(keys[1]);

    // A validation rejection is definite: the next attempt is a different command.
    nextError = new ApiError(400, 'INVALID_REQUEST', 'amount is required', null);
    await act(async () => {
      await result.current.run({});
    });
    expect(result.current.state.phase).toBe('failed');

    nextError = null;
    await act(async () => {
      await result.current.run({});
    });
    expect(keys[3]).not.toBe(keys[2]);
  });

  it('reports a conflict as a definite failure rather than an unknown outcome', async () => {
    const perform = vi.fn(async () => {
      throw new ApiError(409, 'INVALID_STATE', 'payment is already captured', null);
    });
    const { result } = renderHook(() => useIdempotentCommand('capture', perform));

    await act(async () => {
      await result.current.run({});
    });

    expect(result.current.state.phase).toBe('failed');
    if (result.current.state.phase === 'failed') {
      expect((result.current.state.error as ApiError).code).toBe('INVALID_STATE');
    }
  });

  it('forgets the command when reset, so a later attempt starts a new one', async () => {
    const keys: string[] = [];
    const perform = vi.fn(async (key: string) => {
      keys.push(key);
      throw new ApiError(0, 'NETWORK_UNAVAILABLE', 'unknown', null);
    });
    const { result } = renderHook(() => useIdempotentCommand('authorize', perform));

    await act(async () => {
      await result.current.run({});
    });
    expect(result.current.state.phase).toBe('uncertain');

    act(() => result.current.reset());
    expect(result.current.state.phase).toBe('idle');

    await act(async () => {
      await result.current.run({});
    });
    expect(keys[1]).not.toBe(keys[0]);
  });
});
