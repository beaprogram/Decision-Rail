import { defineConfig, devices } from '@playwright/test';

/**
 * Browser verification against a running application with real PostgreSQL and Kafka.
 *
 * These are integration tests, not unit tests with a fake network. Responses are only intercepted in
 * the few places where a delay or an outage has to be produced deliberately, and even then the
 * interception is stated in the test rather than standing in for the backend.
 */
const baseURL = process.env.DASHBOARD_BASE_URL ?? 'http://localhost:8080';

export default defineConfig({
  testDir: './e2e',
  // Serial by default: the suite shares one application and one database, and several tests assert on
  // state they created. Parallel workers would make those assertions depend on each other.
  workers: 1,
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  // No retries anywhere. A browser test that only passes on a second attempt is reporting a real
  // defect in the application or in itself, and a retry would hide exactly the first-attempt
  // failures this suite exists to catch. CI matches local so a green CI run means green locally.
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  outputDir: 'test-results',
  use: {
    baseURL,
    // Traces and screenshots can contain session cookies and tenant data, so they are kept only for
    // a failure and never committed.
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
