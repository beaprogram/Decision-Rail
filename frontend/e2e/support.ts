import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { expect, type Page } from '@playwright/test';

const run = promisify(execFile);

/**
 * Shared helpers for browser verification.
 *
 * Credentials are read from the environment and never written into a URL, a log line, or a screenshot.
 * Where a test needs infrastructure to misbehave it drives the local Compose stack directly, because
 * the application deliberately exposes no endpoint that could do it.
 */

export interface Credentials {
  username: string;
  password: string;
}

function credential(variable: string, username: string): Credentials {
  const password = process.env[variable];
  if (!password) {
    throw new Error(
      `${variable} is not set. Load the generated .env before running browser tests; these tests use the real identities.`,
    );
  }
  return { username, password };
}

export const identities = {
  merchant: () => credential('MERCHANT_DEMO_PASSWORD', 'demo-merchant'),
  otherMerchant: () => credential('MERCHANT_OTHER_PASSWORD', 'other-merchant'),
  admin: () => credential('ADMIN_PASSWORD', 'admin'),
  operations: () => credential('OPERATIONS_PASSWORD', 'operations'),
};

export const COMPOSE_FILE = process.env.COMPOSE_FILE_PATH ?? '../compose.yaml';
const BROKER_SERVICE = process.env.BROKER_SERVICE ?? 'broker';
const DATABASE_SERVICE = process.env.DATABASE_SERVICE ?? 'database';
const DATABASE_NAME = process.env.DATABASE_NAME ?? 'decisionrail';
const DATABASE_USER = process.env.JDBC_USERNAME ?? 'decisionrail';

async function compose(args: string[], timeoutMs = 120_000): Promise<string> {
  try {
    const { stdout } = await run('docker', ['compose', '--file', COMPOSE_FILE, ...args], {
      timeout: timeoutMs,
      env: process.env,
    });
    return stdout;
  } catch (cause) {
    // execFile's default message hides the command's own output, which is where the reason is.
    const detail = cause as { stderr?: string; stdout?: string; message?: string; code?: unknown; signal?: unknown };
    throw new Error(
      `docker compose ${args.join(' ')} failed ` +
        `(code=${String(detail.code)}, signal=${String(detail.signal)}).\n` +
        `stderr: ${detail.stderr ?? ''}\nstdout: ${detail.stdout ?? ''}\n${detail.message ?? ''}`,
      { cause },
    );
  }
}

/** Runs one statement against the development database. Local test tooling, not an application route. */
export async function sql(statement: string): Promise<string> {
  const output = await compose([
    'exec', '-T', DATABASE_SERVICE,
    'psql', '--username', DATABASE_USER, '--dbname', DATABASE_NAME, '--tuples-only', '--no-align',
    '--command', statement,
  ]);
  return output.trim();
}

export async function stopBroker(): Promise<void> {
  await compose(['stop', BROKER_SERVICE]);
}

export async function startBroker(): Promise<void> {
  await compose(['start', BROKER_SERVICE]);
  // Wait for the broker to accept connections again before asserting recovery.
  const deadline = Date.now() + 120_000;
  for (;;) {
    try {
      await compose([
        'exec', '-T', BROKER_SERVICE,
        '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', `${BROKER_SERVICE}:9092`, '--list',
      ], 30_000);
      return;
    } catch (cause) {
      if (Date.now() > deadline) throw cause;
      await new Promise((resolve) => setTimeout(resolve, 2_000));
    }
  }
}

/** Signs in through the real form and waits for the dashboard to be usable. */
export async function signIn(page: Page, who: Credentials): Promise<void> {
  await page.goto('/dashboard/');
  await page.getByLabel('Username').fill(who.username);
  await page.getByLabel('Password').fill(who.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText(who.username, { exact: true })).toBeVisible();
}

export async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
}

/**
 * Authorizes a payment through the dashboard and returns its identifier.
 *
 * Goes through the real form, including the confirmation step, so the flow under test is the one an
 * operator would actually use.
 */
export async function authorizeThroughUi(
  page: Page,
  amount: string,
  options: { country?: string; accountIndex?: number } = {},
): Promise<string> {
  await page.goto('/dashboard/payments/new');
  const accountSelect = page.getByLabel('Account');
  await expect(accountSelect.locator('option').nth(1)).toBeAttached();
  await accountSelect.selectOption({ index: (options.accountIndex ?? 0) + 1 });
  await page.getByLabel(/^Amount/).fill(amount);
  if (options.country) await page.getByLabel('Country').fill(options.country);
  await page.getByRole('button', { name: 'Review and authorize' }).click();
  await page.getByRole('button', { name: 'Authorize', exact: true }).click();

  const recorded = page.getByRole('heading', { name: 'Authorization recorded' });
  await expect(recorded).toBeVisible();
  await page.getByRole('button', { name: 'Open payment' }).click();
  await expect(page).toHaveURL(/\/dashboard\/payments\/[0-9a-f-]{36}$/);
  const url = page.url();
  return url.slice(url.lastIndexOf('/') + 1);
}

/** Saves a screenshot for the delivery report. Sanitised: no credential is ever on screen. */
export async function capture(page: Page, name: string): Promise<void> {
  const directory = process.env.SCREENSHOT_DIR;
  if (!directory) return;
  await page.screenshot({ path: `${directory}/${name}.png`, fullPage: true });
}
