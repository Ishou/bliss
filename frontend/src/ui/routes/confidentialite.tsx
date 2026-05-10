// Privacy notice (French primary). Counterpart to `/privacy` (English).
// Eager half: route definition + `head()` only. The page component +
// PrivacyNotice import live in `./confidentialite.lazy` and load on
// demand via `Route.lazy()`.

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
  path: '/confidentialite',
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/confidentialite')!;
    const base = buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/confidentialite`,
      ogImage: `${SITE_BASE_URL}${r.ogImagePath}`,
    });
    return {
      ...base,
      scripts: [
        {
          type: 'application/ld+json',
          children: breadcrumbJsonLd([
            { name: 'Accueil', item: `${SITE_BASE_URL}/` },
            { name: r.title, item: `${SITE_BASE_URL}/confidentialite` },
          ]),
        },
      ],
    };
  },
}).lazy(() => import('./confidentialite.lazy').then((m) => m.Route));
