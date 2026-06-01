// `/contribuer` eager half — route definition + head(); lazy UI in `./contribuer.lazy`.

import { createRoute } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { ContentPage } from '@/ui/components/layout';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

const skeletonArticleStyles = css({
  display: 'flex', flexDirection: 'column', gap: 'lg', width: '100%', maxWidth: '720px',
});
const skeletonHeadingStyles = css({
  fontSize: { base: 'xl', md: 'display' }, fontWeight: 'bold',
  letterSpacing: '-0.02em', margin: 0, color: 'fg',
});
const skeletonIntroStyles = css({ fontSize: 'body', color: 'fgMuted', margin: 0 });
const skeletonStatusStyles = css({ fontSize: 'body', color: 'fgMuted', margin: 0 });

// pendingMs=0 + this skeleton stops the lazy-chunk flash where the previous route's chrome stayed visible.
function ContribuerSkeleton() {
  return (
    <ContentPage>
      <article className={skeletonArticleStyles}>
        <h1 className={skeletonHeadingStyles}>Campagne de qualité des indices</h1>
        <p className={skeletonIntroStyles}>
          Notez la qualité des définitions en un clic : mauvaise, à passer, ou bonne.
          Vos retours alimentent la sélection des indices.
        </p>
        <p className={skeletonStatusStyles} role="status">Chargement…</p>
      </article>
    </ContentPage>
  );
}

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/contribuer',
  pendingMs: 0,
  pendingComponent: ContribuerSkeleton,
  head: () =>
    buildHead({
      title: 'Campagne — WordSparrow',
      description:
        'Aidez à améliorer les indices de mots fléchés en notant les définitions générées.',
      canonical: `${SITE_BASE_URL}/contribuer`,
      noindex: true,
    }),
}).lazy(() => import('./contribuer.lazy').then((m) => m.Route));
