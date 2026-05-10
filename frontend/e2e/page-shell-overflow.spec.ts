import { test, expect } from '@playwright/test';

// Layer 2 — page-shell overflow truth gate (ADR-0036 §5).
//
// Real Chromium layout, runs in CI's e2e job. Asserts no horizontal
// overflow on documentElement at 320 / 375 / 768 px viewports across
// every public route.
//
// Expected failures at time of PR 1:
//
//   Accueil at 320 / 375 px — the mobile cards grid uses
//   gridTemplateColumns '1fr' instead of 'minmax(0, 1fr)'. A 1fr track
//   resolves to minmax(auto, 1fr) and refuses to shrink below its
//   min-content width. When the MultijoueurCard's PinInput + eye-toggle
//   row has a min-content wider than the viewport, the track overflows.
//   PR 3 fixes this by migrating / to <ContentPage> and changing the
//   mobile track to minmax(0, 1fr).
//
//   Confidentialité at 320 / 375 px — the privacy policy tables have
//   no responsive wrapping. At narrow viewports the table intrinsic
//   width exceeds the viewport, causing horizontal overflow. Pre-existing
//   bug surfaced by this test, not introduced by PR 1. Fix is to add
//   overflow-x: auto on the table container; tracked for the PR 2
//   migration of /confidentialite (when PrivacyNotice is refactored to
//   slot into ContentPage). Marked test.fail() so this PR doesn't carry
//   a known broken case as green.

const ROUTES = [
  { name: 'Accueil',         path: '/' },
  { name: 'Aide',             path: '/aide' },
  { name: 'Confidentialité', path: '/confidentialite' },
  { name: 'Grille',           path: '/grille' },
  { name: 'Mentions légales', path: '/mentions-legales' },
] as const;

const VIEWPORTS = [320, 375, 768] as const;

const isExpectedFailure = (path: string, width: number): boolean =>
  (path === '/' || path === '/confidentialite') && (width === 320 || width === 375);

const expectedFailureReason = (path: string): string =>
  path === '/'
    ? 'Accueil mobile-overflow bug (1fr track) — fixed in PR 3'
    : 'Confidentialité table overflow on narrow viewports — fixed with overflow-x:auto in PR 3';

test.describe('page-shell horizontal overflow', () => {
  for (const { name, path } of ROUTES) {
    for (const width of VIEWPORTS) {
      test(`${name} at ${width}px has no horizontal overflow`, async ({ page }) => {
        if (isExpectedFailure(path, width)) {
          test.fail(true, expectedFailureReason(path));
        }
        await page.setViewportSize({ width, height: 800 });
        await page.goto(path);
        // Wait for the route's content to actually render. #main-content
        // is rendered by the new primitive (and by all existing inline
        // shells today), so it's a stable settle marker for both pre-
        // and post-migration code.
        await page.waitForSelector('#main-content');
        const overflow = await page.evaluate(() =>
          document.documentElement.scrollWidth > document.documentElement.clientWidth,
        );
        expect(overflow).toBe(false);
      });
    }
  }
});
