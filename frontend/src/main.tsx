// Composition root for the Bliss frontend bundle. This file is the only
// place where the ui and infrastructure layers are wired together; it is
// excluded from the layered architecture rules in eslint.config.js.
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from '@/ui/App';
import { createAppRouter } from '@/ui/router';
import {
  createHttpAuthClient,
  createHttpLobbyClient,
  createHttpPuzzleRepository,
  createHttpPuzzleSolver,
  createHttpSurveyClient,
  createReconnectingGameClient,
  createWebSocketGameClient,
} from '@/infrastructure';
import { createHttpSessionClient } from '@/infrastructure/api/grid/HttpSessionClient';
import { AuthProvider } from '@/ui/components/auth';
import {
  createMatomoTracker,
  readMatomoConfigFromEnv,
} from '@/infrastructure/analytics/matomoTracker';
import {
  initOtelTracer,
  readOtelConfigFromEnv,
  reportCaughtError,
} from '@/infrastructure/observability/otelTracer';
import {
  clearSession,
  getOrCreateSessionId,
  getPseudonym,
  setPseudonym,
} from '@/infrastructure/session/localStorageSession';
import {
  clearAllSoloEntriesForEverySession,
  clearSoloEntriesForPuzzle,
  loadSoloEntries,
  loadSoloHintsUsed,
  loadSoloLockedCells,
  recordSoloHintUsed,
  saveSoloLetter,
  saveSoloLockedCell,
} from '@/infrastructure/session/localStorageSolo';
import {
  clearTourSeen,
  getTourSeen,
  setTourSeen,
} from '@/infrastructure/session/localStorageTour';
import {
  createSoloEntriesStore,
  type SoloEntriesStorage,
  type SoloEntriesStore,
} from '@/application/solo/SoloEntriesStore';
import type { TourSeenStore } from '@/application/tour/TourSeenStore';
import type { SessionClient } from '@/application/session/SessionClient';
import { registerServiceWorker } from '@/infrastructure/pwa';
import { sessionStorageLobbyJoinCodeStash } from '@/infrastructure/session/sessionStorageLobbyJoinCode';
import type { Pseudonym, SessionId } from '@/domain/game';
// `fonts.css` is imported separately (rather than via `@import` from
// `index.css`) so the `@font-face` rules reach the `fontaine` Vite
// plugin's `transform` hook directly. CSS-side `@import` is resolved
// after that hook runs, which would hide the rules from fontaine and
// no metrics-matched fallback face would be generated. See
// `vite.config.ts` and `src/ui/styles/fonts.css` for the rationale.
import '@/ui/styles/fonts.css';
import '@/ui/styles/index.css';

// MSW bootstrap (ADR-0007 §5). Two independent flags pick which API
// surfaces are intercepted:
//
//   * VITE_MOCK_GRID_API  → mocks `/v1/puzzles/...` (grid backend)
//   * VITE_MOCK_GAME_API  → mocks `/v1/lobbies/...` REST and the
//                           `/v1/lobbies/:id/ws` WebSocket
//
// `.env`             — both false (production hits real backends).
// `.env.preview`     — both true  (Cloudflare Pages previews are
//                                  self-contained, no live backends).
// `.env.development` — both false (real backends on localhost). A
//                      contributor without one of the services
//                      running drops a `.env.development.local`
//                      override that flips the matching flag to
//                      `true` for an MSW fallback.
//
// Production builds set both flags false; the dynamic `import()`
// below becomes dead code under falsy literal env values, so Vite
// tree-shakes `msw/browser` and every handler out. Verified with:
//
//   pnpm build && grep -r setupWorker dist/   # → empty
//
// The promise-chained app bootstrap ensures the service worker is
// active *before* React renders, so the very first XHR from the
// router loader is intercepted (avoids a race where the initial
// fetch slips through to the real API host).
async function enableMocks(): Promise<void> {
  const mockGrid = import.meta.env.VITE_MOCK_GRID_API === 'true';
  const mockGame = import.meta.env.VITE_MOCK_GAME_API === 'true';
  if (!mockGrid && !mockGame) return;
  const mod = await import('@/infrastructure/mocks/browser');
  const handlersMod = await import('@/infrastructure/mocks/handlers');
  const handlers = [
    ...(mockGrid ? handlersMod.gridApiHandlers : []),
    ...(mockGame ? handlersMod.gameApiHandlers : []),
  ];
  const worker = mod.createWorker(handlers);
  await worker.start({
    serviceWorker: { url: '/mockServiceWorker.js' },
    onUnhandledRequest: 'bypass',
  });
  // Expose the worker + the `http`/`HttpResponse` helpers on
  // `globalThis.__msw__` so e2e specs can call `worker.use(...)` to
  // override a single handler per test. This is the only way to swap a
  // response when MSW's service worker is intercepting: Playwright's
  // `page.route` is bypassed by the SW fetch handler. Guarded by the
  // same mock flags above, so production builds (both flags false)
  // tree-shake this branch out alongside the rest of `enableMocks()`.
  // The handle is intentionally namespaced with `__` to flag it as a
  // test seam; nothing in `src/` reads it.
  const mswMod = await import('msw');
  const w = globalThis as unknown as {
    __msw__?: {
      worker: typeof worker;
      http: typeof mswMod.http;
      HttpResponse: typeof mswMod.HttpResponse;
    };
    __mswReady__?: Promise<void>;
  };
  w.__msw__ = { worker, http: mswMod.http, HttpResponse: mswMod.HttpResponse };
  // If an e2e spec (via `page.addInitScript`) seeded a deferred
  // `__mswReady__` promise, await it so per-test `worker.use(...)`
  // handlers are registered before the router's loaders fire their
  // first fetch. Resolves immediately when no test is wiring this up.
  if (w.__mswReady__) {
    await w.__mswReady__;
  }
}

