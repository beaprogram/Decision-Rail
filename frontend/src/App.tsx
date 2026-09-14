import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ApiError, StaleIdentityError } from './api/client';
import { SessionProvider, useSession } from './auth/session';
import { Shell } from './components/Shell';
import { Card, LoadingRows, Notice } from './components/ui';
import { AccountsPage } from './routes/Accounts';
import { AuthorizePage } from './routes/Authorize';
import { DeliveryPage } from './routes/Delivery';
import { PaymentDetailPage } from './routes/PaymentDetail';
import { PaymentsPage } from './routes/Payments';
import { PoliciesPage } from './routes/Policies';
import { ReconciliationPage } from './routes/Reconciliation';
import { ReplayDetailPage } from './routes/ReplayDetail';
import { ReplayPage } from './routes/Replay';
import { ShadowPage } from './routes/Shadow';
import { SignInPage } from './routes/SignIn';

/**
 * Retry policy.
 *
 * Nothing authentication or authorisation related is retried: repeating a request that was refused
 * cannot change the answer and only delays the sign-in screen. A stale-identity rejection is a
 * cancellation, not a failure, so it is never retried either.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (attempt, error) => {
        if (error instanceof StaleIdentityError) return false;
        if (error instanceof ApiError && (error.status === 401 || error.status === 403 || error.status === 404)) {
          return false;
        }
        return attempt < 2;
      },
      staleTime: 5_000,
      refetchOnWindowFocus: false,
    },
    mutations: { retry: false },
  },
});

/** Renders the dashboard only for a signed-in identity, and the sign-in screen otherwise. */
function Authenticated() {
  const { state } = useSession();

  if (state.status === 'loading') {
    return (
      <div className="page-body">
        <LoadingRows rows={3} label="Checking your session" />
      </div>
    );
  }
  // A sign-out in progress: the protected workspace is already gone, and the server has not yet said
  // whether the session went with it. Rendering this rather than the sign-in form is deliberate - the
  // form would imply the sign-out had completed, and it has not.
  if (state.status === 'signing-out') {
    return <SigningOut />;
  }
  if (state.status === 'anonymous') {
    return (
      <SignInPage
        sessionExpired={state.reason === 'expired'}
        {...(state.unconfirmedSignOut ? { unconfirmedSignOut: state.unconfirmedSignOut } : {})}
      />
    );
  }

  return (
    <Shell>
      <Routes>
        <Route path="/" element={<Navigate to="/payments" replace />} />
        <Route path="/payments" element={<PaymentsPage />} />
        <Route path="/payments/new" element={<AuthorizePage />} />
        <Route path="/payments/:paymentId" element={<PaymentDetailPage />} />
        <Route path="/accounts" element={<AccountsPage />} />
        <Route path="/reconciliation" element={<ReconciliationPage />} />
        <Route path="/policies" element={<PoliciesPage />} />
        <Route path="/replay" element={<ReplayPage />} />
        <Route path="/replay/:jobId" element={<ReplayDetailPage />} />
        <Route path="/shadow" element={<ShadowPage />} />
        <Route path="/delivery" element={<DeliveryPage />} />
        <Route path="*" element={<Navigate to="/payments" replace />} />
      </Routes>
    </Shell>
  );
}

/** Shown between the sign-out request leaving and the server answering it. */
function SigningOut() {
  return (
    <div className="signin-page">
      <div className="signin-card">
        <Card title="Signing out" scope="Closing your session">
          <Notice tone="info" title="Your workspace has been cleared">
            <span>
              Nothing from the session is on screen any more. The server has not confirmed yet that the
              session itself is closed; you will be told either way as soon as it answers.
            </span>
          </Notice>
        </Card>
      </div>
    </div>
  );
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        {/* Matches the path the backend serves the bundle from, so deep links survive a refresh. */}
        <BrowserRouter basename="/dashboard">
          <Authenticated />
        </BrowserRouter>
      </SessionProvider>
    </QueryClientProvider>
  );
}
