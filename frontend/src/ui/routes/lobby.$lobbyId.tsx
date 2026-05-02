import { createRoute } from '@tanstack/react-router';
import { useEffect, useState } from 'react';
import { css } from 'styled-system/css';
import { LobbyClientError, type GameEvent } from '@/application/game';
import type { Lobby, LobbyId } from '@/domain/game';
import { Route as RootRoute } from './__root';

// `/lobby/:lobbyId` route — Wave G PR #15. Loader bootstraps lobby
// state via REST; the component opens the WebSocket on mount, tears it
// down on unmount, and folds inbound `GameEvent` frames into local
// state so subsequent waves (WaitingRoom + grid mounting) read a single
// source of truth. WaitingRoom UI, share-URL, pseudonym editor, grid
// integration, and connection banners ship in Wave H (#16, #17, #19).

const pageStyles = css({
  minHeight: '100dvh', display: 'flex', flexDirection: 'column',
  alignItems: 'center', justifyContent: 'center', gap: 'sm',
  padding: 'lg', bg: 'bg', color: 'fg', fontFamily: 'body', textAlign: 'center',
});

const headingStyles = css({
  fontSize: { base: 'xl', md: 'display' }, fontWeight: 'bold',
  letterSpacing: '-0.02em', margin: 0,
});

const detailStyles = css({ fontSize: 'body', margin: 0, color: 'accent' });

const LobbyShell = ({ children }: { children: React.ReactNode }) => (
  <main className={pageStyles}>{children}</main>
);

const LobbyStatus = ({ role, text }: { role: 'status' | 'alert'; text: string }) => (
  <LobbyShell>
    <p className={detailStyles} role={role}>{text}</p>
  </LobbyShell>
);

function LobbyPage() {
  const initialLobby = Route.useLoaderData() as Lobby;
  const { lobbyId } = Route.useParams();
  const { gameClient, getSession } = Route.useRouteContext();
  const [lobby, setLobby] = useState<Lobby>(initialLobby);

  // Single side effect: connect on mount, disconnect on unmount. Connect
  // failures are non-fatal so the loader's snapshot stays rendered until
  // Wave H · #17 ships the connection-state banner.
  useEffect(() => {
    const { sessionId, pseudonym } = getSession();
    const unsubscribe = gameClient.subscribe((event) => {
      setLobby((current) => applyEvent(current, event));
    });
    void gameClient.connect({ lobbyId: lobbyId as LobbyId, sessionId, pseudonym }).catch(() => {});
    return () => { unsubscribe(); gameClient.disconnect(); };
  }, [gameClient, lobbyId, getSession]);

  return (
    <LobbyShell>
      <h1 className={headingStyles}>Salon · {lobbyId}</h1>
      <p className={detailStyles}>
        {lobby.players.length} {lobby.players.length === 1 ? 'joueur' : 'joueurs'}
      </p>
    </LobbyShell>
  );
}

// Folds a server→client `GameEvent` into the locally-cached `Lobby`.
// `gameStarted`/`cellUpdated`/`gameSolved`/`error` pass through — the
// grid and banner UIs that consume them land in Wave H.
function applyEvent(current: Lobby, event: GameEvent): Lobby {
  switch (event.type) {
    case 'lobbyState':
      return {
        players: event.players, ownerSessionId: event.ownerSessionId,
        state: event.state, gridConfig: event.gridConfig, game: event.game,
      };
    case 'playerJoined':
      if (current.players.some((p) => p.sessionId === event.sessionId)) return current;
      return {
        ...current,
        players: [...current.players, {
          sessionId: event.sessionId, pseudonym: event.pseudonym, joinedAt: event.joinedAt,
        }],
      };
    case 'playerLeft':
      return { ...current, players: current.players.filter((p) => p.sessionId !== event.sessionId) };
    case 'playerRenamed':
      return {
        ...current,
        players: current.players.map((p) =>
          p.sessionId === event.sessionId ? { ...p, pseudonym: event.newPseudonym } : p,
        ),
      };
    default:
      return current;
  }
}

function LobbyErrorComponent({ error }: { error: Error }) {
  if (error instanceof LobbyClientError) {
    switch (error.kind) {
      case 'not-found':
        return <LobbyStatus role="alert" text="Salon introuvable." />;
      case 'upstream-unavailable':
        return <LobbyStatus role="alert" text="Serveur indisponible. Réessayez dans un instant." />;
      case 'validation':
      case 'transient':
        return <LobbyStatus role="alert" text="Une erreur est survenue. Réessayez." />;
    }
  }
  return <LobbyStatus role="alert" text="Une erreur est survenue. Réessayez." />;
}

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/lobby/$lobbyId',
  loader: ({ context, params }): Promise<Lobby> =>
    context.lobbyClient.getLobby(params.lobbyId as LobbyId),
  component: LobbyPage,
  pendingComponent: () => <LobbyStatus role="status" text="Chargement du salon…" />,
  errorComponent: LobbyErrorComponent,
  head: () => ({ meta: [{ title: 'Salon · WordSparrow' }] }),
});
