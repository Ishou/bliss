/**
 * Authed header + /compte route — Phase 5 sub-PR 4. Uses
 * `worker.use(...)` (not `page.route(...)`): the preview build's MSW
 * service worker intercepts cross-origin fetches BEFORE Playwright's
 * CDP layer sees them. The composition root exposes the worker on
 * `__msw__` for this purpose; same handshake as `my-lobbies.spec.ts`.
 */
import { expect, test, type Page } from '@playwright/test';

const AUTHED_USER = { userId: 'u-1', displayName: 'Lapin 472' };

// Hardcoded ISO (not `new Date()`) — non-deterministic test data is a
// latent flake source (PR #378).
const LINKED_AT = '2026-05-17T10:00:00.000Z';

const ME_PAYLOAD = {
  id: AUTHED_USER.userId,
  displayName: AUTHED_USER.displayName,
  createdAt: '2026-05-01T10:00:00.000Z',
  providers: [{ provider: 'google', linkedAt: LINKED_AT, emailOptIn: false }],
};

interface MswHandle {
  worker: { use: (...handlers: unknown[]) => void };
  http: {
    get: (path: string, resolver: () => unknown) => unknown;
    post: (path: string, resolver: () => unknown) => unknown;
  };
  HttpResponse: {
    json: (body: unknown) => unknown;
    new (body: BodyInit | null, init?: ResponseInit): Response;
  };
}

async function seedAuth(page: Page, mode: 'authed' | 'anon'): Promise<void> {
  await page.addInitScript(
    ({ kind, user, me }) => {
      const w = window as unknown as {
        __msw__?: MswHandle;
        __mswReady__?: Promise<void>;
      };
      let resolveReady: () => void = () => {};
      w.__mswReady__ = new Promise<void>((res) => { resolveReady = res; });
      const tick = (): void => {
        if (w.__msw__ != null) {
          const { worker, http, HttpResponse } = w.__msw__;
          if (kind === 'authed') {
            worker.use(
              http.get('*/v1/auth/whoami', () => HttpResponse.json(user)),
              http.get('*/v1/users/me', () => HttpResponse.json(me)),
              http.post(
                '*/v1/auth/logout',
                () => new HttpResponse(null, { status: 204 }),
              ),
            );
          } else {
            worker.use(
              http.get(
                '*/v1/auth/whoami',
                () =>
                  new HttpResponse(
                    JSON.stringify({ type: 'about:blank', title: 'unauthenticated', status: 401 }),
                    { status: 401, headers: { 'content-type': 'application/problem+json' } },
                  ),
              ),
            );
          }
          resolveReady();
          return;
        }
        setTimeout(tick, 10);
      };
      tick();
    },
    { kind: mode, user: AUTHED_USER, me: ME_PAYLOAD },
  );
}

test('authed user sees avatar with their initial; popover lists Mon compte + Se déconnecter', async ({ page }) => {
  await seedAuth(page, 'authed');
  await page.goto('/');

  const trigger = page.getByRole('button', { name: 'Compte' });
  await expect(trigger).toBeVisible();
  await expect(trigger).toHaveText('L');

  await trigger.click();
  await expect(page.getByText('Lapin 472')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Mon compte' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Se déconnecter' })).toBeVisible();
});

test('/compte renders Pseudonyme + Google provider row for authed user', async ({ page }) => {
  await seedAuth(page, 'authed');
  await page.goto('/compte');

  await expect(page.getByRole('heading', { name: 'Pseudonyme' })).toBeVisible();
  await expect(page.getByText('Lapin 472').first()).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Comptes liés' })).toBeVisible();
  await expect(page.getByText(/Google · lié le 17 mai 2026/)).toBeVisible();
  await expect(page.getByText(/Apple · bientôt disponible/)).toBeVisible();
});

test('/compte redirects anon users back to /', async ({ page }) => {
  await seedAuth(page, 'anon');
  await page.goto('/compte');

  await page.waitForURL('**/');
  expect(new URL(page.url()).pathname).toBe('/');
});

test('Se déconnecter navigates to / and shows sign-in button', async ({ page }) => {
  // Stateful MSW: logout POST handler overrides whoami to 401 so that
  // refresh() resolves to anon and the sign-in button re-appears.
  await page.addInitScript(
    ({ user, me }) => {
      const w = window as unknown as {
        __msw__?: MswHandle;
        __mswReady__?: Promise<void>;
      };
      let resolveReady: () => void = () => {};
      w.__mswReady__ = new Promise<void>((res) => { resolveReady = res; });
      const tick = (): void => {
        if (w.__msw__ != null) {
          const { worker, http, HttpResponse } = w.__msw__;
          worker.use(
            http.get('*/v1/auth/whoami', () => HttpResponse.json(user)),
            http.get('*/v1/users/me', () => HttpResponse.json(me)),
            http.post('*/v1/auth/logout', () => {
              worker.use(
                http.get('*/v1/auth/whoami', () =>
                  new HttpResponse(
                    JSON.stringify({ type: 'about:blank', title: 'unauthenticated', status: 401 }),
                    { status: 401, headers: { 'content-type': 'application/problem+json' } },
                  ),
                ),
              );
              return new HttpResponse(null, { status: 204 });
            }),
          );
          resolveReady();
          return;
        }
        setTimeout(tick, 10);
      };
      tick();
    },
    { user: AUTHED_USER, me: ME_PAYLOAD },
  );

  await page.goto('/');
  await page.getByRole('button', { name: 'Compte' }).click();
  await page.getByRole('button', { name: 'Se déconnecter' }).click();

  await page.waitForURL('**/');
  expect(new URL(page.url()).pathname).toBe('/');
  // Sign-in button re-appears after auth refresh resolves to anon.
  await expect(page.getByRole('button', { name: /Se connecter/i })).toBeVisible({ timeout: 3000 });
});
