/**
 * Clue size-vs-cell ratio contract.
 *
 * Complements `clue-overflow.spec.ts`: that test asserts text doesn't
 * spill past its container, this one asserts text isn't *too small*
 * inside it. Together they bracket the "sweet spot" — clues neither
 * overflow nor go microscopic.
 *
 * For each rendered FitText span, we compute fontSize / cellWidth
 * (where cellWidth is the def-cell's bounding box, including both
 * halves for stacked clues — the visual unit the eye reads against).
 * The asserted floor is the minimum visible-readability ratio: any
 * clue rendering below this is the "tiny for no reason" symptom we
 * want to surface as a regression.
 *
 * Adjust MIN_RATIO to taste — it's the dial for how aggressively
 * Phase 2 / fallback paths can shrink a clue before the test fails.
 *
 * --------------------------------------------------------------------
 * Console helper — paste into DevTools with $0 being a cell, half-cell,
 * or FitText span:
 * --------------------------------------------------------------------
 * const r = (el = $0) => {
 *   if (!el) return console.warn('select an element first');
 *   const spans = el.matches?.('span[style*="font-size"]')
 *     ? [el]
 *     : Array.from(el.querySelectorAll?.('span[style*="font-size"]') ?? []);
 *   if (!spans.length) return console.warn('no FitText span in selection');
 *   const cell = el.closest('[data-cell-kind="definition"]') ?? el;
 *   const cellW = cell.getBoundingClientRect().width;
 *   return spans.map(s => {
 *     const wrap = s.parentElement;
 *     const wrapW = wrap.getBoundingClientRect().width;
 *     const fs = parseFloat(getComputedStyle(s).fontSize);
 *     return {
 *       text: s.textContent.slice(0, 40),
 *       fontSize: +fs.toFixed(2),
 *       wrapWidth: +wrapW.toFixed(2),
 *       cellWidth: +cellW.toFixed(2),
 *       ratioVsWrap: +(fs / wrapW).toFixed(3),
 *       ratioVsCell: +(fs / cellW).toFixed(3),
 *     };
 *   });
 * };
 * console.table(r());
 * --------------------------------------------------------------------
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import { expect, test } from '@playwright/test';

const STRESS_FIXTURE_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  'fixtures',
  'puzzle-stress.json',
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

// Lower bound on fontSize / cell-width ratio. Anything below this is
// visually "too tiny" — the symptom we're trying to catch. Currently
// matches FitText's Phase-2 floor (`min × PHASE2_FLOOR_FACTOR`):
// `min = 0.18`, factor `0.5` → effective floor `0.09`. Setting the
// threshold above 0.09 means the test only fails if a clue lands
// strictly *below* the algorithmic floor — i.e. a real bug. Raise this
// (e.g. to 0.12 or 0.14) to enforce a stricter "no clue is ever this
// small" contract; the test will then surface the cells we'd need to
// retune for.
const MIN_RATIO = 0.10;

interface RatioReport {
  row: string | null;
  col: string | null;
  cellKind: string | null;
  text: string;
  fontSize: number;
  cellWidth: number;
  wrapWidth: number;
  ratioVsWrap: number;
  ratioVsCell: number;
}

for (const vp of VIEWPORTS) {
  test(`clue ratios stay above MIN_RATIO at ${vp.name} (${vp.width}×${vp.height})`, async ({
    page,
  }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height });
    await page.route(/\/v1\/puzzles\//, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(STRESS_FIXTURE),
      });
    });
    await page.goto('/');
    await page.waitForSelector('[role="grid"]', { state: 'visible' });
    await page.evaluate(() => document.fonts.ready);
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r(null))));

    const reports = await page.evaluate((): RatioReport[] => {
      const round = (n: number, d = 2) => Math.round(n * 10 ** d) / 10 ** d;
      const out: RatioReport[] = [];
      const cells = document.querySelectorAll('[data-cell-kind="definition"]');
      for (const cell of cells) {
        const cellW = cell.getBoundingClientRect().width;
        const spans = cell.querySelectorAll('span[style*="font-size"]');
        for (const s of spans) {
          const el = s as HTMLElement;
          const wrap = el.parentElement;
          const wrapW = wrap ? wrap.getBoundingClientRect().width : cellW;
          const fs = parseFloat(getComputedStyle(el).fontSize);
          out.push({
            row: cell.getAttribute('data-row'),
            col: cell.getAttribute('data-col'),
            cellKind: cell.getAttribute('data-clue-count'),
            text: (el.textContent ?? '').slice(0, 50).replace(/\n/g, ' / '),
            fontSize: round(fs),
            cellWidth: round(cellW),
            wrapWidth: round(wrapW),
            ratioVsWrap: round(fs / wrapW, 3),
            ratioVsCell: round(fs / cellW, 3),
          });
        }
      }
      return out;
    });

    // Distribution summary — printed every run so the test output
    // doubles as a diagnostic for tuning MIN_RATIO / Phase-2 floors.
    const ratios = reports.map((r) => r.ratioVsCell).sort((a, b) => a - b);
    const min = ratios[0];
    const max = ratios[ratios.length - 1];
    const median = ratios[Math.floor(ratios.length / 2)];
    const mean = ratios.reduce((a, b) => a + b, 0) / ratios.length;
    const buckets: Record<string, number> = {
      '< 0.10': 0, '0.10–0.14': 0, '0.14–0.18': 0,
      '0.18–0.22': 0, '0.22–0.28': 0, '≥ 0.28': 0,
    };
    for (const r of ratios) {
      if (r < 0.10) buckets['< 0.10']++;
      else if (r < 0.14) buckets['0.10–0.14']++;
      else if (r < 0.18) buckets['0.14–0.18']++;
      else if (r < 0.22) buckets['0.18–0.22']++;
      else if (r < 0.28) buckets['0.22–0.28']++;
      else buckets['≥ 0.28']++;
    }
    console.log(
      `[${vp.name}] ratioVsCell n=${ratios.length} `
      + `min=${min} median=${median} mean=${mean.toFixed(3)} max=${max}`,
    );
    console.log(
      `  histogram: `
      + Object.entries(buckets).map(([k, v]) => `${k}=${v}`).join(' '),
    );

    const tooTiny = reports.filter((r) => r.ratioVsCell < MIN_RATIO);
    if (tooTiny.length) {
      console.log(`  ${tooTiny.length} below MIN_RATIO=${MIN_RATIO}:`);
      for (const r of tooTiny) {
        console.log(
          `    r${r.row}c${r.col} (${r.cellKind} clue${r.cellKind === '1' ? '' : 's'}): `
          + `"${r.text}" font=${r.fontSize}px cell=${r.cellWidth}px ratio=${r.ratioVsCell}`,
        );
      }
    }
    expect(
      tooTiny,
      `${tooTiny.length} clue(s) under ratio ${MIN_RATIO} (visually too tiny)`,
    ).toHaveLength(0);
  });
}
