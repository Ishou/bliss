// Privacy notice (English). Counterpart to `/confidentialite` (French).
// The French version is canonical; any divergence is a bug.

import { createRoute } from '@tanstack/react-router';
import { PrivacyNotice } from '@/ui/components/PrivacyNotice';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/privacy',
  head: () => ({
    meta: [
      { title: 'Privacy — Bliss' },
      {
        name: 'description',
        content:
          'Bliss privacy policy: minimal data collection, anonymized audience measurement, right to erasure.',
      },
    ],
  }),
  component: PrivacyRoute,
});

function PrivacyRoute() {
  const { sessionClient } = Route.useRouteContext();
  return <PrivacyNotice lang="en" sessionClient={sessionClient} />;
}
