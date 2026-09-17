import { expect, test, type Page } from '@playwright/test';

/**
 * The guided demo, recorded.
 *
 * Two segments, two videos. The first is what a visitor can do with the public credentials on the
 * sign-in page. The second uses a private administrator identity and is recorded against an
 * isolated instance; it is labelled as the operator walkthrough wherever it is published, because a
 * visitor to the public demo cannot perform those actions.
 *
 * Credentials come from the environment and never appear in the video: the visitor's are typed by
 * the page's own "Use the visitor credentials" button, which is what a visitor would press, and the
 * password field masks them; the administrator's are filled without being shown.
 */
const VISITOR_PASSWORD = process.env.VISITOR_PASSWORD ?? '';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD ?? '';
const CANDIDATE = 'strict-amount-v1';

/** A beat between steps so the recording can be followed; not an assertion of anything. */
async function beat(page: Page, ms = 1200): Promise<void> {
  await page.waitForTimeout(ms);
}

async function signInAsVisitor(page: Page): Promise<void> {
  await page.goto('/dashboard/');
  await expect(page.getByText('Public demo - synthetic money, shared state')).toBeVisible();
  await beat(page, 2500);
  await page.getByRole('button', { name: 'Use the visitor credentials' }).click();
  await beat(page);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('navigation', { name: 'Dashboard sections' }).getByText('visitor', { exact: true })).toBeVisible();
}

