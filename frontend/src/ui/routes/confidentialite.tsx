// Privacy notice (French primary). Counterpart to `/privacy` (English).
// Content lives in `@/ui/components/PrivacyNotice`; this file just wires
// the route into TanStack Router.

import { createRoute } from '@tanstack/react-router';
import { PrivacyNotice } from '@/ui/components/PrivacyNotice';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/confidentialite',
  head: () => ({
    meta: [
      { title: 'Confidentialité — Bliss' },
      {
        name: 'description',
        content:
          'Politique de confidentialité de Bliss : données minimales, mesure d\'audience anonyme, droit à l\'effacement.',
      },
    ],
  }),
  component: PrivacyRoute,
});

function PrivacyRoute() {
  const { sessionClient } = Route.useRouteContext();
  return <PrivacyNotice lang="fr" sessionClient={sessionClient} />;
}
