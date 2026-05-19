import { expect, test, type Page } from '@playwright/test';

// Pre-seed anon sessionId so getOrCreateSessionId returns a deterministic value.
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

// Seeds anon sessionId before app boot and suppresses the SoloTour backdrop.
async function seedAnonSession(page: Page, sessionId: string): Promise<void> {
  await page.addInitScript((id) => {
    window.localStorage.setItem('bliss.session.id', id);
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  }, sessionId);
}

// Installs MSW handlers: flip-able whoami and rebind call recorder.
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

  // Flip to authed and trigger AuthProvider refresh via visibilitychange.
  await page.evaluate(() => {
    (window as unknown as { __authedFlag__: boolean }).__authedFlag__ = true;
    document.dispatchEvent(new Event('visibilitychange'));
  });

  await expect(page.getByRole('button', { name: 'Compte' })).toBeVisible();

  // Poll for rebind POST to survive microtask gap between state flip and network.
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

  // Second visibilitychange must not re-trigger the rebind.
  await page.evaluate(() => {
    document.dispatchEvent(new Event('visibilitychange'));
  });

  // Wait for any incorrect second POST to land, then assert still exactly one.
  await page.waitForTimeout(150);
  const calls = await page.evaluate(
    () =>
      (window as unknown as { __rebindCalls__: Array<{ anonSessionId: string }> })
        .__rebindCalls__,
  );
  expect(calls).toHaveLength(1);
});
