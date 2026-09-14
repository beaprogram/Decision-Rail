import { NavLink } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useSession } from '../auth/session';
import { Notice } from './ui';

/**
 * Navigation and identity surround.
 *
 * Links are shown according to the capabilities the server reported, which is a usability decision and
 * not a security one: each endpoint enforces its own rule, so reaching a hidden route by typing its
 * path gets a refusal rather than data.
 */
export function Shell({ children }: { children: ReactNode }) {
  const { can, username, signOut, state, idle } = useSession();
  const roles = state.status === 'authenticated' ? state.identity.roles : [];
  const readableRole = roles.map((role) => role.replace(/^ROLE_/, '')).join(', ') || 'none';

  // An identity with no dashboard capability at all gets an explanation rather than an empty console.
  const hasAnyWorkspace = can.viewPayments || can.viewPolicies || can.administerDelivery;

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      <nav className="sidebar" aria-label="Dashboard sections">
        <div className="brand">
          <span className="brand-name">DecisionRail</span>
          <span className="brand-note">Operator console · synthetic data</span>
        </div>

        <div className="nav">
          {can.viewPayments && (
            <>
              <span className="nav-group-label" id="nav-merchant">
                Merchant
              </span>
              <NavLink to="/payments">Payments</NavLink>
              <NavLink to="/accounts">Accounts</NavLink>
              <NavLink to="/reconciliation">Reconciliation</NavLink>
              {can.viewReplay && <NavLink to="/replay">Policy replay</NavLink>}
              {can.viewShadowComparisons && <NavLink to="/shadow">Shadow comparisons</NavLink>}
            </>
          )}
          {can.viewPolicies && (
            <>
              <span className="nav-group-label">Policy</span>
              <NavLink to="/policies">Policy versions</NavLink>
            </>
          )}
          {can.administerDelivery && (
            <>
              <span className="nav-group-label">Administration</span>
              <NavLink to="/delivery">Event delivery</NavLink>
              <NavLink to="/shadow">Shadow configuration</NavLink>
            </>
          )}
        </div>

        <div className="identity-card">
          <div>
            <div className="identity-name">{username}</div>
            <div className="identity-role">Role: {readableRole}</div>
          </div>
          {/* Disabled while a transition is outstanding, so a second click cannot start an
              overlapping identity change. The outcome is reported through session state rather than
              discarded: a refused sign-out shows as unconfirmed on the sign-in screen. */}
          <button type="button" onClick={() => void signOut()} disabled={!idle}>
            {idle ? 'Sign out' : 'Signing out…'}
          </button>
        </div>
      </nav>

      <main className="content" id="main">
        {!hasAnyWorkspace ? (
          <div className="page-body">
            <Notice tone="info" title="This identity has no dashboard workspace">
              <span>
                The operations identity exists to read protected metrics and nothing else. It is
                deliberately not an administrator, so there is no payment, policy or delivery workspace
                for it here. Use the admin identity for delivery operations, or a merchant identity for
                payments.
              </span>
            </Notice>
          </div>
        ) : (
          children
        )}
      </main>
    </div>
  );
}

/** Page heading with a one-line statement of what the screen is for. */
export function PageHeader({
  title,
  description,
  actions,
  badge,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
  badge?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div className="row between">
        <div className="page-title-row">
          <h1>{title}</h1>
          {badge}
        </div>
        {actions && <div className="row">{actions}</div>}
      </div>
      {description && <p className="page-description">{description}</p>}
    </header>
  );
}
