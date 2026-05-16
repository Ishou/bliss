import { useLayoutEffect } from 'react';
import { css } from 'styled-system/css';
import { ContentPage } from '@/ui/components/layout';
import { INDEXABLE_ROUTES } from '@/ui/seo';

// Pending-state skeleton for `/grilles`. Mirrors the Accueil skeleton's
// pulse animation tokens so the visual language stays unified across
// loading surfaces. The role="status" sentinel is what the prerender
// script (scripts/prerender.ts) waits on before snapshotting the HTML.

const srOnly = css({
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
});

const pulse = css({
  bg: 'surfaceElevated',
  borderRadius: '6px',
  animation: 'wordsparrow-skeleton-pulse 1.4s ease-in-out infinite',
});

const monthBar = css({ width: '40%', height: '24px', marginBlock: 'md' });
const card = css({
  bg: 'surface',
  borderRadius: 'md',
  padding: 'md',
  border: '1px solid token(colors.border)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
  marginBlock: 'sm',
});
const cardTitle = css({ width: '60%', height: '20px' });
const cardBar = css({ width: '100%', height: '14px' });
const cardCta = css({ width: '30%', height: '16px', alignSelf: 'flex-end' });

export function GrillesSkeleton() {
  // Same trick as AccueilSkeleton: TanStack's `head()` runs after the
  // loader resolves, so we set the title imperatively for the prerender
  // pass + crawlers / share-link previews.
  useLayoutEffect(() => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/grilles');
    if (!r) return;
    const previous = document.title;
    document.title = r.title;
    return () => { document.title = previous; };
  }, []);
  return (
    <ContentPage>
      <h1 className={srOnly}>Anciennes grilles</h1>
      <div className={`${pulse} ${monthBar}`} aria-hidden />
      {[0, 1, 2].map((i) => (
        <section className={card} aria-hidden key={i}>
          <div className={`${pulse} ${cardTitle}`} />
          <div className={`${pulse} ${cardBar}`} />
          <div className={`${pulse} ${cardCta}`} />
        </section>
      ))}
      <p className={srOnly} role="status">Chargement…</p>
    </ContentPage>
  );
}
