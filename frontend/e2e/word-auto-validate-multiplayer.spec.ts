/**
 * Multiplayer word auto-validation contract.
 *
 * The server is authoritative: when the player fills the last letter
 * of a word and every cell is correct, the server broadcasts a
 * `wordLocked` frame whose `positions` mark the cells locked. From
 * that point on, the server silently ignores subsequent `cellUpdate`
 * frames targeting any of those positions. The route reduces the
 * frame into `validatedPositions`, which `<Grid>` renders sage +
 * read-only via the existing `letterCellValidated` style.
 *
 * The MSW WebSocket mock encodes the same contract for previews:
 * `lobbyStore.ts` exports `MOCK_ANSWERS` carrying "DEMO" across
 * (0,1)..(0,4) (matches `buildMockPuzzle`'s single across clue), and
 * `game.ts` emits `wordLocked` once those four cells hold the right
 * letters. Locked positions are tracked in the WS handler too — a
 * write to a locked cell never produces a `cellUpdated` echo.
 */
import { expect, test, type Locator, type Page } from '@playwright/test';

async function startMultiplayerGame(page: Page): Promise<void> {
  await page.goto('/');
  // Home page renders the multiplayer CTA via VITE_FEATURE_MULTIPLAYER
  // (set in .env.preview). Click → POST /v1/lobbies → navigate.
  await page.getByRole('button', { name: /Créer une partie multijoueur/i }).click();
  await page.waitForURL(/\/lobby\/[^/]+$/);

  // Wait until the WaitingRoom hydrates from the lobbyState snapshot
  // and the WS is `connected` (the ConnectionBanner is mounted only
  // while the connection is unhealthy — its absence is our signal).
  // `canStart` requires a member count >= 1 AND owner identity, both
  // of which the snapshot delivers.
  const startBtn = page.getByRole('button', { name: /Démarrer la partie/i });
  await expect(startBtn).toBeEnabled({ timeout: 10_000 });
  await expect(page.getByTestId('connection-banner')).toHaveCount(0);

  await startBtn.click();
  await page.waitForSelector('[role="grid"]', { state: 'visible', timeout: 10_000 });
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
}

function letterInput(page: Page, row: number, col: number): Locator {
  return page.locator(
    `input[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`,
  );
}

async function typeWordAcross(
  page: Page,
  row: number,
  startCol: number,
  letters: readonly string[],
): Promise<void> {
  // Focus the first cell ONCE, then dispatch each InputEvent against
  // `document.activeElement` so the grid's auto-advance carries focus
  // forward and React's focus state ref always matches the cell we
  // target. Re-focusing an explicit selector per keystroke caused a
  // shift bug: el.focus() does not synchronously flush React's
  // setFocused, so handleInput read a stale `stateRef.current.focused`
  // and emitted cellUpdate frames against the previous column.
  await page.evaluate(({ row, col }) => {
    const sel = `input[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`;
    document.querySelector<HTMLInputElement>(sel)?.focus();
  }, { row, col: startCol });
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

test('completing a correct word broadcasts wordLocked and locks the cells for the local client', async ({ page }) => {
  await startMultiplayerGame(page);

  // Type "DEMO" across (0,1)..(0,4) — matches `MOCK_ANSWERS` in
  // `lobbyStore.ts`. The mock echoes a `cellUpdated` per keystroke
  // and emits a single `wordLocked` once the 4th letter is correct.
  await typeWordAcross(page, 0, 1, ['D', 'E', 'M', 'O']);

  // `readonly` is the user-facing lock signal — the sage tint is
  // controlled by `data-validated="true"` on the wrapping <div>, but
  // assert against `readonly` since that's what stops further input.
  for (let col = 1; col <= 4; col++) {
    const cell = letterInput(page, 0, col);
    await expect(cell).toHaveAttribute('readonly', '');
  }
});

test('typing a wrong last letter does not lock anything', async ({ page }) => {
  await startMultiplayerGame(page);

  // D, E, M, X — last letter wrong, so the mock's word check finds
  // (0,4)='X' != 'O' and emits no wordLocked.
  await typeWordAcross(page, 0, 1, ['D', 'E', 'M', 'X']);
  await page.waitForTimeout(500);

  for (let col = 1; col <= 4; col++) {
    const cell = letterInput(page, 0, col);
    await expect(cell).toBeEditable();
  }
});

test('writes to a locked cell are silently ignored by the server', async ({ page }) => {
  await startMultiplayerGame(page);
  await typeWordAcross(page, 0, 1, ['D', 'E', 'M', 'O']);

  const first = letterInput(page, 0, 1);
  await expect(first).toHaveAttribute('readonly', '');

  // Try to overwrite the locked cell. `useGridNavigation.handleInput`
  // bails on `target.readOnly` before mutating state, AND the MSW WS
  // handler tracks lockedKeys server-side so even if the client
  // somehow sent a cellUpdate, no cellUpdated would echo back.
  await page.evaluate(() => {
    const sel = 'input[data-cell-kind="letter"][data-row="0"][data-col="1"]';
    const el = document.querySelector<HTMLInputElement>(sel);
    if (!el) return;
    el.focus();
    el.dispatchEvent(new InputEvent('input', {
      inputType: 'insertText',
      data: 'Z',
      bubbles: true,
    }));
  });
  await expect(first).toHaveValue('D');
});
