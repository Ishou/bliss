import { createRoute, useNavigate } from '@tanstack/react-router';
import { useEffect } from 'react';
import { css } from 'styled-system/css';
import { LobbyClientError } from '@/application/game';
import type { Lobby, LobbyId } from '@/domain/game';
import { LOBBY_CODE_PATTERN } from '@/domain/game/lobbyCode';
import { AppHeader, Footer } from '@/ui/components/layout';
import { buildHead, SITE_BASE_URL } from '@/ui/seo';
import { Route as RootRoute } from './__root';

// Single-purpose share-link landing — ADR-0027.
//
// The code is the only thing the joiner ever types or pastes; the
// lobbyId is internal plumbing. This route resolves the code via the
// existing `findByCode` adapter (PR #262), stashes the code in
// per-tab sessionStorage, and replaces the URL with `/lobby/$lobbyId`.
// `replace: true` keeps the share link out of the back-stack: a
// streamer's viewer who follows the link doesn't get a "back to
// /join/<code>" affordance.
//
// Reload-after-join doesn't need to revisit `/join/$code`: the lobby
// route's WS join falls through to the sessionId-keyed reconnect
// branch on the server, which bypasses the code check by design.

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

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/join/$code',
  parseParams: (raw) => {
    const code = String(raw.code ?? '').toUpperCase();
    if (!LOBBY_CODE_PATTERN.test(code)) {
      // Throw at parse-time so the route's `errorComponent` renders
      // without first running the loader (which would hit `findByCode`
      // for a value we already know is malformed).
      throw new Error('Code invalide ou partie expirée.');
    }
    return { code };
  },
  loader: ({ context, params }) => context.lobbyClient!.findByCode(params.code),
  component: JoinCodeRedirect,
  errorComponent: JoinCodeError,
  head: () =>
    buildHead({
      title: 'WordSparrow — Rejoindre',
      description: 'Rejoindre une partie WordSparrow.',
      canonical: `${SITE_BASE_URL}/`,
      noindex: true,
    }),
});
