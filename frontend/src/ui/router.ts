import { createRouter } from '@tanstack/react-router';
import type { AppRouterContext } from './routes/__root';
import { Route as RootRoute } from './routes/__root';
import { Route as IndexRoute } from './routes/index';
import { Route as AideRoute } from './routes/aide';
import { Route as LobbyRoute } from './routes/lobby.$lobbyId';
import { Route as ConfidentialiteRoute } from './routes/confidentialite';
import { Route as PrivacyRoute } from './routes/privacy';
import { Route as LegalNoticeRoute } from './routes/mentions-legales';

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
    IndexRoute,
    AideRoute,
    ConfidentialiteRoute,
    PrivacyRoute,
    LegalNoticeRoute,
  ];
  const children = multiplayer ? [...baseChildren, LobbyRoute] : baseChildren;
  const routeTree = RootRoute.addChildren(children);
  return createRouter({ routeTree, context });
}
export type AppRouter = ReturnType<typeof createAppRouter>;

declare module '@tanstack/react-router' {
  interface Register {
    router: AppRouter;
  }
}
