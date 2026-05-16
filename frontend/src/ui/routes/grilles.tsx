import { createRoute } from '@tanstack/react-router';
import { GrillesPage } from '@/ui/components/grilles/GrillesPage';
import { GrillesSkeleton } from '@/ui/components/grilles/GrillesSkeleton';
import { buildHead, INDEXABLE_ROUTES, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

// `/grilles` — archive of past dailies. Loader fetches the first DESC
// page (newest 100); the page component owns subsequent "Charger mois
// précédent" appends and per-row progress derivation from the
// session-scoped solo store. Route file stays a thin shell so the
// reusable logic lives under `ui/components/grilles/`.
function GrillesRoute() {
  const ctx = Route.useRouteContext();
  const initialPage = Route.useLoaderData();
  return (
    <GrillesPage
      initialPage={initialPage}
      puzzleRepository={ctx.puzzleRepository}
      soloEntriesStore={ctx.soloEntriesStore}
    />
  );
}

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/grilles',
  loader: ({ context }) => context.puzzleRepository.listDailySummaries(),
  component: GrillesRoute,
  // pendingMs 200 ms matches Accueil: fast navs skip the skeleton, slow
  // navs / cold loads surface it. The prerender script waits on the
  // skeleton's role="status" sentinel before snapshotting HTML.
  pendingMs: 200,
  pendingComponent: GrillesSkeleton,
  // Loader failures fall back to the skeleton plus the calm empty
  // status the page renders when it has no items — the archive is a
  // tertiary surface and a global error boundary would feel heavy.
  errorComponent: () => <GrillesSkeleton />,
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/grilles')!;
    return buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/grilles`,
      ogImage: `${SITE_BASE_URL}${r.ogImagePath}`,
    });
  },
});
