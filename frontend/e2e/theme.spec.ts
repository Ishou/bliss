/**
 * Nature/forest theme — palette assertion (ADR-0043).
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
 *     expected rgb(255, 255, 255) for letter cell bg, got rgb(245, 239, 224)
 *     → `surface` is mapping to neutral.100 instead of pure white
 *
 * If the palette ever swaps again, update this file's PALETTE
 * constant; the role names in the selectors don't change, only the
 * expected values do.
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

// Nature/forest palette (ADR-0043) — every value below is what the
// role-token graph in `panda.config.ts` should produce. Browsers
// normalise CSS colours to `rgb(...)` / `rgba(...)`, so we keep these
// in that form to avoid double conversion in every assertion.
const PALETTE = {
  bg:               'rgb(250, 246, 235)',  // #faf6eb — page bg (neutral.50, papier crème)
  surface:          'rgb(255, 255, 255)',  // #ffffff — letter cell (pure white "cellule")
  surfaceVariant:   'rgb(251, 237, 208)',  // #fbedd0 — clue cell (secondary.100, miel pâle)
  onSurfaceVariant: 'rgb(122, 78, 26)',    // #7a4e1a — text on clue (secondary.700, miel profond)
  fg:               'rgb(31, 46, 37)',     // #1f2e25 — text on paper (neutral.900, forêt profonde)
  accent:           'rgb(63, 100, 49)',    // #3f6431 — mousse (primary.500, AA-tuned from ADR-0043 #5a8a4a anchor)
  border:           'rgb(224, 216, 196)',  // #e0d8c4 — border (neutral.200, bordure sable)
  gridLine:         'rgb(212, 204, 184)',  // #d4ccb8 — grid line (neutral.300, trait de grille)
  focusBg:          'rgb(251, 237, 208)',  // #fbedd0 — focused-cell bg (secondary.100, miel pâle)
  focusRingHoney:   'rgb(200, 148, 86)',   // #c89456 — focused-cell ring (secondary.500, miel main)
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

test.describe('nature/forest theme palette (ADR-0043)', () => {
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

    expect(pageBg, 'page <main> bg = neutral.50 (papier crème)').toBe(PALETTE.bg);
    expect(letterCellBg, 'letter cell bg = surface (pure white cellule)').toBe(PALETTE.surface);
    expect(defCellBg, 'def cell bg = surfaceVariant (secondary.100 miel pâle)').toBe(PALETTE.surfaceVariant);
    expect(defCellText, 'def cell text = onSurfaceVariant (secondary.700 miel profond)').toBe(PALETTE.onSurfaceVariant);
  });

  test('grid container background is the gridLine token (paints internal lines)', async ({ page }) => {
    await bootstrap(page);
    const gridBg = await computedColor(page, '[role="grid"]', 'background-color');
    // gridLine is aliased to neutral.300 (trait de grille — sand mid-tone).
    expect(gridBg, 'grid container bg = gridLine (neutral.300), shows in 1 px gaps as cell-divider lines').toBe(PALETTE.gridLine);
  });

  test('focused letter cell uses focusBg + inset honey ring', async ({ page }) => {
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
    expect(focused!.bg, 'focused-cell bg = focusBg (secondary.100 miel pâle)').toBe(PALETTE.focusBg);
    expect(focused!.color, 'focused-cell text = fg (#1f2e25 forêt profonde)').toBe(PALETTE.fg);
    // box-shadow inset honey ring: ~1.5 px solid token(focusRing).
    // `getComputedStyle.boxShadow` returns the resolved colour first
    // followed by offsets and the `inset` keyword. We just check the
    // honey rgb() string is present and the `inset` keyword is set.
    expect(focused!.boxShadow, 'focused-cell carries an inset shadow').toContain('inset');
    expect(focused!.boxShadow, 'focused-cell ring colour = focusRing (miel main)').toContain(PALETTE.focusRingHoney);
  });

  test('wordmark `Sparrow` half resolves to mousse `accent`', async ({ page }) => {
    await bootstrap(page);

    // ADR-0043 §4: the wordmark stays bicolor — `Word` in primary fg
    // (forêt profonde), `Sparrow` in mousse. The accent span carries
    // `data-testid="wordmark-sage"` (testid name kept for stability;
    // the colour role is now mousse not sage). We only assert that
    // half here — the `Word` half inherits its colour from the parent
    // span's `fg` and is exercised by the surface-text contrast tests
    // below.
    const sparrowColor = await computedColor(
      page,
      '[data-testid="wordmark-sage"]',
      'color',
    );
    expect(sparrowColor, '"Sparrow" half = accent (mousse primary.500)').toBe(PALETTE.accent);
  });
});
