import { expect, test, devices } from '@playwright/test';

test.use({ ...devices['iPhone 13'] });

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
});

async function bannerHeight(page: import('@playwright/test').Page): Promise<number> {
  return page.evaluate(() => {
    const panel = document.querySelector('[role="group"][aria-label="Clavier mots fléchés"]');
    if (!panel) throw new Error('keyboard panel not found');
    const banner = panel.firstElementChild;
    if (!banner) throw new Error('clue banner not found');
    return banner.getBoundingClientRect().height;
  });
}

test('banner height is identical across empty / single-clue / intersection focus states', async ({
  page,
}) => {
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await expect(page.getByRole('group', { name: 'Clavier mots fléchés' })).toBeVisible();

  // State A: empty — page loads with no cell focused; banner shows the "Touchez une case" placeholder.
  const hEmpty = await bannerHeight(page);

  // State B/C: walk cells to capture the first single-clue and first intersection heights.
  const cells = page.locator('[role="gridcell"]:has(input[data-cell-kind="letter"])');
  const count = await cells.count();
  let hSingle: number | null = null;
  let hIntersection: number | null = null;
  for (let i = 0; i < count && (hSingle === null || hIntersection === null); i++) {
    await cells.nth(i).click();
    const altPresent = await page
      .getByRole('button', { name: /Basculer sur la définition/ })
      .count();
    if (altPresent > 0 && hIntersection === null) hIntersection = await bannerHeight(page);
    if (altPresent === 0 && hSingle === null) hSingle = await bannerHeight(page);
  }
  expect(hSingle, 'expected to find a single-clue cell').not.toBeNull();
  expect(hIntersection, 'expected to find an intersection cell').not.toBeNull();

  // Sub-pixel slack only — anything bigger means content drives the height again.
  expect(Math.abs(hEmpty - hSingle!)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(hSingle! - hIntersection!)).toBeLessThanOrEqual(0.5);
  expect(Math.abs(hEmpty - hIntersection!)).toBeLessThanOrEqual(0.5);
});
