import { expect, test } from '@playwright/test';
import { capture, identities, signIn, signOut } from './support';

/** Sign-in, sign-out, expiry, role boundaries, and forged-request rejection. */
test.describe('browser authentication', () => {
  test('shows the sign-in screen and refuses a wrong password', async ({ page }) => {
    await page.goto('/dashboard/');
    await expect(page.getByRole('heading', { name: 'Sign in to DecisionRail' })).toBeVisible();
    await capture(page, '01-sign-in');

    await page.getByLabel('Username').fill('demo-merchant');
    await page.getByLabel('Password').fill('definitely-not-the-password');
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page.getByText('Sign in failed')).toBeVisible();
    // The message must not hint at which half was wrong.
    await expect(page.getByText(/username or password is not correct/i)).toBeVisible();
    // Nothing protected is on screen.
    await expect(page.getByRole('link', { name: 'Payments' })).toHaveCount(0);
  });

  test('signs a merchant in, shows only merchant navigation, and signs out', async ({ page }) => {
    await signIn(page, identities.merchant());

    await expect(page.getByRole('link', { name: 'Payments' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Accounts' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Policy replay' })).toBeVisible();
    // Administration is not offered to a merchant.
    await expect(page.getByRole('link', { name: 'Event delivery' })).toHaveCount(0);

    await signOut(page);
    // After signing out, a protected route must not render tenant data.
    await page.goto('/dashboard/payments');
    await expect(page.getByRole('heading', { name: 'Sign in to DecisionRail' })).toBeVisible();
    await expect(page.getByRole('table')).toHaveCount(0);
  });

  test('an administrator gets the delivery workspace and no payment workspace', async ({ page }) => {
    await signIn(page, identities.admin());

    await expect(page.getByRole('link', { name: 'Event delivery' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Policy versions' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Payments' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Accounts' })).toHaveCount(0);

    // Typing a merchant route gets a refusal rather than data: hidden navigation is not the control.
    await page.goto('/dashboard/payments');
    await expect(page.getByText(/not permitted for your role|Payment search failed/i).first()).toBeVisible();
    await expect(page.getByRole('table')).toHaveCount(0);
    await capture(page, '02-admin-refused-merchant-route');
  });

  test('the operations identity is told it has no workspace rather than being given one', async ({ page }) => {
    await signIn(page, identities.operations());

    await expect(page.getByText('This identity has no dashboard workspace')).toBeVisible();
    await expect(page.getByRole('link', { name: 'Payments' })).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Event delivery' })).toHaveCount(0);
    await capture(page, '03-operations-metrics-only');
  });

  test('an expired session clears the screen and asks for sign-in again', async ({ page }) => {
    await signIn(page, identities.merchant());
    await page.goto('/dashboard/payments');
    await expect(page.getByRole('heading', { name: 'Payments', level: 1 })).toBeVisible();

    // Destroying the session cookie is what expiry looks like to the browser.
    await page.context().clearCookies({ name: 'JSESSIONID' });
    // Change a filter so this is a genuinely new query rather than one the cache can answer.
    await page.getByLabel('Payment ID').fill('11111111-2222-3333-4444-555555555555');
    await page.getByRole('button', { name: 'Apply' }).click();

    await expect(page.getByText('Your session ended')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Sign in to DecisionRail' })).toBeVisible();
    // No tenant rows survive the transition.
    await expect(page.getByRole('table')).toHaveCount(0);
    await capture(page, '04-session-expired');
  });

  test('a state-changing request without a valid CSRF token is refused', async ({ page }) => {
    await signIn(page, identities.merchant());

    // Authenticated by cookie but with no token: the shape of a cross-site forgery.
    const forged = await page.evaluate(async () => {
      const response = await fetch('/ui/payments/authorizations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'e2e-csrf-000001' },
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

    // A wrong token is refused too, not only a missing one.
    const wrongToken = await page.evaluate(async () => {
      const response = await fetch('/ui/payments/authorizations', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'e2e-csrf-000002',
          'X-XSRF-TOKEN': 'not-the-real-token',
        },
        credentials: 'same-origin',
        body: JSON.stringify({ accountId: '11111111-1111-1111-1111-111111111111', amountMinor: 100, currency: 'CAD', country: 'CA' }),
      });
      return response.status;
    });
    expect(wrongToken).toBe(403);
  });

  test('the session cookie cannot authenticate the scripted API', async ({ page }) => {
    await signIn(page, identities.merchant());

    const viaSession = await page.evaluate(async () => {
      const response = await fetch('/v1/activity', { credentials: 'same-origin' });
      return response.status;
    });
    // A browser session is not an alternative way into the stateless API.
    expect(viaSession).toBe(401);
  });

  test('no credential is written to browser storage', async ({ page }) => {
    const who = identities.merchant();
    await signIn(page, who);

    const stored = await page.evaluate(() => ({
      local: JSON.stringify(Object.entries({ ...localStorage })),
      session: JSON.stringify(Object.entries({ ...sessionStorage })),
    }));

    expect(stored.local).not.toContain(who.password);
    expect(stored.session).not.toContain(who.password);
    // Nothing at all is kept, so there is nothing to leak.
    expect(stored.local).toBe('[]');
    expect(stored.session).toBe('[]');
    // The session cookie is not readable by script.
    const cookiesVisibleToScript = await page.evaluate(() => document.cookie);
    expect(cookiesVisibleToScript).not.toContain('JSESSIONID');
  });
});
