/**
 * End-to-end regression for the solo-mode hint flow.
 *
 * Pins the realistic browser-event sequence that the vitest harness
 * can only approximate via `act(() => input.blur())`: a cell is
 * focused, the user types into it, the hint button is clicked, and
 * the cell's blur fires *before* the click — exactly the timing that
 * caused `getCurrentWord` to read a null `activeFocusRef` and silently
 * drop the request. This spec exercises the chain in a real Chromium
 * with React 18's between-discrete-event passive-effect flush, so it
 * is the canonical net for the bug.
 *
 * Asserts two visible signals after a successful click:
 *   1. The `n/N` badge drops by one (3/3 → 2/3).
 *   2. A status pill appears with the typed word.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import { expect, test } from '@playwright/test';

const FIXTURE_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', 'src', 'infrastructure', 'mocks', 'fixtures', 'puzzle.json',
);
const PUZZLE_FIXTURE = JSON.parse(
  readFileSync(FIXTURE_PATH, 'utf-8'),
) as Record<string, unknown>;

test('clicking the hint button after typing a word decrements the budget and shows a status', async ({
  page,
}) => {
  // GET /v1/puzzles/{id} → fixture. Hint endpoint is left to MSW (loaded
  // by `pnpm dev:preview`) so the response shape stays in lockstep with
  // the OpenAPI generator.
  await page.route(/\/v1\/puzzles\/[^/]+$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(PUZZLE_FIXTURE),
    });
  });

  await page.goto('/');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });

  // Pre-condition: badge starts at 3/3.
  await expect(
    page.getByRole('button', { name: 'Indice (3 / 3)' }),
  ).toBeVisible();

  // (1,7) is the first cell of the across word at (1,7..9); the
  // starting-clue preference picks across over the down word that also
  // passes through (since (0,7) starts the down word, not (1,7)).
  // Three letters is the MSW handler's "exists" threshold. The gridcell
  // wrapper intercepts pointer events (the input has
  // `pointer-events: none`); click the wrapper, then the inner input
  // takes focus via the wrapper's onClick.
  const wrapperAt = (row: number, col: number) =>
    page.locator(`[role="gridcell"][data-row="${row}"][data-col="${col}"]`);

  await wrapperAt(1, 7).click();
  await page.keyboard.press('A');
  await page.keyboard.press('B');
  await page.keyboard.press('C');

  // Click the hint button — focus moves off the cell, the cell blurs,
  // React flushes the focus-change effect between blur and click.
  await page.getByRole('button', { name: 'Indice (3 / 3)' }).click();

  // Post-condition: budget dropped to 2/3 and a status pill is shown.
  await expect(
    page.getByRole('button', { name: 'Indice (2 / 3)' }),
  ).toBeVisible();
  // The toolbar's hint status pill — there are two other `role="status"`
  // regions on the page (the current-clue panel and an sr-only live
  // region), so we scope by the toolbar.
  await expect(
    page.getByRole('toolbar', { name: 'Outils de la grille' }).getByRole('status'),
  ).toContainText(/« .+ »/);
});
