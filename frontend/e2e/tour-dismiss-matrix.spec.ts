/**
 * Exhaustive dismiss-path matrix. The user reported the backdrop
 * lingering after dismiss from step > 1 (Real Chrome on macOS).
 * Investigation traced it to the auto-open `useEffect` re-firing
 * `tour.start()` after dismiss because `tourSeenStore.get()` racily
 * returned `false` despite a synchronous `set(true)` in `onStatusChange`
 * — see `useSoloTour.ts` `dismissedThisSessionRef` for the fix.
 *
 * This matrix exercises every (tooltip step) × (dismiss trigger) cell
 * with `page.mouse.click(x, y, { delay: 100 })` (closer to a real
 * human mouse: separate `mousedown` → 100 ms hold → `mouseup`) and
 * asserts:
 *
 *   1. NO `[data-scope="tour"]` element with `data-state="open"` is
 *      in the DOM 500 ms after dismiss. This is the *symptom* the user
 *      reported — Ark stamps `data-state="open"` whenever the machine
 *      is in the open tag, so a stuck "open" backdrop means the
 *      machine re-opened (welcome) silently after dismiss.
 *   2. No leftover fullscreen dim overlay anywhere on the page (catches
 *      a backdrop that escaped `data-scope="tour"` — leaked
 *      dismissable layer, focus-trap helper, etc.).
 *   3. `document.body` does not retain the `data-inert` /
 *      `pointer-events: none` artefacts that `@zag-js/dismissable`
 *      applies for pointer-blocking layers.
 */
import { type Page, expect, test } from '@playwright/test';

const TOOLTIP_STEPS: ReadonlyArray<{ readonly index: number; readonly title: string }> = [
  { index: 2, title: "Cases d'indices" },
  { index: 3, title: 'Suivez les flèches' },
  { index: 4, title: 'Coup de pouce' },
  { index: 5, title: 'Ajuster le zoom' },
  { index: 6, title: 'Progression et validation' },
];

async function clearAndOpen(page: Page) {
  await page.goto('/');
  await page.evaluate(() => window.localStorage.clear());
  await page.goto('/');
  await page.waitForSelector('[role="grid"]');
  await expect(
    page.getByRole('alertdialog', { name: 'Bienvenue' }),
  ).toBeVisible();
}

async function walkToStep(page: Page, target: number) {
  for (let i = 1; i < target; i += 1) {
    await humanClickButton(page, 'Suivant');
    await page.waitForTimeout(120);
  }
}

// `page.mouse.click(x, y, { delay: 100 })` separates `mousedown` →
// 100 ms hold → `mouseup`, closer to a real mouse than `.click()`'s
// instant synthetic event. The user explicitly hinted that synthetic
// Playwright clicks may not reproduce real-Chrome behaviour for
// dismiss flows.
async function humanClickButton(page: Page, name: string) {
  const button = page.getByRole('button', { name });
  await button.waitFor({ state: 'visible' });
  const box = await button.boundingBox();
  if (!box) throw new Error(`button "${name}" has no bounding box`);
  await page.mouse.click(
    box.x + box.width / 2,
    box.y + box.height / 2,
    { delay: 100 },
  );
}

async function assertTourFullyClosed(page: Page, label: string) {
  // Wait long enough for any auto-open useEffect to have fired and
  // committed a re-open render (or NOT, if the dismiss-session ref is
  // doing its job).
  await page.waitForTimeout(500);

  // 1. No "still-open" backdrop / spotlight / content lingers. Ark
  // sets `data-state="open"` based on the machine's `open` tag, so
  // any stuck `data-state="open"` means the machine itself thinks it
  // re-opened.
  const stuckOpenParts = await page
    .locator('[data-scope="tour"][data-state="open"]')
    .count();
  expect(
    stuckOpenParts,
    `${label}: ${stuckOpenParts} tour parts still in data-state="open"`,
  ).toBe(0);

  // 2. No leftover fullscreen dim overlay anywhere on the page (catches
  // a non-tour-scoped overlay that wasn't cleaned up).
  const dimOverlays = await page.evaluate(() => {
    return Array.from(document.querySelectorAll('div, span, section'))
      .filter((el) => {
        const cs = window.getComputedStyle(el);
        if (cs.display === 'none' || cs.visibility === 'hidden') return false;
        if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
        const r = el.getBoundingClientRect();
        if (r.width < 500 || r.height < 500) return false;
        const bg = cs.backgroundColor;
        if (!bg.startsWith('rgba')) return false;
        const m = bg.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*([0-9.]+)\)/);
        if (!m) return false;
        const alpha = parseFloat(m[4]);
        return alpha > 0.1;
      })
      .map((el) => ({
        tag: el.tagName,
        rect: el.getBoundingClientRect().toJSON(),
        bg: window.getComputedStyle(el).backgroundColor,
        attrs: Array.from(el.attributes).map((a) => `${a.name}=${a.value}`).join(' '),
      }));
  });
  expect(
    dimOverlays,
    `${label}: leftover dim overlay(s): ${JSON.stringify(dimOverlays)}`,
  ).toEqual([]);

  // 3. `document.body` is not pointer-blocked. `@zag-js/dismissable`
  // sets `data-inert` + `pointer-events: none` while a pointer-blocking
  // layer is active; both must be cleared after dismiss.
  const bodyState = await page.evaluate(() => ({
    dataInert: document.body.hasAttribute('data-inert'),
    pointerEvents: window.getComputedStyle(document.body).pointerEvents,
  }));
  expect(bodyState.dataInert, `${label}: body retained data-inert`).toBe(false);
  expect(
    bodyState.pointerEvents === 'auto' || bodyState.pointerEvents === '',
    `${label}: body retained pointer-events="${bodyState.pointerEvents}"`,
  ).toBe(true);
}

test.describe('dismiss-path matrix (every tooltip step × every dismiss trigger)', () => {
  for (const step of TOOLTIP_STEPS) {
    test(`step ${step.index} "${step.title}" — ESC closes everything`, async ({
      page,
    }) => {
      await clearAndOpen(page);
      await walkToStep(page, step.index);
      await expect(
        page.getByRole('alertdialog', { name: step.title }),
      ).toBeVisible();
      await page.keyboard.press('Escape');
      await assertTourFullyClosed(page, `step ${step.index} ESC`);
    });

    test(`step ${step.index} "${step.title}" — Passer le tour closes everything`, async ({
      page,
    }) => {
      await clearAndOpen(page);
      await walkToStep(page, step.index);
      await expect(
        page.getByRole('alertdialog', { name: step.title }),
      ).toBeVisible();
      await humanClickButton(page, 'Passer le tour');
      await assertTourFullyClosed(page, `step ${step.index} Passer`);
    });
  }

  test('step 6 — Terminer closes everything', async ({ page }) => {
    await clearAndOpen(page);
    await walkToStep(page, 6);
    await expect(
      page.getByRole('alertdialog', { name: 'Progression et validation' }),
    ).toBeVisible();
    await humanClickButton(page, 'Terminer');
    await assertTourFullyClosed(page, 'step 6 Terminer');
  });
});
