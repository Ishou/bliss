/**
 * Accueil "Mes parties" section — ADR-0039 (multiplayer persistence).
 *
 * Wires PR #378's `LobbyClient.listMyLobbies` into the Multijoueur card
 * on the Accueil route. The loader fetches the calling session's
 * lobbies in parallel with the daily puzzle, so the section is hydrated
 * before first paint of the page body.
 *
 * Three behaviours pinned:
 *   1. Empty list (the MSW default) renders the heading + an empty-
 *      state blurb that keeps the surface discoverable rather than
 *      collapsing.
 *   2. A populated list renders one `<li>` per lobby with the wired
 *      title / code / size / player-count meta.
 *   3. Clicking an item navigates to `/lobby/<id>` so a player can
 *      resume the relevant session.
 *
 * Why `worker.use(...)` and not Playwright's `page.route(...)`: the
 * preview build registers MSW as a service worker (`mockServiceWorker.js`)
 * which intercepts page-originated fetches inside the browser, BEFORE
 * Chromium's CDP `Fetch.enable` layer sees them. That means `page.route`
 * never matches when the SW is active. The composition root
 * (`src/main.tsx`) exposes the running `SetupWorker` on
 * `globalThis.__mswWorker__` exclusively when the mock flags are on, so
 * specs can install per-test handlers via the same `worker.use(...)`
 * API the unit tests rely on.
 */
import { expect, test, type Page } from '@playwright/test';

// Wire shape mirrors `components['schemas']['LobbySummary']` from
// game/api/openapi.yaml. Inlined here to keep the spec self-contained
// (e2e tests don't import from `src/` per the runner's tsconfig).
interface WireLobbySummary {
  id: string;
  code: string;
  state: 'WAITING' | 'IN_PROGRESS' | 'COMPLETED';
  gridConfig: { width: number; height: number };
  playerCount: number;
  lastActivityAt: string;
  title?: string;
}

async function gotoAccueil(page: Page): Promise<void> {
  // Pre-seed the tour-seen flag so the SoloTour backdrop does not block
  // pointer events on the cards once the daily-puzzle loader resolves.
  // Mirrors `e2e/lib/multiHelpers.ts`.
  await page.addInitScript(() => {
    window.localStorage.setItem('wordsparrow.tour.seen', 'true');
  });
  await page.goto('/');
  // Wait for the loader to resolve and the Multijoueur card to mount.
  await expect(page.getByRole('heading', { name: 'Multijoueur', level: 2 })).toBeVisible();
}

/**
 * Install a one-test MSW handler returning `lobbies` from the
 * `GET /v1/sessions/:sessionId/lobbies` endpoint, then reload so the
 * Accueil loader's parallel fetch picks it up. The override is scoped
 * to the current page; the next test starts with the default empty
 * list because Playwright spins a fresh page per test.
 */
async function seedMyLobbies(page: Page, lobbies: readonly WireLobbySummary[]): Promise<void> {
  // Two-step handshake with `main.tsx`'s `enableMocks()`:
  //   1. Before MSW is started we install a deferred `__mswReady__`
  //      promise on `window`. `main.tsx` awaits it AFTER the worker is
  //      exposed on `__msw__` but BEFORE rendering React (so before
  //      route loaders fire any fetch).
  //   2. As soon as `__msw__` is exposed (polled cheaply), we register
  //      the per-test handler via `worker.use(...)` and resolve the
  //      promise — main.tsx then unblocks and the loader sees our
  //      overridden response.
  // Without the handshake the test races the SW: the loader fetch can
  // fire before our handler is in place, falling back to MSW's default
  // empty list.
  await page.addInitScript((payload) => {
    type MswHandle = {
      worker: { use: (...handlers: unknown[]) => void };
      http: {
        get: (path: string, resolver: () => unknown) => unknown;
      };
      HttpResponse: { json: (body: unknown) => unknown };
    };
    const w = window as unknown as {
      __msw__?: MswHandle;
      __mswReady__?: Promise<void>;
    };
    let resolveReady: () => void = () => {};
    w.__mswReady__ = new Promise<void>((res) => {
      resolveReady = res;
    });
    const tick = (): void => {
      if (w.__msw__ != null) {
        const { worker, http, HttpResponse } = w.__msw__;
        worker.use(
          http.get('*/v1/sessions/:sessionId/lobbies', () =>
            HttpResponse.json(payload),
          ),
        );
        resolveReady();
        return;
      }
      setTimeout(tick, 10);
    };
    tick();
  }, lobbies as WireLobbySummary[]);
}

test('Mes parties renders an empty-state when the session has no lobbies', async ({ page }) => {
  // Default MSW handler returns `[]`. No override here so we exercise
  // the actual production response shape for the "no lobbies yet"
  // surface.
  await gotoAccueil(page);

  const heading = page.getByRole('heading', { name: 'Mes parties', level: 3 });
  await expect(heading).toBeVisible();
  await expect(
    page.getByText('Vos parties multijoueur en cours apparaîtront ici.'),
  ).toBeVisible();
});

test('Mes parties renders one list item per lobby returned by the API', async ({ page }) => {
  await seedMyLobbies(page, [
    {
      id: '7Hk2pQrS',
      code: 'A2B3C4',
      state: 'IN_PROGRESS',
      gridConfig: { width: 15, height: 12 },
      playerCount: 3,
      lastActivityAt: '2026-05-10T18:00:00Z',
      title: 'Partie du soir',
    },
    {
      id: '9zN4tVwX',
      code: 'D5E6F7',
      state: 'WAITING',
      gridConfig: { width: 7, height: 7 },
      playerCount: 1,
      lastActivityAt: '2026-05-09T12:00:00Z',
    },
  ]);
  await gotoAccueil(page);

  const heading = page.getByRole('heading', { name: 'Mes parties', level: 3 });
  await expect(heading).toBeVisible();

  // List structure: one <li> per lobby, anchored to the
  // `Mes parties` heading via `aria-labelledby`.
  const list = page.getByRole('list').filter({ hasText: 'Partie du soir' });
  const items = list.getByRole('listitem');
  await expect(items).toHaveCount(2);

  // First item shows the explicit title; second item falls back to the
  // `Partie du <date>` form because `title` was absent.
  await expect(items.first()).toContainText('Partie du soir');
  await expect(items.first()).toContainText('A2B3C4');
  await expect(items.first()).toContainText('15×12');
  await expect(items.first()).toContainText('3 joueurs');

  await expect(items.nth(1)).toContainText('D5E6F7');
  await expect(items.nth(1)).toContainText('7×7');
  await expect(items.nth(1)).toContainText('1 joueur');
});

test('clicking a Mes parties item navigates to /lobby/<id>', async ({ page }) => {
  const targetId = '7Hk2pQrS';
  await seedMyLobbies(page, [
    {
      id: targetId,
      code: 'A2B3C4',
      state: 'IN_PROGRESS',
      gridConfig: { width: 15, height: 12 },
      playerCount: 2,
      lastActivityAt: '2026-05-10T18:00:00Z',
      title: 'Partie du soir',
    },
  ]);
  await gotoAccueil(page);

  const link = page.getByRole('link', { name: /Partie du soir/i });
  await expect(link).toBeVisible();
  await link.click();
  await page.waitForURL(new RegExp(`/lobby/${targetId}$`));
});
