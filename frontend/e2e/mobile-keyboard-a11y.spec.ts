// Phase 6 Task 3 — axe-core scan over the mobile custom keyboard panel.
// Scoped to the panel via the role=group accessible name so violations on
// unrelated parts of the grille route do not bleed into this gate.
import { expect, test, devices } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

test.use({ ...devices['iPhone 13'] });

test('mobile keyboard has no axe-core violations', async ({ page }) => {
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  // Click the gridcell wrapper around the first letter input.
  await page.locator('[role="gridcell"]:has(input[data-cell-kind="letter"])').first().click();
  await expect(page.getByRole('group', { name: 'Clavier mots fléchés' })).toBeVisible();
  const results = await new AxeBuilder({ page })
    .include('[role="group"][aria-label="Clavier mots fléchés"]')
    .analyze();
  expect(results.violations, JSON.stringify(results.violations, null, 2)).toEqual([]);
});
