import { expect, test, type Page } from '@playwright/test';
import {
  capture,
  createIsolatedAccount,
  identities,
  paymentCountForAccount,
  replayJobCountForCandidate,
  signIn,
  signOut,
  sql,
} from './support';

/**
 * What a command sends when it is retried after its outcome became unknown.
 *
 * Each test lets the request reach the server and commit, then drops the response. That is the one
 * shape of failure where retrying is both necessary and dangerous: the work exists, so a retry that
 * differs from the original would either be refused as a conflicting reuse of the key or, worse, be
 * accepted as a second command. The assertions are therefore on the bytes of the retried request and
 * on the database, not only on what the screen says.
 *
 * Fixtures are this file's own. Accounts are created for these tests so no authorization is ever made
 * against the balances the seeded demo accounts carry.
 */

const CANDIDATE_DEFINITION = JSON.stringify(
  {
    rules: [
      {
        code: 'STRICT_AMOUNT',
        description: 'Candidate declines at or above 1000 minor units.',
        scoreContribution: 60,
        flag: 'HIGH_AMOUNT',
        terminal: false,
        expression: { operator: 'AMOUNT_AT_LEAST', amountMinor: 1000 },
      },
    ],
  },
  null,
  2,
);

/**
 * The notice holding an unresolved command.
 *
 * Assertions about what is being held must be scoped to it. A confirmation dialog states the same
 * amount and payment id, and since Playwright resolves a locator before filtering on visibility, a
 * page-wide match would hit the dialog's copy and say nothing about the command itself.
 */
function unresolvedNotice(page: Page) {
  return page.locator('.notice', { hasText: 'Submitted command' });
}

/** Lets the request reach the server, then drops the response so the outcome is unknowable. */
async function withholdResponses(page: Page, pattern: string): Promise<void> {
  await page.route(pattern, async (route) => {
    if (route.request().method() !== 'POST') {
      await route.continue();
      return;
    }
    await route.fetch();
    await route.abort('connectionclosed');
  });
}

test.describe.configure({ mode: 'serial' });

