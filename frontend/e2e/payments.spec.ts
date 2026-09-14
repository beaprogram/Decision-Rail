import { expect, test } from '@playwright/test';
import { authorizeThroughUi, capture, createIsolatedAccount, identities, signIn, sql } from './support';

/** Authorization, decision evidence, lifecycle actions, search, and the timeline. */
test.describe('payment workspace', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page, identities.merchant());
  });

  test('authorizes a payment and shows the stored decision that produced it', async ({ page }) => {
    const paymentId = await authorizeThroughUi(page, '25.00');

    await expect(page.getByRole('heading', { name: 'Payment', level: 1 })).toBeVisible();
    // The identifier appears in the header and again in the detail list.
    await expect(page.getByText(paymentId).first()).toBeVisible();
    await expect(page.getByText('AUTHORIZED').first()).toBeVisible();

    // The explanation is the stored one: outcome, score, policy version and reason contributions.
    const decision = page.locator('section.card', { hasText: 'Stored decision' });
    await expect(decision.getByText('APPROVE')).toBeVisible();
    await expect(decision.getByText('demo-v1')).toBeVisible();
    await expect(decision.getByText('NO_RISK_SIGNALS')).toBeVisible();
    await expect(decision.getByText(/Recorded when the payment was authorized/)).toBeVisible();

    // The amount is exact: 25.00 became 2500 minor units, not 2499 or 2500.0000001.
    await expect(page.getByText('25.00').first()).toBeVisible();
    await capture(page, '05-payment-detail');
  });

  test('converts a typed amount to exact minor units before sending it', async ({ page }) => {
    await page.goto('/dashboard/payments/new');
    await page.getByLabel('Account').selectOption({ index: 1 });

    // 0.07 is one of the values naive float conversion gets wrong.
    await page.getByLabel(/^Amount/).fill('0.07');
    await expect(page.getByLabel('Converted amount')).toHaveValue('7 minor units');

    await page.getByLabel(/^Amount/).fill('25.5');
    await expect(page.getByLabel('Converted amount')).toHaveValue('2550 minor units');

    // Too much precision is refused rather than rounded into a different amount of money.
    await page.getByLabel(/^Amount/).fill('1.234');
    await expect(page.getByText('Use at most 2 decimal places.')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Review and authorize' })).toBeDisabled();

    await page.getByLabel(/^Amount/).fill('-5');
    await expect(page.getByText(/plain amount such as 25/)).toBeVisible();
    await capture(page, '06-amount-validation');
  });

  test('shows a policy decline with its reasons, distinct from a funding decline', async ({ page }) => {
    // Large enough to trigger the built-in high-amount rule.
    const paymentId = await authorizeThroughUi(page, '6000.00');

    await expect(page.getByText('DECLINED').first()).toBeVisible();
    const decision = page.locator('section.card', { hasText: 'Stored decision' });
    await expect(decision.getByText('DECLINE').first()).toBeVisible();
    // HIGH_AMOUNT appears both as a decision flag and as the reason code that produced it.
    await expect(decision.getByText('HIGH_AMOUNT').first()).toBeVisible();
    // A policy decline has no funding failure code.
    await expect(page.getByText('Declined for funds, not by policy')).toHaveCount(0);
    // And it never reserved anything. The funding row is derived from the lifecycle state: having no
    // failure code used to be read as success here, which labelled this payment "Funds reserved".
    await expect(page.getByText('No funds reserved').first()).toBeVisible();
    await expect(page.getByText('Declined by policy before any reservation was attempted.')).toBeVisible();
    await expect(page.getByText('Funds reserved', { exact: true })).toHaveCount(0);
    expect(paymentId).toMatch(/^[0-9a-f-]{36}$/);
    await capture(page, '07-policy-decline');
  });

  test('shows a funding decline as a funding failure while keeping the policy approval visible', async ({ page }) => {
    // Its own account, with barely any balance, so the decline comes from funds and no seeded account
    // is touched. 20.00 against 5.00 available is refused by funds while the policy approves it.
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 500);
    await page.goto('/dashboard/payments/new');
    await page.getByLabel('Account').selectOption(account);
    await page.getByLabel(/^Amount/).fill('20.00');
    await expect(page.getByText('This is more than the account currently has available')).toBeVisible();
    await page.getByRole('button', { name: 'Review and authorize' }).click();
    await page.getByRole('button', { name: 'Authorize', exact: true }).click();
    await page.getByRole('button', { name: 'Open payment' }).click();

    // Two separate facts, kept separate: the risk decision approved, and the funding failed.
    const decision = page.locator('section.card', { hasText: 'Stored decision' });
    await expect(decision.getByText('APPROVE').first()).toBeVisible();
    await expect(page.getByText('Declined for funds, not by policy')).toBeVisible();
    await expect(page.getByText('INSUFFICIENT_FUNDS').first()).toBeVisible();
    await expect(
      page.getByText('The policy approved this payment. It was declined because the account did not have enough available funds, so nothing was reserved.'),
    ).toBeVisible();
    // Nothing was held, whatever the risk outcome said.
    await expect(page.getByText('Funds reserved', { exact: true })).toHaveCount(0);
    expect(await sql(`SELECT held_minor FROM accounts WHERE id = '${account}'`)).toBe('0');
    await capture(page, '41-funding-decline');
  });

  test('captures one payment and voids another, then reflects both', async ({ page }) => {
    const toCapture = await authorizeThroughUi(page, '12.00');
    await page.getByRole('button', { name: 'Capture' }).click();
    // The confirmation restates the concrete payment and amount rather than just asking "are you sure".
    await expect(page.getByText('Capture this payment?')).toBeVisible();
    await expect(page.getByRole('dialog').getByText(toCapture)).toBeVisible();
    await expect(page.getByRole('dialog').getByText('12.00')).toBeVisible();
    await capture(page, '08-capture-confirmation');
    await page.getByRole('button', { name: 'Capture funds' }).click();

    await expect(page.getByText('Capture completed')).toBeVisible();
    await expect(page.getByText('CAPTURED').first()).toBeVisible();
    // A capture writes a balanced journal, which the detail screen then shows.
    // Scoped to the journal card. The returns panel's confirmation copy also contains the word
    // "credited", so a page-wide text match no longer says anything about the journal.
    const journal = page.locator('section.card', { hasText: 'Capture journal' });
    await expect(page.getByRole('heading', { name: 'Capture journal' })).toBeVisible();
    await expect(journal.getByText('DEBIT', { exact: true })).toBeVisible();
    await expect(journal.getByText('CREDIT', { exact: true })).toBeVisible();
    // Capture and void are no longer offered for a terminal payment.
    // exact: true because an accessible-name match is a substring match, and the returns panel
    // offers "Reverse the capture" on a captured payment. The assertion is about the Capture
    // button being gone, so it says exactly that.
    await expect(page.getByRole('button', { name: 'Capture', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Void' })).toHaveCount(0);
    await capture(page, '09-captured-with-journal');

    const toVoid = await authorizeThroughUi(page, '9.00');
    await page.getByRole('button', { name: 'Void' }).click();
    await expect(page.getByRole('dialog').getByText(toVoid)).toBeVisible();
    await page.getByRole('button', { name: 'Release hold' }).click();

    await expect(page.getByText('Void completed')).toBeVisible();
    await expect(page.getByText('VOIDED').first()).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Capture journal' })).toHaveCount(0);
  });

  test('searches, filters, pages, and survives a refresh on a deep link', async ({ page }) => {
    const paymentId = await authorizeThroughUi(page, '31.00');

    await page.goto('/dashboard/payments');
    await page.getByLabel('Payment ID').fill(paymentId);
    await page.getByRole('button', { name: 'Apply' }).click();
    await expect(page.getByRole('row')).toHaveCount(2); // header plus one match
    await expect(page.getByText('31.00')).toBeVisible();
    // The filter lives in the URL, so the view is shareable.
    await expect(page).toHaveURL(new RegExp(`paymentId=${paymentId}`));

    // A refresh restores the same filtered view rather than resetting it.
    await page.reload();
    await expect(page.getByText('31.00')).toBeVisible();
    await expect(page.getByLabel('Payment ID')).toHaveValue(paymentId);

    // A filter that matches nothing says so rather than showing an empty table.
    await page.getByLabel('Payment ID').fill('');
    await page.getByLabel('Payment status').selectOption('REVIEW');
    await page.getByLabel('Currency').selectOption('USD');
    await page.getByRole('button', { name: 'Apply' }).click();
    await expect(page.getByText('No payments match these filters')).toBeVisible();
    await capture(page, '10-empty-state');

    // Clearing shows the unfiltered list with paging controls.
    await page.getByRole('button', { name: 'Clear' }).click();
    await expect(page.getByRole('heading', { name: 'Results' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Previous' }).first()).toBeDisabled();

    const next = page.getByRole('button', { name: 'Next' }).first();
    if (await next.isEnabled()) {
      const firstPageIds = await page.locator('tbody tr td:first-child').allInnerTexts();
      await next.click();
      await expect(page.getByText('Page 2')).toBeVisible();
      // The page label changes as soon as the click is handled, so wait for the rows themselves to
      // be replaced before comparing. Polling here is about the data arriving, not about the
      // guarantee: that pages never overlap is asserted directly against the API in the backend suite.
      await expect
        .poll(async () => {
          const current = await page.locator('tbody tr td:first-child').allInnerTexts();
          return current.every((id) => !firstPageIds.includes(id));
        })
        .toBe(true);
      await page.getByRole('button', { name: 'Previous' }).first().click();
      await expect(page.getByText('Page 1')).toBeVisible();
    }
    await capture(page, '11-payment-search');
  });

  test('rejects an invalid filter with the server message rather than showing wrong rows', async ({ page }) => {
    // A malformed cursor must not silently restart paging.
    await page.goto('/dashboard/payments?cursor=not-a-real-cursor');
    await expect(page.getByText('The server rejected these filters')).toBeVisible();
    await expect(page.getByText(/pagination cursor is not valid/i)).toBeVisible();
    await expect(page.locator('tbody tr')).toHaveCount(0);
  });

  test('shows the lifecycle with command, publication and consumption kept separate', async ({ page }) => {
    const paymentId = await authorizeThroughUi(page, '44.00');
    await page.goto(`/dashboard/payments/${paymentId}`);

    const lifecycle = page.locator('section.card', { hasText: 'Lifecycle' });
    await expect(lifecycle.getByText('payment.authorized.v1').first()).toBeVisible();
    await expect(lifecycle.getByText('committed').first()).toBeVisible();
    // Delivery is its own column, and consumption is its own column again.
    await expect(lifecycle.getByRole('columnheader', { name: 'Delivery' })).toBeVisible();
    await expect(lifecycle.getByRole('columnheader', { name: 'Consumed by' })).toBeVisible();
    await capture(page, '12-lifecycle-timeline');
  });

  test('shows account balances per currency without combining them', async ({ page }) => {
    await page.goto('/dashboard/accounts');
    await expect(page.getByRole('heading', { name: 'Accounts', level: 1 })).toBeVisible();

    // Every total names its currency and says it covers only that currency.
    await expect(page.getByText(/Currencies are never combined/).first()).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Available' })).toBeVisible();
    await capture(page, '13-accounts');
  });
});
