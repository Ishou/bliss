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

test('Suivant walks through all six desktop steps; Terminer closes and persists', async ({
  page,
}) => {
  await page.goto('/');
  await expect(page.getByRole('grid')).toBeVisible();
  await expect(
    page.getByRole('alertdialog', { name: 'Bienvenue' }),
  ).toBeVisible();

  // Step 2: Cases d'indices.
  await page.getByRole('button', { name: 'Suivant' }).click();
  await expect(
    page.getByRole('alertdialog', { name: "Cases d'indices" }),
  ).toBeVisible();
  await page.screenshot({ path: '/tmp/tour-step-2-clue-cells.png' });

  // Step 3: Suivez les flèches.
  await page.getByRole('button', { name: 'Suivant' }).click();
  await expect(
    page.getByRole('alertdialog', { name: 'Suivez les flèches' }),
  ).toBeVisible();
  await page.screenshot({ path: '/tmp/tour-step-3-arrows.png' });

  // Step 4: Coup de pouce (hint button).
  await page.getByRole('button', { name: 'Suivant' }).click();
  await expect(
    page.getByRole('alertdialog', { name: 'Coup de pouce' }),
  ).toBeVisible();
  await page.screenshot({ path: '/tmp/tour-step-4-hints.png' });

  // Step 5 (desktop only): Ajuster le zoom.
  await page.getByRole('button', { name: 'Suivant' }).click();
  await expect(
    page.getByRole('alertdialog', { name: 'Ajuster le zoom' }),
  ).toBeVisible();
  await page.screenshot({ path: '/tmp/tour-step-5-zoom.png' });

  // Step 6: Progression et validation (progress bar).
  await page.getByRole('button', { name: 'Suivant' }).click();
  await expect(
    page.getByRole('alertdialog', { name: 'Progression et validation' }),
  ).toBeVisible();
  await page.screenshot({ path: '/tmp/tour-step-6-validation.png' });

  // The backdrop carries the spotlight clip-path on tooltip steps;
  // Playwright's element-from-point check sees the backdrop covering
  // the popover area and refuses to click. The popover content has
  // `pointer-events: auto` and z-index 1003 so a real mouse click goes
  // through fine — bypass Playwright's hit-testing with `force: true`.
  await page.getByRole('button', { name: 'Terminer' }).click({ force: true });
  // Wait long enough for any auto-open useEffect to have re-fired and
  // committed a re-open render (or NOT, if the dismiss-session ref is
  // doing its job — see `useSoloTour.ts dismissedThisSessionRef`).
  await page.waitForTimeout(500);
  // Tour parts stay mounted under `<Tour.Root>` (Ark's lifecycle drives
  // visibility via the `hidden` HTML attribute + our
  // `[data-state="closed"]:d_none!` Panda override). What must NOT
  // happen is any part keeping `data-state="open"` — that flag is
  // stamped only when the zag machine is in the open tag, so a stuck
  // "open" state means the machine silently re-opened (which is the
  // user-reported bug).
  await expect(
    page.locator('[data-scope="tour"][data-state="open"]'),
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
  // No `data-state="open"` after reload either.
  await expect(
    page.locator('[data-scope="tour"][data-state="open"]'),
  ).toHaveCount(0);
});

test('every footer button stays inside the popover content box', async ({ page }) => {
  // Regression: a previous build sized the popover for 1–2 buttons
  // (Dialog primitive), which the four-chip tour footer (Passer · dots
  // · Précédent · Suivant) overflowed by ~30–60 px. The Suivant pill
  // visually clipped past the popover's right padding. This test walks
  // every step (welcome → validation) and asserts that each button's
  // right edge stays inside the content card's right edge — the
  // strongest expression of "fits inside the popover".
  await page.goto('/');
  await expect(page.getByRole('grid')).toBeVisible();
  await expect(page.getByRole('alertdialog', { name: 'Bienvenue' })).toBeVisible();

  for (let stepIndex = 0; stepIndex < 6; stepIndex += 1) {
    const overflow = await page.evaluate(() => {
      const content = document.querySelector(
        '[data-scope="tour"][data-part="content"]',
      ) as HTMLElement | null;
      if (!content) return { error: 'no content' };
      const c = content.getBoundingClientRect();
      const buttons = Array.from(
        document.querySelectorAll('[data-scope="tour"][data-part="action-trigger"]'),
      ).map((b) => {
        const r = b.getBoundingClientRect();
        return {
          text: b.textContent?.trim(),
          right: r.right,
          left: r.left,
        };
      });
      return { contentRight: c.right, contentLeft: c.left, buttons };
    });
    if ('error' in overflow) throw new Error(overflow.error);
    for (const btn of overflow.buttons) {
      // Allow 1 px sub-pixel rounding tolerance.
      expect(
        btn.right,
        `step ${stepIndex + 1}, button "${btn.text}": right ${btn.right} > content ${overflow.contentRight}`,
      ).toBeLessThanOrEqual(overflow.contentRight + 1);
      expect(
        btn.left,
        `step ${stepIndex + 1}, button "${btn.text}": left ${btn.left} < content ${overflow.contentLeft}`,
      ).toBeGreaterThanOrEqual(overflow.contentLeft - 1);
    }
    if (stepIndex < 5) {
      await page.getByRole('button', { name: 'Suivant' }).click();
      await page.waitForTimeout(150);
    }
  }
});

test('Terminer on step 6 does NOT silently reopen the tour at welcome', async ({
  page,
}) => {
  // The user-reported bug: after Terminer (last-step dismiss), the
  // popover briefly disappears, but the backdrop stays — and a second
  // ESC is required to fully clear it. Investigation traced this to
  // the auto-open `useEffect` re-firing `tour.start()` because
  // `tourSeenStore.get()` racily returned `false` despite a
  // synchronous `set(true)` in `onStatusChange`. Fix is the
  // `dismissedThisSessionRef` synchronous guard in `useSoloTour.ts`.
  await page.goto('/');
  await expect(page.getByRole('grid')).toBeVisible();
  for (let i = 0; i < 5; i += 1) {
    await page.getByRole('button', { name: 'Suivant' }).click();
    await page.waitForTimeout(120);
  }
  await expect(
    page.getByRole('alertdialog', { name: 'Progression et validation' }),
  ).toBeVisible();
  await page.getByRole('button', { name: 'Terminer' }).click({ force: true });
  // 500 ms is long enough for any auto-open re-fire to have rendered
  // a welcome alertdialog. If the bug regresses, this catches it.
  await page.waitForTimeout(500);
  await expect(
    page.getByRole('alertdialog', { name: 'Progression et validation' }),
  ).toHaveCount(0);
  await expect(
    page.getByRole('alertdialog', { name: 'Bienvenue' }),
  ).toHaveCount(0);
  await expect(
    page.locator('[data-scope="tour"][data-state="open"]'),
  ).toHaveCount(0);
  // Seen flag persisted for cross-reload behaviour.
  const seen = await page.evaluate(() =>
    window.localStorage.getItem('wordsparrow.tour.seen'),
  );
  expect(seen).toBe('true');
});

test('zoom step description mentions wheel zoom on desktop', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('grid')).toBeVisible();
  // Walk to step 5 (zoom).
  for (let i = 0; i < 4; i += 1) {
    await page.getByRole('button', { name: 'Suivant' }).click();
    await page.waitForTimeout(120);
  }
  const zoom = page.getByRole('alertdialog', { name: 'Ajuster le zoom' });
  await expect(zoom).toBeVisible();
  await expect(zoom).toContainText('molette');
});

