/**
 * Clue overflow contract.
 *
 * Definition cells render clue text via the FitText component, which
 * binary-searches a font size that fits the cell. The offline
 * `scripts/eval/clue_metrics.py` gate filters the dataset so every
 * shipped clue is supposed to fit at the comfortable floor — but
 * sub-pixel rounding, font-metric drift, and FitText's Phase-2 fallback
 * can still produce visible overflow.
 *
 * This test loads the puzzle (with MSW returning the spec fixture) at
 * a few representative viewport sizes and asserts that every clue
 * inside a definition cell stays within its container box. If the
 * gate or the runtime regress, this fails before users see clipped
 * text.
 *
 * Why three viewports: FitText scales font with cell width, so the
 * answer is supposed to be zoom-invariant — but rendering rounds
 * differently at integer cell sizes (e.g. cell = 47.4 vs 48.6 px),
 * which is exactly where the historical "fits at one window size,
 * overflows at another" bugs lived. Three sizes catches that without
 * being slow.
 */
import { expect, test } from '@playwright/test';

const VIEWPORTS = [
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
        // Each definition cell contains 1 or 2 FitText spans. The span
        // is the element FitText writes inline `font-size` to and
        // measures clientWidth/Height on, so it's the right thing to
        // check for overflow.
        const spans = cell.querySelectorAll('span[style*="font-size"]');
        for (const s of spans) {
          const el = s as HTMLElement;
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
      const detail = overflows
        .map(
          (o) =>
            `  - (r${o.row}, c${o.col}, ${o.cellKind ?? '?'} clues) "${o.text}" `
            + `@ ${o.fontSize}: scroll=${o.scrollW}×${o.scrollH} `
            + `client=${o.clientW}×${o.clientH}`,
        )
        .join('\n');
      throw new Error(
        `${overflows.length} clue(s) overflow their cell at ${vp.name}:\n${detail}`,
      );
    }
    expect(overflows).toHaveLength(0);
  });
}
