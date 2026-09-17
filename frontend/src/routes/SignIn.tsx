import { useState } from 'react';
import { ApiError } from '../api/client';
import { useSession } from '../auth/session';
import { Card, Field, Notice } from '../components/ui';

/**
 * Sign-in.
 *
 * The password is held in component state for the moment it takes to submit and never written to
 * browser storage, a URL, or a log. Nothing about the credential survives this component.
 */
export function SignInPage({
  sessionExpired,
  unconfirmedSignOut,
}: {
  sessionExpired: boolean;
  unconfirmedSignOut?: { detail: string };
}) {
  const { signIn, retrySignOut, ready, retryBootstrap, idle, publicDemo } = useSession();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await signIn(username, password);
      // Cleared immediately on success so it is not retained in state any longer than needed.
      setPassword('');
    } catch (cause) {
      setError(
        cause instanceof ApiError
          ? cause.detail
          : 'The server could not be reached. Check that the application is running.',
      );
      setSubmitting(false);
    }
  };

  return (
    <div className="signin-page">
      <div className="signin-card">
        <Card title="Sign in to DecisionRail" scope="Operator console for synthetic payment data">
          {publicDemo && (
            <Notice tone="info" title="Public demo - synthetic money, shared state">
              <span>
                This is a portfolio deployment of a payment decisioning system. Every amount is
                synthetic: nothing here touches a real account, card, bank or payment network.
              </span>
              <span>
                Sign in as <strong>{publicDemo.visitorUsername}</strong> with the password{' '}
                <code data-testid="visitor-password">{publicDemo.visitorPassword}</code>. Every visitor
                shares this merchant, so you will see what other visitors have done and they will see
                what you do. Commands are limited to {publicDemo.commandsPerMinute} a minute and each
                account holds at most {publicDemo.maxPaymentsPerAccount} payments; when a limit is
                reached the server says so rather than dropping your request.
              </span>
              <div className="row">
                <button
                  type="button"
                  onClick={() => {
                    setUsername(publicDemo.visitorUsername);
                    setPassword(publicDemo.visitorPassword);
                  }}
                >
                  Use the visitor credentials
                </button>
              </div>
            </Notice>
          )}
          {sessionExpired && (
            <Notice tone="warning" title="Your session ended">
              Sign in again to continue. Nothing from the previous session is still on screen.
            </Notice>
          )}
          {unconfirmedSignOut && (
            <Notice tone="warning" title="Your sign-out could not be confirmed">
              <span>{unconfirmedSignOut.detail}</span>
              <span>
                Everything on screen has been cleared, but the server did not confirm that the session
                was destroyed, so it may still be open. Retry to close it properly.
              </span>
              <div className="row">
                <button type="button" onClick={() => void retrySignOut()} disabled={!idle}>
                  {idle ? 'Retry sign-out' : 'Retrying…'}
                </button>
              </div>
            </Notice>
          )}
          {!ready && (
            <Notice tone="danger" title="Sign-in is not available">
              <span>
                The server could not be reached to start a session, so signing in would be refused.
              </span>
              <div className="row">
                <button type="button" onClick={() => void retryBootstrap()}>
                  Try again
                </button>
              </div>
            </Notice>
          )}
          <form className="stack" onSubmit={submit} noValidate>
            <Field label="Username">
              {(props) => (
                <input
                  {...props}
                  type="text"
                  name="username"
                  autoComplete="username"
                  autoCapitalize="none"
                  spellCheck={false}
                  required
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                />
              )}
            </Field>
            <Field label="Password">
              {(props) => (
                <input
                  {...props}
                  type="password"
                  name="password"
                  autoComplete="current-password"
                  required
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                />
              )}
            </Field>
            {error && (
              <Notice tone="danger" title="Sign in failed">
                {error}
              </Notice>
            )}
            <button
              type="submit"
              className="primary"
              disabled={submitting || !username || !password || !ready || !idle}
            >
              {submitting ? 'Signing in…' : 'Sign in'}
            </button>
          </form>
          <p className="field-hint">
            Credentials come from the local environment file generated by the setup script. They are
            never stored in this page, in the URL, or in browser storage.
          </p>
        </Card>
      </div>
    </div>
  );
}
