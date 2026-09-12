import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  StaleIdentityError,
  advanceIdentityGeneration,
  apiFetch,
  onSessionEnded,
} from './client';

/**
 * The API client's two hardest jobs: deciding when an outcome is genuinely unknown, and refusing to
 * let a response for one identity reach another.
 *
 * Both are about failures that look like successes. A mutation whose response body never arrives has
 * still probably been applied, and treating it as a clean success discards the only key that could
 * recover it. A stale 401 looks exactly like a current one, and acting on it ends a session that was
 * perfectly healthy.
 */

type FetchMock = ReturnType<typeof vi.fn>;

/** A promise whose settlement the test controls. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const originalFetch = globalThis.fetch;

/** A response whose body resolves, rejects, or hangs, so body timing can be modelled exactly. */
function responseWith(options: {
  status?: number;
  contentType?: string | null;
  body?: () => Promise<string>;
}): Response {
  const headers = new Headers();
  if (options.contentType !== null) {
    headers.set('content-type', options.contentType ?? 'application/json');
  }
  return {
    status: options.status ?? 200,
    ok: (options.status ?? 200) < 400,
    statusText: 'test',
    headers,
    text: options.body ?? (async () => '{}'),
    json: async () => JSON.parse(await (options.body ?? (async () => '{}'))()),
  } as unknown as Response;
}