// Initialise OTel before the first fetch so the FetchInstrumentation can
// patch the global. Noop when VITE_OTEL_OTLP_ENDPOINT is unset
// (dev / preview / pre-PR-F.2 prod). ADR-0033.
initOtelTracer(readOtelConfigFromEnv());

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root container #root not found in index.html');
}

enableMocks()
  .catch((err: unknown) => {
    // Dev/preview only; tree-shaken away in production builds.
    console.error('[MSW] worker failed to start, continuing without mock:', err);
  })
  .then(() => {
    const gridApiBaseUrl = import.meta.env.VITE_GRID_API_URL;
    const puzzleRepository = createHttpPuzzleRepository({
      baseUrl: gridApiBaseUrl,
    });
    // Session id is retained for multiplayer presence; grid-api hints authenticate via cookie.
    const sessionId = getOrCreateSessionId();
    const puzzleSolver = createHttpPuzzleSolver({
      baseUrl: gridApiBaseUrl,
    });
    // Compose the full SessionClient: the HTTP adapter covers eraseSession
    // while the localStorage helpers cover getSessionId/clearLocalSession.
    // This is the only place allowed to import both; ui/ components receive
    // the composed port through router context (ADR-0002 §7).
    //
    // The HTTP adapter fans the erasure call out to BOTH grid-api and
    // game-api so the RGPD "Effacer mes données" surface covers the
    // multiplayer cascade (ADR-0039) in addition to grid hints. When
    // multiplayer is disabled the game-api base URL is left undefined and
    // the adapter degrades to a grid-only call.
    const multiplayerForErase = import.meta.env.VITE_FEATURE_MULTIPLAYER === 'true';
    const sessionClient: SessionClient = {
      ...createHttpSessionClient({
        gridBaseUrl: gridApiBaseUrl,
        gameBaseUrl: multiplayerForErase ? import.meta.env.VITE_GAME_API_BASE_URL : undefined,
      }),
      getSessionId: getOrCreateSessionId,
      clearLocalSession: () => {
        // Sweep all solo-entries keys first; RGPD Art. 17 would orphan per-grid data under the old session id otherwise.
        clearAllSoloEntriesForEverySession();
        clearSession();
        clearTourSeen();
      },
    };

    // Lazy session-id binding makes session rotation (RGPD erase → reseed) transparent to ui/.
    const soloEntriesStorage: SoloEntriesStorage = {
      loadEntries: loadSoloEntries,
      saveLetter: saveSoloLetter,
      loadLocked: loadSoloLockedCells,
      lockCell: saveSoloLockedCell,
      loadHintsUsed: loadSoloHintsUsed,
      recordHintUsed: recordSoloHintUsed,
      clearForPuzzle: clearSoloEntriesForPuzzle,
    };
    const soloEntriesStore: SoloEntriesStore = createSoloEntriesStore({
      getSessionId: getOrCreateSessionId,
      storage: soloEntriesStorage,
    });

    // Onboarding-tour completion flag. Same indirection rationale as
    // SoloEntriesStore — keep the localStorage seam outside ui/.
    const tourSeenStore: TourSeenStore = {
      get: getTourSeen,
      set: setTourSeen,
      clear: clearTourSeen,
    };

    // Cookieless Matomo tracker (ADR-0025). No-op when env vars are unset
    // (local dev / preview / pre-Matomo prod).
    const tracker = createMatomoTracker(readMatomoConfigFromEnv());
    // ADR-0018 §10 — multiplayer ships dark. The lobby route, its
    // adapters, and the session accessor are only instantiated when
    // the runtime flag is on. Production flips this to `'true'` after
    // the game-api Helm chart is live and the smoke tests pass; the
    // flag expires no later than 2026-08-02 per .env.
    // Identity-api adapter. Defaults to the production host so the
    // bundle works even if the env var is unset; preview / dev override
    // via .env.preview / .env.development.local.
    const identityApiBaseUrl =
      import.meta.env.VITE_IDENTITY_API_BASE_URL ?? 'https://auth.wordsparrow.io';
    const authClient = createHttpAuthClient({ baseUrl: identityApiBaseUrl });

    const multiplayer = import.meta.env.VITE_FEATURE_MULTIPLAYER === 'true';
    // Survey-api adapter (ADR-0056). Defaults to the prod host so the
    // bundle works without env config; preview / dev override via env.
    const surveyApiBaseUrl =
      import.meta.env.VITE_SURVEY_API_BASE_URL ?? 'https://survey.wordsparrow.io';
    const surveyClient = createHttpSurveyClient({ baseUrl: surveyApiBaseUrl });
    // Analytics port — closes over the Matomo tracker so the survey
    // route can record `survey_*` custom events without importing
    // `infrastructure/` from `ui/`.
    const analytics = {
      trackEvent: (category: string, action: string, name?: string, value?: number) => {
        tracker.trackEvent(category, action, name, value);
      },
    };
    const baseContext = { authClient, getPseudonym, surveyClient, analytics };
    const context = multiplayer
      ? (() => {
          const gameApiBaseUrl = import.meta.env.VITE_GAME_API_BASE_URL;
          const lobbyClient = createHttpLobbyClient({ baseUrl: gameApiBaseUrl });
          // WebSocket URL derives from the same host: swap http(s) for
          // ws(s) so a single env var configures both adapters.
          const wsBaseUrl = gameApiBaseUrl.replace(/^http/, 'ws');
          // Wrap the bare WebSocket adapter in a backoff-driven reconnect
          // wrapper so an involuntary close (network blip, server restart
          // inside the warm-slot window) is silently retried instead of
          // surfacing the misleading "Connexion perdue" banner. The
          // wrapper exposes the same `GameClient` port; the lobby route
          // sees the `reconnecting` state on `subscribeConnectionState`.
          const gameClient = createReconnectingGameClient({
            inner: createWebSocketGameClient({ wsBaseUrl }),
          });
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
            ...baseContext,
            puzzleRepository,
            puzzleSolver,
            sessionClient,
            soloEntriesStore,
            tourSeenStore,
            lobbyClient,
            gameClient,
            getSession,
            setPseudonym: setPersistedPseudonym,
            lobbyJoinCodeStash: sessionStorageLobbyJoinCodeStash,
          };
        })()
      : { ...baseContext, puzzleRepository, puzzleSolver, sessionClient, soloEntriesStore, tourSeenStore };
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

    // Multiplayer-gated so non-multiplayer bundles don't pull `lobbyClient`
    // through here.
    const onAuthed =
      multiplayer && 'lobbyClient' in context
        ? async (anonSessionId: string) => {
            await context.lobbyClient.rebindLobbySessions(anonSessionId as SessionId);
          }
        : undefined;

    // onCaughtError only: onUncaughtError would double-emit via the window.error handler.
    createRoot(container, {
      onCaughtError: (error, errorInfo) => {
        if (import.meta.env.DEV) {
          // dev-only: React's default console.error firehose.
          console.error('Caught error:', error, errorInfo);
        }
        reportCaughtError(error, 'react-caught');
      },
    }).render(
      <StrictMode>
        <AuthProvider
          authClient={authClient}
          getPseudonym={getPseudonym}
          getLocalSessionId={getOrCreateSessionId}
          onAuthed={onAuthed}
        >
          <App router={router} />
        </AuthProvider>
      </StrictMode>,
    );

    registerServiceWorker();
  });
