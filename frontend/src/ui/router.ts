import { createRouter } from '@tanstack/react-router';
import type { AppRouterContext } from './routes/__root';
import { Route as RootRoute } from './routes/__root';
import { Route as AccueilRoute } from './routes/accueil';
import { Route as GrilleRoute } from './routes/grille';
import { Route as GrillesRoute } from './routes/grilles';
import { Route as AideRoute } from './routes/aide';
import { Route as CompteRoute } from './routes/compte';
import { Route as JoinRoute } from './routes/join.$code';
import { Route as LobbyRoute } from './routes/lobby.$lobbyId';
import { Route as ConfidentialiteRoute } from './routes/confidentialite';
import { Route as PrivacyRoute } from './routes/privacy';
import { Route as LegalNoticeRoute } from './routes/mentions-legales';
import { Route as SondageRoute } from './routes/sondage';
import { Route as SondagePairsRoute } from './routes/sondage.pairs';

// Composition root supplies `context`. Keeping `createAppRouter` a
// factory means `ui/` never instantiates `infrastructure/` directly
// (ADR-0002 §7). The `multiplayer` flag (ADR-0018 §10) gates the lobby
// route so it stays unreachable in environments where game-api is not
// yet deployed.
export interface CreateAppRouterOptions {
  readonly context: AppRouterContext;
  readonly multiplayer: boolean;
}

export function createAppRouter({ context, multiplayer }: CreateAppRouterOptions) {
  const baseChildren = [
    AccueilRoute,
    GrilleRoute,
    GrillesRoute,
    AideRoute,
    CompteRoute,
    ConfidentialiteRoute,
    PrivacyRoute,
    LegalNoticeRoute,
    SondageRoute,
    SondagePairsRoute,
  ];
  // Multiplayer-flag-gated routes: lobby + the `/join/$code` share-link
  // landing both require the game-api adapter on the router context.
  const children = multiplayer ? [...baseChildren, JoinRoute, LobbyRoute] : baseChildren;
  const routeTree = RootRoute.addChildren(children);
  return createRouter({ routeTree, context });
}
export type AppRouter = ReturnType<typeof createAppRouter>;

declare module '@tanstack/react-router' {
  interface Register {
    router: AppRouter;
  }
}
