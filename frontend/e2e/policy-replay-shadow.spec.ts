import { expect, test } from '@playwright/test';
import { authorizeThroughUi, capture, identities, signIn, signOut } from './support';

/** Candidate registration, replay against history, and shadow evaluation alongside live decisions. */

const CANDIDATE = `candidate-e2e-${Date.now().toString(36)}`;
const STRICT_DEFINITION = JSON.stringify(
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

test.describe.configure({ mode: 'serial' });

test.describe('policy, replay and shadow', () => {
  test('an administrator registers a candidate and sees structured validation errors', async ({ page }) => {
    await signIn(page, identities.admin());
    await page.goto('/dashboard/policies');

    // The built-in policy is marked authoritative; candidates are marked as not.
    await expect(page.getByText('Authoritative').first()).toBeVisible();

    await page.getByRole('button', { name: 'Register candidate' }).click();
    await page.getByLabel('Version identifier').fill(`${CANDIDATE}-invalid`);

    // A negative predicate amount is an input error, and the server names the path that is wrong.
    await page.getByLabel('Definition').fill(
      STRICT_DEFINITION.replace('"amountMinor": 1000', '"amountMinor": -5'),
    );
    await page.getByRole('button', { name: 'Register candidate' }).last().click();
    await expect(page.getByText('The definition was rejected')).toBeVisible();
    await expect(page.getByText('$.rules[0].expression.amountMinor')).toBeVisible();
    await capture(page, '16-policy-validation-error');

    // An unsupported operator is rejected too, rather than silently ignored.
    await page.getByLabel('Definition').fill(
      STRICT_DEFINITION.replace('"AMOUNT_AT_LEAST"', '"REGEX_MATCH"'),
    );
    await page.getByRole('button', { name: 'Register candidate' }).last().click();
    await expect(page.getByText(/unsupported operator/i)).toBeVisible();

    // The valid document is accepted and identified as a candidate.
    await page.getByLabel('Version identifier').fill(CANDIDATE);
    await page.getByLabel('Definition').fill(STRICT_DEFINITION);
    await page.getByRole('button', { name: 'Register candidate' }).last().click();
    await expect(page.getByText(`Stored as ${CANDIDATE}`)).toBeVisible();
    await expect(page.getByText(/not authoritative/i).first()).toBeVisible();

    // Registering opens the new version's rules, in evaluation order with the condition spelled out.
    const rules = page.locator('section.card', { hasText: `Rules in ${CANDIDATE}` });
    await expect(rules.getByText('STRICT_AMOUNT')).toBeVisible();
    await expect(rules.getByText('amount is at least 1000 minor units')).toBeVisible();
    await expect(rules.getByText('+60')).toBeVisible();
    // No false claim that the hash was verified against the returned text.
    await expect(page.getByText('About the definition hash')).toBeVisible();
    await capture(page, '17-policy-rules');
  });

  test('a merchant replays the candidate against history and compares explanations', async ({ page }) => {
    await signIn(page, identities.merchant());
    // Something for the candidate to disagree about.
    await authorizeThroughUi(page, '30.00');

    await page.goto('/dashboard/replay');
    await page.getByLabel('Candidate policy').selectOption(CANDIDATE);
    await page.getByLabel('Maximum inputs').fill('50');
    await page.getByRole('button', { name: 'Create job' }).click();

    await expect(page.getByText('Job created')).toBeVisible();
    await page.getByRole('link', { name: 'Open the job' }).click();

    // The job finishes on its own; polling stops when it does.
    await expect(page.getByText('COMPLETED').first()).toBeVisible({ timeout: 45_000 });
    await expect(page.getByText('Pinned inputs')).toBeVisible();
    await capture(page, '18-replay-report');

    // The report states its denominator and refuses to invent accuracy figures.
    await expect(page.getByText(/completedCount/)).toBeVisible();
    await expect(page.getByText(/No labelled fraud outcomes exist/)).toBeVisible();
    await expect(page.getByText(/not a benchmark/)).toBeVisible();

    // Baseline and candidate sit side by side, with reasons on both sides.
    await expect(page.getByText('Baseline · stored risk decision').first()).toBeVisible();
    await expect(page.getByText(new RegExp(`Candidate · ${CANDIDATE}`)).first()).toBeVisible();
    await expect(page.getByText('STRICT_AMOUNT').first()).toBeVisible();

    // Filtering to divergences only keeps working.
    await page.getByLabel('Diverged only').check();
    await expect(page.getByText('diverged').first()).toBeVisible();
    await capture(page, '19-replay-comparison');
  });

  test('an administrator enables shadow evaluation and a merchant sees the divergence', async ({ page }) => {
    await signIn(page, identities.admin());
    await page.goto('/dashboard/shadow');

    await expect(page.getByText(/does not.*backfill|not backfill/i).first()).toBeVisible();
    await page.getByLabel('Candidate policy').selectOption(CANDIDATE);
    // The control reads "Enable" when shadow is off and "Switch candidate" when it is already on, and
    // a previous run may have left it on.
    await page.getByRole('button', { name: /^(Enable|Switch candidate)$/ }).click();
    await expect(page.getByText('Configuration updated')).toBeVisible();
    await expect(page.getByText('Enabled')).toBeVisible();
    await capture(page, '20-shadow-configuration');

    await signOut(page);
    await signIn(page, identities.merchant());
    // Authorized while shadow is enabled, so a comparison is produced for it.
    const paymentId = await authorizeThroughUi(page, '55.00');

    await expect(async () => {
      await page.goto(`/dashboard/payments/${paymentId}`);
      await expect(page.getByText(new RegExp(`Candidate ${CANDIDATE}.*diverged`))).toBeVisible({ timeout: 5_000 });
    }).toPass({ timeout: 60_000 });

    // The live decision is untouched: still APPROVE and still AUTHORIZED.
    await expect(page.getByText('AUTHORIZED').first()).toBeVisible();
    const decision = page.locator('section.card', { hasText: 'Stored decision' });
    await expect(decision.getByText('APPROVE')).toBeVisible();
    // And the candidate's disagreement is shown as an observation.
    await expect(page.getByText('observation only')).toBeVisible();
    await capture(page, '21-shadow-divergence');

    // Turn it off again so later runs start from a known state.
    await signOut(page);
    await signIn(page, identities.admin());
    await page.goto('/dashboard/shadow');
    await page.getByRole('button', { name: 'Disable' }).click();
    await expect(page.getByText('Configuration updated')).toBeVisible();
  });
});
