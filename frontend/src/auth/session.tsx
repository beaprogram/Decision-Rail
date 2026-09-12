import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { ApiError, advanceIdentityGeneration, csrfToken, onSessionEnded } from '../api/client';
import { identityApi } from '../api/endpoints';
import type { Capabilities, Identity } from '../api/types';

/**
 * Who is signed in, and the one place that changes.
 *
 * Identity changes are the riskiest moment in a tenant-scoped dashboard, for three separate reasons.
 *
 * **A response for the previous identity must never decorate the next one's screen.** Every transition
 * advances the identity generation, cancels in-flight queries, and removes the cache entirely rather
 * than invalidating it, so no previous tenant's rows can appear even for the instant before fresh data
 * arrives.
 *
 * **The next request needs a usable CSRF token.** Signing in rotates the token and signing out destroys
 * it, so a page that stays mounted across a transition can be left holding nothing to prove its next
 * request came from us. The server now returns a fresh token with both responses; this provider checks
 * that it actually has one and fetches it from the bootstrap endpoint if not, rather than presenting a
 * form that will be refused.
 *
 * **A logout that the server refused is not a logout.** Clearing the screen is necessary but says
 * nothing about whether the session still exists, so the two are reported separately: data is cleared
 * immediately, and the sign-out is only described as done once the server confirms it.
 */

export interface UnconfirmedSignOut {
  detail: string;
}

export type SessionState =
  | { status: 'loading' }
  | {
      status: 'anonymous';
      reason?: 'expired' | 'signed-out';
      /**
       * Set when the screen was cleared but the server never confirmed the session was destroyed.
       * The session may still be live, so this is not presented as a completed sign-out.
       */
      unconfirmedSignOut?: UnconfirmedSignOut;
    }
  | { status: 'authenticated'; identity: Identity };

