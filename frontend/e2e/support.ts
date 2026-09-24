import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { expect, request, type Page } from '@playwright/test';

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

/**
 * Creates an account of this test's own, so a test that moves money never touches the balances the
 * seeded demo accounts carry for the walkthrough.
 *
 * @returns the account id, which appears in the dashboard's account picker
 */
export async function createIsolatedAccount(
  merchantId: string,
  currency: 'CAD' | 'USD',
  balanceMinor: number,
): Promise<string> {
  const id = crypto.randomUUID();
  await sql(
    `INSERT INTO accounts (id, merchant_id, currency, opening_balance_minor, balance_minor) ` +
      `VALUES ('${id}', '${merchantId}', '${currency}', ${balanceMinor}, ${balanceMinor})`,
  );
  await assertTheApplicationSeesIt(id, merchantId);
  return id;
}

/**
 * Proves the row this suite just wrote is a row the application under test can read.
 *
 * `sql` reaches a database through Compose, and the browser reaches an application through
 * `DASHBOARD_BASE_URL`. Nothing forces those two to be the same deployment: point the browser at a
 * second stack and leave `COMPOSE_PROJECT_NAME` alone and every insert lands in the first one, which
 * then surfaces much later as an account missing from a picker, one fifteen-second timeout at a time -
 * and writes this suite's rows into whichever stack Compose did resolve to. Asking the application
 * once, here, turns that into a sentence naming the two variables.
 */
async function assertTheApplicationSeesIt(accountId: string, merchantId: string): Promise<void> {
  const who = merchantId === 'other-merchant' ? identities.otherMerchant() : identities.merchant();
  const api = await request.newContext({
    baseURL: process.env.DASHBOARD_BASE_URL ?? 'http://localhost:8080',
    httpCredentials: { username: who.username, password: who.password },
  });
  try {
    const response = await api.get(`/v1/accounts/${accountId}`);
    if (response.status() === 404) {
      throw new Error(
        `The application under test cannot see account ${accountId}, which this suite just inserted ` +
          `through ${COMPOSE_FILE}. The SQL helper and the application are looking at different ` +
          `deployments: set COMPOSE_PROJECT_NAME (and COMPOSE_FILE_PATH, DATABASE_SERVICE) to the ` +
          `stack that DASHBOARD_BASE_URL points at, or the inserted rows land somewhere else.`,
      );
    }
    expect(response.status(), 'reading back the inserted account').toBe(200);
  } finally {
    await api.dispose();
  }
}

/** How many payments exist against one account. Used to prove a retry produced no second effect. */
export async function paymentCountForAccount(accountId: string): Promise<number> {
  return Number(await sql(`SELECT count(*) FROM payments WHERE account_id = '${accountId}'`));
}

/** How many replay jobs exist for one candidate policy. */
export async function replayJobCountForCandidate(candidateVersion: string): Promise<number> {
  return Number(await sql(`SELECT count(*) FROM replay_jobs WHERE candidate_version = '${candidateVersion}'`));
}

/** Signs in through the real form, loading the dashboard first. */
export async function signIn(page: Page, who: Credentials): Promise<void> {
  await page.goto('/dashboard/');
  await signInOnCurrentPage(page, who);
}

/**
 * Signs in using the form already on screen, without navigating.
 *
 * Navigating would remount the whole application and re-run its bootstrap, which is exactly the step
 * that used to hide a broken transition: a reload obtains a fresh CSRF token as a side effect, so a
 * sign-in that only works after a reload looks like a sign-in that works.
 */
export async function signInOnCurrentPage(page: Page, who: Credentials): Promise<void> {
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  await page.getByLabel('Username').fill(who.username);
  await page.getByLabel('Password').fill(who.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByText(who.username, { exact: true })).toBeVisible();
}

export async function signOut(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
}

/** The current session cookie value, so a test can prove an identity change replaced it. */
export async function sessionCookie(page: Page): Promise<string | undefined> {
  const cookies = await page.context().cookies();
  return cookies.find((cookie) => cookie.name === 'JSESSIONID')?.value;
}

/**
 * Replays one session cookie from outside the browser's own cookie jar.
 *
 * A page's own `fetch` cannot do this. `Cookie` is a forbidden header name, so the browser drops it
 * silently and the request goes out with whatever the page already has — which after a sign-out is
 * nothing. Such a check returns 401 whether or not the server destroyed the session, so it proves
 * nothing. This issues the request outside the browser, where the header is honoured.
 */
export async function statusWithSessionCookie(cookie: string | undefined, path: string): Promise<number> {
  if (!cookie) throw new Error('No session cookie was captured, so there is nothing to replay.');
  const context = await request.newContext({
    baseURL: process.env.DASHBOARD_BASE_URL ?? 'http://localhost:8080',
    extraHTTPHeaders: { Cookie: `JSESSIONID=${cookie}` },
  });
  try {
    return (await context.get(path)).status();
  } finally {
    await context.dispose();
  }
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
  options: { country?: string; accountId?: string } = {},
): Promise<string> {
  // Its own funded account unless the caller names one.
  //
  // Selecting by position, as this did, meant depending on the account list's ordering and on how many
  // accounts the rest of the suite had already created. The list is bounded and unpaged, so on a
  // database with more accounts than that bound something is always missing from it: ordered oldest
  // first a new account is invisible, ordered newest first the seeded one is. An account this call
  // just created is the one case that is visible either way, and it makes each authorization
  // independent of every other test's leftovers, which is what the rest of this suite already does.
  const account = options.accountId ?? (await createIsolatedAccount('demo-merchant', 'CAD', 5_000_000));
  await page.goto('/dashboard/payments/new');
  const accountSelect = page.getByLabel('Account');
  await expect(accountSelect.locator('option').nth(1)).toBeAttached();
  await accountSelect.selectOption(account);
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

/**
 * Records a refund through the public API, as the given merchant.
 *
 * Used only to build a fixture large enough to have more than one page of history. Driving fifty-odd
 * refunds through the form would take minutes and would prove nothing the form's own tests do not
 * already prove; what the browser test needs is a payment whose history genuinely spans pages, and
 * these are real returns made by the real service, not rows inserted behind it.
 */
export async function refundThroughApi(
  who: Credentials,
  paymentId: string,
  amountMinor: number,
  reason: string,
): Promise<void> {
  const context = await request.newContext({
    baseURL: process.env.DASHBOARD_BASE_URL ?? 'http://localhost:8080',
    httpCredentials: { username: who.username, password: who.password },
  });
  try {
    const response = await context.post(`/v1/payments/${paymentId}/refunds`, {
      headers: { 'Idempotency-Key': `e2e-refund-${crypto.randomUUID()}` },
      data: { amountMinor, reason },
    });
    if (response.status() !== 201) {
      throw new Error(`Refund of ${amountMinor} was refused with ${response.status()}: ${await response.text()}`);
    }
  } finally {
    await context.dispose();
  }
}

/** Saves a screenshot for the delivery report. Sanitised: no credential is ever on screen. */
export async function capture(page: Page, name: string): Promise<void> {
  const directory = process.env.SCREENSHOT_DIR;
  if (!directory) return;
  await page.screenshot({ path: `${directory}/${name}.png`, fullPage: true });
}
