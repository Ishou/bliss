// Lazy half of `/join/$code`. The eager half (`./join.$code.tsx`)
// keeps the route definition, `parseParams`, `loader`, and `head()` —
// all of which the router needs without the lazy chunk loaded. The
// redirect component + error boundary live below and only ship when a
// share-link recipient actually navigates to `/join/<code>`.

import { createLazyRoute, useNavigate } from '@tanstack/react-router';
import { useEffect } from 'react';
import { css } from 'styled-system/css';
import { LobbyClientError } from '@/application/game';
import type { Lobby, LobbyId } from '@/domain/game';
import { AppHeader, Footer } from '@/ui/components/layout';

const pageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  color: 'fg',
  fontFamily: 'body',
});

const mainStyles = css({
  flex: '1 0 auto',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  width: '100%',
  bg: 'bg',
  paddingBlock: 'lg',
});

const messageStyles = css({
  fontSize: 'body',
  margin: 0,
  color: 'accent',
  textAlign: 'center',
});

const errorBlockStyles = css({
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  gap: 'sm',
  textAlign: 'center',
  paddingInline: 'md',
});

const errorMessageStyles = css({
  fontSize: 'body',
  color: 'errorText',
  margin: 0,
});

const errorLinkStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
  textDecoration: 'underline',
  _hover: { color: 'fg' },
});

function JoinCodeRedirect() {
  const lobby = Route.useLoaderData() as Lobby & { readonly id: LobbyId };
  const { code } = Route.useParams();
  const ctx = Route.useRouteContext();
  // The route is registered under the multiplayer flag (router.ts) so
  // this adapter is guaranteed present — same posture as `lobbyClient`.
  const lobbyJoinCodeStash = ctx.lobbyJoinCodeStash!;
  const navigate = useNavigate();

  // One-shot: stash the code so the lobby route's WS-open can pick it
  // up, then replace the URL. The redirect runs AFTER paint so the
  // brief "Connexion à la partie…" is visible — preferable to a flash
  // of nothing on slow connections.
  useEffect(() => {
    lobbyJoinCodeStash.stash(lobby.id, code);
    void navigate({ to: '/lobby/$lobbyId', params: { lobbyId: lobby.id }, replace: true });
  }, [code, lobby.id, lobbyJoinCodeStash, navigate]);

  return (
    <div className={pageStyles}>
      <AppHeader />
      <main className={mainStyles}>
        <p className={messageStyles} role="status">Connexion à la partie…</p>
      </main>
      <Footer />
    </div>
  );
}

function JoinCodeError({ error }: { readonly error: Error }) {
  const detail =
    error instanceof LobbyClientError && error.kind === 'not-found'
      ? 'Code invalide ou partie expirée.'
      : 'Une erreur est survenue. Réessayez.';
  return (
    <div className={pageStyles}>
      <AppHeader />
      <main className={mainStyles}>
        <div className={errorBlockStyles}>
          <p className={errorMessageStyles} role="alert">{detail}</p>
          <a href="/" className={errorLinkStyles}>Retour à l&apos;accueil</a>
        </div>
      </main>
      <Footer />
    </div>
  );
}

export const Route = createLazyRoute('/join/$code')({
  component: JoinCodeRedirect,
  errorComponent: JoinCodeError,
});
