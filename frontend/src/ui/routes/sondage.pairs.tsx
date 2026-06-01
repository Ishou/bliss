// `/sondage/pairs` → `/contribuer/pairs` redirect; preserves old bookmarks after the route rename.

import { createRoute, redirect } from '@tanstack/react-router';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/sondage/pairs',
  beforeLoad: () => {
    throw redirect({ to: '/contribuer/pairs' });
  },
});
