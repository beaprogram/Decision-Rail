import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { merchantApi } from '../api/endpoints';
import { PageHeader } from '../components/Shell';
import {
  Card,
  EmptyState,
  ErrorNotice,
  Identifier,
  LoadingRows,
  Money,
  Notice,
  TableScroll,
} from '../components/ui';

/**
 * The merchant's accounts and their synthetic balances.
 *
 * Amounts are never summed across currencies. CAD and USD are separate units of account, so a combined
 * total would be a meaningless number presented as a meaningful one; each currency is subtotalled on its
 * own instead.
 */
export function AccountsPage() {
  const accounts = useQuery({ queryKey: ['accounts'], queryFn: ({ signal }) => merchantApi.accounts(signal) });

  const byCurrency = new Map<string, { balance: number; held: number; available: number; count: number }>();
  for (const account of accounts.data ?? []) {
    const totals = byCurrency.get(account.currency) ?? { balance: 0, held: 0, available: 0, count: 0 };
    totals.balance += account.balanceMinor;
    totals.held += account.heldMinor;
    totals.available += account.availableMinor;
    totals.count += 1;
    byCurrency.set(account.currency, totals);
  }

  return (
    <>
      <PageHeader
        title="Accounts"
        description="Synthetic balances for the accounts this merchant owns. Held funds are reservations from authorizations that have not yet been captured or voided."
      />
      <div className="page-body">
        {accounts.isPending && <LoadingRows rows={3} label="Loading accounts" />}
        {accounts.error && <ErrorNotice error={accounts.error} context="Loading accounts" />}

        {byCurrency.size > 0 && (
          <div className="grid cols-2">
            {[...byCurrency.entries()].map(([currency, totals]) => (
              <Card
                key={currency}
                title={`${currency} totals`}
                scope={`Across ${totals.count} ${totals.count === 1 ? 'account' : 'accounts'} in ${currency} only. Currencies are never combined.`}
              >
                <div className="grid cols-3">
                  <div className="stat">
                    <span className="stat-label">Balance</span>
                    <span className="stat-value small">
                      <Money minorUnits={totals.balance} currency={currency} />
                    </span>
                  </div>
                  <div className="stat">
                    <span className="stat-label">Held</span>
                    <span className="stat-value small">
                      <Money minorUnits={totals.held} currency={currency} />
                    </span>
                  </div>
                  <div className="stat">
                    <span className="stat-label">Available</span>
                    <span className="stat-value small">
                      <Money minorUnits={totals.available} currency={currency} />
                    </span>
                  </div>
                </div>
              </Card>
            ))}
          </div>
        )}

        <Card title="Accounts" scope="Only accounts belonging to the signed-in merchant." tight>
          {accounts.data && accounts.data.length === 0 && (
            <EmptyState title="No accounts for this merchant">
              <span>Synthetic accounts are created by the local setup fixtures.</span>
            </EmptyState>
          )}
          {accounts.data && accounts.data.length > 0 && (
            <TableScroll>
              <table>
                <caption className="visually-hidden">Accounts with balance, held and available amounts</caption>
                <thead>
                  <tr>
                    <th scope="col">Account</th>
                    <th scope="col">Currency</th>
                    <th scope="col" className="numeric">Balance</th>
                    <th scope="col" className="numeric">Held</th>
                    <th scope="col" className="numeric">Available</th>
                    <th scope="col">Payments</th>
                  </tr>
                </thead>
                <tbody>
                  {accounts.data.map((account) => (
                    <tr key={account.id}>
                      <td><Identifier value={account.id} /></td>
                      <td>{account.currency}</td>
                      <td className="numeric"><Money minorUnits={account.balanceMinor} currency={account.currency} /></td>
                      <td className="numeric"><Money minorUnits={account.heldMinor} currency={account.currency} /></td>
                      <td className="numeric"><Money minorUnits={account.availableMinor} currency={account.currency} /></td>
                      <td>
                        <Link to={`/payments?accountId=${account.id}`}>View payments</Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </TableScroll>
          )}
        </Card>

        {/* Mirrors PaymentReadService.MAX_ACCOUNTS. A truncated list that says nothing about being
            truncated is the same mistake the payment search avoids by reporting matchedCountCapped;
            until this list is paged, saying so is the least it can do. */}
        {accounts.data && accounts.data.length >= 100 && (
          <Notice tone="warning" title="Showing the 100 most recent accounts">
            <span>
              You own at least this many. Older accounts are not listed here, and this screen cannot
              page through them yet.
            </span>
          </Notice>
        )}

        {accounts.data && accounts.data.length > 0 && (
          <Notice tone="info" title="Available is balance minus held">
            An authorization holds funds without spending them. Capturing consumes the hold and reduces the
            balance; voiding releases it back to available.
          </Notice>
        )}
      </div>
    </>
  );
}
