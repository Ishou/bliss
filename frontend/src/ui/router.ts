import { createRouter } from '@tanstack/react-router';
import type { AppRouterContext } from './routes/__root';
import { Route as RootRoute } from './routes/__root';
import { Route as IndexRoute } from './routes/index';
import { Route as LobbyRoute } from './routes/lobby.$lobbyId';

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
  const children = multiplayer ? [IndexRoute, LobbyRoute] : [IndexRoute];
  const routeTree = RootRoute.addChildren(children);
  return createRouter({ routeTree, context });
}
export type AppRouter = ReturnType<typeof createAppRouter>;

declare module '@tanstack/react-router' {
  interface Register {
    router: AppRouter;
  }
}
