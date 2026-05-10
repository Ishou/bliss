// Privacy notice (English). Counterpart to `/confidentialite` (French).
// The French version is canonical; any divergence is a bug.

import { createRoute } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { PrivacyNotice } from '@/ui/components/PrivacyNotice';
import { AppHeader, Footer } from '@/ui/components/layout';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

const pageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  bg: 'bg',
});

const contentSlotStyles = css({
  flex: '1 1 auto',
  display: 'flex',
  flexDirection: 'column',
});

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
    <div className={pageStyles}>
      <AppHeader />
      <div className={contentSlotStyles}>
        <PrivacyNotice lang="en" sessionClient={sessionClient} />
      </div>
      <Footer />
    </div>
  );
}
