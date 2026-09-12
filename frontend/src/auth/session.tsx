import { useQueryClient } from '@tanstack/react-query';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { ApiError, advanceIdentityGeneration, onSessionEnded } from '../api/client';
import { identityApi } from '../api/endpoints';
import type { Capabilities, Identity } from '../api/types';

/**
 * Who is signed in, and the one place that changes.
 *
 * Identity changes are the riskiest moment in a tenant-scoped dashboard: a response for the previous
 * user must never decorate the next user's screen. Three things happen together on every sign-in,
 * sign-out and expiry, and they happen here so they cannot drift apart:
 *
 *  1. the identity generation advances, so any request already in flight refuses to return its data;
 *  2. every in-flight query is cancelled;
 *  3. the entire query cache is removed, not merely invalidated, so no previous tenant's rows can be
 *     shown even for the instant before fresh data arrives.
 */

export type SessionState =
  | { status: 'loading' }
  | { status: 'anonymous'; reason?: 'expired' }
  | { status: 'authenticated'; identity: Identity };

interface SessionContextValue {
  state: SessionState;
  signIn: (username: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  /** Capabilities of the signed-in identity, or all-false when anonymous. */
  can: Capabilities;
  username: string | null;
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

  /** Drops everything the previous identity could see. */
  const clearTenantData = useCallback(() => {
    void queries.cancelQueries();
    queries.removeQueries();
  }, [queries]);

  // A 401 from anywhere ends the session exactly once, however many screens were loading.
  useEffect(
    () =>
      onSessionEnded(() => {
        clearTenantData();
        setState((previous) =>
          previous.status === 'authenticated' ? { status: 'anonymous', reason: 'expired' } : previous,
        );
      }),
    [clearTenantData],
  );

  // Initial bootstrap. This request also causes the CSRF cookie to be issued, which sign-in needs.
  useEffect(() => {
    const controller = new AbortController();
    identityApi
      .current(controller.signal)
      .then((identity) =>
        setState(identity.authenticated ? { status: 'authenticated', identity } : { status: 'anonymous' }),
      )
      .catch((cause) => {
        if (controller.signal.aborted) return;
        // An unreachable server is not the same as being signed out, but either way nothing
        // protected may be shown.
        setState({ status: 'anonymous' });
        if (!(cause instanceof ApiError)) throw cause;
      });
    return () => controller.abort();
  }, []);

  const signIn = useCallback(
    async (username: string, password: string) => {
      const identity = await identityApi.signIn(username, password);
      // A new identity starts from nothing: no cached rows, no in-flight request from before.
      advanceIdentityGeneration();
      clearTenantData();
      setState({ status: 'authenticated', identity });
    },
    [clearTenantData],
  );

  const signOut = useCallback(async () => {
    try {
      await identityApi.signOut();
    } finally {
      // Local state is cleared even if the call failed, so a failed logout never leaves tenant data
      // on screen. The server-side session is the authority and will refuse the next request.
      advanceIdentityGeneration();
      clearTenantData();
      setState({ status: 'anonymous' });
    }
  }, [clearTenantData]);

  const value = useMemo<SessionContextValue>(
    () => ({
      state,
      signIn,
      signOut,
      can: state.status === 'authenticated' ? state.identity.capabilities : NO_CAPABILITIES,
      username: state.status === 'authenticated' ? state.identity.username : null,
    }),
    [state, signIn, signOut],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession must be used inside a SessionProvider');
  return value;
}

export { NO_CAPABILITIES };
