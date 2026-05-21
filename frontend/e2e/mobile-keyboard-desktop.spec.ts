// Phase 6 Task 1 (desktop negative) — confirms the mobile keyboard panel
// stays absent and CurrentCluePanel mounts on a non-touch-primary device.
// Split from `mobile-keyboard.spec.ts` because Playwright forbids changing
// `defaultBrowserType` (which `devices['Desktop Chrome']` sets) per
// `test.describe` block.
import { expect, test, devices } from '@playwright/test';

test.use({ ...devices['Desktop Chrome'] });

test('panel absent on desktop; CurrentCluePanel present', async ({ page }) => {
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await expect(page.getByRole('group', { name: 'Clavier mots fléchés' })).toHaveCount(0);
  await expect(page.getByTestId('current-clue-panel')).toBeVisible();
});
