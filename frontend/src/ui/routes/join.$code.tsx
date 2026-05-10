// Single-purpose share-link landing — ADR-0027.
//
// The code is the only thing the joiner ever types or pastes; the
// lobbyId is internal plumbing. This route resolves the code via the
// existing `findByCode` adapter (PR #262), stashes the code in
// per-tab sessionStorage, and replaces the URL with `/lobby/$lobbyId`.
// `replace: true` keeps the share link out of the back-stack: a
// streamer's viewer who follows the link doesn't get a "back to
// /join/<code>" affordance.
//
// Reload-after-join doesn't need to revisit `/join/$code`: the lobby
// route's WS join falls through to the sessionId-keyed reconnect
// branch on the server, which bypasses the code check by design.
//
// Eager half: keeps the route definition, `parseParams` (so a
// malformed code rejects without running the loader), the loader
// (which resolves the code via `findByCode` before the lazy chunk is
// fetched), and `head()`. The redirect component + error boundary
// live in `./join.$code.lazy` and load on demand.

import { createRoute } from '@tanstack/react-router';
import { LOBBY_CODE_PATTERN } from '@/domain/game/lobbyCode';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/join/$code',
  parseParams: (raw) => {
    const code = String(raw.code ?? '').toUpperCase();
    if (!LOBBY_CODE_PATTERN.test(code)) {
      // Throw at parse-time so the route's `errorComponent` renders
      // without first running the loader (which would hit `findByCode`
      // for a value we already know is malformed).
      throw new Error('Code invalide ou partie expirée.');
    }
    return { code };
  },
  loader: ({ context, params }) => context.lobbyClient!.findByCode(params.code),
  head: () =>
    buildHead({
      title: 'WordSparrow — Rejoindre',
      description: 'Rejoindre une partie WordSparrow.',
      canonical: `${SITE_BASE_URL}/`,
      noindex: true,
    }),
}).lazy(() => import('./join.$code.lazy').then((m) => m.Route));
