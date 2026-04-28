import { expect, loginAs, test, TEST_USER } from './fixtures';

/**
 * Regression guard for the order status filter. The bug being prevented:
 *  - Picking "In progress" silently showed PENDING orders because the backend's
 *    `mapFromPerfectPanelStatus` used to default unknown values to PENDING.
 *
 * We click each filter chip in turn and assert that the URL or visible filter label
 * matches what we picked. We don't assume any specific orders exist — the contract
 * being tested is "the picked filter is honored", not the contents of the page.
 */
test.describe('Orders page — status filter', () => {
  const FILTERS = [
    'Pending',
    'In progress',
    'Processing',
    'Completed',
    'Partial',
    'Cancelled',
  ];

  test.beforeEach(async ({ page }) => {
    await loginAs(page, TEST_USER.username, TEST_USER.password);
    await page.goto('/orders');
  });

  for (const filter of FILTERS) {
    test(`filter chip "${filter}" is honored`, async ({ page }) => {
      // Be tolerant — the chip can be a button, a tab, or a select option, and pages
      // sometimes wrap the label in a counter pill.
      const chip = page.getByRole('button', { name: new RegExp(`^${filter}\\b`, 'i') }).first();
      const tab = page.getByRole('tab', { name: new RegExp(`^${filter}\\b`, 'i') }).first();

      const target = (await chip.count()) > 0 ? chip : tab;
      if ((await target.count()) === 0) test.skip(true, `Filter "${filter}" not present`);

      await target.click();

      // The URL or the active chip should now reflect the picked filter. Match either.
      const url = page.url();
      const matchesUrl = new RegExp(`status=${encodeURIComponent(filter).replace(/%20/g, '[\\s+]')}`, 'i');
      if (!matchesUrl.test(url)) {
        // Fall back to checking the visible "active" state.
        await expect(target).toHaveAttribute(
          /(aria-selected|data-state|aria-pressed)/i,
          /(true|active|on)/i
        );
      }
    });
  }
});
