import { createRoute, useNavigate } from '@tanstack/react-router';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { css, cx } from 'styled-system/css';
import { InvalidDisplayNameError, type AuthClient, type GetMeResult } from '@/application/auth';
import { useAuth } from '@/ui/components/auth';
import { ContentPage } from '@/ui/components/layout';
import { Button, Dialog, DialogDescription, TextField, useToast } from '@/ui/components/primitives';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

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

const dangerSectionStyles = css({
  bg: 'surface', borderRadius: 'md', border: '1px solid token(colors.errorText)',
  padding: 'lg', display: 'flex', flexDirection: 'column', gap: 'sm',
});
const dangerTitleStyles = css({
  fontSize: 'lg', fontWeight: 'semibold', margin: 0, color: 'errorText',
});
const dangerButtonStyles = css({
  bg: 'transparent', color: 'errorText',
  border: '1px solid token(colors.errorText)',
  _hover: { bg: 'errorBg' },
});
const dialogActionsStyles = css({
  display: 'flex', gap: 'sm', flexWrap: 'wrap', justifyContent: 'flex-end', marginTop: 'sm',
});

function formatLinkedDate(iso: string): string {
  const date = new Date(iso);
  return date.toLocaleDateString('fr-FR', {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  });
}

function DangerZone({
  me,
  authClient,
  onDeleted,
  onDeleteStart,
}: {
  readonly me: GetMeResult;
  readonly authClient: AuthClient;
  readonly onDeleted: () => Promise<void>;
  readonly onDeleteStart: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState('');
  const [deleting, setDeleting] = useState(false);
  const navigate = useNavigate();
  const toast = useToast();

  const canConfirm = typed === me.displayName && !deleting;

  function onClose(): void {
    if (deleting) return;
    setOpen(false);
    setTyped('');
  }

  async function onConfirm(): Promise<void> {
    setDeleting(true);
    try {
      await authClient.deleteMe();
    } catch {
      toast.show({ text: 'La suppression a échoué. Réessayez.', tone: 'error' });
      setDeleting(false);
      return;
    }
    onDeleteStart();
    toast.show({ text: 'Compte supprimé.', tone: 'info' });
    await onDeleted().catch(() => { /* refresh failure is non-fatal; account is gone */ });
    setOpen(false);
    setTyped('');
    void navigate({ to: '/' });
  }

  return (
    <section className={dangerSectionStyles} aria-labelledby="compte-danger-title">
      <h2 id="compte-danger-title" className={dangerTitleStyles}>Zone de danger</h2>
      <p className={statusStyles}>
        La suppression de votre compte est immédiate et définitive.
      </p>
      <div className={submitRowStyles}>
        <Button
          variant="secondary"
          className={dangerButtonStyles}
          onClick={() => setOpen(true)}
        >
          Supprimer mon compte
        </Button>
      </div>
      <Dialog
        open={open}
        onClose={onClose}
        title="Supprimer définitivement votre compte"
      >
        <DialogDescription>
          Cette action est irréversible. Tapez votre pseudonyme (<strong>{me.displayName}</strong>) pour confirmer.
        </DialogDescription>
        <TextField
          label="Confirmation du pseudonyme"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          autoComplete="off"
          disabled={deleting}
        />
        <div className={dialogActionsStyles}>
          <Button variant="secondary" onClick={onClose} disabled={deleting}>
            Annuler
          </Button>
          <Button
            variant="primary"
            className={dangerButtonStyles}
            onClick={onConfirm}
            disabled={!canConfirm}
          >
            {deleting ? 'Suppression…' : 'Supprimer'}
          </Button>
        </div>
      </Dialog>
    </section>
  );
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
  // Set by DangerZone when a successful delete is in flight, so the
  // anon-guard effect below doesn't clobber the "Compte supprimé." toast
  // with the "Connectez-vous..." redirect toast when state flips to anon.
  const deletedRef = useRef(false);

  // Anon guard: state-driven redirect (Phase 5 §Testing). No beforeLoad.
  useEffect(() => {
    if (state.status === 'anon' && !deletedRef.current) {
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

  if (!authClient) throw new Error('authClient not wired despite authed state');
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

        <DangerZone
          me={me}
          authClient={authClient}
          onDeleted={refresh}
          onDeleteStart={() => { deletedRef.current = true; }}
        />
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
