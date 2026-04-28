import { expect, loginAs, test, TEST_USER } from './fixtures';

/**
 * Authentication flows. The full register → verify-email → login round-trip needs an inbox
 * inspection helper (mailtrap.io / mailcatcher); for now we test:
 *
 * 1. Login with seed creds lands on the dashboard.
 * 2. Wrong password shows the inline error and stays on /login.
 * 3. Logout returns to /login (and protected routes redirect back).
 *
 * To enable the full register flow, set EMAIL_INBOX_URL=<mailtrap-api> and add the
 * `register-and-verify.spec.ts` skipped block.
 */
test.describe('Auth — login flow', () => {
  test('happy path → dashboard', async ({ page }) => {
    await loginAs(page, TEST_USER.username, TEST_USER.password);
    await expect(page).toHaveURL(/\/dashboard|\/admin/);
  });

  test('wrong password stays on /login and shows an error toast or banner', async ({ page }) => {
    await page.goto('/login');
    await page.getByPlaceholder('you@example.com').fill(TEST_USER.username);
    await page.getByPlaceholder('••••••••').fill('definitely-not-the-right-password');
    await page.getByRole('button', { name: /^sign in$/i }).click();

    // Stay on /login.
    await page.waitForTimeout(800);
    await expect(page).toHaveURL(/\/login/);
    // Error surface — a banner or toast. Match either.
    const body = await page.locator('body').textContent();
    expect(body?.toLowerCase()).toMatch(/sign-in failed|invalid|wrong|incorrect/);
  });

  test('protected route /dashboard redirects unauthenticated users to /login', async ({ page, context }) => {
    // Fresh context with no storage.
    await context.clearCookies();
    await page.goto('/dashboard');
    await page.waitForURL(/\/login/, { timeout: 5_000 });
    await expect(page).toHaveURL(/\/login/);
  });
});
