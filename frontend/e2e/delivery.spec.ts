import { expect, test } from '@playwright/test';
import {
  authorizeThroughUi,
  capture,
  identities,
  signIn,
  signOut,
  sql,
  startBroker,
  stopBroker,
} from './support';

/**
 * The administrative delivery workspace, including a real broker outage.
 *
 * The outage is produced by stopping the broker container through the local Compose stack, because the
 * application deliberately exposes no endpoint that could do it from a browser. These tests run serially
 * and restore the broker afterwards.
 */
test.describe.configure({ mode: 'serial' });

test.describe('delivery workspace', () => {
  test.afterAll(async () => {
    // Whatever happened above, the next run starts with a working broker.
    await startBroker();
  });

  test('shows liveness, readiness and asynchronous capability as three separate signals', async ({ page }) => {
    await signIn(page, identities.admin());
    await page.goto('/dashboard/delivery');

    // Each signal is its own labelled tile rather than one rolled-up status.
    for (const signal of ['Liveness', 'Readiness', 'Asynchronous delivery', 'Broker circuit']) {
      await expect(page.getByText(signal).first()).toBeVisible();
    }
    // Counts by delivery status and the stalled-stream count are both present.
    await expect(page.getByText('PUBLISHED events')).toBeVisible();
    await expect(page.getByText('Stalled payment streams')).toBeVisible();
    await capture(page, '22-delivery-healthy');
  });

  test('a failed event is inspectable and can be redriven from an explicit selection', async ({ page }) => {
    // An isolated fixture: one payment of this test's own, whose event fails to deliver.
    //
    // The event is failed while the broker is down. A published outbox row is immutable delivery
    // evidence and the database refuses to rewrite one, so the only honest way to produce a failed
    // event is to catch it before it can be published, which an outage does.
    await stopBroker();
    let paymentId: string;
    try {
      await signIn(page, identities.merchant());
      paymentId = await authorizeThroughUi(page, '61.00');
      await signOut(page);

      const updated = await sql(
        `UPDATE outbox_events SET status = 'FAILED', attempts = 8, ` +
          `last_error = 'BrokerSendException: injected for browser verification', last_attempt_at = now() ` +
          `WHERE aggregate_id = '${paymentId}' AND status <> 'PUBLISHED'`,
      );
      expect(updated).toContain('UPDATE 1');
    } finally {
      await startBroker();
    }

    await signIn(page, identities.admin());
    await page.goto('/dashboard/delivery');

    const row = page.getByRole('row', { name: new RegExp(paymentId.slice(0, 8)) });
    await expect(row).toBeVisible();
    // The stored failure detail is what makes a redrive decision possible.
    await expect(row.getByText(/injected for browser verification/)).toBeVisible();
    await capture(page, '23-failed-events');

    // Redrive is offered only for an explicit selection.
    await expect(page.getByRole('button', { name: 'Redrive selected' })).toBeDisabled();
    await row.getByRole('checkbox').check();
    await expect(page.getByRole('button', { name: 'Redrive selected' })).toBeEnabled();
    await page.getByRole('button', { name: 'Redrive selected' }).click();

    // The confirmation restates exactly what will be redriven.
    await expect(page.getByText('Redrive these events?')).toBeVisible();
    // The selected event is restated with its sequence and payment, so the scope is explicit.
    await expect(page.getByRole('dialog').getByText(/sequence \d+ · payment [0-9a-f]{8}/)).toBeVisible();
    await capture(page, '24-redrive-confirmation');
    await page.getByRole('button', { name: /^Redrive 1 event$/ }).click();

    await expect(page.getByText(/Redrive returned 1 event/)).toBeVisible();
    await expect(page.getByText(/identity and payload are unchanged/)).toBeVisible();
    await capture(page, '25-redrive-result');
  });

  test('a broker outage degrades delivery while payment reads stay available', async ({ page }) => {
    await stopBroker();
    try {
      // A merchant can still authorize and read payments with the broker down.
      await signIn(page, identities.merchant());
      const paymentId = await authorizeThroughUi(page, '77.00');
      await expect(page.getByText('AUTHORIZED').first()).toBeVisible();

      await page.goto(`/dashboard/payments?paymentId=${paymentId}`);
      await expect(page.getByText('77.00')).toBeVisible();
      await capture(page, '26-payments-during-outage');

      // Its event is committed but undelivered, and the timeline says so without inventing a time.
      await page.goto(`/dashboard/payments/${paymentId}`);
      const lifecycle = page.locator('section.card', { hasText: 'Lifecycle' });
      await expect(lifecycle.getByText('not yet published').first()).toBeVisible();
      await expect(lifecycle.getByText('no consumer has recorded it').first()).toBeVisible();

      await signOut(page);
      await signIn(page, identities.admin());

      // The administrative view reports the degradation, and explains that payments are unaffected.
      await expect(async () => {
        await page.goto('/dashboard/delivery');
        await expect(page.getByText('Asynchronous delivery is degraded')).toBeVisible({ timeout: 5_000 });
      }).toPass({ timeout: 90_000 });

      await expect(page.getByText(/Readiness/).first()).toBeVisible();
      await expect(page.getByText(/accumulating rather than being lost/)).toBeVisible();
      await capture(page, '27-delivery-degraded');
    } finally {
      await startBroker();
    }

    // Once the broker returns, delivery recovers on its own.
    await expect(async () => {
      await page.goto('/dashboard/delivery');
      await expect(page.getByText('Asynchronous delivery is degraded')).toHaveCount(0, { timeout: 5_000 });
    }).toPass({ timeout: 180_000 });
    await capture(page, '28-delivery-recovered');
  });
});
