import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LobbyClientError, type GameClient, type GameEvent, type LobbyClient } from '@/application/game';
import type { PuzzleRepository } from '@/application';
import type { Lobby, LobbyId, Pseudonym, SessionId } from '@/domain/game';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/index';
import { Route as LobbyRoute } from '@/ui/routes/lobby.$lobbyId';

// Wave G PR #15 — `/lobby/:lobbyId` route. Covers loader happy path,
// loader 404, WS connect-on-mount + disconnect-on-unmount, and the
// player-count rendering. Adapter classes are not mocked: the route
// consumes the *port* (`GameClient`/`LobbyClient`), so the test stands
// up the simplest in-memory fakes that satisfy the interface.

const sessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b' as SessionId;
const pseudonym = 'Joueur 1234' as Pseudonym;
const lobbyId = '7gQ2xK9p' as LobbyId;

const baseLobby: Lobby = {
  ownerSessionId: sessionId,
  players: [
    { sessionId, pseudonym, joinedAt: '2026-05-02T15:30:00Z' },
    {
      sessionId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId,
      pseudonym: 'Joueur 5678' as Pseudonym,
      joinedAt: '2026-05-02T15:30:01Z',
    },
  ],
  state: 'WAITING',
  gridConfig: { width: 7, height: 7 },
  game: null,
};

interface FakeGameClient extends GameClient {
  readonly connectCalls: Array<{ lobbyId: LobbyId }>;
  readonly disconnectCalls: { count: number };
}

const makeFakeGameClient = (): FakeGameClient => {
  const subscribers = new Set<(e: GameEvent) => void>();
  const connectCalls: Array<{ lobbyId: LobbyId }> = [];
  const disconnectCalls = { count: 0 };
  return {
    connectCalls,
    disconnectCalls,
    connect: (args) => { connectCalls.push({ lobbyId: args.lobbyId }); return Promise.resolve(); },
    joinLobby: () => {},
    renameSelf: () => {},
    setGridConfig: () => {},
    startGame: () => {},
    cellUpdate: () => {},
    leaveLobby: () => {},
    disconnect: () => { disconnectCalls.count += 1; },
    subscribe: (handler) => { subscribers.add(handler); return () => { subscribers.delete(handler); }; },
  };
};

const stubPuzzleRepository: PuzzleRepository = {
  fetchById: vi.fn().mockRejectedValue(new Error('unused in lobby tests')),
};

const renderLobby = (overrides: { lobbyClient?: Partial<LobbyClient>; gameClient?: GameClient }) => {
  const lobbyClient: LobbyClient = {
    createLobby: vi.fn().mockRejectedValue(new Error('unused')),
    getLobby: vi.fn().mockResolvedValue(baseLobby),
    ...overrides.lobbyClient,
  };
  const gameClient = overrides.gameClient ?? makeFakeGameClient();
  const routeTree = RootRoute.addChildren([IndexRoute, LobbyRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`/lobby/${lobbyId}`] }),
    context: {
      puzzleRepository: stubPuzzleRepository,
      lobbyClient,
      gameClient,
      getSession: () => ({ sessionId, pseudonym }),
    },
  });
  return { ...render(<RouterProvider router={router} />), lobbyClient, gameClient };
};

afterEach(() => vi.restoreAllMocks());

describe('Lobby route loader', () => {
  it('renders the lobby id and player count from the loader payload', async () => {
    const getLobby = vi.fn().mockResolvedValue(baseLobby);
    renderLobby({ lobbyClient: { getLobby } });
    await screen.findByRole('heading', { name: /Salon · 7gQ2xK9p/ });
    expect(getLobby).toHaveBeenCalledTimes(1);
    expect(getLobby).toHaveBeenCalledWith(lobbyId);
    expect(screen.getByText('2 joueurs')).toBeInTheDocument();
  });

  it('renders "Salon introuvable" when the lobby client throws kind=not-found', async () => {
    const notFound = new LobbyClientError({
      kind: 'not-found', status: 404, problem: null, message: 'No lobby with id 7gQ2xK9p',
    });
    renderLobby({ lobbyClient: { getLobby: vi.fn().mockRejectedValue(notFound) } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Salon introuvable.');
    expect(screen.queryByRole('heading', { name: /Salon · / })).toBeNull();
  });

  it('renders "Serveur indisponible" when the lobby client throws kind=upstream-unavailable', async () => {
    const unavailable = new LobbyClientError({
      kind: 'upstream-unavailable', status: null, problem: null, message: 'fetch failed',
    });
    renderLobby({ lobbyClient: { getLobby: vi.fn().mockRejectedValue(unavailable) } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/Serveur indisponible/);
  });
});

describe('Lobby route WebSocket lifecycle', () => {
  it('connects to the GameClient on mount with the route lobby id', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /Salon · 7gQ2xK9p/ });
    expect(gameClient.connectCalls).toEqual([{ lobbyId }]);
  });

  it('disconnects the GameClient on unmount', async () => {
    const gameClient = makeFakeGameClient();
    const { unmount } = renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /Salon · 7gQ2xK9p/ });
    expect(gameClient.disconnectCalls.count).toBe(0);
    unmount();
    expect(gameClient.disconnectCalls.count).toBe(1);
  });
});
