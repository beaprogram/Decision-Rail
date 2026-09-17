import { expect, test, type Page } from '@playwright/test';
import { capture, createIsolatedAccount, identities, refundThroughApi, signIn, sql } from './support';

/**
 * Returning captured money from the browser: partial refunds, the remainder, a reversal, and an
 * attempt whose outcome is unknown.
 *
 * Every test creates its own account, so no assertion about a balance depends on what another test
 * left behind, and no synthetic demo balance is consumed.
 */
test.describe.configure({ mode: 'serial' });

const returnsCard = (page: Page) => page.locator('section.card', { hasText: 'Returns' });

/** The sequence numbers rendered in the returns table, top to bottom. */
async function sequencesOnScreen(card: ReturnType<typeof returnsCard>): Promise<number[]> {
  const cells = await card.locator('tbody tr td:first-child').allInnerTexts();
  return cells.map((text) => Number(text.trim()));
}

/** The notice holding an unresolved command. Scoped, because the dialog restates the same figures. */
const unresolvedNotice = (page: Page) => page.locator('.notice', { hasText: 'Submitted command' });

async function capturedPaymentOn(page: Page, account: string, amount: string): Promise<string> {
  await page.goto('/dashboard/payments/new');
  await page.getByLabel('Account').selectOption(account);
  await page.getByLabel(/^Amount/).fill(amount);
  await page.getByRole('button', { name: 'Review and authorize' }).click();
  await page.getByRole('button', { name: 'Authorize', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Authorization recorded' })).toBeVisible();
  await page.getByRole('button', { name: 'Open payment' }).click();
  await expect(page).toHaveURL(/\/dashboard\/payments\/[0-9a-f-]{36}$/);

  await page.getByRole('button', { name: 'Capture' }).click();
  await page.getByRole('button', { name: 'Capture funds' }).click();
  await expect(page.getByText('Capture completed')).toBeVisible();
  return page.url().slice(page.url().lastIndexOf('/') + 1);
}

async function refundThroughUi(page: Page, amount: string, reason: string): Promise<void> {
  const card = returnsCard(page);
  await card.getByLabel(/^Refund amount/).fill(amount);
  await card.getByLabel(/^Reason/).fill(reason);
  await card.getByRole('button', { name: 'Refund', exact: true }).click();
  await page.getByRole('button', { name: 'Return the funds' }).click();
  await expect(page.getByText('Refund recorded')).toBeVisible();
}

test.describe('returning captured money', () => {
  test.beforeEach(async ({ page }) => {
    await signIn(page, identities.merchant());
  });

  test('shows captured, returned and remaining, and refunds part then the rest', async ({ page }) => {
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 500_000);
    const paymentId = await capturedPaymentOn(page, account, '50.00');

    const card = returnsCard(page);
    await expect(card.getByText('50.00 CAD').first()).toBeVisible();
    await expect(card.getByText('No returns on this payment')).toBeVisible();

    await refundThroughUi(page, '20.00', 'customer returned one item');
    // The totals shown are re-read from the server, not patched from the command's own response.
    await expect(card.getByText('20.00 CAD').first()).toBeVisible();
    await expect(card.getByText('customer returned one item')).toBeVisible();
    await expect(card.getByText('REFUND').first()).toBeVisible();
    await capture(page, '45-partial-refund');

    // "Refund everything remaining" fills the exact remaining amount rather than sending a request
    // that means "whatever is left".
    await card.getByRole('button', { name: 'Refund everything remaining' }).click();
    await expect(card.getByLabel(/^Refund amount/)).toHaveValue('30.00');
    await card.getByRole('button', { name: 'Refund', exact: true }).click();
    await page.getByRole('button', { name: 'Return the funds' }).click();
    await expect(page.getByText('Refund recorded')).toBeVisible();

    await expect(card.getByText('No further returns are possible')).toBeVisible();
    await expect(card.getByText(/Everything captured on this payment has already been returned/)).toBeVisible();
    await expect(card.getByRole('button', { name: 'Refund', exact: true })).toHaveCount(0);

    // The database agrees, and the account is whole again.
    expect(await sql(`SELECT count(*) FROM payment_returns WHERE payment_id = '${paymentId}'`)).toBe('2');
    expect(await sql(`SELECT returned_amount_minor FROM payments WHERE id = '${paymentId}'`)).toBe('5000');
    expect(await sql(`SELECT balance_minor FROM accounts WHERE id = '${account}'`)).toBe('500000');
    // One capture journal and two return journals: the original evidence is untouched.
    expect(await sql(
      `SELECT count(*) FROM ledger_journals WHERE payment_id = '${paymentId}' AND journal_kind = 'CAPTURE'`,
    )).toBe('1');
    expect(await sql(
      `SELECT count(*) FROM ledger_journals WHERE payment_id = '${paymentId}' AND journal_kind = 'RETURN'`,
    )).toBe('2');
  });

  test('refuses more than remains, and refuses excess decimal precision', async ({ page }) => {
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 500_000);
    const paymentId = await capturedPaymentOn(page, account, '10.00');
    const card = returnsCard(page);

    await card.getByLabel(/^Refund amount/).fill('10.01');
    await expect(card.getByText('At most 10.00 CAD can still be returned.')).toBeVisible();
    await expect(card.getByRole('button', { name: 'Refund', exact: true })).toBeDisabled();

    // Money is converted on the digit string; too much precision is a mistake to show, not to round.
    await card.getByLabel(/^Refund amount/).fill('1.234');
    await expect(card.getByText('Use at most 2 decimal places.')).toBeVisible();
    await expect(card.getByRole('button', { name: 'Refund', exact: true })).toBeDisabled();

    await card.getByLabel(/^Refund amount/).fill('0.07');
    await expect(card.getByRole('button', { name: 'Refund', exact: true })).toBeEnabled();
    await card.getByRole('button', { name: 'Refund', exact: true }).click();
    await page.getByRole('button', { name: 'Return the funds' }).click();
    await expect(page.getByText('Refund recorded')).toBeVisible();

    // 0.07 is one of the amounts naive float conversion gets wrong; it must be exactly 7 minor units.
    expect(await sql(`SELECT returned_amount_minor FROM payments WHERE id = '${paymentId}'`)).toBe('7');
    expect(await sql(`SELECT count(*) FROM payment_returns WHERE payment_id = '${paymentId}'`)).toBe('1');
  });

  test('reverses a whole capture, and stops offering reversal once anything has been refunded', async ({ page }) => {
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 500_000);
    const reversible = await capturedPaymentOn(page, account, '30.00');
    const card = returnsCard(page);

    await expect(card.getByRole('button', { name: 'Reverse the capture' })).toBeVisible();
    await card.getByRole('button', { name: 'Reverse the capture' }).click();
    await page.getByRole('button', { name: 'Reverse the capture', exact: true }).last().click();
    await expect(page.getByText('Reversal recorded')).toBeVisible();
    expect(await sql(`SELECT return_type FROM payment_returns WHERE payment_id = '${reversible}'`)).toBe('REVERSAL');
    expect(await sql(`SELECT returned_amount_minor FROM payments WHERE id = '${reversible}'`)).toBe('3000');
    await capture(page, '46-reversal');

    // A different payment, partially refunded: the reversal is gone and the screen says why.
    const partiallyRefunded = await capturedPaymentOn(page, account, '30.00');
    await refundThroughUi(page, '5.00', 'partial');
    await expect(card.getByRole('button', { name: 'Reverse the capture' })).toHaveCount(0);
    await expect(card.getByText('Reversal is no longer available')).toBeVisible();
    await expect(card.getByText(/can no longer be reversed/)).toBeVisible();
    // Refunding the remainder is still offered, because that is a refund and not a reversal.
    await expect(card.getByRole('button', { name: 'Refund', exact: true })).toBeVisible();
    expect(await sql(
      `SELECT count(*) FROM payment_returns WHERE payment_id = '${partiallyRefunded}' AND return_type = 'REVERSAL'`,
    )).toBe('0');
  });

  test('an uncaptured payment explains that void, not refund, is the applicable action', async ({ page }) => {
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 500_000);
    await page.goto('/dashboard/payments/new');
    await page.getByLabel('Account').selectOption(account);
    await page.getByLabel(/^Amount/).fill('12.00');
    await page.getByRole('button', { name: 'Review and authorize' }).click();
    await page.getByRole('button', { name: 'Authorize', exact: true }).click();
    await page.getByRole('button', { name: 'Open payment' }).click();

    const card = returnsCard(page);
    await expect(card.getByText('not captured')).toBeVisible();
    await expect(card.getByText(/Nothing has been captured on this payment/)).toBeVisible();
    await expect(card.getByRole('button', { name: 'Refund', exact: true })).toHaveCount(0);
    await expect(card.getByRole('button', { name: 'Reverse the capture' })).toHaveCount(0);
  });

  /**
   * History that genuinely spans more than one page, traversed with the page's own controls.
   *
   * The API's page size is 50 and the dashboard does not override it, so a payment needs more than
   * fifty return operations before there is a second page to reach at all. An earlier version of this
   * test created four and asserted on a single page while claiming to cover paging; the buttons it
   * describes were never pressed. These returns are real operations made through the API because
   * fifty-odd trips through the form would take minutes without testing anything the form's own tests
   * above do not already cover - what is under test here is the traversal.
   *
   * What a return committing between two page reads does to the pages already fetched is asserted
   * where it can be asserted without depending on the dashboard's cache timing: against the API, in
   * RefundIntegrationTest#aReturnCommittedWhilePagingDoesNotDisturbThePagesAlreadyRead, alongside the
   * 201-operation history that proves nothing is unreachable past the old cap.
   */
  test('reaches older returns and comes back, without a page overlapping or hiding one', async ({ page }) => {
    test.slow();
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 500_000);
    const paymentId = await capturedPaymentOn(page, account, '60.00');
    const card = returnsCard(page);

    // The first one through the form, so this payment's history starts the way an operator would start
    // it; the rest through the API, to get past the page boundary quickly.
    await refundThroughUi(page, '1.00', 'slice 1');
    for (let i = 2; i <= 52; i++) {
      await refundThroughApi(identities.merchant(), paymentId, 100, `slice ${i}`);
    }
    expect(await sql(`SELECT count(*) FROM payment_returns WHERE payment_id = '${paymentId}'`)).toBe('52');

    await page.goto(`/dashboard/payments/${paymentId}`);

    // Page one: the server's page size, and the payment's total stated separately from it. The count
    // beside the rows is the payment's, not the page's, which is the distinction that used to be wrong.
    await expect(card.getByText(/Showing 50 of 52 returns, newest first/)).toBeVisible();
    await expect(card.getByText(/Older operations continue below/)).toBeVisible();
    const firstPage = await sequencesOnScreen(card);
    expect(firstPage).toHaveLength(50);
    expect(firstPage[0]).toBe(52);
    expect(firstPage.at(-1)).toBe(3);
    // Newest first with no gaps inside the page.
    expect(firstPage).toEqual(Array.from({ length: 50 }, (_, index) => 52 - index));
    await expect(card.getByRole('button', { name: 'Newest' })).toHaveCount(0);
    await capture(page, '50-return-history');

    // Older, by the page's own control.
    await card.getByRole('button', { name: 'Older returns' }).click();
    await expect(card.getByText(/Showing 2 of 52 returns, newest first/)).toBeVisible();
    const secondPage = await sequencesOnScreen(card);
    expect(secondPage).toEqual([2, 1]);
    // The two pages neither overlap nor leave a gap: every operation is reachable exactly once, which
    // is the whole claim a keyset-paged history makes.
    expect(secondPage.filter((sequence) => firstPage.includes(sequence))).toEqual([]);
    expect([...firstPage, ...secondPage].sort((left, right) => left - right))
      .toEqual(Array.from({ length: 52 }, (_, index) => index + 1));
    // The last page offers no older one, and does offer the way back.
    await expect(card.getByRole('button', { name: 'Older returns' })).toHaveCount(0);
    await expect(card.getByRole('button', { name: 'Newest' })).toBeVisible();

    // And back again, which is a traversal in its own right: paging is forward-only on the cursor, so
    // returning to the newest page means starting a new one rather than reversing the last request.
    await card.getByRole('button', { name: 'Newest' }).click();
    await expect(card.getByText(/Showing 50 of 52 returns, newest first/)).toBeVisible();
    expect(await sequencesOnScreen(card)).toEqual(firstPage);
    await expect(card.getByRole('button', { name: 'Newest' })).toHaveCount(0);
    await expect(card.getByRole('button', { name: 'Older returns' })).toBeVisible();

    expect(await sql(`SELECT count(*) FROM payment_returns WHERE payment_id = '${paymentId}'`)).toBe('52');
    expect(await sql(`SELECT returned_amount_minor FROM payments WHERE id = '${paymentId}'`)).toBe('5200');
  });

  test('a refund whose outcome is unknown is retried as the same command, not a new one', async ({ page }) => {
    const account = await createIsolatedAccount('demo-merchant', 'CAD', 500_000);
    const paymentId = await capturedPaymentOn(page, account, '40.00');
    const card = returnsCard(page);

    // The request reaches the server and commits; the response is dropped, so the outcome is unknowable.
    await page.route(`**/ui/payments/${paymentId}/refunds`, async (route) => {
      if (route.request().method() !== 'POST') {
        await route.continue();
        return;
      }
      await route.fetch();
      await route.abort('connectionclosed');
    });

    await card.getByLabel(/^Refund amount/).fill('15.00');
    await card.getByLabel(/^Reason/).fill('unknown outcome');
    await card.getByRole('button', { name: 'Refund', exact: true }).click();
    await page.getByRole('button', { name: 'Return the funds' }).click();

    const held = unresolvedNotice(page);
    await expect(held.getByText('Refund may or may not have been applied')).toBeVisible();
    await expect(held.getByText('15.00 CAD')).toBeVisible();
    await expect(held.getByText(/^ui-refund-[0-9a-f-]{36}$/)).toBeVisible();

    // It did commit: exactly one return exists, and a retry must not make a second.
    expect(await sql(`SELECT count(*) FROM payment_returns WHERE payment_id = '${paymentId}'`)).toBe('1');
    await capture(page, '47-unknown-refund');

    await page.unroute(`**/ui/payments/${paymentId}/refunds`);
    const [retried] = await Promise.all([
      page.waitForRequest(
        (request) => request.url().includes(`/ui/payments/${paymentId}/refunds`) && request.method() === 'POST',
      ),
      held.getByRole('button', { name: /^Retry/ }).click(),
    ]);

    // The bytes and the key are the submitted ones, so the server recognises the original command.
    expect(JSON.parse(retried.postData() ?? '{}')).toEqual({ amountMinor: 1500, reason: 'unknown outcome' });
    expect(retried.headers()['idempotency-key']).toMatch(/^ui-refund-[0-9a-f-]{36}$/);

    await expect(page.getByText('Refund recorded')).toBeVisible();
    expect(await sql(`SELECT count(*) FROM payment_returns WHERE payment_id = '${paymentId}'`)).toBe('1');
    expect(await sql(`SELECT returned_amount_minor FROM payments WHERE id = '${paymentId}'`)).toBe('1500');
    // And the screen shows authoritative state re-read after the retry, not the command's own response.
    await expect(card.getByText('25.00 CAD').first()).toBeVisible();
  });
});