describe('apiFetch', () => {
  let fetchMock: FetchMock;

  beforeEach(() => {
    fetchMock = vi.fn();
    globalThis.fetch = fetchMock as unknown as typeof fetch;
    // Each test starts from a known identity generation.
    advanceIdentityGeneration();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.useRealTimers();
  });

  describe('incomplete successful responses', () => {
    it('treats a success status with malformed JSON as an unknown outcome, not a success', async () => {
      fetchMock.mockResolvedValue(
        responseWith({ status: 201, body: async () => '{"id":"payment-1"' }),
      );

      // The command may well have been applied. Reporting success would discard the only key that
      // could recover it, and reporting failure would invite a second authorization.
      const failure = await apiFetch('/ui/payments/authorizations', { method: 'POST' }).catch((error) => error);
      expect(failure).toBeInstanceOf(ApiError);
      expect((failure as ApiError).indeterminate).toBe(true);
      expect((failure as ApiError).code).toBe('RESPONSE_UNREADABLE');
    });

    it('treats a success status whose body stream fails as an unknown outcome', async () => {
      fetchMock.mockResolvedValue(
        responseWith({
          status: 201,
          body: async () => {
            throw new TypeError('network error while reading body');
          },
        }),
      );

      const failure = await apiFetch('/ui/payments/authorizations', { method: 'POST' }).catch((error) => error);
      expect(failure).toBeInstanceOf(ApiError);
      expect((failure as ApiError).indeterminate).toBe(true);
    });

    it('applies the deadline to the body, not only to the headers', async () => {
      // Headers arrive immediately; the body never completes. The old implementation cleared its
      // timer once headers were in, so this hung rather than resolving to an unknown outcome.
      fetchMock.mockResolvedValue(
        responseWith({ status: 201, body: () => new Promise<string>(() => {}) }),
      );

      const failure = await apiFetch('/ui/payments/authorizations', {
        method: 'POST',
        timeoutMs: 120,
      }).catch((error) => error);

      expect(failure).toBeInstanceOf(ApiError);
      expect((failure as ApiError).indeterminate).toBe(true);
    });

    it('returns a well-formed JSON body normally', async () => {
      fetchMock.mockResolvedValue(
        responseWith({ status: 201, body: async () => '{"id":"payment-1","status":"AUTHORIZED"}' }),
      );

      await expect(apiFetch<{ id: string }>('/ui/payments/authorizations', { method: 'POST' }))
        .resolves.toEqual({ id: 'payment-1', status: 'AUTHORIZED' });
    });

    it('accepts a bodyless 204, which is what logout returns', async () => {
      fetchMock.mockResolvedValue(responseWith({ status: 204, contentType: null }));
      await expect(apiFetch('/ui/session', { method: 'DELETE' })).resolves.toBeUndefined();
    });

    it('treats a success that should carry JSON but carries none as unknown', async () => {
      // A 200 with no body from an endpoint whose result the caller needs is not a usable success.
      fetchMock.mockResolvedValue(responseWith({ status: 200, contentType: 'text/plain', body: async () => 'ok' }));

      const failure = await apiFetch('/ui/payments/authorizations', { method: 'POST' }).catch((error) => error);
      expect(failure).toBeInstanceOf(ApiError);
      expect((failure as ApiError).indeterminate).toBe(true);
    });

    it('does not require a body from an endpoint that is not expected to return one', async () => {
      fetchMock.mockResolvedValue(responseWith({ status: 200, contentType: null }));
      await expect(apiFetch('/ui/session', { method: 'DELETE', expectsBody: false })).resolves.toBeUndefined();
    });

    it('keeps the status of an error whose problem body cannot be read', async () => {
      fetchMock.mockResolvedValue(
        responseWith({ status: 409, body: async () => 'not json at all' }),
      );

      const failure = await apiFetch('/ui/payments/authorizations', { method: 'POST' }).catch((error) => error);
      expect(failure).toBeInstanceOf(ApiError);
      // A 409 is a definite answer even when its explanation is unreadable, so it must not become
      // indeterminate and must not lose its status.
      expect((failure as ApiError).status).toBe(409);
      expect((failure as ApiError).indeterminate).toBe(false);
      expect((failure as ApiError).detail).not.toBe('');
    });
  });

  describe('identity fencing', () => {
    it('does not end the current session because of a previous identity\'s 401', async () => {
      const ended = vi.fn();
      const unsubscribe = onSessionEnded(ended);

      const held = deferred<void>();
      fetchMock.mockImplementation(async () => {
        await held.promise;
        return responseWith({ status: 401, body: async () => '{"code":"AUTHENTICATION_REQUIRED"}' });
      });

      const pending = apiFetch('/ui/payments').catch((error) => error);
      // Someone else signs in while that request is outstanding.
      advanceIdentityGeneration();
      held.resolve();

      const failure = await pending;
      // The stale 401 is a cancellation, not an expiry of the session that now exists.
      expect(failure).toBeInstanceOf(StaleIdentityError);
      expect(ended).not.toHaveBeenCalled();
      unsubscribe();
    });

    it('still ends the session for a 401 belonging to the current identity', async () => {
      const ended = vi.fn();
      const unsubscribe = onSessionEnded(ended);
      fetchMock.mockResolvedValue(
        responseWith({ status: 401, body: async () => '{"code":"AUTHENTICATION_REQUIRED"}' }),
      );

      const failure = await apiFetch('/ui/payments').catch((error) => error);
      expect(failure).toBeInstanceOf(ApiError);
      expect((failure as ApiError).unauthenticated).toBe(true);
      expect(ended).toHaveBeenCalledTimes(1);
      unsubscribe();
    });

    it('does not return a successful body that finished arriving after the identity changed', async () => {
      const body = deferred<string>();
      fetchMock.mockResolvedValue(responseWith({ status: 200, body: () => body.promise }));

      const pending = apiFetch('/ui/payments').catch((error) => error);
      // Headers are in and the body is still arriving when the identity changes.
      await Promise.resolve();
      advanceIdentityGeneration();
      body.resolve('{"payments":[{"id":"other-tenant-payment"}]}');

      const result = await pending;
      // The previous tenant's rows must not be handed to the caller.
      expect(result).toBeInstanceOf(StaleIdentityError);
    });

    it('does not report a stale transport failure as this identity\'s failure', async () => {
      const request = deferred<Response>();
      fetchMock.mockImplementation(() => request.promise);

      const pending = apiFetch('/ui/payments', { method: 'POST' }).catch((error) => error);
      advanceIdentityGeneration();
      request.reject(new TypeError('connection reset'));

      const failure = await pending;
      // A command belonging to a previous identity must not put the new identity's screen into an
      // uncertain state.
      expect(failure).toBeInstanceOf(StaleIdentityError);
    });

    it('reports repeated stale failures without disturbing the authentication lifecycle', async () => {
      const ended = vi.fn();
      const unsubscribe = onSessionEnded(ended);
      fetchMock.mockResolvedValue(
        responseWith({ status: 401, body: async () => '{"code":"AUTHENTICATION_REQUIRED"}' }),
      );

      const generation = advanceIdentityGeneration();
      const requests = [apiFetch('/ui/payments'), apiFetch('/ui/accounts'), apiFetch('/ui/policies')].map((promise) =>
        promise.catch((error) => error),
      );
      expect(generation).toBeGreaterThan(0);
      advanceIdentityGeneration();

      for (const failure of await Promise.all(requests)) {
        expect(failure).toBeInstanceOf(StaleIdentityError);
      }
      expect(ended).not.toHaveBeenCalled();
      unsubscribe();
    });
  });
});
