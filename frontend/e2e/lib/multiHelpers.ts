import { expect, type Page } from '@playwright/test';

export interface StartMultiplayerOptions {
  /** Stop after the WaitingRoom is hydrated; do not click "Démarrer". */
  readonly stopBeforeStart?: boolean;
}

/**
 * Drive the home → create-lobby → waiting-room → game flow against the
 * MSW WebSocket mock. Used by both the multiplayer e2e and the a11y
 * scan of the waiting room.
 */
export async function startMultiplayerGame(
  page: Page,
  options: StartMultiplayerOptions = {},
): Promise<void> {
  // Pre-seed the tour-seen flag so the SoloTour backdrop does not block
  // pointer events on the "Créer une partie multijoueur" button.
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
  await page.goto('/grille');
  await page.getByRole('button', { name: /Créer une partie multijoueur/i }).click();
  await page.waitForURL(/\/lobby\/[^/]+$/);

  const startBtn = page.getByRole('button', { name: /Démarrer la partie/i });
  await expect(startBtn).toBeEnabled({ timeout: 10_000 });
  await expect(page.getByTestId('connection-banner')).toHaveCount(0);

  if (options.stopBeforeStart) return;

  await startBtn.click();
  await page.waitForSelector('[role="grid"]', { state: 'visible', timeout: 10_000 });
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
}