test.describe('walkthrough', () => {
  test('visitor: decision, lifecycle, returns, reconciliation, replay and shadow', async ({ page }) => {
    test.skip(!VISITOR_PASSWORD, 'VISITOR_PASSWORD is required');
    await signInAsVisitor(page);
    await beat(page, 1500);

    // The seeded history: every row here was produced by the real service.
    await expect(page.getByRole('heading', { name: 'Payments', level: 1 })).toBeVisible();
    await expect(page.locator('tbody tr').first()).toBeVisible();
    await beat(page, 2500);

    // A policy decline, found with the server-side filters: the stored decision names the rule.
    await page.getByLabel('Payment status').selectOption('DECLINED');
    await page.getByLabel('Currency').selectOption('CAD');
    await page.getByRole('button', { name: 'Apply' }).click();
    await expect(page.locator('tbody tr').first()).toBeVisible();
    await beat(page, 2000);
    await page.locator('tbody tr', { hasText: 'DECLINED' }).first().getByRole('link').first().click();
    await expect(page.getByRole('heading', { name: 'Payment', level: 1 })).toBeVisible();
    const decision = page.locator('section.card', { hasText: 'Stored decision' });
    await expect(decision.getByText('DECLINE').first()).toBeVisible();
    await expect(decision.getByText('TEST_COUNTRY_BLOCKED').first()).toBeVisible();
    await beat(page, 3000);

    // A funding decline is a different thing, and is shown as one.
    await page.goto('/dashboard/payments?status=DECLINED&currency=USD');
    await expect(page.locator('tbody tr').first()).toBeVisible();
    await page.locator('tbody tr', { hasText: 'DECLINED' }).first().getByRole('link').first().click();
    await expect(page.getByText('Declined for funds, not by policy')).toBeVisible();
    await beat(page, 3000);

    // A live authorization through the form, then capture, then a partial refund with its evidence.
    await page.goto('/dashboard/payments/new');
    const accountSelect = page.getByLabel('Account');
    await expect(accountSelect.locator('option').nth(1)).toBeAttached();
    await accountSelect.selectOption('aaaa0001-0000-4000-8000-000000000002');
    await page.getByLabel(/^Amount/).fill('42.00');
    await beat(page);
    await page.getByRole('button', { name: 'Review and authorize' }).click();
    await beat(page, 1500);
    await page.getByRole('button', { name: 'Authorize', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Authorization recorded' })).toBeVisible();
    await beat(page, 1500);
    await page.getByRole('button', { name: 'Open payment' }).click();
    await expect(page.getByRole('heading', { name: 'Payment', level: 1 })).toBeVisible();
    await expect(decision.getByText('APPROVE')).toBeVisible();
    await beat(page, 2000);

    await page.getByRole('button', { name: 'Capture' }).click();
    await beat(page);
    await page.getByRole('button', { name: 'Capture funds' }).click();
    await expect(page.getByText('Capture completed')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Capture journal' })).toBeVisible();
    await beat(page, 2500);

    const returns = page.locator('section.card', { hasText: 'Returns' });
    await returns.getByLabel(/^Refund amount/).fill('15.00');
    await returns.getByLabel(/^Reason/).fill('customer returned one item');
    await beat(page);
    await returns.getByRole('button', { name: 'Refund', exact: true }).click();
    await beat(page);
    await page.getByRole('button', { name: 'Return the funds' }).click();
    await expect(page.getByText('Refund recorded')).toBeVisible();
    await expect(returns.getByText('customer returned one item')).toBeVisible();
    await beat(page, 3000);

    // Reconciliation: read-only, and it says what it examined.
    await page.getByRole('link', { name: 'Reconciliation' }).click();
    await expect(page.getByRole('heading', { name: 'Reconciliation', level: 1 })).toBeVisible();
    await page.getByRole('button', { name: 'Reconcile' }).click();
    await expect(page.getByText(/Everything in scope reconciles|discrepanc/i).first()).toBeVisible();
    await beat(page, 3500);

    // Replay: the seeded job against the candidate, and where it diverged from the stored decisions.
    await page.getByRole('link', { name: 'Policy replay' }).click();
    await expect(page.getByText('COMPLETED').first()).toBeVisible();
    await page.locator('tbody tr', { hasText: 'COMPLETED' }).first().getByRole('link').first().click();
    await expect(page.getByText('Pinned inputs')).toBeVisible();
    await beat(page, 2500);
    await page.getByLabel('Diverged only').check();
    await expect(page.getByText('diverged').first()).toBeVisible();
    await beat(page, 3000);

    // Shadow: the candidate's disagreement recorded beside a live decision it did not change.
    await page.getByRole('link', { name: 'Shadow comparisons' }).click();
    await expect(page.getByText(CANDIDATE).first()).toBeVisible();
    await beat(page, 3000);

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await beat(page, 1500);
  });

  test('operator (isolated instance): policy registration, shadow configuration, delivery state', async ({ page }) => {
    test.skip(!ADMIN_PASSWORD, 'ADMIN_PASSWORD is required');
    await page.goto('/dashboard/');
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await page.getByLabel('Username').fill('admin');
    await page.getByLabel('Password').fill(ADMIN_PASSWORD);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page.getByRole('navigation', { name: 'Dashboard sections' }).getByText('admin', { exact: true })).toBeVisible();
    await beat(page, 1500);

    // Policy versions: the authoritative version, the candidate, and structured validation.
    await page.getByRole('link', { name: 'Policy versions' }).click();
    await expect(page.getByText('Authoritative').first()).toBeVisible();
    await beat(page, 2500);
    await page.getByRole('button', { name: 'Register candidate' }).click();
    await page.getByLabel('Version identifier').fill('walkthrough-invalid');
    await page.getByLabel('Definition').fill('{"rules":[{"code":"BAD","description":"x","scoreContribution":10,"flag":"HIGH_AMOUNT","terminal":false,"expression":{"operator":"AMOUNT_AT_LEAST","amountMinor":-5}}]}');
    await beat(page);
    await page.getByRole('button', { name: 'Register candidate' }).last().click();
    await expect(page.getByText('The definition was rejected')).toBeVisible();
    await beat(page, 3000);

    // Shadow configuration: selecting a candidate grants it no authority.
    await page.getByRole('link', { name: 'Shadow configuration' }).click();
    await expect(page.getByText(/not backfill/i).first()).toBeVisible();
    await beat(page, 3000);

    // Delivery: what is published, what is stalled, what an operator may redrive.
    await page.getByRole('link', { name: 'Event delivery' }).click();
    await expect(page.getByText('PUBLISHED events')).toBeVisible();
    await expect(page.getByText('Stalled payment streams')).toBeVisible();
    await beat(page, 3500);

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  });
});
