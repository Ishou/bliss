/**
 * Clue overflow contract.
 *
 * Definition cells render clue text via the FitText component, which
 * binary-searches a font size that fits the cell. The offline
 * `scripts/eval/clue_metrics.py` gate filters the dataset so every
 * shipped clue is supposed to fit at the comfortable floor — but
 * sub-pixel rounding, font-metric drift, and historical absolute-pixel
 * floors have been known to produce visible overflow.
 *
 * This test loads the puzzle, REWRITES the API response so each
 * definition cell carries a realistic-length clue text (the MSW
 * fixture's own texts are trivially short — "psycho", "déco" — and
 * would never stress the layout), and asserts that no clue overflows
 * its FitText span at any of four viewport sizes spanning narrow
 * mobile to wide desktop.
 *
 * The four viewport sizes catch the historical "fits at one window
 * size, overflows at another" bugs that lived at sub-pixel boundaries
 * (cell = 47.4 vs 48.6 px rounds differently). Four widths covers
 * the integer-cell-size regimes without being slow.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import { expect, test } from '@playwright/test';

// Stress fixture — a real 10×10 grid the user observed overflowing in
// their parallel session. Lives next to the test rather than under
// `grid/api/examples/` because it's frontend-test-only data: the API
// fixture (`get-puzzle-200.json`) carries trivial single-word clue
// texts that no layout would ever fail, so the e2e test was passing
// vacuously. This fixture has 28 long clues including pathological
// cases ("Lettre grecque (19e)", "Longue période historique",
// "Règle d'équerre ou note") that exercise hyphenation, multi-line
// stacking, and unbreakable-token wrap.
// Same fixture MSW serves in dev/preview — see
// `frontend/src/infrastructure/mocks/handlers.ts`. Loading from the
// shared location guarantees "what the test sees" === "what the
// reviewer sees in preview".
const STRESS_FIXTURE_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', 'src', 'infrastructure', 'mocks', 'fixtures', 'puzzle.json',
);
const STRESS_FIXTURE = JSON.parse(
  readFileSync(STRESS_FIXTURE_PATH, 'utf-8'),
) as Record<string, unknown>;

const VIEWPORTS = [
  { name: 'mobile-tiny',   width: 320, height: 568 },
  { name: 'mobile-narrow', width: 360, height: 740 },
  { name: 'tablet',        width: 820, height: 1180 },
  { name: 'desktop',       width: 1440, height: 900 },
] as const;

interface OverflowReport {
  row: string | null;
  col: string | null;
  cellKind: string | null;
  text: string;
  scrollW: number;
  clientW: number;
  scrollH: number;
  clientH: number;
  fontSize: string;
}

// Sub-pixel slop. scrollWidth / scrollHeight are integer-rounded
// (typically up); 1 px of overflow can appear in the DOM measurement
// while the actual rendered glyphs sit safely inside the visual
// boundary. Anything > 1 px is real overflow — a clipped clue.
const OVERFLOW_TOLERANCE_PX = 1;

for (const vp of VIEWPORTS) {
  test(`no clue overflows definition cells at ${vp.name} (${vp.width}×${vp.height})`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height });

    // Replace the puzzle response with the stress fixture — a real
    // 10×10 grid the user observed overflowing. The default MSW
    // fixture has trivially short clue texts that no layout would
    // ever fail, so the e2e test would pass vacuously without this.
    await page.route(/\/v1\/puzzles\//, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(STRESS_FIXTURE),
      });
    });

    await page.goto('/');

    // Wait for the grid to render and FitText's first layout pass to
    // settle. Two beats:
    //   1. The grid role is present.
    //   2. Fonts have loaded — FitText re-fits on `document.fonts.ready`,
    //      and we only want to measure post-fit state.
    await page.waitForSelector('[role="grid"]', { state: 'visible' });
    await page.evaluate(() => document.fonts.ready);
    // One animation frame to flush the post-fonts re-fit's style write.
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r(null))));

    const overflows = await page.evaluate((tolerance): OverflowReport[] => {
      const results: OverflowReport[] = [];
      const cells = document.querySelectorAll('[data-cell-kind="definition"]');
      for (const cell of cells) {
        // Each definition cell contains 1 or 2 FitText spans.
        const spans = cell.querySelectorAll('span[style*="font-size"]');
        for (const s of spans) {
          const el = s as HTMLElement;
          // Box-level overflow on the FitText span itself. With our
          // chain of `overflow: hidden` (span → defStackClue/defSingle
          // → defStack), any leak past the span's clientWidth/Height
          // is what the user sees as a "demi-word at the cell edge":
          // the last line is partially clipped, showing only the
          // upper halves of the glyphs. We do NOT also check Range
          // bbox against parent — Range ignores `overflow: hidden`
          // and would flag invisible-because-clipped overflow as a
          // failure, which doesn't match what the user sees.
          const widthOver = el.scrollWidth - el.clientWidth;
          const heightOver = el.scrollHeight - el.clientHeight;
          if (widthOver > tolerance || heightOver > tolerance) {
            results.push({
              row: cell.getAttribute('data-row'),
              col: cell.getAttribute('data-col'),
              cellKind: cell.getAttribute('data-clue-count'),
              text: (el.textContent ?? '').slice(0, 80),
              scrollW: el.scrollWidth,
              clientW: el.clientWidth,
              scrollH: el.scrollHeight,
              clientH: el.clientHeight,
              fontSize: el.style.fontSize,
            });
          }
        }
      }
      return results;
    }, OVERFLOW_TOLERANCE_PX);

    if (overflows.length > 0) {
      // Capture a screenshot so the failure has a visual artifact
      // attached. Playwright's HTML reporter surfaces test attachments.
      const screenshot = await page.screenshot({ fullPage: false });
      await test.info().attach(`grid-${vp.name}.png`, {
        body: screenshot,
        contentType: 'image/png',
      });
      const detail = overflows
        .map(
          (o) =>
            `  - (r${o.row}, c${o.col}, ${o.cellKind ?? '?'} clues) `
            + `"${o.text}" @ ${o.fontSize}: `
            + `scroll=${o.scrollW}×${o.scrollH} client=${o.clientW}×${o.clientH}`,
        )
        .join('\n');
      throw new Error(
        `${overflows.length} clue(s) overflow their cell at ${vp.name}:\n${detail}`,
      );
    }
    expect(overflows).toHaveLength(0);
  });
}
