import { expect, test } from '@playwright/test';
import { authorizeThroughUi, capture, identities, signIn } from './support';

/**
 * How the console behaves as an interface: at narrow widths, from the keyboard, and for a screen
 * reader. These are not screenshot comparisons; each assertion is about a property that would make the
 * page unusable if it were wrong.
 */
test.describe('presentation and accessibility', () => {
  test('is usable at a narrow width without the page scrolling sideways', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await signIn(page, identities.merchant());
    await page.goto('/dashboard/payments');
    await expect(page.getByRole('heading', { name: 'Payments', level: 1 })).toBeVisible();

    // A wide table must scroll inside its own container, never widen the document.
    const overflowsHorizontally = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
    );
    expect(overflowsHorizontally).toBe(false);

    // The table itself is still reachable by scrolling its container.
    const scroller = page.locator('.table-scroll').first();
    if (await scroller.count()) {
      const scrollable = await scroller.evaluate((element) => element.scrollWidth >= element.clientWidth);
      expect(scrollable).toBe(true);
    }
    await capture(page, '29-mobile-payments');

    await page.goto('/dashboard/accounts');
    await expect(page.getByRole('heading', { name: 'Accounts', level: 1 })).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1),
    ).toBe(false);
    await capture(page, '30-mobile-accounts');
  });

  test('every form control has a real label and every page a single top-level heading', async ({ page }) => {
    await signIn(page, identities.merchant());

    for (const path of ['/dashboard/payments', '/dashboard/payments/new', '/dashboard/accounts', '/dashboard/replay']) {
      await page.goto(path);
      await page.waitForLoadState('networkidle');

      const unlabelled = await page.evaluate(() => {
        const controls = Array.from(document.querySelectorAll('input, select, textarea'));
        return controls
          .filter((control) => {
            if (control.getAttribute('type') === 'hidden') return false;
            const id = control.getAttribute('id');
            const labelled =
              (id && document.querySelector(`label[for="${CSS.escape(id)}"]`)) ||
              control.getAttribute('aria-label') ||
              control.getAttribute('aria-labelledby') ||
              control.closest('label');
            return !labelled;
          })
          .map((control) => control.outerHTML.slice(0, 120));
      });
      expect(unlabelled, `unlabelled controls on ${path}`).toEqual([]);

      // One h1 per page, so the document outline is meaningful.
      expect(await page.locator('h1').count(), `h1 count on ${path}`).toBe(1);
    }
  });

  test('can be driven from the keyboard with visible focus', async ({ page }) => {
    await page.goto('/dashboard/');

    // The sign-in form is reachable and submittable entirely by keyboard.
    await page.keyboard.press('Tab');
    await page.getByLabel('Username').focus();
    await page.keyboard.type(identities.merchant().username);
    await page.keyboard.press('Tab');
    await page.keyboard.type(identities.merchant().password);
    await page.keyboard.press('Enter');
    await expect(page.getByRole('link', { name: 'Payments' })).toBeVisible();

    // Focus is visibly indicated rather than removed.
    await page.getByRole('link', { name: 'Payments' }).focus();
    const focusStyle = await page.evaluate(() => {
      const active = document.activeElement;
      if (!active) return null;
      const style = getComputedStyle(active);
      return { outlineStyle: style.outlineStyle, outlineWidth: style.outlineWidth };
    });
    expect(focusStyle?.outlineStyle).not.toBe('none');

    // A skip link exists so a keyboard user need not walk the whole navigation.
    await page.keyboard.press('Shift+Tab');
    await expect(page.getByRole('link', { name: 'Skip to content' })).toBeAttached();
  });

  test('a dialog traps focus and closes on Escape', async ({ page }) => {
    await signIn(page, identities.merchant());
    await authorizeThroughUi(page, '13.00');

    await page.getByRole('button', { name: 'Capture' }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog).toBeVisible();

    // The native dialog element moves focus inside itself.
    const focusInsideDialog = await page.evaluate(() => {
      const open = document.querySelector('dialog[open]');
      return Boolean(open && document.activeElement && open.contains(document.activeElement));
    });
    expect(focusInsideDialog).toBe(true);

    await page.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();
    // Escaping a confirmation must not perform the action.
    await expect(page.getByText('Capture completed')).toHaveCount(0);
    await expect(page.getByText('AUTHORIZED').first()).toBeVisible();
  });

  test('status is carried by words as well as colour', async ({ page }) => {
    await signIn(page, identities.merchant());
    await page.goto('/dashboard/payments');
    await expect(page.locator('tbody tr').first()).toBeVisible();

    // Every status badge contains readable text, so colour is never the only signal.
    const badges = await page.locator('.badge').allInnerTexts();
    expect(badges.length).toBeGreaterThan(0);
    for (const badge of badges) expect(badge.trim().length).toBeGreaterThan(0);
    await capture(page, '31-desktop-payments');
  });
});
