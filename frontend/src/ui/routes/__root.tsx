import { HeadContent, Outlet, createRootRouteWithContext } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import type { PuzzleRepository } from '@/application';
import type { GameClient, LobbyClient } from '@/application/game';
import type { Pseudonym, SessionId } from '@/domain/game';

// Router context surface — every route loader receives this object as
// `ctx.context`. The composition root (`main.tsx`) is the only place
// that wires concrete adapters, keeping `ui/` free of `infrastructure/`
// imports (ADR-0002 §7). Wave G adds `lobbyClient` (REST bootstrap for
// the lobby route's loader), `gameClient` (WebSocket lifecycle the
// route owns on mount/unmount), and a `getSession` accessor that hides
// the localStorage adapter behind a function reference.
export interface AppSession {
  readonly sessionId: SessionId;
  readonly pseudonym: Pseudonym;
}

export interface AppRouterContext {
  readonly puzzleRepository: PuzzleRepository;
  readonly lobbyClient: LobbyClient;
  readonly gameClient: GameClient;
  readonly getSession: () => AppSession;
}

const errorPageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  padding: 'lg',
  bg: 'bg',
  color: 'fg',
  fontFamily: 'body',
  textAlign: 'center',
});

const errorTitleStyles = css({
  fontSize: { base: 'xl', md: 'display' },
  fontWeight: 'bold',
  letterSpacing: '-0.02em',
  margin: 0,
});

const errorMessageStyles = css({
  marginTop: 'sm',
  fontSize: 'body',
  opacity: 0.8,
});

function RootErrorBoundary() {
  return (
    <main className={errorPageStyles}>
      <h1 className={errorTitleStyles}>Something went wrong.</h1>
      <p className={errorMessageStyles}>Please reload the page to try again.</p>
    </main>
  );
}

export const Route = createRootRouteWithContext<AppRouterContext>()({
  component: () => (
    <>
      <HeadContent />
      <Outlet />
    </>
  ),
  errorComponent: RootErrorBoundary,
});
