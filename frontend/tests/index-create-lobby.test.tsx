import { act, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PuzzleRepository } from '@/application';
import { LobbyClientError, type GameClient, type LobbyClient } from '@/application/game';
import type { Puzzle } from '@/domain';
import type { Lobby, LobbyId, Pseudonym, SessionId } from '@/domain/game';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/index';
import { Route as LobbyRoute } from '@/ui/routes/lobby.$lobbyId';

// Covers the "Créer une partie multijoueur" button: flag gate,
// session-keyed `createLobby` call, post-success navigation,
// error-banner copy per `LobbyClientError.kind`, and the disabled
// pending state. Adapters are not mocked: the route consumes the
// *port* (`LobbyClient`), so the test stands up the simplest in-memory
// fakes that satisfy the interface.

const sessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b' as SessionId;
const pseudonym = 'Joueur 1234' as Pseudonym;
const createdLobbyId = '7gQ2xK9p' as LobbyId;
const buttonName = 'Créer une partie multijoueur';

const samplePuzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 'WordSparrow', language: 'fr', width: 1, height: 1,
  cells: [{ kind: 'letter', position: { row: 0, col: 0 }, entry: '' }],
};

// Plain function — `vi.restoreAllMocks()` would erase a `mockResolvedValue`.
const stubPuzzleRepository: PuzzleRepository = {
  fetchById: () => Promise.resolve(samplePuzzle),
};

const baseCreatedLobby: Lobby & { readonly id: LobbyId } = {
  id: createdLobbyId,
  ownerSessionId: sessionId,
  players: [{ sessionId, pseudonym, joinedAt: '2026-05-02T15:30:00Z' }],
  state: 'WAITING', gridConfig: { width: 7, height: 7 }, game: null,
};

// Minimal `GameClient` — the lobby route mounts on success-path
// navigation and will call `subscribe`/`connect` on mount; both are
// no-ops here so the destination render does not blow up.
const stubGameClient: GameClient = {
  connect: () => Promise.resolve(),
  joinLobby: () => {}, renameSelf: () => {}, setGridConfig: () => {},
  startGame: () => {}, cellUpdate: () => {}, leaveLobby: () => {},
  disconnect: () => {}, subscribe: () => () => {},
  subscribeConnectionState: () => () => {},
};

const renderHome = (overrides: { lobbyClient?: Partial<LobbyClient> } = {}) => {
  const lobbyClient: LobbyClient = {
    createLobby: vi.fn().mockResolvedValue(baseCreatedLobby),
    getLobby: vi.fn().mockResolvedValue(baseCreatedLobby),
    ...overrides.lobbyClient,
  };
  // Mounting the lobby route alongside index lets the success-path
  // navigation actually render the destination.
  const routeTree = RootRoute.addChildren([IndexRoute, LobbyRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/'] }),
    context: {
      puzzleRepository: stubPuzzleRepository,
      lobbyClient,
      gameClient: stubGameClient,
      getSession: () => ({ sessionId, pseudonym }),
    },
  });
  return { ...render(<RouterProvider router={router} />), lobbyClient };
};

const clickButton = async (label = buttonName) => {
  const button = screen.getByRole('button', { name: label });
  await act(async () => { button.click(); });
  return button;
};

afterEach(() => { vi.unstubAllEnvs(); vi.restoreAllMocks(); });

describe('Index route — multiplayer flag gate', () => {
  it('does not render the create-lobby button when the flag is off', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'false');
    renderHome();
    await screen.findByRole('grid');
    expect(screen.queryByRole('button', { name: buttonName })).toBeNull();
  });

  it('renders the create-lobby button when the flag is on', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true');
    renderHome();
    await screen.findByRole('grid');
    expect(screen.getByRole('button', { name: buttonName })).toBeInTheDocument();
  });
});

describe('Index route — create-lobby button click', () => {
  beforeEach(() => { vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true'); });

  it('calls createLobby with the current session on click', async () => {
    const { lobbyClient } = renderHome();
    await screen.findByRole('grid');
    await clickButton();
    expect(lobbyClient.createLobby).toHaveBeenCalledTimes(1);
    expect(lobbyClient.createLobby).toHaveBeenCalledWith({
      ownerSessionId: sessionId, ownerPseudonym: pseudonym,
    });
  });

  it('navigates to /lobby/:id on success', async () => {
    renderHome();
    await screen.findByRole('grid');
    await clickButton();
    // Both routes render the WordSparrow heading, so the player-count
    // line (rendered only by the lobby route, with the seed pseudonym
    // present in `baseCreatedLobby.players`) is the unambiguous witness
    // that navigation completed and the destination route mounted.
    await screen.findByText('1 joueur');
  });

  it.each<[LobbyClientError['kind'], number | null, RegExp]>([
    ['upstream-unavailable', null, /Service indisponible/],
    ['validation', 400, /Une erreur est survenue\. Réessayez\./],
    ['transient', 503, /Une erreur est survenue\. Réessayez\./],
  ])('renders matching copy on kind=%s', async (kind, status, copyMatch) => {
    const err = new LobbyClientError({ kind, status, problem: null, message: 'boom' });
    renderHome({ lobbyClient: { createLobby: vi.fn().mockRejectedValue(err) } });
    await screen.findByRole('grid');
    await clickButton();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(copyMatch);
  });

  it('disables the button while the createLobby request is in flight', async () => {
    let resolveCreate: ((value: Lobby & { readonly id: LobbyId }) => void) | null = null;
    const createLobby = vi.fn().mockImplementation(
      () => new Promise<Lobby & { readonly id: LobbyId }>((resolve) => { resolveCreate = resolve; }),
    );
    renderHome({ lobbyClient: { createLobby } });
    await screen.findByRole('grid');
    expect(screen.getByRole('button', { name: buttonName })).not.toBeDisabled();
    await clickButton();
    // Pending: the same button is now disabled with the pending label.
    const pendingButton = screen.getByRole('button', { name: 'Création…' });
    expect(pendingButton).toBeDisabled();
    // Settle so the test does not leak a pending promise.
    await act(async () => { resolveCreate?.(baseCreatedLobby); });
  });
});
