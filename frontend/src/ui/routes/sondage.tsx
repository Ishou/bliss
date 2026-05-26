// `/sondage` — eager half. Holds the route definition + head() so the
// prerender pass can surface per-route metadata without waiting for the
// lazy chunk. UI (the rating loop, cards, banner) lives in
// `./sondage.lazy` and is loaded on demand via `Route.lazy()`.
//
// noindex: keeping survey collateral out of search indices is the spec
// posture — the page is a contributor surface, not landing copy.

import { createRoute } from '@tanstack/react-router';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/sondage',
  head: () =>
    buildHead({
      title: 'Sondage — WordSparrow',
      description:
        'Aidez à améliorer les indices de mots fléchés en notant les définitions générées.',
      canonical: `${SITE_BASE_URL}/sondage`,
      noindex: true,
    }),
}).lazy(() => import('./sondage.lazy').then((m) => m.Route));
