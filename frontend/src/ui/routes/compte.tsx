import { createRoute, useNavigate } from '@tanstack/react-router';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { css, cx } from 'styled-system/css';
import { InvalidDisplayNameError, type GetMeResult } from '@/application/auth';
import { useAuth } from '@/ui/components/auth';
import { ContentPage } from '@/ui/components/layout';
import { Button, TextField, useToast } from '@/ui/components/primitives';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

// Phase 5 sub-PR 5 — editable Pseudonyme; read-only providers list.

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

const formStyles = css({ display: 'flex', flexDirection: 'column', gap: 'sm' });
const submitRowStyles = css({ display: 'flex', justifyContent: 'flex-end' });

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
  const { state, refresh } = useAuth();
  const navigate = useNavigate();
  const toast = useToast();
  const [me, setMe] = useState<GetMeResult | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

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
        if (!cancelled) setLoadError('Impossible de charger votre compte. Réessayez.');
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

  if (loadError != null) {
    return (
      <ContentPage>
        <p role="alert" className={statusStyles}>{loadError}</p>
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

  async function onSubmit(e: FormEvent<HTMLFormElement>): Promise<void> {
    e.preventDefault();
    if (!authClient) throw new Error('authClient not wired despite authed state');
    const value = inputRef.current?.value.trim() ?? '';
    setSaveError(null);
    setSaving(true);
    try {
      await authClient.updateMe(value);
      const fresh = await authClient.getMe();
      setMe(fresh);
      await refresh();
      toast.show({ text: 'Pseudonyme mis à jour.' });
    } catch (err) {
      setSaveError(
        err instanceof InvalidDisplayNameError
          ? err.message
          : 'Une erreur est survenue. Réessayez.',
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <ContentPage>
      <article className={articleStyles}>
        <h1 className={pageTitleStyles}>Mon compte</h1>

        <section className={sectionStyles} aria-labelledby="compte-pseudo-title">
          <h2 id="compte-pseudo-title" className={sectionTitleStyles}>
            Pseudonyme
          </h2>
          <form className={formStyles} onSubmit={onSubmit} noValidate>
            <TextField
              ref={inputRef}
              name="displayName"
              label="Pseudonyme"
              defaultValue={me.displayName}
              maxLength={30}
              minLength={1}
              required
              autoComplete="off"
              invalid={saveError != null}
              errorText={saveError ?? undefined}
            />
            <div className={submitRowStyles}>
              <Button type="submit" variant="primary" disabled={saving}>
                {saving ? 'Enregistrement…' : 'Enregistrer'}
              </Button>
            </div>
          </form>
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
