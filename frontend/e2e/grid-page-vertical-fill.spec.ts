// Non-regression for PR #590's failure mode: the grid container must fill the
// vertical space between the toolbar and the keyboard panel without breaking
// page layout (no overflow, no horizontal scrollbar, sibling regions keep
// their natural heights).
import { expect, test, devices } from '@playwright/test';

test.use({ ...devices['iPhone 13'] });

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
});

test('grid container fills the vertical space between toolbar and keyboard', async ({ page }) => {
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await expect(page.getByRole('group', { name: 'Clavier mots fléchés' })).toBeVisible();

  const measurements = await page.evaluate(() => {
    const toolbar = document.querySelector('[role="toolbar"][aria-label="Outils de la grille"]');
    const keyboard = document.querySelector('[role="group"][aria-label="Clavier mots fléchés"]');
    const gridArea = document.querySelector('[data-testid="grid-area"]');
    if (!toolbar || !keyboard || !gridArea) {
      throw new Error('toolbar, keyboard, or grid-area not found');
    }
    return {
      toolbar: toolbar.getBoundingClientRect(),
      keyboard: keyboard.getBoundingClientRect(),
      gridArea: gridArea.getBoundingClientRect(),
      viewportHeight: window.innerHeight,
      viewportWidth: window.innerWidth,
      docScrollHeight: document.documentElement.scrollHeight,
      docScrollWidth: document.documentElement.scrollWidth,
    };
  });

  // Sibling regions keep their natural sizes (PR #590 broke this).
  expect(measurements.toolbar.height).toBeGreaterThan(20);
  expect(measurements.toolbar.height).toBeLessThan(80);
  expect(measurements.keyboard.height).toBeGreaterThan(180);
  expect(measurements.keyboard.height).toBeLessThan(400);

  // Grid container reaches close to the keyboard top — no large gap of page
  // background between the cells region and the keyboard. Tolerance of 16 px
  // covers the wrapper's gap + sub-pixel rounding.
  const gridBottom = measurements.gridArea.bottom;
  const keyboardTop = measurements.keyboard.top;
  expect(keyboardTop - gridBottom).toBeLessThanOrEqual(16);

  // No vertical overflow — page is exactly the viewport.
  expect(measurements.docScrollHeight).toBeLessThanOrEqual(measurements.viewportHeight + 1);
  // No horizontal overflow.
  expect(measurements.docScrollWidth).toBeLessThanOrEqual(measurements.viewportWidth + 1);
});
