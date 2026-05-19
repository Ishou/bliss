/**
 * Anon → authed lobby rebind handshake.
 *
 * Closes the FOLLOW_UP gap from PR #529: the AuthProvider's onAuthed
 * effect fires `POST /v1/lobbies/players/rebind` exactly once on the
 * anon→authed transition, carrying the local anon `sessionId` in the
 * body so the server can re-stamp the player's anon seats with the
 * authed `user_id` it derives from the cookie.
 *
 * MSW handshake (mirrors my-lobbies.spec.ts + auth-authed.spec.ts):
 * the per-test init script seeds a deferred `__mswReady__` promise that
 * `main.tsx` awaits, then registers two stateful handlers through
 * `worker.use(...)`: a flip-able `/v1/auth/whoami` that starts 401 and
 * a recorder for `/v1/lobbies/players/rebind` that pushes every body
 * onto `window.__rebindCalls__`. Flipping whoami to 200 plus a
 * synthetic `visibilitychange` drives AuthProvider through its
 * anon→authed transition.
 */
import { expect, test, type Page } from '@playwright/test';

// Hardcoded anon sessionId pre-seeded into localStorage so the AuthProvider's
// `getLocalSessionId` callback returns a deterministic value. UUID v7 shape
// matches the production `getOrCreateSessionId` invariant.
const ANON_SESSION_ID = '0192f000-0000-7000-8000-000000000001';
const AUTHED_USER = { userId: 'u-rebind-1', displayName: 'Renard 123' };

interface MswHandle {
  worker: { use: (...handlers: unknown[]) => void };
  http: {
    get: (path: string, resolver: () => unknown) => unknown;
    post: (
      path: string,
      resolver: (args: { request: Request }) => unknown,
    ) => unknown;
  };
  HttpResponse: {
    json: (body: unknown) => unknown;
    new (body: BodyInit | null, init?: ResponseInit): Response;
  };
}

// Pre-seed the anon sessionId in localStorage BEFORE the app boots so
// `getOrCreateSessionId()` returns the known value when the AuthProvider
// reads it inside the onAuthed callback. Also suppress the SoloTour
// backdrop (mirrors my-lobbies.spec.ts).
async function seedAnonSession(page: Page, sessionId: string): Promise<void> {
  await page.addInitScript((id) => {
    window.localStorage.setItem('bliss.session.id', id);
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  }, sessionId);
}

// Install the MSW handlers. `window.__authedFlag__` is mutable from the
// test body via `page.evaluate(...)` so a single goto can drive both the
// initial anon settle and the later anon→authed transition without
// re-routing. The rebind handler records every request body.
async function seedHandlers(page: Page, user: typeof AUTHED_USER): Promise<void> {
  await page.addInitScript((authedUser) => {
    type RebindBody = { anonSessionId: string };
    type W = Window & {
      __msw__?: MswHandle;
      __mswReady__?: Promise<void>;
      __rebindCalls__?: RebindBody[];
      __authedFlag__?: boolean;
    };
    const w = window as unknown as W;
    w.__rebindCalls__ = [];
    w.__authedFlag__ = false;
    let resolveReady: () => void = () => {};
    w.__mswReady__ = new Promise<void>((res) => {
      resolveReady = res;
    });
    const tick = (): void => {
      if (w.__msw__ != null) {
        const { worker, http, HttpResponse } = w.__msw__;
        worker.use(
          http.get('*/v1/auth/whoami', () => {
            if (w.__authedFlag__) {
              return HttpResponse.json(authedUser);
            }
            return new HttpResponse(
              JSON.stringify({
                type: 'about:blank',
                title: 'unauthenticated',
                status: 401,
              }),
              {
                status: 401,
                headers: { 'content-type': 'application/problem+json' },
              },
            );
          }),
          http.post('*/v1/lobbies/players/rebind', async ({ request }) => {
            const body = (await request.json()) as RebindBody;
            (w.__rebindCalls__ ??= []).push(body);
            return new HttpResponse(null, { status: 204 });
          }),
        );
        resolveReady();
        return;
      }
      setTimeout(tick, 10);
    };
    tick();
  }, user);
}

test('AuthProvider fires POST /v1/lobbies/players/rebind on anon→authed transition with anon sessionId', async ({
  page,
}) => {
  await seedAnonSession(page, ANON_SESSION_ID);
  await seedHandlers(page, AUTHED_USER);

  await page.goto('/');

  // Wait for the AuthProvider to settle into the anon steady state.
  await expect(page.getByRole('link', { name: 'Se connecter' })).toBeVisible();

  // Flip the simulated cookie state to authed and force AuthProvider
  // to refetch whoami. dispatchEvent on visibilitychange triggers the
  // `refresh()` path bound in the AuthProvider's useEffect.
  await page.evaluate(() => {
    (window as unknown as { __authedFlag__: boolean }).__authedFlag__ = true;
    document.dispatchEvent(new Event('visibilitychange'));
  });

  // The header re-renders to authed once refresh() resolves; this is a
  // visible proxy for the anon→authed state transition the onAuthed
  // effect listens to.
  await expect(page.getByRole('button', { name: 'Compte' })).toBeVisible();

  // The rebind POST should have fired exactly once, carrying the anon
  // sessionId pre-seeded in localStorage. Polled via expect.poll so the
  // assertion survives the microtask gap between the React state flip
  // and the network round-trip.
  await expect
    .poll(async () =>
      page.evaluate(() => {
        const calls = (window as unknown as { __rebindCalls__?: unknown[] })
          .__rebindCalls__;
        return calls?.length ?? 0;
      }),
    )
    .toBeGreaterThanOrEqual(1);

  const calls = await page.evaluate(
    () =>
      (window as unknown as { __rebindCalls__: Array<{ anonSessionId: string }> })
        .__rebindCalls__,
  );
  expect(calls).toHaveLength(1);
  expect(calls[0]).toEqual({ anonSessionId: ANON_SESSION_ID });
});

test('rebind hook does not refire on subsequent visibilitychange while still authed (idempotency latch)', async ({
  page,
}) => {
  await seedAnonSession(page, ANON_SESSION_ID);
  await seedHandlers(page, AUTHED_USER);

  await page.goto('/');
  await expect(page.getByRole('link', { name: 'Se connecter' })).toBeVisible();

  await page.evaluate(() => {
    (window as unknown as { __authedFlag__: boolean }).__authedFlag__ = true;
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await expect(page.getByRole('button', { name: 'Compte' })).toBeVisible();

  // Second visibilitychange while still authed must NOT re-trigger the
  // rebind — the AuthProvider's `onAuthedLatch` ref pins it to one shot
  // per anon→authed cycle.
  await page.evaluate(() => {
    document.dispatchEvent(new Event('visibilitychange'));
  });

  // Give a beat for any (incorrect) second POST to land, then assert
  // the count is still exactly one.
  await page.waitForTimeout(150);
  const calls = await page.evaluate(
    () =>
      (window as unknown as { __rebindCalls__: Array<{ anonSessionId: string }> })
        .__rebindCalls__,
  );
  expect(calls).toHaveLength(1);
});
