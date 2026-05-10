// Lazy half of the `/confidentialite` route. The eager half
// (`./confidentialite.tsx`) keeps the route definition + `head()` so
// prerender surfaces per-route metadata without waiting for this chunk.

import { createLazyRoute } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { PrivacyNotice } from '@/ui/components/PrivacyNotice';
import { AppHeader, Footer } from '@/ui/components/layout';

const pageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  bg: 'bg',
});

// `PrivacyNotice` already renders its own `<main>`, so we wrap it in a
// flex column that lets the content take the available height — the
// footer always sits at the bottom even when the notice is short.
const contentSlotStyles = css({
  flex: '1 1 auto',
  display: 'flex',
  flexDirection: 'column',
});

export const Route = createLazyRoute('/confidentialite')({
  component: PrivacyRoute,
});

function PrivacyRoute() {
  const { sessionClient } = Route.useRouteContext();
  return (
    <div className={pageStyles}>
      <AppHeader />
      <div className={contentSlotStyles}>
        <PrivacyNotice lang="fr" sessionClient={sessionClient} />
      </div>
      <Footer />
    </div>
  );
}
