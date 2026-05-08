// Composition root for the Bliss frontend bundle. This file is the only
// place where the ui and infrastructure layers are wired together; it is
// excluded from the layered architecture rules in eslint.config.js.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/ui/App';
import { createAppRouter } from '@/ui/router';
import {
  createHttpLobbyClient,
  createHttpPuzzleRepository,
  createHttpPuzzleSolver,
  createWebSocketGameClient,
} from '@/infrastructure';
import { createHttpSessionClient } from '@/infrastructure/api/grid/HttpSessionClient';
import {
  createMatomoTracker,
  readMatomoConfigFromEnv,
} from '@/infrastructure/analytics/matomoTracker';
import {
  getOrCreateSessionId,
  getPseudonym,
  setPseudonym,
} from '@/infrastructure/session/localStorageSession';
import { registerServiceWorker } from '@/infrastructure/pwa';
import type { Pseudonym, SessionId } from '@/domain/game';
// `fonts.css` is imported separately (rather than via `@import` from
// `index.css`) so the `@font-face` rules reach the `fontaine` Vite
// plugin's `transform` hook directly. CSS-side `@import` is resolved
// after that hook runs, which would hide the rules from fontaine and
// no metrics-matched fallback face would be generated. See
// `vite.config.ts` and `src/ui/styles/fonts.css` for the rationale.
import '@/ui/styles/fonts.css';
import '@/ui/styles/index.css';

// MSW preview-mode bootstrap (ADR-0007 §5). Cloudflare Pages preview
// builds set `VITE_USE_MOCK_API=true` via `.env.preview`; production
// builds load `.env` (`VITE_USE_MOCK_API=false`) and the dynamic import
// below is dead code, so Vite tree-shakes `msw/browser` and every
// handler out of the prod bundle. Verified with:
//
//   pnpm build && grep -r setupWorker dist/   # → empty
//
// The promise-chained app bootstrap ensures the service worker is
// active *before* React renders, so the very first XHR from the
// router loader is intercepted (avoids a race where the initial
// fetch slips through to the real API host).
async function enableMocks(): Promise<void> {
  if (import.meta.env.VITE_USE_MOCK_API !== 'true') return;
  const { worker } = await import('@/infrastructure/mocks/browser');
  await worker.start({
    serviceWorker: { url: '/mockServiceWorker.js' },
    onUnhandledRequest: 'bypass',
  });
}

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root container #root not found in index.html');
}

enableMocks()
  .catch((err: unknown) => {
    console.error('[MSW] worker failed to start, continuing without mock:', err);
  })
  .then(() => {
    const gridApiBaseUrl = import.meta.env.VITE_GRID_API_URL;
    const puzzleRepository = createHttpPuzzleRepository({
      baseUrl: gridApiBaseUrl,
    });
    // Solo + multiplayer both need a stable session id for the hints
    // endpoint's X-Session-Id header. Generated client-side and persisted
    // in localStorage on first visit.
    const sessionId = getOrCreateSessionId();
    const puzzleSolver = createHttpPuzzleSolver({
      baseUrl: gridApiBaseUrl,
      sessionId,
    });
    const sessionClient = createHttpSessionClient({ baseUrl: gridApiBaseUrl });

    // Cookieless Matomo tracker (ADR-0025). No-op when env vars are unset
    // (local dev / preview / pre-Matomo prod).
    const tracker = createMatomoTracker(readMatomoConfigFromEnv());
    // ADR-0018 §10 — multiplayer ships dark. The lobby route, its
    // adapters, and the session accessor are only instantiated when
    // the runtime flag is on. Production flips this to `'true'` after
    // the game-api Helm chart is live and the smoke tests pass; the
    // flag expires no later than 2026-08-02 per .env.
    const multiplayer = import.meta.env.VITE_FEATURE_MULTIPLAYER === 'true';
    const context = multiplayer
      ? (() => {
          const gameApiBaseUrl = import.meta.env.VITE_GAME_API_BASE_URL;
          const lobbyClient = createHttpLobbyClient({ baseUrl: gameApiBaseUrl });
          // WebSocket URL derives from the same host: swap http(s) for
          // ws(s) so a single env var configures both adapters.
          const wsBaseUrl = gameApiBaseUrl.replace(/^http/, 'ws');
          const gameClient = createWebSocketGameClient({ wsBaseUrl });
          // `getSession` is a thin closure over the localStorage helpers
          // so routes don't pull `infrastructure/` into `ui/` directly.
          // Branding is asserted at this single seam. `setPersistedPseudonym`
          // is the write-side counterpart used by the lobby route's
          // `onRename` callback so a chosen pseudonym survives reload.
          const getSession = () => ({
            sessionId: sessionId as SessionId,
            pseudonym: getPseudonym() as Pseudonym,
          });
          const setPersistedPseudonym = (pseudonym: Pseudonym) => {
            setPseudonym(pseudonym);
          };
          return {
            puzzleRepository,
            puzzleSolver,
            sessionClient,
            lobbyClient,
            gameClient,
            getSession,
            setPseudonym: setPersistedPseudonym,
          };
        })()
      : { puzzleRepository, puzzleSolver, sessionClient };
    const router = createAppRouter({ context, multiplayer });

    // Track page views on every route resolution. `onResolved` fires after
    // a navigation completes (initial mount included), giving us the canonical
    // matched URL — query strings are kept off the wire by hooking on
    // `location.pathname` only, so a shareable lobby id isn't leaked into
    // analytics as part of the URL.
    router.subscribe('onResolved', (event) => {
      const url = event.toLocation.pathname;
      tracker.trackPageView(url, document.title || undefined);
    });

    createRoot(container).render(
      <StrictMode>
        <App router={router} />
      </StrictMode>,
    );

    registerServiceWorker();
  });
