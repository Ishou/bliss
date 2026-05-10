/**
 * Lobby WAITING-phase footer-overlap regression probe.
 *
 * ADR-0036 §Rollout (PR 3): the lobby route uses <ContentPage> while
 * lobby.state === 'WAITING'. This prevents the WaitingRoom content from
 * visually intruding into the footer below — the original mobile bug
 * catalogued as ADR-0036 §Context finding #5.
 *
 * Root cause: when ViewportPage is used during WAITING, <main> gets
 * `flex: 1 1 0; minHeight: 0`, which caps its height to the remaining
 * viewport space. The WaitingRoom content (player list + grid-size
 * selector + start button) exceeds that cap on narrow mobile viewports
 * and visually overflows <main>'s box, painting over the footer below.
 *
 * Regression assertion: after ContentPage is applied, <main>'s
 * scrollHeight must equal its clientHeight — meaning <main> grew to
 * accommodate its children and no content escapes the element's box.
 * If ViewportPage were (re)introduced for WAITING, scrollHeight would
 * exceed clientHeight and this test would fail.
 *
 * The probe runs at 375 px wide (iPhone SE / narrow Android) where the
 * WaitingRoom content is tall enough to trigger the original overflow.
 */
import { expect, test } from '@playwright/test';

import { startMultiplayerGame } from './lib/multiHelpers';

test('lobby WAITING phase: WaitingRoom content does not overflow <main> at 375 px', async ({ page }) => {
  // 375×667 — the narrowest common phone viewport (iPhone SE). The
  // overflow was observed at 320–375 px; 375 is the canonical probe
  // width per ADR-0036 §6.
  await page.setViewportSize({ width: 375, height: 667 });

  // Seeds a WAITING-phase lobby via the MSW WebSocket mock and stops
  // before clicking "Démarrer la partie". The helper waits for the
  // button to be enabled, so the WaitingRoom is fully rendered when
  // this returns.
  await startMultiplayerGame(page, { stopBeforeStart: true });

  // One animation frame so the flex layout engine has flushed any
  // post-hydration style mutations before we measure.
  await page.evaluate(() => new Promise<void>((r) => requestAnimationFrame(() => r())));

  const overflow = await page.evaluate(() => {
    const main = document.getElementById('main-content');
    if (!main) return null;
    return {
      scrollHeight: main.scrollHeight,
      clientHeight: main.clientHeight,
    };
  });

  expect(overflow, '<main id="main-content"> not found in DOM').not.toBeNull();

  // With ContentPage (flex: 1 0 auto): <main> grows to fit its
  // children → scrollHeight ≈ clientHeight, delta ≤ 1 px (sub-pixel).
  // With ViewportPage (flex: 1 1 0; minHeight: 0): <main> is capped by
  // the remaining viewport height → scrollHeight > clientHeight when
  // WaitingRoom content is taller than that cap.
  expect(overflow!.scrollHeight).toBeLessThanOrEqual(overflow!.clientHeight + 1);
});
