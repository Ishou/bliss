/**
 * WCAG 2.2 A + AA accessibility scan.
 *
 * Runs axe-core (via `@axe-core/playwright`) against representative
 * routes at one desktop viewport. Severity policy and tag set live in
 * `lib/axeRun.ts` — see ADR-0034 for the rationale.
 *
 * Why one viewport (desktop) per route:
 *   - colour-contrast violations are viewport-independent (axe reads
 *     computed styles, not painted pixels);
 *   - structural / aria-role / landmark issues are viewport-independent;
 *   - the few viewport-sensitive a11y rules
 *     (`scrollable-region-focusable`) won't change between 360 px and
 *     1440 px in this app's layout.
 *
 * Complements `clue-overflow.spec.ts` (visual layout) and
 * `clue-ratio.spec.ts` (font-size lower bound).
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import { test } from '@playwright/test';

import { runAxe } from './lib/axeRun';

// Same fixture MSW serves in dev/preview — see
// `frontend/src/infrastructure/mocks/handlers.ts`. Keeps the scanned
// page representative of what a reviewer / production user sees.
const STRESS_FIXTURE_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', 'src', 'infrastructure', 'mocks', 'fixtures', 'puzzle.json',
);
const STRESS_FIXTURE = JSON.parse(
  readFileSync(STRESS_FIXTURE_PATH, 'utf-8'),
) as Record<string, unknown>;

test.describe('WCAG 2.2 A + AA accessibility', () => {
  test('grille route (puzzle grid)', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    // Pre-seed `wordsparrow.tour.seen=true` so the SoloTour doesn't
    // open on first visit. The tour is a one-time onboarding overlay;
    // the steady-state grid (returning user) is the canonical scan
    // target. Tour-internal a11y is tracked separately.
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
    // `/` is the accueil landing page after the WordSparrow UI refactor
    // (`75885ce`). The puzzle grid lives on `/grille` — that's the
    // route this scan must hit.
    await page.goto('/grille');
    await page.waitForSelector('[role="grid"]', { state: 'visible' });
    await page.evaluate(() => document.fonts.ready);
    await page.evaluate(() => new Promise(r => requestAnimationFrame(() => r(null))));

    await runAxe(page, 'grille');
  });
});
