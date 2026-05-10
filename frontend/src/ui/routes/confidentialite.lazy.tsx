// Lazy half of the `/confidentialite` route. The eager half
// (`./confidentialite.tsx`) keeps the route definition + `head()` so
// prerender surfaces per-route metadata without waiting for this chunk.

import { createLazyRoute } from '@tanstack/react-router';
import { PrivacyNotice } from '@/ui/components/PrivacyNotice';
import { ContentPage } from '@/ui/components/layout';

export const Route = createLazyRoute('/confidentialite')({
  component: PrivacyRoute,
});

function PrivacyRoute() {
  const { sessionClient } = Route.useRouteContext();
  return (
    <ContentPage>
      <PrivacyNotice lang="fr" sessionClient={sessionClient} />
    </ContentPage>
  );
}
