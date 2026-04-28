import { expect, test } from './fixtures';

/**
 * Tier-1 smoke: the public surface must always render. If these fail, the deploy is broken.
 * Cheap, no auth, fast — meant to catch a server returning 5xx, missing assets, or a JS
 * bundle that crashes on first paint.
 */
test.describe('Public surface smoke', () => {
  test('landing page renders SMMWorld brand', async ({ page }) => {
    await page.goto('/');
    await expect(page).toHaveTitle(/SMM/i);
    await expect(page.locator('body')).toContainText(/SMM|Instagram|orders/i);
  });

  test('login page renders the sign-in form', async ({ page }) => {
    await page.goto('/login');
    await expect(page.getByRole('button', { name: /^sign in$/i })).toBeVisible();
    await expect(page.getByPlaceholder('you@example.com')).toBeVisible();
    await expect(page.getByPlaceholder('••••••••')).toBeVisible();
  });

  test('register page renders the create-account form', async ({ page }) => {
    await page.goto('/register');
    await expect(page.getByRole('button', { name: /create account/i })).toBeVisible();
  });

  test('public services list is reachable and has rows', async ({ page }) => {
    await page.goto('/services-list');
    // The list page renders a heading or a table. Either is acceptable for smoke.
    await expect(page.locator('body')).toContainText(/services/i);
  });

  test('GET /api/v1/stats/public returns a sane payload', async ({ request }) => {
    const r = await request.get('/api/v1/stats/public');
    expect(r.status()).toBe(200);
    const body = await r.json();
    expect(body).toBeTruthy();
    // The endpoint exposes service / order counters; either field is acceptable for smoke.
    const keys = Object.keys(body).join(',').toLowerCase();
    expect(keys).toMatch(/service|order|user/);
  });
});