test.describe('recovering a command whose outcome is unknown', () => {
  test('an authorization retry resends the submitted amount, not the edited one', async ({ page }) => {
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 5_000_000);
    await signIn(page, identities.merchant());

    await page.goto('/dashboard/payments/new');
    await page.getByLabel('Account').selectOption(account);
    await page.getByLabel(/^Amount/).fill('41.00');

    await withholdResponses(page, '**/ui/payments/authorizations');
    await page.getByRole('button', { name: 'Review and authorize' }).click();
    await page.getByRole('button', { name: 'Authorize', exact: true }).click();

    const held = unresolvedNotice(page);
    await expect(held.getByText('The outcome of this authorization is unknown')).toBeVisible();
    // The notice names the command it is holding, so "retry safely" is a claim the operator can check.
    // Scoped to the notice: the confirmation dialog states the same amount, and a page-wide locator
    // would match its text too and prove nothing about what is actually being held.
    await expect(held.getByText('41.00 CAD')).toBeVisible();
    await expect(held.getByText('4100 minor units')).toBeVisible();
    // The key is shown, so a retry is something the operator can reconcile against the server's records.
    await expect(held.getByText(/^ui-authorize-[0-9a-f-]{36}$/)).toBeVisible();
    await capture(page, '38-unknown-authorization');

    // The server did commit it, so exactly one payment exists and a retry must not make a second.
    expect(await paymentCountForAccount(account)).toBe(1);
    // No second command may be started while this one is unresolved.
    await expect(page.getByRole('button', { name: 'Review and authorize' })).toBeDisabled();

    // The operator edits the amount anyway, then retries.
    await page.getByLabel(/^Amount/).fill('999.00');
    await page.unroute('**/ui/payments/authorizations');

    const [retryRequest] = await Promise.all([
      page.waitForRequest(
        (request) => request.url().includes('/ui/payments/authorizations') && request.method() === 'POST',
      ),
      page.getByRole('button', { name: 'Retry safely' }).click(),
    ]);

    // The retry carries the submitted amount under the original key, not what the form now holds.
    expect(retryRequest.postData()).toContain('"amountMinor":4100');
    expect(retryRequest.postData()).not.toContain('99900');

    await expect(page.getByRole('heading', { name: 'Authorization recorded' })).toBeVisible();
    expect(await paymentCountForAccount(account)).toBe(1);
    expect(await sql(`SELECT amount_minor FROM payments WHERE account_id = '${account}'`)).toBe('4100');
    await capture(page, '39-recovered-authorization');
  });

  test('a replay retry resends the submitted candidate and bound, not the edited ones', async ({ page }) => {
    const stamp = Date.now().toString(36);
    const submittedCandidate = `recovery-submitted-${stamp}`;
    const editedCandidate = `edited-after-submitting-${stamp}`;

    await signIn(page, identities.admin());
    await page.goto('/dashboard/policies');
    await page.getByRole('button', { name: 'Register candidate' }).first().click();
    for (const version of [submittedCandidate, editedCandidate]) {
      await page.getByLabel('Version identifier').fill(version);
      await page.getByLabel('Definition').fill(CANDIDATE_DEFINITION);
      await page.getByRole('button', { name: 'Register candidate' }).last().click();
      await expect(page.getByText(`Stored as ${version}`)).toBeVisible();
    }

    await signOut(page);
    await signIn(page, identities.merchant());
    await page.goto('/dashboard/replay');
    await page.getByLabel('Candidate policy').selectOption(submittedCandidate);
    await page.getByLabel('Maximum inputs').fill('7');

    await withholdResponses(page, '**/ui/replay-jobs');
    await page.getByRole('button', { name: 'Create job' }).click();

    const held = unresolvedNotice(page);
    await expect(held.getByText('The job may or may not have been created')).toBeVisible();
    await expect(held.getByText(submittedCandidate)).toBeVisible();
    await expect(held.getByText('7', { exact: true })).toBeVisible();
    expect(await replayJobCountForCandidate(submittedCandidate)).toBe(1);
    await expect(page.getByRole('button', { name: 'Create job' })).toBeDisabled();

    // Both the candidate and the membership bound are changed before retrying.
    await page.getByLabel('Candidate policy').selectOption(editedCandidate);
    await page.getByLabel('Maximum inputs').fill('5000');
    await page.unroute('**/ui/replay-jobs');

    const [retryRequest] = await Promise.all([
      page.waitForRequest((request) => request.url().includes('/ui/replay-jobs') && request.method() === 'POST'),
      page.getByRole('button', { name: 'Retry safely' }).click(),
    ]);

    expect(retryRequest.postData()).toContain(submittedCandidate);
    expect(retryRequest.postData()).not.toContain(editedCandidate);
    expect(retryRequest.postData()).toContain('"limit":7');

    await expect(page.getByText('Job created')).toBeVisible();
    // The original job, not a second one, and nothing at all against the edited candidate.
    expect(await replayJobCountForCandidate(submittedCandidate)).toBe(1);
    expect(await replayJobCountForCandidate(editedCandidate)).toBe(0);
    await capture(page, '40-recovered-replay-job');
  });

  test('a capture retry replays the same command and captures once', async ({ page }) => {
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 5_000_000);
    await signIn(page, identities.merchant());

    await page.goto('/dashboard/payments/new');
    await page.getByLabel('Account').selectOption(account);
    await page.getByLabel(/^Amount/).fill('19.00');
    await page.getByRole('button', { name: 'Review and authorize' }).click();
    await page.getByRole('button', { name: 'Authorize', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Authorization recorded' })).toBeVisible();
    await page.getByRole('button', { name: 'Open payment' }).click();
    await expect(page).toHaveURL(/\/dashboard\/payments\/[0-9a-f-]{36}$/);
    const paymentId = page.url().slice(page.url().lastIndexOf('/') + 1);

    const capturePattern = `**/payments/${paymentId}/capture`;
    await withholdResponses(page, capturePattern);
    await page.getByRole('button', { name: 'Capture' }).click();
    await page.getByRole('button', { name: 'Capture funds' }).click();

    const held = unresolvedNotice(page);
    await expect(held.getByText('Capture may or may not have been applied')).toBeVisible();
    await expect(held.getByText(paymentId, { exact: true })).toBeVisible();
    // The capture did commit, despite the lost response.
    expect(await sql(`SELECT status FROM payments WHERE id = '${paymentId}'`)).toBe('CAPTURED');

    await page.unroute(capturePattern);
    const [retryRequest] = await Promise.all([
      page.waitForRequest(
        (request) => request.url().includes(`/payments/${paymentId}/capture`) && request.method() === 'POST',
      ),
      page.getByRole('button', { name: 'Retry safely' }).click(),
    ]);
    expect(retryRequest.url()).toContain(`/ui/payments/${paymentId}/capture`);

    // One capture, one sealed journal: the retry returned the original result rather than capturing twice.
    await expect(page.getByText('Capture completed')).toBeVisible();
    expect(await sql(`SELECT count(*) FROM ledger_journals WHERE payment_id = '${paymentId}'`)).toBe('1');
  });
});
