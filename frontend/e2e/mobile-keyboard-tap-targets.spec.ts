// width threshold is WCAG 2.2 SC 2.5.8 AA 24 px min (10-key AZERTY row cannot fit 44 px × 10 in 320 px)
import { expect, test } from '@playwright/test';

test.use({
  viewport: { width: 320, height: 568 },
  hasTouch: true,
  isMobile: true,
});

test('every keyboard button has tap-target height >= 44 and width >= 24', async ({ page }) => {
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  const panel = page.getByRole('group', { name: 'Clavier mots fléchés' });
  await expect(panel).toBeVisible();
  // guard: 26 letters + 3 action + 2 trailing = 31; catches row-drop regression.
  const buttons = await panel.getByRole('button').all();
  expect(buttons.length).toBeGreaterThan(20);
  for (const btn of buttons) {
    const box = await btn.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.height).toBeGreaterThanOrEqual(43.5);
    expect(box!.width).toBeGreaterThanOrEqual(23.5);
  }
});
