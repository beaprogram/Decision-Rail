import { expect, test } from '@playwright/test';
import {
  capture,
  identities,
  sessionCookie,
  signIn,
  signInOnCurrentPage,
  signOut,
  statusWithSessionCookie,
} from './support';

/**
 * Authentication transitions inside one mounted application.
 *
 * Nothing here reloads the page between transitions. A reload re-runs the bootstrap and obtains a
 * fresh CSRF token as a side effect, which would make a broken transition look like a working one.
 */
test.describe('session transitions without reloading', () => {
  test('signs in, out, and in again from the same mounted page', async ({ page }) => {
    await signIn(page, identities.merchant());
    await signOut(page);

    // The second sign-in uses the form already on screen. This used to be refused, because the
    // logout deleted the CSRF cookie and nothing issued a replacement.
    await signInOnCurrentPage(page, identities.merchant());
    await expect(page.getByRole('link', { name: 'Payments' })).toBeVisible();

    // And a third round trip, to show the transition is repeatable rather than accidentally working.
    await signOut(page);
    await signInOnCurrentPage(page, identities.otherMerchant());
    await expect(page.getByText('other-merchant', { exact: true })).toBeVisible();
    await capture(page, '32-repeated-transitions');
  });

  test('signs an operations identity in and straight back out with no workspace request between', async ({ page }) => {
    // This identity has no workspace, so nothing incidental fetches a token after login. Any
    // transition that depended on an unrelated request to work will fail here.
    await signIn(page, identities.operations());
    await expect(page.getByText('This identity has no dashboard workspace')).toBeVisible();

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    // Logout must be confirmed, not merely displayed.
    await expect(page.getByText(/could not be confirmed/i)).toHaveCount(0);

    // The session really is gone: reloading must not restore it.
    await page.reload();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await expect(page.getByText('operations', { exact: true })).toHaveCount(0);
    await capture(page, '33-operations-signed-out');
  });

  test('a confirmed sign-out leaves a session cookie that no longer authenticates', async ({ page }) => {
    await signIn(page, identities.merchant());
    const before = await sessionCookie(page);
    expect(before).toBeTruthy();

    await signOut(page);

    // Replayed from outside the browser, so this is the destroyed session being refused rather than
    // the page simply no longer holding a cookie.
    expect(await statusWithSessionCookie(before, '/ui/payments?limit=1')).toBe(401);
  });

  test('a mutation still needs a valid CSRF token after a transition', async ({ page }) => {
    await signIn(page, identities.merchant());
    await signOut(page);
    await signInOnCurrentPage(page, identities.merchant());

    // Repairing the token flow must not have loosened the protection it exists for.
    const forged = await page.evaluate(async () => {
      const response = await fetch('/ui/payments/authorizations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'e2e-transition-0001' },
        credentials: 'same-origin',
        body: JSON.stringify({
          accountId: '11111111-1111-1111-1111-111111111111',
          amountMinor: 100,
          currency: 'CAD',
          country: 'CA',
        }),
      });
      return { status: response.status, body: await response.text() };
    });
    expect(forged.status).toBe(403);
    expect(forged.body).toContain('CSRF_TOKEN_INVALID');
  });

  test('hides the protected workspace as soon as sign-out begins, before the server answers', async ({ page }) => {
    await signIn(page, identities.merchant());
    await page.goto('/dashboard/payments');
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible();
    expect(await rows.count()).toBeGreaterThan(0);

    // Held before delivery. While these assertions run the server has not seen the request, so the
    // session is definitely still alive and anything still on screen is genuinely exposed.
    let release!: () => void;
    const releaseHold = new Promise<void>((resolve) => {
      release = resolve;
    });
    let delivered = false;
    await page.route('**/ui/session', async (route) => {
      if (route.request().method() !== 'DELETE') {
        await route.continue();
        return;
      }
      await releaseHold;
      delivered = true;
      await route.continue();
    });

    await page.getByRole('button', { name: 'Sign out' }).click();

    // The workspace goes at once. Advancing the identity generation and dropping the cache is not
    // enough on its own: the screens stay mounted and re-render, so the rows were still there.
    // The dedicated screen, not the old "Signing out…" label on a button inside a workspace that is
    // still mounted behind it.
    await expect(page.getByRole('heading', { name: 'Signing out' })).toBeVisible();
    await expect(page.getByText('Your workspace has been cleared')).toBeVisible();
    await expect(rows).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Payments' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Sign out' })).toHaveCount(0);
    await expect(page.getByText('demo-merchant')).toHaveCount(0);
    // None of that claims the session is closed, because it is not: the request is still in this
    // test's hands and the sign-in form has not been offered yet.
    await expect(page.getByRole('button', { name: 'Sign in' })).toHaveCount(0);
    await expect(page.getByText(/could not be confirmed/i)).toHaveCount(0);
    expect(delivered).toBe(false);
    await capture(page, '35-signing-out');

    // Releasing it completes the sign-out normally.
    release();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await expect(page.getByText(/could not be confirmed/i)).toHaveCount(0);
    expect(delivered).toBe(true);
    await page.unroute('**/ui/session');
    // That the destroyed session's cookie no longer authenticates is already proven above, so it is
    // not repeated here.
  });

  test('a logout the server never received is reported as unconfirmed, not as signed out', async ({ page }) => {
    await signIn(page, identities.merchant());
    await page.goto('/dashboard/payments');
    await expect(page.locator('tbody tr').first()).toBeVisible();

    // The request never reaches the server, so the session is definitely still alive.
    await page.route('**/ui/session', async (route) => {
      if (route.request().method() === 'DELETE') await route.abort('failed');
      else await route.continue();
    });

    await page.getByRole('button', { name: 'Sign out' }).click();

    // Protected data goes immediately, because it is on screen and the screen must be cleared.
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await expect(page.locator('tbody tr')).toHaveCount(0);
    // But the UI does not claim a sign-out that never happened.
    await expect(page.getByText('Your sign-out could not be confirmed')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Retry sign-out' })).toBeVisible();
    await capture(page, '34-unconfirmed-signout');

    // Connectivity returns and the retry closes the session properly.
    await page.unroute('**/ui/session');
    const before = await sessionCookie(page);
    await page.getByRole('button', { name: 'Retry sign-out' }).click();
    await expect(page.getByText('Your sign-out could not be confirmed')).toHaveCount(0);

    // The cookie that was live throughout now authenticates nothing, checked from outside the browser.
    expect(await statusWithSessionCookie(before, '/ui/payments?limit=1')).toBe(401);

    // And signing in again from the same page still works.
    await signInOnCurrentPage(page, identities.merchant());
    await expect(page.getByRole('link', { name: 'Payments' })).toBeVisible();
  });

  test('a logout that committed but lost its response reconciles to signed out', async ({ page }) => {
    await signIn(page, identities.merchant());

    // The request reaches the server and succeeds; only the reply is lost. The session really is gone,
    // so reconciling with the server must resolve this rather than leaving it unconfirmed forever.
    await page.route('**/ui/session', async (route) => {
      if (route.request().method() !== 'DELETE') {
        await route.continue();
        return;
      }
      await route.fetch();
      await route.abort('connectionclosed');
    });

    await page.getByRole('button', { name: 'Sign out' }).click();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await page.unroute('**/ui/session');

    // The server says nobody is signed in, so the sign-out is treated as done.
    await expect(page.getByText('Your sign-out could not be confirmed')).toHaveCount(0);
    await signInOnCurrentPage(page, identities.merchant());
    await expect(page.getByRole('link', { name: 'Payments' })).toBeVisible();
  });

  test('an ordinary sign-out answers 204 with no body and is accepted', async ({ page }) => {
    await signIn(page, identities.merchant());

    const [response] = await Promise.all([
      page.waitForResponse((candidate) =>
        candidate.url().includes('/ui/session') && candidate.request().method() === 'DELETE'),
      page.getByRole('button', { name: 'Sign out' }).click(),
    ]);

    expect(response.status()).toBe(204);
    // A 204 carries no body at all, which the client must accept rather than demanding JSON.
    expect(response.headers()['content-length']).toBeUndefined();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
    await expect(page.getByText(/could not be confirmed/i)).toHaveCount(0);
  });
});
