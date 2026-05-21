// Phase 6 Task 1 — mobile custom keyboard happy path on iPhone 13 emulation.
// Covers panel visibility, cell focus + letter typing, backspace, clue
// navigation, and hint reveal. The desktop-Chrome negative test lives in
// `mobile-keyboard-desktop.spec.ts` because Playwright forbids changing
// `defaultBrowserType` per `test.describe` block.
import { expect, test, devices } from '@playwright/test';

test.use({ ...devices['iPhone 13'] });

test.beforeEach(async ({ page }) => {
  // Skip the SoloTour onboarding overlay so the keyboard sits unobscured.
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
});

test('panel renders on touch-primary and CurrentCluePanel does not', async ({ page }) => {
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await expect(page.getByRole('group', { name: 'Clavier mots fléchés' })).toBeVisible();
  await expect(page.getByTestId('current-clue-panel')).toHaveCount(0);
});

// data-cell-kind="letter" lives on the inner <input>; the gridcell wrapper
// owns the pointer-events. Locate the wrapper as the ancestor of the input.
const firstLetterWrapper = (page: import('@playwright/test').Page) =>
  page.locator('[role="gridcell"]:has(input[data-cell-kind="letter"])').first();
const firstLetterInput = (page: import('@playwright/test').Page) =>
  page.locator('input[data-cell-kind="letter"]').first();

test('tap a cell, type letters, first cell holds the leading letter', async ({ page }) => {
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await firstLetterWrapper(page).click();
  await page.getByLabel('Lettre B').click();
  await page.getByLabel('Lettre O').click();
  await page.getByLabel('Lettre N').click();
  // Auto-advance moves focus along the word, so the first cell holds B.
  await expect(firstLetterInput(page)).toHaveValue('B');
});

test('backspace clears the last typed letter', async ({ page }) => {
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await firstLetterWrapper(page).click();
  await page.getByLabel('Lettre A').click();
  await expect(firstLetterInput(page)).toHaveValue('A');
  // Backspace from the next cell deletes back into the previous cell.
  await page.getByLabel('Effacer').click();
  await expect(firstLetterInput(page)).toHaveValue('');
});

test('Suiv. moves to the next clue', async ({ page }) => {
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await firstLetterWrapper(page).click();
  const panel = page.getByRole('group', { name: 'Clavier mots fléchés' });
  const before = await panel.textContent();
  await page.getByLabel('Indice suivant').click();
  const after = await panel.textContent();
  expect(after).not.toBe(before);
});

test('Indice request changes the counter label', async ({ page }) => {
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await firstLetterWrapper(page).click();
  const hintBtn = page.getByRole('button', { name: /Demander un indice/ });
  const before = await hintBtn.getAttribute('aria-label');
  await hintBtn.click();
  await expect(hintBtn).not.toHaveAttribute('aria-label', before ?? '');
});
