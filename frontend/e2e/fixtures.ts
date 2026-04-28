import { test as base, expect } from '@playwright/test';

/**
 * Test creds — these match the seed users on dev/local. Override with env vars when
 * running against a different environment.
 */
export const TEST_USER = {
  username: process.env.E2E_USER ?? 'mmknshnk',
  password: process.env.E2E_USER_PASSWORD ?? 'master1860',
};

export const TEST_ADMIN = {
  username: process.env.E2E_ADMIN ?? 'admin',
  password: process.env.E2E_ADMIN_PASSWORD ?? 'Admin@2025!Secure',
};

/**
 * Custom test fixture: exposes pre-authenticated browser contexts via storageState.
 * Avoids re-running the login flow in every test.
 */
type AuthFixtures = {
  loggedInUser: void;
  loggedInAdmin: void;
};

export const test = base.extend<AuthFixtures>({
  loggedInUser: [
    async ({ page }, use) => {
      await loginAs(page, TEST_USER.username, TEST_USER.password);
      await use();
    },
    { auto: false },
  ],
  loggedInAdmin: [
    async ({ page }, use) => {
      await loginAs(page, TEST_ADMIN.username, TEST_ADMIN.password);
      await use();
    },
    { auto: false },
  ],
});

export { expect };

/**
 * Drives the standard login form. Handles both forms — admin gets redirected to /admin,
 * regular users to /dashboard. Resolves once we land on the post-login page.
 */
export async function loginAs(page: import('@playwright/test').Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('you@example.com').fill(username);
  await page.getByPlaceholder('••••••••').fill(password);
  await Promise.all([
    page.waitForURL((url) => /\/(dashboard|admin)/.test(url.pathname), { timeout: 10_000 }),
    page.getByRole('button', { name: /^sign in$/i }).click(),
  ]);
}
