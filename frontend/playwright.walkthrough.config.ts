import { defineConfig, devices } from '@playwright/test';

/**
 * The recorded walkthrough, not a test suite.
 *
 * Runs the guided demo path against a public-mode instance and records it, slowed down enough to
 * follow. It is separate from playwright.config.ts on purpose: the regular suite asserts and runs
 * fast against the ordinary configuration; this drives the real application the way a visitor
 * would and keeps the video. It is never part of `npx playwright test`.
 */
const baseURL = process.env.WALKTHROUGH_BASE_URL ?? 'https://localhost:8443';

export default defineConfig({
  testDir: './walkthrough',
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 240_000,
  expect: { timeout: 20_000 },
  reporter: [['list']],
  outputDir: 'walkthrough-output',
  use: {
    baseURL,
    // The rehearsal serves a locally issued certificate; the public deployment a public one.
    ignoreHTTPSErrors: true,
    video: { mode: 'on', size: { width: 1440, height: 900 } },
    viewport: { width: 1440, height: 900 },
    launchOptions: { slowMo: 350 },
    trace: 'off',
    screenshot: 'off',
    actionTimeout: 20_000,
    navigationTimeout: 30_000,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } }],
});
