import { apiFetch, queryString } from './client';
import type {
  Account,
  DeliveryStatus,
  FailedEventPage,
  Identity,
  LedgerEntry,
  Payment,
  PaymentSearchPage,
  PaymentTimeline,
  PolicyVersion,
  RedriveResult,
  ReplayJob,
  ReplayReport,
  ReplayResult,
  ShadowComparison,
  ShadowSettings,
} from './types';

/** Every call the dashboard makes, in one place, so each screen states its intent rather than a URL. */

export const identityApi = {
  current: (signal?: AbortSignal) => apiFetch<Identity>('/ui/identity', { signal }),

  /**
   * Signs in. Form-encoded because this posts to Spring Security's own login filter, which is what
   * provides session fixation protection and CSRF token rotation rather than hand-rolled logic.
   */
  signIn: (username: string, password: string) =>
    apiFetch<Identity>('/ui/session', {
      method: 'POST',
      form: { username, password },
      // A refusal here is about these credentials, not about a session that has ended.
      authenticationAttempt: true,
    }),

  // Logout answers 204 with no body, so no usable body is required of it.
  signOut: () => apiFetch<void>('/ui/session', { method: 'DELETE', expectsBody: false }),
};

export interface PaymentSearchParams {
  paymentId?: string;
  accountId?: string;
  status?: string;
  riskOutcome?: string;
  currency?: string;
  createdFrom?: string;
  createdTo?: string;
  limit?: number;
  cursor?: string | null;
}

/**
 * Merchant-scoped reads.
 *
 * Financial mutations are deliberately absent here, as they are from {@link replayApi}. Every command
 * goes through {@link useIdempotentCommand}, which captures the method, path, body and idempotency key
 * as one immutable submitted command so a retry resends exactly what was submitted. A convenience
 * helper in this module would be a second way to issue the same command that quietly loses that
 * guarantee.
 */
export const merchantApi = {
  accounts: (signal?: AbortSignal) => apiFetch<Account[]>('/ui/accounts', { signal }),

  searchPayments: (params: PaymentSearchParams, signal?: AbortSignal) =>
    apiFetch<PaymentSearchPage>(`/ui/payments${queryString({ ...params })}`, { signal }),

  payment: (id: string, signal?: AbortSignal) => apiFetch<Payment>(`/ui/payments/${id}`, { signal }),

  ledger: (id: string, signal?: AbortSignal) => apiFetch<LedgerEntry[]>(`/ui/payments/${id}/ledger`, { signal }),

  timeline: (id: string, signal?: AbortSignal) =>
    apiFetch<PaymentTimeline>(`/ui/payments/${id}/timeline`, { signal }),

  paymentShadow: (id: string, signal?: AbortSignal) =>
    apiFetch<ShadowComparison[]>(`/ui/payments/${id}/shadow`, { signal }),

  shadowComparisons: (divergedOnly: boolean, limit: number, signal?: AbortSignal) =>
    apiFetch<ShadowComparison[]>(`/ui/shadow-comparisons${queryString({ divergedOnly, limit })}`, { signal }),

};

export const policyApi = {
  list: (signal?: AbortSignal) => apiFetch<PolicyVersion[]>('/ui/policies?limit=100', { signal }),
  get: (versionId: string, signal?: AbortSignal) =>
    apiFetch<PolicyVersion>(`/ui/policies/${encodeURIComponent(versionId)}`, { signal }),
  register: (versionId: string, definition: unknown) =>
    apiFetch<PolicyVersion>('/ui/policies', { method: 'POST', body: { versionId, definition } }),
};

export const replayApi = {
  jobs: (signal?: AbortSignal) => apiFetch<ReplayJob[]>('/ui/replay-jobs?limit=50', { signal }),
  job: (id: string, signal?: AbortSignal) => apiFetch<ReplayJob>(`/ui/replay-jobs/${id}`, { signal }),
  report: (id: string, signal?: AbortSignal) => apiFetch<ReplayReport>(`/ui/replay-jobs/${id}/report`, { signal }),
  results: (id: string, divergedOnly: boolean, limit: number, offset: number, signal?: AbortSignal) =>
    apiFetch<ReplayResult[]>(`/ui/replay-jobs/${id}/results${queryString({ divergedOnly, limit, offset })}`, { signal }),
};

export const opsApi = {
  delivery: (signal?: AbortSignal) => apiFetch<DeliveryStatus>('/ui/ops/delivery', { signal }),
  failedEvents: (limit: number, offset: number, signal?: AbortSignal) =>
    apiFetch<FailedEventPage>(`/ui/ops/outbox/failed${queryString({ limit, offset })}`, { signal }),
  redrive: (target: { paymentId?: string; eventIds?: string[] }) =>
    apiFetch<RedriveResult>('/ui/ops/outbox/redrive', { method: 'POST', body: target }),
  shadowSettings: (signal?: AbortSignal) => apiFetch<ShadowSettings>('/ui/ops/shadow', { signal }),
  configureShadow: (enabled: boolean, candidateVersion: string | null) =>
    apiFetch<ShadowSettings>('/ui/ops/shadow', {
      method: 'PUT',
      body: candidateVersion ? { enabled, candidateVersion } : { enabled },
    }),
};
