import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for SMM Panel e2e tests.
 *
 * Default base URL is the Vite dev server at http://localhost:5173. CI overrides via
 * `E2E_BASE_URL`. Tests assume a running backend at the URL exposed via the Vite proxy
 * (configured in vite.config.ts) so calls to /api/* hit the real backend on localhost:8080.
 *
 * Run modes:
 *   npm run e2e                 # headless against http://localhost:5173
 *   E2E_BASE_URL=… npm run e2e  # against any other host
 */
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:5173';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: process.env.CI ? [['github'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  expect: { timeout: 5_000 },

  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // Cloudflare in front of prod sometimes adds a brief gate page; relax navigation timeout.
    navigationTimeout: 15_000,
  },

  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['iPhone 13'] } },
  ],

  // The dev server is heavy (Vite + Tailwind + JIT) — only auto-start it for local runs.
  webServer: process.env.CI || process.env.E2E_NO_WEBSERVER
    ? undefined
    : {
        command: 'npm run dev',
        url: BASE_URL,
        reuseExistingServer: true,
        timeout: 60_000,
      },
});
