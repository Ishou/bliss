/**
 * Anon header surface — Phase 5 sub-PR 3. The unauthenticated home
 * page renders a real `<a href="…/v1/auth/google/login?return_to=…">`
 * anchor so the browser can follow the cross-origin 302 + Set-Cookie
 * chain. A button + `window.location.assign` would lose anchor
 * semantics; the spec keeps that invariant under regression.
 */
import { expect, test } from '@playwright/test';

test('anon user on / sees "Se connecter" anchor with return_to', async ({ page }) => {
  // Stub the identity-api whoami probe so AuthProvider settles to anon
  // without touching the live host (the preview MSW worker does not
  // yet intercept identity calls — see ADR-0007 §5).
  await page.route(/\/v1\/auth\/whoami$/, async (route) => {
    await route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      body: JSON.stringify({ type: 'about:blank', title: 'unauthenticated', status: 401 }),
    });
  });

  await page.goto('/');

  const link = page.getByRole('link', { name: 'Se connecter' });
  await expect(link).toBeVisible();
  const href = await link.getAttribute('href');
  expect(href).toBeTruthy();
  const url = new URL(href!);
  expect(url.pathname).toBe('/v1/auth/google/login');
  const returnTo = url.searchParams.get('return_to');
  expect(returnTo).toBeTruthy();
  // The current page URL is encoded into the return_to query param so the
  // identity-api callback can 302 the player back where they came from.
  expect(returnTo).toContain('/');
});