test('ESC dismisses the welcome step', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('grid')).toBeVisible();
  await expect(
    page.getByRole('alertdialog', { name: 'Bienvenue' }),
  ).toBeVisible();
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);
  await expect(
    page.locator('[data-scope="tour"][data-state="open"]'),
  ).toHaveCount(0);
  await expect(
    page.getByRole('alertdialog', { name: 'Bienvenue' }),
  ).toHaveCount(0);
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

test.describe('mobile viewport', () => {
  test.use({ viewport: { width: 360, height: 720 } });

  test('exposes 5 steps (zoom step skipped on mobile)', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('grid')).toBeVisible();
    await expect(
      page.getByRole('alertdialog', { name: 'Bienvenue' }),
    ).toBeVisible();

    // Step counter reads "1 sur 5" on mobile (no zoom step).
    await expect(page.getByText('Étape 1 sur 5')).toBeVisible();
  });

  test('"Passer le tour" button text does not wrap mid-pill', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('grid')).toBeVisible();
    const skip = page.getByRole('button', { name: 'Passer le tour' });
    await expect(skip).toBeVisible();
    // `whiteSpace: nowrap` is set on the action-trigger so the long
    // French label stays on a single line. If the rule regresses, the
    // button height grows past the surrounding row — assert against the
    // measured height instead of the CSS literal so the check still
    // passes if a future change moves the styling to the Button
    // primitive.
    const heights = await skip.evaluate((el) => {
      const cs = window.getComputedStyle(el);
      const fs = parseFloat(cs.fontSize);
      return { offsetH: (el as HTMLElement).offsetHeight, fontSize: fs };
    });
    // Single-line: 16 px font + ~8 px padding × 2 ≈ 42 px (border
    // included). Two-line wrap pushes height past 3 × font-size.
    expect(heights.offsetH).toBeLessThan(heights.fontSize * 3);
  });

  test('footer buttons remain reachable on a 360 px viewport', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('grid')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Passer le tour' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Suivant' })).toBeVisible();
    // Walk through to step 2 to verify the Précédent + Suivant pair fits.
    await page.getByRole('button', { name: 'Suivant' }).click();
    await expect(page.getByRole('button', { name: 'Précédent' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Suivant' })).toBeVisible();
    await page.screenshot({ path: '/tmp/tour-mobile-step-2.png' });
  });
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
