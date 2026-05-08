// Privacy notice (French primary). Counterpart to `/privacy` (English).
// Content lives in `@/ui/components/PrivacyNotice`; this file wires
// the route into TanStack Router and wraps it in the standard page
// chrome (header + footer) so the legal pages match the rest of the
// app's layout.

import { createRoute } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { PrivacyNotice } from '@/ui/components/PrivacyNotice';
import { AppHeader, Footer } from '@/ui/components/layout';
import { Route as RootRoute } from './__root';

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

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/confidentialite',
  head: () => ({
    meta: [
      { title: 'Confidentialité — WordSparrow' },
      {
        name: 'description',
        content:
          'Politique de confidentialité de WordSparrow : données minimales, mesure d\'audience anonyme, droit à l\'effacement.',
      },
    ],
  }),
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
