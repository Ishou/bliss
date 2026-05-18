import { createRoute, useNavigate } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { css, cx } from 'styled-system/css';
import type { GetMeResult } from '@/application/auth';
import { useAuth } from '@/ui/components/auth';
import { ContentPage } from '@/ui/components/layout';
import { useToast } from '@/ui/components/primitives';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

// Phase 5 sub-PR 4 — /compte foundation: read-only Pseudonyme + providers.

const articleStyles = css({
  display: 'flex', flexDirection: 'column', gap: 'lg', width: '100%', maxWidth: '640px',
});
const pageTitleStyles = css({
  fontSize: { base: 'xl', md: 'display' }, letterSpacing: '-0.02em', margin: 0, color: 'fg',
});
const sectionStyles = css({
  bg: 'surface', borderRadius: 'md', border: '1px solid token(colors.border)',
  padding: 'lg', display: 'flex', flexDirection: 'column', gap: 'sm',
});
const sectionTitleStyles = css({
  fontSize: 'lg', fontWeight: 'semibold', margin: 0, color: 'fg',
});
const fieldValueStyles = css({ fontSize: 'body', color: 'fg', margin: 0 });
const providerListStyles = css({
  display: 'flex', flexDirection: 'column', gap: 'sm', margin: 0, padding: 0, listStyle: 'none',
});
const providerRowStyles = css({
  display: 'flex', alignItems: 'center', gap: 'sm', paddingBlock: 'xs', color: 'fg', fontSize: 'body',
});
const providerRowDisabledStyles = css({ opacity: 0.55, color: 'fgMuted' });
const badgeStyles = css({
  display: 'inline-flex', alignItems: 'center', fontFamily: 'body', fontSize: 'xs',
  fontWeight: 'semibold', color: 'accent', border: '1px solid token(colors.accent)',
  borderRadius: 'sm', paddingInline: '6px', paddingBlock: '2px',
});
const statusStyles = css({ fontSize: 'body', color: 'fgMuted', margin: 0 });

function formatLinkedDate(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleDateString('fr-FR', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });
}

function ComptePage() {
  const { authClient } = Route.useRouteContext();
  const { state } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const [me, setMe] = useState<GetMeResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Anon guard: state-driven redirect (Phase 5 §Testing). No beforeLoad.
  useEffect(() => {
    if (state.status === 'anon') {
      toast.show({
        text: 'Connectez-vous pour accéder à votre compte.',
        tone: 'info',
      });
      void navigate({ to: '/' });
    }
  }, [state.status, navigate, toast]);

  useEffect(() => {
    if (state.status !== 'authed') return;
    if (!authClient) throw new Error('authClient not wired despite authed state');
    let cancelled = false;
    authClient
      .getMe()
      .then((result) => {
        if (!cancelled) setMe(result);
      })
      .catch(() => {
        if (!cancelled) setError('Impossible de charger votre compte. Réessayez.');
      });
    return () => { cancelled = true; };
  }, [authClient, state.status]);

  if (state.status === 'loading') {
    return (
      <ContentPage>
        <p role="status" className={statusStyles}>Chargement…</p>
      </ContentPage>
    );
  }
  if (state.status !== 'authed') return null;

  if (error != null) {
    return (
      <ContentPage>
        <p role="alert" className={statusStyles}>{error}</p>
      </ContentPage>
    );
  }

  if (me == null) {
    return (
      <ContentPage>
        <p role="status" className={statusStyles}>Chargement…</p>
      </ContentPage>
    );
  }

  const google = me.providers.find((p) => p.provider === 'google');

  return (
    <ContentPage>
      <article className={articleStyles}>
        <h1 className={pageTitleStyles}>Mon compte</h1>

        <section className={sectionStyles} aria-labelledby="compte-pseudo-title">
          <h2 id="compte-pseudo-title" className={sectionTitleStyles}>
            Pseudonyme
          </h2>
          <p className={fieldValueStyles}>{me.displayName}</p>
        </section>

        <section className={sectionStyles} aria-labelledby="compte-providers-title">
          <h2 id="compte-providers-title" className={sectionTitleStyles}>
            Comptes liés
          </h2>
          <ul className={providerListStyles}>
            {google != null ? (
              <li className={providerRowStyles}>
                <span>Google · lié le {formatLinkedDate(google.linkedAt)}</span>
                {google.emailOptIn ? (
                  <span className={badgeStyles}>Reçoit les emails</span>
                ) : null}
              </li>
            ) : null}
            <li className={cx(providerRowStyles, providerRowDisabledStyles)}>
              Apple · bientôt disponible
            </li>
          </ul>
        </section>
      </article>
    </ContentPage>
  );
}

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/compte',
  head: () =>
    buildHead({
      title: 'Mon compte — WordSparrow',
      description: 'Gérez votre pseudonyme et vos comptes liés sur WordSparrow.',
      canonical: `${SITE_BASE_URL}/compte`,
      noindex: true,
    }),
  component: ComptePage,
});
