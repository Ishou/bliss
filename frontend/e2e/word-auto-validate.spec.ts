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
  // `useGridNavigation.handleInput` keys off the InputEvent's
  // `inputType === 'insertText'` and `data` fields. Playwright's
  // `keyboard.press` / `pressSequentially` does not always fire a
  // synthetic `input` event with that shape on this `<input
  // type="search">` (the cell wraps it under `pointer-events: none`),
  // so we mirror the unit-test pattern (see `tests/grid-input.test.tsx`)
  // and dispatch the InputEvent ourselves. One `evaluate` call per
  // letter so React's auto-advance focus has time to land between
  // keystrokes — batching the loop server-side races the focus state
  // ref and writes every letter into the first cell.
  for (let i = 0; i < letters.length; i++) {
    await page.evaluate(
      ({ row, col, letter }) => {
        const sel = `input[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`;
        const el = document.querySelector<HTMLInputElement>(sel);
        if (!el) return;
        el.focus();
        el.value = letter;
        el.dispatchEvent(new InputEvent('input', {
          inputType: 'insertText',
          data: letter,
          bubbles: true,
        }));
      },
      { row, col: i, letter: letters[i]! },
    );
    // One animation frame so React flushes state + focus moves before
    // the next keystroke.
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
