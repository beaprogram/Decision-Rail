import { expect, test } from '@playwright/test';
import {
  authorizeThroughUi,
  capture,
  createIsolatedAccount,
  identities,
  signIn,
  signInOnCurrentPage,
  signOut,
  sql,
} from './support';

/** Every payment search the dashboard makes. */
const SEARCH = '**/ui/payments?*';

/** A promise this test settles itself, so each step waits for a signal rather than for a duration. */
function deferred<T = void>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((settle) => {
    resolve = settle;
  });
  return { promise, resolve };
}

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

  /**
   * A search response for one merchant, deliberately held open across a sign-out and a sign-in, in one
   * document.
   *
   * Three things this is careful about, because the previous version of it was not:
   *
   *  - **No navigation.** `page.goto` would remount the SPA and rebuild its state from scratch, which
   *    is the one thing that makes this scenario safe by accident. Everything here happens by clicking
   *    inside the application the first merchant was already using.
   *  - **The response is held, not the request.** The route handler fetches the server's real answer
   *    for the first merchant and keeps it in hand, so the interleaving is a completed response waiting
   *    to be delivered rather than a request that never left.
   *  - **No sleeps.** Each step waits for a named signal: the response being in hand, the transition
   *    finishing, the next merchant's own search resolving.
   *
   * What it can establish is that the held response does not reach the next identity's screen. The
   * dashboard cancels the outstanding request when the identity changes, so in this path the response
   * is abandoned in the browser rather than being received and rejected; that is asserted below as
   * cancellation, which is what actually happens. The generation guard - the code that refuses a
   * response which does arrive whole after the identity moved on - cannot be reached through this path
   * and is covered directly in src/api/client.test.ts.
   */
  test('a held response for the previous identity is abandoned at the transition', async ({ page }) => {
    // The next identity needs resources of its own, so the ownership assertions below have something
    // to be true of rather than passing on an empty table.
    const theirAccount = await createIsolatedAccount('other-merchant', 'CAD', 5_000_000);
    await signIn(page, identities.otherMerchant());
    const theirs = await authorizeThroughUi(page, '41.00', { accountId: theirAccount });
    await signOut(page);

    await signIn(page, identities.merchant());
    const mine = await authorizeThroughUi(page, '23.00');

    // Rendezvous points. Nothing below waits on a duration.
    const inHand = deferred<void>();
    const release = deferred<void>();
    let heldBody = '';
    let firstSearchHeld = false;
    let deliveryOutcome: 'delivered' | 'abandoned' | null = null;

    await page.route(SEARCH, async (route) => {
      // Only the first search is held. The next merchant's own search must go straight through, or
      // this test would be waiting on itself.
      if (firstSearchHeld) {
        await route.continue();
        return;
      }
      firstSearchHeld = true;
      // The server's real response for this merchant, obtained now and kept.
      const response = await route.fetch();
      heldBody = await response.text();
      inHand.resolve();
      await release.promise;
      try {
        await route.fulfill({ response });
        deliveryOutcome = 'delivered';
      } catch {
        // The page is no longer waiting for it.
        deliveryOutcome = 'abandoned';
      }
    });

    // Chromium reports the abandonment as a failed request; captured before it can happen.
    const abandoned = page.waitForEvent('requestfailed', {
      predicate: (request) => request.url().includes('/ui/payments?'),
      timeout: 30_000,
    });

    // Inside the application: a click on the sidebar, not a navigation.
    await page.getByRole('navigation', { name: 'Dashboard sections' }).getByRole('link', { name: 'Payments' }).click();
    await expect(page.getByRole('heading', { name: 'Payments', level: 1 })).toBeVisible();
    await inHand.promise;

    // The response now exists and is this merchant's. It has not been delivered to the page.
    expect(heldBody).toContain(mine);
    expect(page.url()).toContain('/dashboard/payments');

    // The identity changes while it is outstanding, in the same document.
    await signOut(page);
    await signInOnCurrentPage(page, identities.otherMerchant());

    // Let it go. The transition has already happened.
    release.resolve();
    const failedRequest = await abandoned;
    expect(failedRequest.failure()?.errorText ?? '').toMatch(/abort/i);
    expect(deliveryOutcome).not.toBe('delivered');

    // The second merchant is on the same route the first one was reading - the URL never changed - so
    // the screen now showing is this identity's own search. Waited for by the row it must contain
    // rather than by a timer or by a response event that may already have happened.
    await expect(page.getByRole('heading', { name: 'Payments', level: 1 })).toBeVisible();
    await expect(page.locator(`tbody tr a[href$="/payments/${theirs}"]`)).toBeVisible();

    // Ownership, established from resource identity rather than from an amount another merchant could
    // legitimately also have. Every payment on screen is named by its own link.
    const rendered = await page.locator('tbody tr a[href*="/payments/"]').evaluateAll((links) =>
      links.map((link) => (link as HTMLAnchorElement).getAttribute('href')?.split('/').pop() ?? ''),
    );
    expect(rendered.length).toBeGreaterThan(0);
    expect(rendered).toContain(theirs);
    expect(rendered).not.toContain(mine);

    // And confirmed against the database rather than against the same response that drew the rows:
    // a count taken from the payload cannot testify about the payload.
    const owners = await sql(
      `SELECT DISTINCT merchant_id FROM payments WHERE id IN (${rendered.map((id) => `'${id}'`).join(',')})`,
    );
    expect(owners.split('\n').map((line) => line.trim()).filter(Boolean)).toEqual(['other-merchant']);

    // The first merchant's payment is nowhere on the screen, by identity.
    await expect(page.getByText(mine)).toHaveCount(0);
    await expect(page.locator(`a[href$="/payments/${mine}"]`)).toHaveCount(0);
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
