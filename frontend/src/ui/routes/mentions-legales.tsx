// Eager half of `/mentions-legales`: route definition + `head()` only.
// The page component + Panda CSS layout chrome live in
// `./mentions-legales.lazy` and load on demand via `Route.lazy()`.

import { createRoute } from '@tanstack/react-router';
import {
  breadcrumbJsonLd,
  buildHead,
  INDEXABLE_ROUTES,
  SITE_BASE_URL,
} from '@/ui/seo';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/mentions-legales',
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/mentions-legales')!;
    const base = buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/mentions-legales`,
      ogImage: `${SITE_BASE_URL}${r.ogImagePath}`,
    });
    return {
      ...base,
      scripts: [
        {
          type: 'application/ld+json',
          children: breadcrumbJsonLd([
            { name: 'Accueil', item: `${SITE_BASE_URL}/` },
            { name: r.title, item: `${SITE_BASE_URL}/mentions-legales` },
          ]),
        },
      ],
    };
  },
}).lazy(() => import('./mentions-legales.lazy').then((m) => m.Route));
