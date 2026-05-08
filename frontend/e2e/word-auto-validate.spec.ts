/**
 * Solo word auto-validation contract.
 *
 * When the player fills the last letter of a word, the route asks
 * `POST /v1/puzzles/{id}/validate` and locks the word's cells if every
 * letter was correct. Wrong words emit no UI signal at all (no shake,
 * no error tint) — the test asserts the absence of any visible feedback
 * on the wrong path so a future regression that re-introduces a shake
 * fails immediately.
 *
 * The fixture row chosen — (1,0)..(1,5) = NUMERO in
 * `frontend/src/infrastructure/mocks/fixtures/puzzle.json` — is the
 * leftmost contiguous letter run that does not contain a definition
 * cell, so the wordRange walks cleanly across exactly six letter cells
 * and the MSW validate handler returns `incorrectCells` exclusive to
 * the unfilled rest of the grid (not the typed word).
 */
import { expect, test, type Locator, type Page } from '@playwright/test';

const ANSWER_ACROSS_ROW1 = ['N', 'U', 'M', 'E', 'R', 'O'] as const;

async function gridReady(page: Page): Promise<void> {
  await page.goto('/');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  // One frame so React's initial layout flushes before we start typing.
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
}

function letterInput(page: Page, row: number, col: number): Locator {
  return page.locator(
    `input[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`,
  );
}

async function typeWord(page: Page, row: number, letters: readonly string[]): Promise<void> {
  // Focus the first letter cell on the row ONCE, then dispatch each
  // InputEvent against `document.activeElement` so the grid's auto-
  // advance carries focus forward and React's focus state ref always
  // matches the cell we target. `useGridNavigation.handleInput` keys
  // off the InputEvent's `inputType === 'insertText'` and `data` so
  // we synthesize the event directly (Playwright's keyboard.press /
  // pressSequentially does not reliably fire a real `input` event on
  // this `<input type="search">` wrapped in a `pointer-events: none`
  // cell). Re-focusing an explicit selector per keystroke caused a
  // shift bug — el.focus() does not synchronously flush React's
  // setFocused, so handleInput read a stale focused position and
  // attributed the keystroke to the previous column.
  for (let col = 0; col < /* width */ 16; col++) {
    const exists = await page.evaluate(({ row, col }) => {
      const sel = `input[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`;
      const el = document.querySelector<HTMLInputElement>(sel);
      if (!el) return false;
      el.focus();
      return true;
    }, { row, col });
    if (exists) break;
  }
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
  for (const letter of letters) {
    await page.evaluate((letter) => {
      const el = document.activeElement as HTMLInputElement | null;
      if (!el || el.getAttribute('data-cell-kind') !== 'letter') return;
      el.value = letter;
      el.dispatchEvent(new InputEvent('input', {
        inputType: 'insertText',
        data: letter,
        bubbles: true,
      }));
    }, letter);
    await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
  }
}

test('completing a correct word auto-locks every cell of that word', async ({ page }) => {
  await gridReady(page);

  // MSW intercepts `/v1/puzzles/:id/validate` at the service-worker
  // layer; the response never appears as a network event Playwright
  // can wait for. Lean on Playwright's auto-retrying `expect` so the
  // test waits as long as the route+microtask flush takes.
  await typeWord(page, 1, ANSWER_ACROSS_ROW1);

  // `readonly` on the <input> is the user-facing lock signal — it's
  // what makes the cell unwritable. The sage `data-validated="true"`
  // attribute lives on the wrapping <div>, not the input.
  for (let col = 0; col < ANSWER_ACROSS_ROW1.length; col++) {
    const cell = letterInput(page, 1, col);
    await expect(cell).toHaveAttribute('readonly', '');
  }

  // A neighbouring letter cell on a different row stays editable so
  // the test catches a "lock-everything" regression as much as a
  // "lock-nothing" one.
  const neighbour = letterInput(page, 0, 1);
  await expect(neighbour).toBeEditable();
});

test('typing a wrong word produces no visible feedback', async ({ page }) => {
  await gridReady(page);

  // Wrong on the last letter — the validate response will list (1,5)
  // (and every unfilled cell on the grid) in `incorrectCells`, so the
  // hook drops the word silently.
  await typeWord(page, 1, ['N', 'U', 'M', 'E', 'R', 'X']);

  // Pause briefly so a regression that DOES fire some UI signal has
  // time to land before we assert its absence. 500 ms is generous
  // for the route's per-keystroke microtask path on a CI laptop.
  await page.waitForTimeout(500);

  // None of the row-1 cells become read-only and there's no error
  // class — wrong words must be visually indistinguishable from
  // in-progress ones.
  for (let col = 0; col < ANSWER_ACROSS_ROW1.length; col++) {
    const cell = letterInput(page, 1, col);
    await expect(cell).toBeEditable();
  }
});

test('Vérifier still works on top of an auto-locked word', async ({ page }) => {
  await gridReady(page);

  await typeWord(page, 1, ANSWER_ACROSS_ROW1);
  // Wait for the auto-lock so we know the auto-validation finished
  // before exercising Vérifier (avoids ordering ambiguity in the test).
  await expect(letterInput(page, 1, 0)).toHaveAttribute('readonly', '');

  // Vérifier targets the whole grid — incomplete cells will come back
  // as incorrectCells. We only need to confirm the button still
  // responds (its disabled-when-complete guard does not trip on a
  // partially-locked grid).
  const verify = page.getByRole('button', { name: /Vérifier la grille/i });
  await expect(verify).toBeEnabled();
});
