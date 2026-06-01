// `/sondage` → `/contribuer` redirect; preserves old bookmarks after the rating surface was renamed.

import { createRoute, redirect } from '@tanstack/react-router';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/sondage',
  beforeLoad: () => {
    throw redirect({ to: '/contribuer' });
  },
});
