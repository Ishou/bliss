// `/sondage` eager half — route definition + head(); lazy UI in `./sondage.lazy`.

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