interface SessionContextValue {
  state: SessionState;
  signIn: (username: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  /** Retries an unconfirmed sign-out, reconciling with the server. */
  retrySignOut: () => Promise<void>;
  /** Capabilities of the signed-in identity, or all-false when anonymous. */
  can: Capabilities;
  username: string | null;
  /** False while a sign-in or sign-out is outstanding, so transitions cannot overlap. */
  idle: boolean;
  /**
   * Whether a state-changing request can currently be made. False when no CSRF token could be
   * obtained, which means the sign-in form would be refused and should say so rather than pretend.
   */
  ready: boolean;
  /** Re-runs the bootstrap after it failed, so a transient outage is recoverable without a reload. */
  retryBootstrap: () => Promise<void>;
}

const SessionContext = createContext<SessionContextValue | null>(null);

const NO_CAPABILITIES: Capabilities = {
  viewPayments: false,
  createPayments: false,
  viewAccounts: false,
  viewPolicies: false,
  registerPolicies: false,
  viewReplay: false,
  createReplay: false,
  viewShadowComparisons: false,
  administerDelivery: false,
  configureShadow: false,
  viewMetrics: false,
};

export function SessionProvider({ children }: { children: ReactNode }) {
  const queries = useQueryClient();
  const [state, setState] = useState<SessionState>({ status: 'loading' });
  const [ready, setReady] = useState(false);

  // Transitions are serialised. Two overlapping sign-ins, or a sign-in racing a sign-out, would leave
  // the client's idea of who is signed in decided by whichever response happened to land last.
  const transitionInFlight = useRef(false);
  const [idle, setIdle] = useState(true);
  // Each transition carries a token. A result from an obsolete one is discarded rather than allowed to
  // overwrite the identity that has since been established.
  const transitionToken = useRef(0);

  /** Drops everything the previous identity could see. */
  const clearTenantData = useCallback(() => {
    void queries.cancelQueries();
    queries.removeQueries();
  }, [queries]);

  /**
   * Asks the server who it thinks the browser is, and takes whatever CSRF cookie comes with the answer.
   *
   * Used to settle an unconfirmed sign-out. It always makes the request, because the presence of a CSRF
   * token says nothing about whether the session behind it still exists.
   */
  const confirmIdentity = useCallback(async (): Promise<Identity | null> => {
    try {
      const identity = await identityApi.current();
      setReady(Boolean(csrfToken()));
      return identity;
    } catch {
      setReady(false);
      return null;
    }
  }, []);

  /**
   * Makes sure a CSRF token is in hand, fetching one from the bootstrap endpoint only if the last
   * response did not carry it. Cheap by design: this runs after every successful transition.
   */
  const ensureCsrfToken = useCallback(async (): Promise<void> => {
    if (csrfToken()) {
      setReady(true);
      return;
    }
    await confirmIdentity();
  }, [confirmIdentity]);

  // A 401 from anywhere ends the session exactly once, however many screens were loading.
  useEffect(
    () =>
      onSessionEnded(() => {
        clearTenantData();
        setState((previous) =>
          previous.status === 'authenticated' ? { status: 'anonymous', reason: 'expired' } : previous,
        );
        // The expired session's token went with it, so a fresh one is needed before signing in again.
        void ensureCsrfToken();
      }),
    [clearTenantData, ensureCsrfToken],
  );

  /**
   * Asks the server who the browser is, and takes the CSRF cookie it issues along the way.
   *
   * Written as a promise chain rather than with await so every state update plainly belongs to a
   * response callback, which is what this is: a subscription to an answer from outside React.
   */
  const bootstrap = useCallback(
    (signal?: AbortSignal) =>
      identityApi
        .current(signal)
        .then((identity) => {
          setState(identity.authenticated ? { status: 'authenticated', identity } : { status: 'anonymous' });
          // This request is also what issues the CSRF cookie, so its presence is checked rather
          // than assumed.
          setReady(Boolean(csrfToken()));
        })
        .catch((cause: unknown) => {
          if (signal?.aborted) return;
          // An unreachable server is not the same as being signed out, but either way nothing
          // protected may be shown, and the form must not claim it can be used.
          setState({ status: 'anonymous' });
          setReady(false);
          if (!(cause instanceof ApiError)) throw cause;
        }),
    [],
  );

  useEffect(() => {
    const controller = new AbortController();
    void bootstrap(controller.signal);
    return () => controller.abort();
  }, [bootstrap]);

  const signIn = useCallback(
    async (username: string, password: string) => {
      if (transitionInFlight.current) {
        throw new ApiError(0, 'TRANSITION_IN_PROGRESS',
          'A sign-in or sign-out is already in progress. Wait for it to finish.', null);
      }
      transitionInFlight.current = true;
      setIdle(false);
      const token = ++transitionToken.current;
      try {
        const identity = await identityApi.signIn(username, password);
        // A late result from a superseded transition must not install an identity nobody asked for.
        if (token !== transitionToken.current) return;
        // A new identity starts from nothing: no cached rows, no in-flight request from before.
        advanceIdentityGeneration();
        clearTenantData();
        setState({ status: 'authenticated', identity });
        // Authentication rotates the token. The login response carries the replacement, and this
        // confirms it arrived rather than trusting that it did.
        await ensureCsrfToken();
      } finally {
        transitionInFlight.current = false;
        setIdle(true);
      }
    },
    [clearTenantData, ensureCsrfToken],
  );

  const performSignOut = useCallback(async () => {
    if (transitionInFlight.current) return;
    transitionInFlight.current = true;
    setIdle(false);
    const token = ++transitionToken.current;

    // Sensitive data goes immediately, whatever happens next. Clearing it is about what is on screen;
    // it says nothing about whether the server session still exists, which is reported separately.
    advanceIdentityGeneration();
    clearTenantData();

    try {
      await identityApi.signOut();
      if (token !== transitionToken.current) return;
      setState({ status: 'anonymous', reason: 'signed-out' });
      await ensureCsrfToken();
    } catch (cause) {
      if (token !== transitionToken.current) return;
      // The request failed, but the session may still have been destroyed with only the response lost.
      // The server is asked unconditionally, because holding a CSRF token proves nothing about whether
      // the session behind it survived, and an unconfirmed sign-out that was in fact completed should
      // not be left looking unresolved.
      const identity = await confirmIdentity();
      if (token !== transitionToken.current) return;
      if (identity && !identity.authenticated) {
        setState({ status: 'anonymous', reason: 'signed-out' });
        return;
      }
      setState({
        status: 'anonymous',
        unconfirmedSignOut: {
          detail:
            cause instanceof ApiError
              ? cause.detail
              : 'The sign-out request could not be completed.',
        },
      });
    } finally {
      transitionInFlight.current = false;
      setIdle(true);
    }
  }, [clearTenantData, ensureCsrfToken, confirmIdentity]);

  const value = useMemo<SessionContextValue>(
    () => ({
      state,
      signIn,
      signOut: performSignOut,
      retrySignOut: performSignOut,
      can: state.status === 'authenticated' ? state.identity.capabilities : NO_CAPABILITIES,
      username: state.status === 'authenticated' ? state.identity.username : null,
      idle,
      ready,
      retryBootstrap: () => bootstrap(),
    }),
    [state, signIn, performSignOut, idle, ready, bootstrap],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession must be used inside a SessionProvider');
  return value;
}

export { NO_CAPABILITIES };
