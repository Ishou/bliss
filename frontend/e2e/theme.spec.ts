/**
 * Charbon + sage theme — palette assertion.
 *
 * Probes the rendered DOM and asserts that the role-token graph
 * resolves to the expected hex values for every visually load-bearing
 * surface and accent. If a token mapping drifts (e.g. somebody points
 * `surface` at the wrong neutral stop), this test surfaces the
 * mismatch deterministically instead of letting it slip through to
 * a "looks slightly off" review comment.
 *
 * The assertions are intentionally per-element-and-property — not
 * pixel-perfect screenshots — so theme refinements that change a
 * specific role mapping fail fast with a useful diff:
 *
 *     expected rgb(33, 34, 42) for letter cell bg, got rgb(48, 50, 61)
 *     → `surface` is mapping to neutral.500 instead of neutral.700
 *
 * If the project ever ships a second theme, copy this file and assert
 * the alternative palette's hex values; the role names in the
 * selectors don't change, only the expected values do.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import { expect, test, type Page } from '@playwright/test';

const STRESS_FIXTURE_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', 'src', 'infrastructure', 'mocks', 'fixtures', 'puzzle.json',
);
const STRESS_FIXTURE = JSON.parse(
  readFileSync(STRESS_FIXTURE_PATH, 'utf-8'),
) as Record<string, unknown>;

// Charbon + sage palette — every value below is what the role-token
// graph in `panda.config.ts` should produce. Browsers normalise CSS
// colours to `rgb(...)` / `rgba(...)`, so we keep these in that form
// to avoid double conversion in every assertion.
const PALETTE = {
  bg:               'rgb(23, 24, 29)',     // #17181D — page bg (neutral.800)
  surface:          'rgb(33, 34, 42)',     // #21222A — letter cell (neutral.700)
  surfaceVariant:   'rgb(58, 20, 30)',     // #3A141E — clue cell (secondary.900, dark plum)
  onSurfaceVariant: 'rgb(232, 163, 179)',  // #E8A3B3 — text on clue (secondary.400, light dusty pink)
  fg:               'rgb(232, 232, 235)',  // #E8E8EB — text on charcoal (neutral.50)
  accent:           'rgb(160, 179, 148)',  // #A0B394 — sage (primary.500)
  border:           'rgb(48, 50, 61)',     // #30323D — border / gridLine (neutral.500)
  focusBg:          'rgb(42, 28, 34)',     // #2A1C22 — focused-cell bg
  focusRingPink:    'rgb(232, 163, 179)',  // #E8A3B3 — same hex as secondary.400
};

async function bootstrap(page: Page, viewport = { width: 1440, height: 900 }) {
  await page.setViewportSize(viewport);
  // Pre-seed `wordsparrow.tour.seen` so the SoloTour doesn't open
  // and obscure the grid during the assertion phase.
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
  await page.route(/\/v1\/puzzles\//, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(STRESS_FIXTURE),
    });
  });
  // `/` is the accueil landing since the WordSparrow refactor (75885ce);
  // the puzzle grid lives on `/grille`.
  await page.goto('/grille');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r(null))));
}

async function computedColor(page: Page, selector: string, prop: 'background-color' | 'color' | 'border-color'): Promise<string | null> {
  return page.evaluate(({ sel, p }) => {
    const el = document.querySelector(sel);
    if (!el) return null;
    const v = getComputedStyle(el).getPropertyValue(p).trim();
    return v || null;
  }, { sel: selector, p: prop });
}

test.describe('charbon + sage theme palette', () => {
  test('static surfaces resolve to the expected hex values', async ({ page }) => {
    await bootstrap(page);

    // Page background is painted on the route's <main> element (set
    // by `__root.tsx`), not on <body> / <html>. Probe <main>.
    const pageBg = await computedColor(page, 'main', 'background-color');
    // Letter-cell bg is on the wrapping <div role="gridcell">, not on
    // the inner <input> (which has bg: transparent and the
    // `data-cell-kind="letter"` attribute). The wrapper is uniquely
    // identifiable as the only gridcell with `data-in-word`.
    const letterCellBg = await computedColor(
      page,
      '[role="gridcell"][data-in-word]',
      'background-color',
    );
    const defCellBg = await computedColor(
      page,
      '[data-cell-kind="definition"]',
      'background-color',
    );
    const defCellText = await computedColor(
      page,
      '[data-cell-kind="definition"]',
      'color',
    );

    expect(pageBg, 'page <main> bg = neutral.800').toBe(PALETTE.bg);
    expect(letterCellBg, 'letter cell bg = surface (neutral.700)').toBe(PALETTE.surface);
    expect(defCellBg, 'def cell bg = surfaceVariant (secondary.900 dark plum)').toBe(PALETTE.surfaceVariant);
    expect(defCellText, 'def cell text = onSurfaceVariant (secondary.400 light dusty pink)').toBe(PALETTE.onSurfaceVariant);
  });

  test('grid container background is the gridLine token (paints internal lines)', async ({ page }) => {
    await bootstrap(page);
    const gridBg = await computedColor(page, '[role="grid"]', 'background-color');
    // gridLine is aliased to neutral.500 (same as `border`).
    expect(gridBg, 'grid container bg = gridLine (neutral.500), shows in 1 px gaps as cell-divider lines').toBe(PALETTE.border);
  });

  test('focused letter cell uses focusBg + inset pink ring', async ({ page }) => {
    await bootstrap(page);

    // Click the first interactive letter cell input and probe its
    // computed style under :focus. We can't query `:focus` via
    // CSS selector in `document.querySelector`, but
    // `document.activeElement` works.
    await page.locator('input[data-cell-kind="letter"]').first().focus();

    const focused = await page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el) return null;
      const cs = getComputedStyle(el);
      return {
        bg: cs.backgroundColor,
        color: cs.color,
        boxShadow: cs.boxShadow,
      };
    });

    expect(focused, 'a letter cell input is focused').not.toBeNull();
    expect(focused!.bg, 'focused-cell bg = focusBg (#2A1C22)').toBe(PALETTE.focusBg);
    expect(focused!.color, 'focused-cell text = fg (#E8E8EB)').toBe(PALETTE.fg);
    // box-shadow inset pink ring: ~1.5 px solid token(focusRing).
    // `getComputedStyle.boxShadow` returns the resolved colour first
    // followed by offsets and the `inset` keyword. We just check the
    // pink rgb() string is present and the `inset` keyword is set.
    expect(focused!.boxShadow, 'focused-cell carries an inset shadow').toContain('inset');
    expect(focused!.boxShadow, 'focused-cell ring colour = focusRing (light pink)').toContain(PALETTE.focusRingPink);
  });

  test('wordmark `Sparrow` half resolves to sage `accent`', async ({ page }) => {
    await bootstrap(page);

    // ADR-0005 §6: the wordmark is bicolor — `Word` in primary fg,
    // `Sparrow` in sage. The sage span carries `data-testid="wordmark-
    // sage"`; we only assert that half here (the `Word` half inherits
    // its colour from the parent span's `fg` and is exercised by the
    // surface-text contrast tests below).
    const sparrowColor = await computedColor(
      page,
      '[data-testid="wordmark-sage"]',
      'color',
    );
    expect(sparrowColor, '"Sparrow" half = accent (sage primary.500)').toBe(PALETTE.accent);
  });
});
