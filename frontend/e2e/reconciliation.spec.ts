import { expect, test } from '@playwright/test';
import { capture, createIsolatedAccount, identities, signIn, sql, statusWithSessionCookie, sessionCookie } from './support';

/**
 * The reconciliation workspace: a correct population, a deliberately inconsistent one, and the
 * boundary that keeps it read-only and merchant-scoped.
 *
 * Inconsistency is introduced only into accounts these tests created. The report covers every account
 * the merchant owns, so a fixture corrupted anywhere else would be indistinguishable from a real
 * finding and would make the clean case unassertable.
 */
test.describe.configure({ mode: 'serial' });

async function capturedPaymentOn(page: import('@playwright/test').Page, account: string, amount: string): Promise<string> {
  await page.goto('/dashboard/payments/new');
  await page.getByLabel('Account').selectOption(account);
  await page.getByLabel(/^Amount/).fill(amount);
  await page.getByRole('button', { name: 'Review and authorize' }).click();
  await page.getByRole('button', { name: 'Authorize', exact: true }).click();
  await page.getByRole('button', { name: 'Open payment' }).click();
  await expect(page).toHaveURL(/\/dashboard\/payments\/[0-9a-f-]{36}$/);
  await page.getByRole('button', { name: 'Capture' }).click();
  await page.getByRole('button', { name: 'Capture funds' }).click();
  await expect(page.getByText('Capture completed')).toBeVisible();
  return page.url().slice(page.url().lastIndexOf('/') + 1);
}

async function reconcile(page: import('@playwright/test').Page, account: string): Promise<void> {
  await page.getByRole('link', { name: 'Reconciliation' }).click();
  // Waited for explicitly. The payments screen this navigates away from has its own field labelled
  // "Account", so filling before the route has changed can resolve against that one instead.
  await expect(page).toHaveURL(/\/dashboard\/reconciliation$/);
  await expect(page.getByRole('heading', { name: 'Reconciliation', level: 1 })).toBeVisible();
  await page.getByLabel(/^Account/).fill(account);
  await page.getByRole('button', { name: 'Reconcile' }).click();
}

test.describe('reconciliation', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page, identities.merchant());
  });

  test('reports a correct account as reconciling, and states what it examined', async ({ page }) => {
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 200_000);
    const paymentId = await capturedPaymentOn(page, account, '60.00');

    // A refund, so the report is covering compensating evidence rather than captures alone.
    const card = page.locator('section.card', { hasText: 'Returns' });
    await card.getByLabel(/^Refund amount/).fill('20.00');
    await card.getByRole('button', { name: 'Refund', exact: true }).click();
    await page.getByRole('button', { name: 'Return the funds' }).click();
    await expect(page.getByText('Refund recorded')).toBeVisible();

    await reconcile(page, account);
    await expect(page.getByText('Everything in scope reconciles')).toBeVisible();
    await expect(page.getByText('Nothing disagreed in what was examined')).toBeVisible();
    await expect(page.getByText('REPEATABLE READ, one snapshot for the whole report')).toBeVisible();
    // The scope is stated, so an absent check is visible rather than mistaken for a passing one.
    await expect(page.getByText(/ACCOUNT_BALANCE: opening balance/)).toBeVisible();
    await expect(page.getByText(/The activity projection is not read/)).toBeVisible();
    expect(paymentId).toMatch(/^[0-9a-f-]{36}$/);
    await capture(page, '48-reconciliation-clean');
  });

  test('detects a balance that disagrees with the ledger and shows the evidence', async ({ page }) => {
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 200_000);
    await capturedPaymentOn(page, account, '60.00');

    // A disposable fixture made inconsistent on purpose: the balance moves, the ledger does not.
    await sql(`UPDATE accounts SET balance_minor = balance_minor - 250 WHERE id = '${account}'`);

    await reconcile(page, account);
    await expect(page.getByText('1 discrepancy found')).toBeVisible();
    const row = page.locator('tbody tr', { hasText: 'ACCOUNT_BALANCE_MISMATCH' });
    await expect(row).toBeVisible();
    // Expected, actual and the difference, so the finding can be acted on without redoing the work.
    await expect(row.getByText('1,940.00 CAD')).toBeVisible();
    await expect(row.getByText('1,937.50 CAD')).toBeVisible();
    await expect(row.getByText('-2.50 CAD')).toBeVisible();
    await expect(page.getByText(/never an edit to history/)).toBeVisible();
    await capture(page, '49-reconciliation-finding');

    // Reporting changes nothing: the balance is exactly as it was left.
    expect(await sql(`SELECT balance_minor FROM accounts WHERE id = '${account}'`)).toBe('193750');
  });

  test('never reports a bounded examination as clean', async ({ page }) => {
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 200_000);
    await capturedPaymentOn(page, account, '5.00');
    await capturedPaymentOn(page, account, '5.00');

    // A limit below the population, requested directly so the browser sees the same report the API
    // produces. The status must not be "clean": an unexamined population has not been cleared.
    await page.goto(`/dashboard/reconciliation`);
    const report = await page.evaluate(async (id) => {
      const response = await fetch(`/ui/reconciliation?accountId=${id}&paymentLimit=1`, {
        headers: { Accept: 'application/json' },
      });
      return response.json();
    }, account);
    expect(report.status).toBe('INCOMPLETE');
    expect(report.findings).toEqual([]);
    expect(report.scope.complete).toBe(false);
    expect(report.scope.incompleteReason).toContain('more than 1 payments match');
  });

  test('another merchant’s accounts are absent, and the administrative view needs its own role', async ({ page }) => {
    const theirs = await createIsolatedAccount('other-merchant', 'CAD', 90_000);
    await sql(`UPDATE accounts SET balance_minor = balance_minor - 500 WHERE id = '${theirs}'`);

    // Broken, and invisible: asking for it by id returns a report that examined nothing.
    await reconcile(page, theirs);
    await expect(page.getByText('Everything in scope reconciles')).toBeVisible();
    await expect(page.locator('tbody tr', { hasText: 'ACCOUNT_BALANCE_MISMATCH' })).toHaveCount(0);

    // Hiding a control is not authorization. The administrative route refuses this session on the
    // server, with a real request rather than an absent button.
    const cookie = await sessionCookie(page);
    expect(await statusWithSessionCookie(cookie, '/ui/ops/reconciliation?merchantId=other-merchant')).toBe(403);
  });
});
