/**
 * End-to-end check of the solo onboarding tour. Exercises the live
 * Vite preview build (MSW-mocked grid + game APIs) so the tour's Ark
 * UI machine, real CSS/portal layering, and prefers-reduced-motion
 * defaults all run as in production.
 *
 * What's verified:
 *   1. First visit → tour auto-opens, Bienvenue card visible, screenshot.
 *   2. Skip dismisses the tour and persists the seen flag.
 *   3. Reload after dismissal → tour stays closed.
 *   4. Aide page renders and the launcher button navigates to /?tour=1.
 *   5. ?tour=1 reopens the tour even when the seen flag is set.
 */
import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page, context }) => {
  // Ensure each test starts from a clean localStorage slate. Use a
  // one-shot navigation + evaluate rather than `addInitScript` so a
  // mid-test page.reload() doesn't wipe the flag the test just set.
  await context.clearCookies();
  await page.goto('about:blank');
  await page.evaluate(() => {
    try {
      window.localStorage.clear();
    } catch {
      /* no-op */
    }
  });
});

test('first visit auto-opens the Bienvenue step', async ({ page }) => {
  await page.goto('/');
  // Wait for the puzzle grid to render so the page is in its steady
  // state before the tour initializes.
  await expect(page.getByRole('grid')).toBeVisible();

  // The Bienvenue dialog renders into a portal at document.body. Look
  // for the Ark-emitted dialog with name "Bienvenue".
  const tourDialog = page.getByRole('alertdialog', { name: 'Bienvenue' });
  await expect(tourDialog).toBeVisible({ timeout: 5_000 });

  // Backdrop must NOT carry the HTML `hidden` attribute — Ark sets it
  // when `step.backdrop` is unset, in which case the user sees no scrim.
  const backdrop = page.locator('[data-scope="tour"][data-part="backdrop"]');
  await expect(backdrop).toBeVisible();
  await expect(backdrop).not.toHaveAttribute('hidden', /.*/);

  await page.screenshot({
    path: '/tmp/tour-step-1-bienvenue.png',
    fullPage: false,
  });

  // Footer controls: skip + next on step 1 (no "Précédent" yet).
  await expect(page.getByRole('button', { name: 'Passer le tour' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Suivant' })).toBeVisible();
});

test('Suivant walks through all four steps; Terminer closes and persists', async ({
  page,
}) => {
  await page.goto('/');
  await expect(page.getByRole('grid')).toBeVisible();
  await expect(
    page.getByRole('alertdialog', { name: 'Bienvenue' }),
  ).toBeVisible();

  await page.getByRole('button', { name: 'Suivant' }).click();
  await expect(
    page.getByRole('alertdialog', { name: "Cases d'indices" }),
  ).toBeVisible();
  await page.screenshot({
    path: '/tmp/tour-step-2-clue-cells.png',
  });

  await page.getByRole('button', { name: 'Suivant' }).click();
  await expect(
    page.getByRole('alertdialog', { name: 'Suivez les flèches' }),
  ).toBeVisible();
  await page.screenshot({
    path: '/tmp/tour-step-3-arrows.png',
  });

  await page.getByRole('button', { name: 'Suivant' }).click();
  await expect(
    page.getByRole('alertdialog', { name: 'Bandeau et validation' }),
  ).toBeVisible();
  await page.screenshot({
    path: '/tmp/tour-step-4-banner.png',
  });

  // The backdrop carries the spotlight clip-path on tooltip steps;
  // Playwright's element-from-point check sees the backdrop covering
  // the popover area and refuses to click. The popover content has
  // `pointer-events: auto` and z-index 1003 so a real mouse click goes
  // through fine — bypass Playwright's hit-testing with `force: true`.
  await page.getByRole('button', { name: 'Terminer' }).click({ force: true });
  await expect(
    page.locator('[data-scope="tour"][data-part="content"][data-state="open"]'),
  ).toHaveCount(0);

  // Persisted in localStorage under the wordsparrow.* namespace.
  const seen = await page.evaluate(() =>
    window.localStorage.getItem('wordsparrow.tour.seen'),
  );
  expect(seen).toBe('true');

  // Reload — tour does NOT reopen.
  await page.reload();
  await expect(page.getByRole('grid')).toBeVisible();
  await expect(
    page.getByRole('alertdialog', { name: 'Bienvenue' }),
  ).toHaveCount(0);

  // After dismissal + reload, the backdrop element may stay mounted
  // (Ark UI keeps Tour parts in the DOM as long as <Tour.Root> is
  // rendered). What matters is that it must NOT be visually rendered:
  // assert `display: none`. A future CSS regression that fights the
  // `hidden` HTML attribute (e.g. an overzealous global `[hidden] {
  // display: block }`) would surface as the user staring at a black
  // overlay even though the tour is "closed".
  const backdropDisplay = await page.evaluate(() => {
    const bd = document.querySelector('[data-scope="tour"][data-part="backdrop"]');
    return bd ? window.getComputedStyle(bd).display : 'no-element';
  });
  expect(backdropDisplay === 'none' || backdropDisplay === 'no-element').toBe(true);
});

test('grid panel size is preserved across the tour lifecycle', async ({ page }) => {
  // Regression: the parent flex container (`contentStyles` in
  // routes/index.tsx) has `gap: 18px md`. When the tour was rendered
  // inline (not via a portal), its closed-state positioner stayed in
  // flow as a 0-height flex item — the flex `gap` then shrank the grid
  // panel by 18 px. The fix wraps `<SoloTour>` in `<Portal>` so the
  // tour parts attach to document.body and never become flex siblings
  // of the grid panel. This test catches a regression of that layout.
  await page.goto('/');
  await expect(page.getByRole('grid')).toBeVisible();

  // Capture the grid rect *during* the welcome dialog (auto-opens on
  // first visit), then again after the tour is dismissed, then again
  // after a reload. All three should match.
  const gridRect = async () => {
    const rect = await page.evaluate(() => {
      const grid = document.querySelector('[role="grid"]');
      const r = grid?.getBoundingClientRect();
      return r ? { w: Math.round(r.width), h: Math.round(r.height) } : null;
    });
    return rect;
  };

  await expect(
    page.getByRole('alertdialog', { name: 'Bienvenue' }),
  ).toBeVisible();
  const duringWelcome = await gridRect();

  await page.getByRole('button', { name: 'Passer le tour' }).click({ force: true });
  await page.waitForTimeout(200);
  const afterDismiss = await gridRect();

  await page.reload();
  await expect(page.getByRole('grid')).toBeVisible();
  const afterReload = await gridRect();

  expect(duringWelcome).not.toBeNull();
  expect(afterDismiss).toEqual(duringWelcome);
  expect(afterReload).toEqual(duringWelcome);
});

test('Aide page launches the tour with ?tour=1', async ({ page }) => {
  // Pretend the user has already seen the tour. The Aide button must
  // override the seen flag. Set it on the same origin (via /aide) so
  // localStorage is keyed correctly.
  await page.goto('/aide');
  await page.evaluate(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
  await page.goto('/aide');

  await expect(
    page.getByRole('heading', { name: 'Aide', level: 1 }),
  ).toBeVisible();
  await page.screenshot({ path: '/tmp/aide-page.png' });

  await page.getByRole('button', { name: 'Lancer le tour' }).click();
  await page.waitForURL(/[?&]tour=1/);
  await expect(page.getByRole('grid')).toBeVisible();
  await expect(
    page.getByRole('alertdialog', { name: 'Bienvenue' }),
  ).toBeVisible({ timeout: 5_000 });
});
