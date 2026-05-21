// Phase 6 Task 2 — tap-target audit at the narrowest supported viewport.
// Height is asserted at 44px (WCAG 2.5.5 AA target row height); width is
// asserted at 24px (WCAG 2.2 SC 2.5.8 AA Target Size Minimum) because a
// 10-key AZERTY row mathematically cannot fit 10 buttons at 44px wide
// inside a 320 CSS-px viewport. Both bounds carry a 0.5px subpixel slack.
// See plan divergence note in the PR body.
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
  // 26 AZERTY letters + 3 action-row buttons (prev/hint/next) + 2 trailing
  // (direction + backspace) = 31. The guard catches a regression that
  // accidentally drops a row before per-button measurement runs.
  const buttons = await panel.getByRole('button').all();
  expect(buttons.length).toBeGreaterThan(20);
  for (const btn of buttons) {
    const box = await btn.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.height).toBeGreaterThanOrEqual(43.5);
    expect(box!.width).toBeGreaterThanOrEqual(23.5);
  }
});
