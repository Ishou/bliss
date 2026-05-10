// Privacy notice (English). Counterpart to `/confidentialite` (French).
// The French version is canonical; any divergence is a bug.

import { createRoute } from '@tanstack/react-router';
import { PrivacyNotice } from '@/ui/components/PrivacyNotice';
import { ContentPage } from '@/ui/components/layout';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/privacy',
  head: () =>
    buildHead({
      title: 'Privacy — WordSparrow',
      description:
        'WordSparrow privacy policy: minimal data collection, anonymized audience measurement, right to erasure.',
      canonical: `${SITE_BASE_URL}/privacy`,
      noindex: true,
    }),
  component: PrivacyRoute,
});

function PrivacyRoute() {
  const { sessionClient } = Route.useRouteContext();
  return (
    <ContentPage>
      <PrivacyNotice lang="en" sessionClient={sessionClient} />
    </ContentPage>
  );
}
