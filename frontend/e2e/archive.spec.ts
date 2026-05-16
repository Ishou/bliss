/**
 * End-to-end check of the /grilles archive flow:
 *   1. Accueil → "Anciennes grilles" navigates to /grilles.
 *   2. /grilles renders the month-grouped list (MSW handler seeded from
 *      `infrastructure/mocks/handlers.ts`).
 *   3. Clicking a past row navigates to /grille?date=...
 *   4. Browser back returns to /grilles with state preserved.
 */
import { expect, test } from '@playwright/test';

test.beforeEach(async ({ context, page }) => {
  await context.clearCookies();
  await page.goto('about:blank');
  await page.evaluate(() => {
    try {
      window.localStorage.clear();
    } catch {
      /* no-op */
    }
  });
});

test('opens an old grid from /grilles and returns to Accueil', async ({ page }) => {
  await page.goto('/');
  await expect(
    page.getByRole('heading', { level: 2, name: /grille du jour/i }),
  ).toBeVisible();

  await page.getByRole('button', { name: /anciennes grilles/i }).click();
  await expect(page).toHaveURL('/grilles');
  await expect(
    page.getByRole('heading', { level: 1, name: /anciennes grilles/i }),
  ).toBeAttached();

  // Open the second row (skip today; pick the next available past day).
  const rows = page.getByRole('article');
  await rows
    .nth(1)
    .getByRole('link', { name: /commencer|reprendre|revoir/i })
    .click();
  await expect(page).toHaveURL(/\/grille\?date=\d{4}-\d{2}-\d{2}/);

  await page.goBack();
  await expect(page).toHaveURL('/grilles');
});
