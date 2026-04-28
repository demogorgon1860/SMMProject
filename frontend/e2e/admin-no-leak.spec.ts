import { expect, loginAs, TEST_ADMIN, test } from './fixtures';

/**
 * E2E counterpart to AdminServiceUserListLeakTest.
 *
 * Hits /api/v2/admin/users with an admin token and asserts the JSON response does NOT
 * contain passwordHash, apiKeyHash, apiKeySalt, or "password". This is the production
 * regression guard — runs against the actual deployed backend.
 */
test.describe('Admin endpoints — secret leak guards', () => {
  test('GET /api/v2/admin/users does not expose passwordHash / apiKeyHash', async ({ page, request }) => {
    // Authenticate via the UI to get a real session/token in storage and cookies.
    await loginAs(page, TEST_ADMIN.username, TEST_ADMIN.password);
    // authStore writes to localStorage under the key "token" (not "accessToken").
    const token = await page.evaluate(() => localStorage.getItem('token'));
    expect(token, 'admin login must yield an access token').toBeTruthy();

    const r = await request.get('/api/v2/admin/users?page=0&size=20', {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(r.status()).toBe(200);
    const json = await r.text();
    expect(json).not.toContain('passwordHash');
    expect(json).not.toContain('apiKeyHash');
    expect(json).not.toContain('apiKeySalt');
    expect(json).not.toMatch(/"password"\s*:/);
  });

  test('admin dashboard renders KPIs, no JS error overlay', async ({ page }) => {
    await loginAs(page, TEST_ADMIN.username, TEST_ADMIN.password);
    await page.goto('/admin');
    await expect(page.locator('body')).toContainText(/dashboard|orders|users|revenue/i);
    // Catch the React error boundary if it has rendered.
    const text = (await page.locator('body').textContent()) ?? '';
    expect(text.toLowerCase()).not.toContain('something went wrong');
  });
});
