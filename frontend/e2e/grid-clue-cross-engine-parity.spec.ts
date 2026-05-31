// Cross-engine gate: def-cell clue container height + FitText font-size must match between Chromium and WebKit (regression guard for the aspect-ratio % -height divergence).
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import { chromium, webkit, expect, test, type Browser } from '@playwright/test';

// 5174 mirrors playwright.config.ts's preview webServer port.
const BASE_URL = process.env.PW_BASE_URL ?? 'http://localhost:5174';

const STRESS_FIXTURE_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', 'src', 'infrastructure', 'mocks', 'fixtures', 'puzzle.json',
);
const STRESS_FIXTURE = readFileSync(STRESS_FIXTURE_PATH, 'utf-8');

// FitText quantises, so identical boxes can land one bisection step apart between engines; the box height is the crisp root-cause signal.
const CONTAINER_H_TOLERANCE_PX = 1;
const FONT_SIZE_TOLERANCE_PX = 0.2;

const VIEWPORT = { width: 390, height: 844 };

interface CellMeasure {
  containerH: number;
  fonts: number[];
  text: string;
}
type Measures = Record<string, CellMeasure>;

async function measure(browser: Browser): Promise<Measures> {
  const ctx = await browser.newContext({ viewport: VIEWPORT });
  const page = await ctx.newPage();
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
  await page.route(/\/v1\/puzzles\//, (route) =>
    route.fulfill({ status: 200, contentType: 'application/json', body: STRESS_FIXTURE }),
  );
  await page.goto(`${BASE_URL}/grille`);
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await page.evaluate(() => document.fonts.ready);
  await page.evaluate(
    () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(() => r(null)))),
  );

  const result = await page.evaluate((): Record<string, CellMeasure> => {
    const round = (n: number, d = 3) => Math.round(n * 10 ** d) / 10 ** d;
    const out: Record<string, CellMeasure> = {};
    const cells = document.querySelectorAll('[data-cell-kind="definition"]');
    for (const cell of cells) {
      const spans = cell.querySelectorAll('span[style*="font-size"]');
      if (!spans.length) continue;
      // The clue spans sit inside the cell's direct-child flex column — the element whose height diverged between engines.
      let container: Element = spans[0];
      while (container.parentElement && container.parentElement !== cell) {
        container = container.parentElement;
      }
      const key = `r${cell.getAttribute('data-row')}c${cell.getAttribute('data-col')}`;
      out[key] = {
        containerH: round(container.getBoundingClientRect().height),
        fonts: Array.from(spans, (s) => round(parseFloat(getComputedStyle(s).fontSize))),
        text: (cell.textContent ?? '').slice(0, 40).replace(/\n/g, ' / '),
      };
    }
    return out;
  });

  await ctx.close();
  return result;
}

test('def-cell clue rendering is identical between Chromium and WebKit', async ({}, testInfo) => {
  // Launches both engines itself, so run once — not per project in the matrix.
  test.skip(testInfo.project.name !== 'chromium', 'cross-engine spec runs under the chromium project only');
  test.slow();
  let chrome: Browser | undefined;
  let safari: Browser | undefined;
  try {
    [chrome, safari] = await Promise.all([chromium.launch(), webkit.launch()]);
    const [chromeM, safariM] = await Promise.all([measure(chrome), measure(safari)]);

    const keys = Object.keys(chromeM).filter((k) => k in safariM);
    expect(keys.length, 'no definition cells measured in both engines').toBeGreaterThan(0);

    const drift: string[] = [];
    for (const key of keys) {
      const c = chromeM[key];
      const s = safariM[key];
      const dh = Math.abs(c.containerH - s.containerH);
      if (dh > CONTAINER_H_TOLERANCE_PX) {
        drift.push(
          `${key} "${c.text}": container height ${c.containerH}px (chromium) vs ${s.containerH}px (webkit), Δ=${dh.toFixed(3)}px`,
        );
      }
      const n = Math.min(c.fonts.length, s.fonts.length);
      for (let i = 0; i < n; i++) {
        const df = Math.abs(c.fonts[i] - s.fonts[i]);
        if (df > FONT_SIZE_TOLERANCE_PX) {
          drift.push(
            `${key} "${c.text}" span#${i}: font ${c.fonts[i]}px (chromium) vs ${s.fonts[i]}px (webkit), Δ=${df.toFixed(3)}px`,
          );
        }
      }
    }

    expect(
      drift,
      `${drift.length}/${keys.length} def-cells diverge between engines:\n${drift.join('\n')}`,
    ).toHaveLength(0);
  } finally {
    await Promise.all([chrome?.close(), safari?.close()]);
  }
});
