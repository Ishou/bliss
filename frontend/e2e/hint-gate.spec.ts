// E2e: disabled hint button for anon; authed path leaves button behaviour untouched.
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

import { expect, test, type Page } from '@playwright/test';

const AUTHED_USER = { userId: 'u-1', displayName: 'Lapin 472' };
const ME_PAYLOAD = {
  id: AUTHED_USER.userId,
  displayName: AUTHED_USER.displayName,
  createdAt: '2026-05-01T10:00:00.000Z',
  providers: [{ provider: 'google', linkedAt: '2026-05-17T10:00:00.000Z', emailOptIn: false }],
};

const FIXTURE_PATH = path.join(
  path.dirname(fileURLToPath(import.meta.url)),
  '..', 'src', 'infrastructure', 'mocks', 'fixtures', 'puzzle.json',
);
const PUZZLE_FIXTURE = JSON.parse(
  readFileSync(FIXTURE_PATH, 'utf-8'),
) as Record<string, unknown>;

interface MswHandle {
  worker: { use: (...handlers: unknown[]) => void };
  http: { get: (path: string, resolver: () => unknown) => unknown };
  HttpResponse: {
    json: (body: unknown) => unknown;
    new (body: BodyInit | null, init?: ResponseInit): Response;
  };
}

async function seedAuth(page: Page, mode: 'authed' | 'anon'): Promise<void> {
  await page.addInitScript(
    ({ kind, user, me }) => {
      const w = window as unknown as { __msw__?: MswHandle; __mswReady__?: Promise<void> };
      let resolveReady: () => void = () => {};
      w.__mswReady__ = new Promise<void>((res) => { resolveReady = res; });
      const tick = (): void => {
        if (w.__msw__ != null) {
          const { worker, http, HttpResponse } = w.__msw__;
          if (kind === 'authed') {
            worker.use(
              http.get('*/v1/auth/whoami', () => HttpResponse.json(user)),
              http.get('*/v1/users/me', () => HttpResponse.json(me)),
            );
          } else {
            worker.use(
              http.get('*/v1/auth/whoami', () =>
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

async function stubPuzzle(page: Page): Promise<void> {
  await page.route(/\/v1\/puzzles\/[^/]+$/, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(PUZZLE_FIXTURE),
    });
  });
}

test('anon user sees the hint button disabled with the sign-in tooltip', async ({ page }) => {
  await seedAuth(page, 'anon');
  await stubPuzzle(page);

  await page.goto('/');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });

  const hintButton = page.getByRole('button', { name: 'Demander un indice' });
  await expect(hintButton).toBeDisabled();
  await expect(hintButton).toHaveAttribute(
    'title',
    'Connectez-vous pour utiliser les indices.',
  );
  await expect(hintButton).toHaveAttribute('aria-disabled', 'true');
});

test('authed user sees the hint button enabled with its original tooltip', async ({ page }) => {
  await seedAuth(page, 'authed');
  await stubPuzzle(page);

  await page.goto('/');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });

  const hintButton = page.getByRole('button', { name: 'Demander un indice' });
  await expect(hintButton).toBeEnabled();
  await expect(hintButton).toHaveAttribute('title', 'Demander un indice');
});
