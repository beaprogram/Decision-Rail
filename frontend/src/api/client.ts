/**
 * The only place the dashboard talks to the server.
 *
 * Three things are centralised here because getting them wrong in one screen would be invisible:
 * the CSRF token every mutation needs, turning a problem response into a typed error, and noticing
 * that the session has ended.
 */

/** A structured problem response from the API, or a transport failure described in the same shape. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly detail: string;
  readonly requestId: string | null;

  constructor(status: number, code: string, detail: string, requestId: string | null) {
    super(detail || code);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
    this.requestId = requestId;
  }

  /** True when the server refused because nobody is signed in any more. */
  get unauthenticated(): boolean {
    return this.status === 401;
  }

  /** True when the request was understood but the identity may not do it. */
  get forbidden(): boolean {
    return this.status === 403;
  }

  /** True when a command lost a race: the resource changed before the request arrived. */
  get conflict(): boolean {
    return this.status === 409;
  }

  /** True when the answer is genuinely unknown, so a command must not be reported as failed. */
  get indeterminate(): boolean {
    return this.code === 'REQUEST_TIMED_OUT' || this.code === 'NETWORK_UNAVAILABLE';
  }
}

/**
 * Raised when a response arrives for an identity that is no longer the current one.
 *
 * A late response must never populate the next user's screen, so the caller treats this as a
 * cancellation rather than as data.
 */
export class StaleIdentityError extends Error {
  constructor() {
    super('The response belongs to a previous session.');
    this.name = 'StaleIdentityError';
  }
}

type Listener = () => void;

const sessionEndedListeners = new Set<Listener>();

/** Notified when the server says the session is gone, so the app can clear tenant data once. */
export function onSessionEnded(listener: Listener): () => void {
  sessionEndedListeners.add(listener);
  return () => sessionEndedListeners.delete(listener);
}

/**
 * Monotonic identity generation.
 *
 * Incremented on every sign-in, sign-out and expiry. A request records the generation it started in
 * and refuses to return data if the generation has moved on, which is what stops a slow response from
 * one identity landing on another identity's screen.
 */
let identityGeneration = 0;

export function currentIdentityGeneration(): number {
  return identityGeneration;
}

export function advanceIdentityGeneration(): number {
  identityGeneration += 1;
  return identityGeneration;
}

function notifySessionEnded(): void {
  advanceIdentityGeneration();
  for (const listener of sessionEndedListeners) listener();
}

/** Reads the CSRF cookie the server issued. Not a secret: it proves the request came from our page. */
export function csrfToken(): string | null {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie);
  return match?.[1] ? decodeURIComponent(match[1]) : null;
}

const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

export interface RequestOptions {
  method?: string;
  body?: unknown;
  /** Sent as form encoding rather than JSON. Used only by the login endpoint. */
  form?: Record<string, string>;
  idempotencyKey?: string;
  signal?: AbortSignal;
  /** Milliseconds before the request is abandoned as indeterminate. */
  timeoutMs?: number;
  /**
   * Set for the sign-in request, whose 401 means "those credentials are wrong" rather than "your
   * session ended". Without this the login screen would report an expired session to someone who
   * never had one, and would hide the real reason they were refused.
   */
  authenticationAttempt?: boolean;
}

const DEFAULT_TIMEOUT_MS = 15_000;

/**
 * Performs one API request.
 *
 * A timeout produces a `REQUEST_TIMED_OUT` error rather than a failure, because a mutation that timed
 * out may well have been applied. The caller must retry it with the same idempotency key instead of
 * telling the operator it definitely did not happen.
 */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET';
  const generation = currentIdentityGeneration();
  const headers: Record<string, string> = { Accept: 'application/json' };

  if (UNSAFE_METHODS.has(method)) {
    const token = csrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  let body: string | undefined;
  if (options.form) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    body = new URLSearchParams(options.form).toString();
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }

  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(new DOMException('timeout', 'TimeoutError')), options.timeoutMs ?? DEFAULT_TIMEOUT_MS);
  const signal = options.signal ? anySignal([options.signal, timeout.signal]) : timeout.signal;

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body,
      signal,
      // The session cookie is the credential; it travels automatically on a same-origin request.
      credentials: 'same-origin',
      // Authenticated responses must not be reused from a cache.
      cache: 'no-store',
      redirect: 'error',
    });
  } catch (cause) {
    clearTimeout(timer);
    if (options.signal?.aborted) throw cause;
    if (cause instanceof DOMException && cause.name === 'TimeoutError') {
      throw new ApiError(0, 'REQUEST_TIMED_OUT',
        'The request took too long and its outcome is unknown. Retrying is safe.', null);
    }
    throw new ApiError(0, 'NETWORK_UNAVAILABLE',
      'The server could not be reached. Its outcome is unknown.', null);
  } finally {
    clearTimeout(timer);
  }

  if (response.status === 401 && !options.authenticationAttempt) {
    // Tell the app once, so every screen clears together rather than each discovering it alone.
    notifySessionEnded();
    throw new ApiError(401, 'AUTHENTICATION_REQUIRED', 'Your session has ended. Sign in again.', null);
  }

  if (generation !== currentIdentityGeneration()) {
    throw new StaleIdentityError();
  }

  if (response.status === 204) return undefined as T;

  const contentType = response.headers.get('content-type') ?? '';
  const payload = contentType.includes('json') ? await response.json().catch(() => null) : null;

  if (!response.ok) {
    const problem = (payload ?? {}) as Record<string, unknown>;
    throw new ApiError(
      response.status,
      typeof problem.code === 'string' ? problem.code : `HTTP_${response.status}`,
      typeof problem.detail === 'string' ? problem.detail : response.statusText,
      typeof problem.requestId === 'string' ? problem.requestId : null,
    );
  }
  return payload as T;
}

/** Combines abort signals, so a caller's cancellation and the timeout both apply. */
function anySignal(signals: AbortSignal[]): AbortSignal {
  const controller = new AbortController();
  for (const signal of signals) {
    if (signal.aborted) {
      controller.abort(signal.reason);
      break;
    }
    signal.addEventListener('abort', () => controller.abort(signal.reason), { once: true });
  }
  return controller.signal;
}

/** Builds a query string, leaving out anything empty so filters stay absent rather than blank. */
export function queryString(params: Record<string, string | number | boolean | null | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue;
    search.set(key, String(value));
  }
  const rendered = search.toString();
  return rendered ? `?${rendered}` : '';
}
