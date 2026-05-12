// `/lobby/:lobbyId` route. Loader bootstraps lobby state via REST; the
// component opens the WebSocket on mount, tears it down on unmount, and
// folds inbound `GameEvent` frames into local state so child components
// read a single source of truth. WaitingRoom / Grid+Timer / EndGameModal
// are mounted exclusively per the lifecycle phase. Registered only when
// the multiplayer flag is on (ADR-0018 §10), so the context fields it
// relies on are guaranteed present at the call site.
//
// Eager half: keeps the route definition, `head()`, and the loader
// (which runs without the lazy chunk loaded). The component, error
// boundary, pending UI, and the entire WebSocket-driven state machine
// live in `./lobby.$lobbyId.lazy` and are loaded on demand via
// `Route.lazy()` — keeping the multiplayer chunk (zag-js, the WS
// adapter consumers, the presence overlay) out of the main bundle when
// the multiplayer flag is off.

import { createRoute } from '@tanstack/react-router';
import type { Lobby, LobbyId } from '@/domain/game';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/lobby/$lobbyId',
  // `pendingMs: 0` — render the lazy half's `pendingComponent` the
  // moment a navigation to the lobby starts, rather than holding the
  // previous route visible until the loader resolves. Without this
  // TanStack Router defaults `pendingMs` to Infinity, which left the
  // Accueil "Mes parties" navigation flashing the home page chrome
  // before swapping to the lobby. The pendingComponent is a thin
  // "Chargement du salon…" status, identical for every entry path
  // (refresh, direct URL, link click), so the user sees stable
  // loading chrome instead of a brief flash of unrelated content.
  pendingMs: 0,
  loader: ({ context, params }): Promise<Lobby> =>
    // Asserted non-null: this route is only registered when the
    // multiplayer flag is on, in which case the composition root
    // guarantees `lobbyClient` is present in context.
    context.lobbyClient!.getLobby(params.lobbyId as LobbyId),
  head: () =>
    buildHead({
      title: 'Salon · WordSparrow',
      description: 'Salon multijoueur WordSparrow.',
      canonical: `${SITE_BASE_URL}/grille`,
      noindex: true,
    }),
}).lazy(() => import('./lobby.$lobbyId.lazy').then((m) => m.Route));
