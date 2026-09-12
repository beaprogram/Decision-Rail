import { expect, test } from '@playwright/test';
import { authorizeThroughUi, capture, identities, signIn, signOut } from './support';

/**
 * Tenant isolation, including the moment an identity changes.
 *
 * The dangerous case is not a merchant reading another merchant's row in a list. It is a response for
 * the previous identity arriving after someone else has signed in, and landing on their screen.
 */
test.describe('merchant isolation', () => {
  test('one merchant cannot see another merchant a payment by any route', async ({ page }) => {
    await signIn(page, identities.merchant());
    const mine = await authorizeThroughUi(page, '17.00');
    await signOut(page);

    await signIn(page, identities.otherMerchant());
    // Searching for it explicitly returns nothing.
    await page.goto(`/dashboard/payments?paymentId=${mine}`);
    await expect(page.getByText('No payments match these filters')).toBeVisible();

    // Opening it directly is reported as absent rather than forbidden, so ids cannot be probed.
    await page.goto(`/dashboard/payments/${mine}`);
    await expect(page.getByText('No such payment')).toBeVisible();
    await expect(page.getByText('17.00')).toHaveCount(0);
    await capture(page, '14-cross-tenant-refused');
  });

  test('a slow response for the previous identity never lands on the next one', async ({ page }) => {
    await signIn(page, identities.merchant());
    const mine = await authorizeThroughUi(page, '23.00');

    // Hold the first merchant's search response open. This is the only interception in the suite:
    // the delay has to be produced deliberately, and the response itself is the server's real one.
    let release: (() => void) | null = null;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route('**/ui/payments?*', async (route) => {
      await held;
      await route.continue();
    });

    await page.goto('/dashboard/payments');
    // Sign out while that request is still outstanding.
    await page.unroute('**/ui/payments?*');
    await signOut(page);
    release?.();

    // Sign in as the other merchant and let the held response arrive.
    await signIn(page, identities.otherMerchant());
    await page.goto('/dashboard/payments');
    await page.waitForTimeout(1_500);

    // The first merchant's payment must not be on this screen.
    await expect(page.getByText(mine)).toHaveCount(0);
    await expect(page.getByText('23.00')).toHaveCount(0);
    // And the identity shown is the one actually signed in.
    await expect(page.getByText('other-merchant', { exact: true })).toBeVisible();
    await capture(page, '15-identity-switch');
  });

  test('signing out clears cached tenant rows immediately', async ({ page }) => {
    await signIn(page, identities.merchant());
    await page.goto('/dashboard/payments');
    await expect(page.getByRole('heading', { name: 'Payments', level: 1 })).toBeVisible();
    // Wait for the search to actually resolve before counting; an empty table mid-load proves nothing.
    await expect(page.locator('tbody tr').first()).toBeVisible();
    expect(await page.locator('tbody tr').count()).toBeGreaterThan(0);

    await signOut(page);
    await signIn(page, identities.otherMerchant());
    await page.goto('/dashboard/payments');

    // Whatever this merchant has, none of it may be the previous merchant's cached page.
    const accounts = page.getByLabel('Account');
    await expect(accounts).toBeVisible();
    await expect(page.getByText('demo-merchant')).toHaveCount(0);
  });
});
