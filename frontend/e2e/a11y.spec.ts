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
import { startMultiplayerGame } from './lib/multiHelpers';

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
  test('accueil route (landing)', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.addInitScript(() => {
      window.localStorage.setItem('wordsparrow.tour.seen', 'true');
    });
    // Mock the puzzle endpoint so the progress widget hydrates
    // deterministically — the widget renders `Nouvelle grille` /
    // `0 / N cases` text only after this resolves, and we need it in
    // the DOM for axe's color-contrast pass.
    await page.route(/\/v1\/puzzles\//, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(STRESS_FIXTURE),
      });
    });
    // `networkidle` (not `domcontentloaded`) so async-rendered cards
    // are present when axe runs. Without this, the progress widget's
    // de-emphasized text was never in the DOM at scan time and the
    // contrast bug went undetected.
    await page.goto('/', { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);

    await runAxe(page, 'accueil');
  });

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

  test('not-found route', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/this-route-does-not-exist');
    await page.evaluate(() => document.fonts.ready);

    await runAxe(page, 'not-found');
  });

  test('multi waiting room', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await startMultiplayerGame(page, { stopBeforeStart: true });
    await page.evaluate(() => document.fonts.ready);

    await runAxe(page, 'multi-waiting-room');
  });
});
