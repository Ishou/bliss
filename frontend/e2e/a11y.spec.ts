/**
 * WCAG 2.2 A + AA accessibility scan.
 *
 * Runs axe-core (via `@axe-core/playwright`) against representative
 * routes at one desktop viewport. Severity policy and tag set live in
 * `lib/axeRun.ts` — see ADR-0050 for the rationale.
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

import { expect, test } from '@playwright/test';

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

  test('grilles route (archive list)', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/grilles');
    // MSW serves the daily/list handler; wait for at least one day-row
    // article to confirm the archive list has hydrated before axe runs.
    await page.waitForSelector('[role="article"]');
    await page.evaluate(() => document.fonts.ready);

    await runAxe(page, 'grilles');
  });

  test('sondage route', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    // Pre-seed tour-seen so the SoloTour doesn't open over the page.
    await page.addInitScript(() => {
      window.localStorage.setItem('wordsparrow.tour.seen', 'true');
    });
    // Stub getNextItem so the rating card hydrates deterministically (auth-optional route).
    await page.route(/\/v1\/items\/next/, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          itemId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
          mot: 'CHAT',
          definition: 'Animal domestique à moustaches',
          pos: 'nom_commun',
          categorie: 'animals',
          style: 'definition_directe',
          forceClaimed: 2,
          longueur: 4,
          tier: 'mid',
          isCalibration: false,
        }),
      });
    });
    await page.goto('/sondage', { waitUntil: 'networkidle' });
    await page.waitForSelector('[data-testid="rating-card"]', { state: 'visible' });
    await page.evaluate(() => document.fonts.ready);

    await runAxe(page, 'sondage');
  });

  test('confidentialite route (FR privacy notice)', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto('/confidentialite', { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);

    await runAxe(page, 'confidentialite');
  });

  test('not-found route', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    // `networkidle` so React hydrates and sets `document.title` via
    // `RootNotFound`'s useLayoutEffect — without this wait, axe scans
    // the pre-hydration shell and `document-title` fails (serious).
    await page.goto('/this-route-does-not-exist', { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);

    await runAxe(page, 'not-found');
  });

  test('multi waiting room', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await startMultiplayerGame(page, { stopBeforeStart: true });
    await page.evaluate(() => document.fonts.ready);

    await runAxe(page, 'multi-waiting-room');
  });

  test('grille route — zoomed in (scrollbars + minimap in DOM)', async ({ page }) => {
    // Same 1440 × 900 viewport used by the rest of the suite; GridZoomControls
    // is only rendered at ≥ 768 px (md breakpoint), so this viewport is fine.
    await page.setViewportSize({ width: 1440, height: 900 });
    // Pre-seed tour-seen so the SoloTour backdrop does not block the zoom
    // controls — same pattern as the steady-state grille test above and
    // the Task 5 E2E (grid-scrollbars-and-minimap.spec.ts).
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
    await page.goto('/grille');
    await page.waitForSelector('[role="grid"]', { state: 'visible' });
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));

    // Zoom in twice — same aria-label and iteration pattern as zoomIn() in
    // grid-scrollbars-and-minimap.spec.ts.  Inline here to avoid coupling
    // the a11y suite to Task-5 helpers.
    const zoomInBtn = page.getByRole('button', { name: /zoom in/i });
    for (let i = 0; i < 2; i++) {
      await zoomInBtn.click();
      await page.waitForTimeout(180); // library animation is 150 ms
    }

    // Confirm the new overlays are in the DOM before running axe so the
    // scan actually covers GridScrollbar and GridMinimap.
    await expect(page.getByRole('scrollbar', { name: /vertical/i })).toBeVisible();
    await expect(page.getByRole('scrollbar', { name: /horizontal/i })).toBeVisible();
    await expect(page.getByRole('img', { name: /aperçu de la grille/i })).toBeVisible();

    await page.evaluate(() => document.fonts.ready);
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));

    await runAxe(page, 'grille-zoomed');
  });
});
