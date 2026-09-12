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

  /**
   * True when the answer is genuinely unknown, so a command must not be reported as failed.
   *
   * RESPONSE_UNREADABLE belongs here for the same reason a timeout does. A mutation that returned a
   * success status but no usable body has very likely been applied; calling that a failure invites the
   * operator to repeat a reservation that already exists.
   */
  get indeterminate(): boolean {
    return (
      this.code === 'REQUEST_TIMED_OUT' ||
      this.code === 'NETWORK_UNAVAILABLE' ||
      this.code === 'RESPONSE_UNREADABLE'
    );
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
  /**
   * A body that was serialized once, earlier, and must be resent byte for byte.
   *
   * Used by retries of a command whose outcome is unknown: rebuilding the body from live state would
   * send different content under the original idempotency key, which the server would then refuse as a
   * conflicting reuse of that key, destroying the only means of recovering the original command.
   */
  rawBody?: string;
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
  /**
   * Whether a successful response must carry a usable JSON body. Defaults to true, because almost
   * every endpoint here returns one and a success without it is not a usable answer. Set false for
   * the endpoints that legitimately return nothing, so this is endpoint-aware rather than a blanket
   * rule applied to every call.
   */
  expectsBody?: boolean;
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
  } else if (options.rawBody !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = options.rawBody;
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }

  const timeout = new AbortController();
  const timer = setTimeout(
    () => timeout.abort(new DOMException('timeout', 'TimeoutError')),
    options.timeoutMs ?? DEFAULT_TIMEOUT_MS,
  );
  const signal = options.signal ? anySignal([options.signal, timeout.signal]) : timeout.signal;

  try {
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
      // Fenced before anything else. A transport failure belonging to a previous identity must not
      // put the identity that exists now into an uncertain state.
      if (generation !== currentIdentityGeneration()) throw new StaleIdentityError();
      if (options.signal?.aborted) throw cause;
      if (timeout.signal.aborted) {
        throw new ApiError(0, 'REQUEST_TIMED_OUT',
          'The request took too long and its outcome is unknown. Retrying is safe.', null);
      }
      throw new ApiError(0, 'NETWORK_UNAVAILABLE',
        'The server could not be reached. Its outcome is unknown.', null);
    }

    // Fenced before any identity-sensitive side effect. Ending the session on a 401 that belongs to a
    // previous identity would sign out the person who just signed in.
    if (generation !== currentIdentityGeneration()) throw new StaleIdentityError();

    if (response.status === 401 && !options.authenticationAttempt) {
      // Tell the app once, so every screen clears together rather than each discovering it alone.
      notifySessionEnded();
      throw new ApiError(401, 'AUTHENTICATION_REQUIRED', 'Your session has ended. Sign in again.', null);
    }

    if (response.status === 204) return undefined as T;

    // The body is read under the same deadline as the headers. Ending the deadline once headers
    // arrive leaves a response whose body never completes hanging forever, which is the one outcome a
    // caller can do nothing with.
    let raw: string | null = null;
    let bodyFailed = false;
    try {
      raw = await readBody(response, signal);
    } catch (cause) {
      if (options.signal?.aborted) throw cause;
      bodyFailed = true;
    }

    // Reading the body is an asynchronous boundary of its own, so identity is checked again. A body
    // that finished arriving after someone else signed in must not be handed back as their data.
    if (generation !== currentIdentityGeneration()) throw new StaleIdentityError();

    const contentType = response.headers.get('content-type') ?? '';
    let payload: unknown = null;
    let payloadReadable = false;
    if (!bodyFailed && raw !== null && raw.trim() !== '' && contentType.includes('json')) {
      try {
        payload = JSON.parse(raw);
        payloadReadable = true;
      } catch {
        payloadReadable = false;
      }
    }

    if (!response.ok) {
      // A refusal is a definite answer even when its explanation is unreadable. It keeps its status
      // and falls back to a usable message rather than becoming an unknown outcome.
      const problem = (payloadReadable ? payload : {}) as Record<string, unknown>;
      throw new ApiError(
        response.status,
        typeof problem.code === 'string' ? problem.code : `HTTP_${response.status}`,
        typeof problem.detail === 'string' && problem.detail !== ''
          ? problem.detail
          : fallbackDetail(response),
        typeof problem.requestId === 'string' ? problem.requestId : null,
      );
    }

    if (!payloadReadable) {
      if (options.expectsBody ?? true) {
        // Succeeded as far as the status line goes, but produced nothing the caller can use. For a
        // mutation that means it may have committed, so the outcome is unknown rather than either
        // a success or a failure.
        throw new ApiError(0, 'RESPONSE_UNREADABLE',
          'The server replied but its response could not be read, so the outcome is unknown. Retrying is safe.',
          null);
      }
      // Nothing was expected and nothing arrived, which is a complete answer rather than an empty one.
      return undefined as T;
    }
    return payload as T;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Reads a response body, giving up when the deadline or the caller's cancellation fires.
 *
 * A real fetch body rejects on abort, but racing the signal explicitly means the deadline also covers
 * a body that simply never completes, which is the case that used to hang.
 */
async function readBody(response: Response, signal: AbortSignal): Promise<string> {
  return Promise.race([
    response.text(),
    new Promise<never>((_resolve, reject) => {
      const fail = () =>
        reject(signal.reason instanceof Error ? signal.reason : new DOMException('aborted', 'AbortError'));
      if (signal.aborted) fail();
      else signal.addEventListener('abort', fail, { once: true });
    }),
  ]);
}

/** A usable message for a refusal whose problem document could not be read. */
function fallbackDetail(response: Response): string {
  return response.statusText && response.statusText.trim() !== ''
    ? response.statusText
    : `The server refused the request with status ${response.status}.`;
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
