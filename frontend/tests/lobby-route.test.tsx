import { act, fireEvent, render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  LobbyClientError,
  type ConnectionState,
  type GameClient,
  type GameEvent,
  type LobbyClient,
} from '@/application/game';
import type { PuzzleRepository } from '@/application';
import type {
  CellEntry,
  GamePuzzle,
  GridConfig,
  Letter,
  Lobby,
  LobbyId,
  Pseudonym,
  SessionId,
} from '@/domain/game';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/index';
import { Route as LobbyRoute } from '@/ui/routes/lobby.$lobbyId';

// `/lobby/:lobbyId` route tests. Covers loader happy path, loader 404,
// WS connect-on-mount + disconnect-on-unmount, and player-count
// rendering. Adapter classes are not mocked: the route consumes the
// *port* (`GameClient`/`LobbyClient`), so the test stands up the
// simplest in-memory fakes that satisfy the interface.

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
  // Recorded send-action calls. Wave H integration tests assert on
  // these so a callback rewire doesn't silently degrade to a no-op.
  readonly renameCalls: Pseudonym[];
  readonly setGridConfigCalls: GridConfig[];
  readonly startGameCalls: { count: number };
  readonly cellUpdateCalls: Array<{ row: number; column: number; letter: Letter | null }>;
  // Outbound presence calls from the local user's focus / direction
  // changes. Wave III asserts the route wires `gameClient.cellFocus`
  // into Grid's `onLocalFocusChange` prop end-to-end.
  readonly cellFocusCalls: Array<{
    row: number | null;
    column: number | null;
    direction: 'across' | 'down' | null;
  }>;
  // Number of currently-attached event subscribers — lets tests assert
  // the route's `unsubscribe` cleanup ran on unmount.
  readonly subscriberCount: () => number;
  // Fan event out to every attached subscriber. Mirrors how the real
  // WebSocket adapter would deliver a server→client frame, so tests can
  // exercise the route's `applyEvent` reducer end-to-end.
  readonly dispatch: (event: GameEvent) => void;
  // Push a connection-state transition through every connection-state
  // subscriber. The real adapter primes a freshly-attached subscriber
  // with the current state synchronously (see WebSocketGameClient
  // `subscribeConnectionState`); the fake mirrors that priming behavior
  // so the lobby route's banner mirrors the real lifecycle.
  readonly dispatchConnectionState: (state: ConnectionState) => void;
}

const makeFakeGameClient = (): FakeGameClient => {
  const subscribers = new Set<(e: GameEvent) => void>();
  const connectionSubscribers = new Set<(s: ConnectionState) => void>();
  let connectionState: ConnectionState = 'connecting';
  const connectCalls: Array<{ lobbyId: LobbyId }> = [];
  const disconnectCalls = { count: 0 };
  const renameCalls: Pseudonym[] = [];
  const setGridConfigCalls: GridConfig[] = [];
  const startGameCalls = { count: 0 };
  const cellUpdateCalls: Array<{ row: number; column: number; letter: Letter | null }> = [];
  const cellFocusCalls: Array<{
    row: number | null;
    column: number | null;
    direction: 'across' | 'down' | null;
  }> = [];
  return {
    connectCalls,
    disconnectCalls,
    renameCalls,
    setGridConfigCalls,
    startGameCalls,
    cellUpdateCalls,
    cellFocusCalls,
    subscriberCount: () => subscribers.size,
    dispatch: (event) => { for (const s of [...subscribers]) s(event); },
    dispatchConnectionState: (state) => {
      connectionState = state;
      for (const s of [...connectionSubscribers]) s(state);
    },
    connect: (args) => { connectCalls.push({ lobbyId: args.lobbyId }); return Promise.resolve(); },
    joinLobby: () => {},
    renameSelf: (pseudonym) => { renameCalls.push(pseudonym); },
    setGridConfig: (config) => { setGridConfigCalls.push(config); },
    startGame: () => { startGameCalls.count += 1; },
    cellUpdate: (row, column, letter) => { cellUpdateCalls.push({ row, column, letter }); },
    cellFocus: (row, column, direction) => { cellFocusCalls.push({ row, column, direction }); },
    leaveLobby: () => {},
    disconnect: () => { disconnectCalls.count += 1; },
    subscribe: (handler) => { subscribers.add(handler); return () => { subscribers.delete(handler); }; },
    // Match the real adapter: prime synchronously with the current
    // state so a freshly-mounted banner reads it immediately.
    subscribeConnectionState: (handler) => {
      connectionSubscribers.add(handler);
      handler(connectionState);
      return () => { connectionSubscribers.delete(handler); };
    },
  };
};

const stubPuzzleRepository: PuzzleRepository = {
  fetchById: vi.fn().mockRejectedValue(new Error('unused in lobby tests')),
};

interface RenderLobbyOverrides {
  readonly lobbyClient?: Partial<LobbyClient>;
  readonly gameClient?: GameClient;
  readonly initialLobby?: Lobby;
  readonly setPseudonym?: (pseudonym: Pseudonym) => void;
}

const renderLobby = (overrides: RenderLobbyOverrides) => {
  const lobbyClient: LobbyClient = {
    createLobby: vi.fn().mockRejectedValue(new Error('unused')),
    getLobby: vi.fn().mockResolvedValue(overrides.initialLobby ?? baseLobby),
    ...overrides.lobbyClient,
  };
  const gameClient = overrides.gameClient ?? makeFakeGameClient();
  const setPseudonym = overrides.setPseudonym ?? vi.fn();
  const routeTree = RootRoute.addChildren([IndexRoute, LobbyRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [`/lobby/${lobbyId}`] }),
    context: {
      puzzleRepository: stubPuzzleRepository,
      lobbyClient,
      gameClient,
      getSession: () => ({ sessionId, pseudonym }),
      setPseudonym,
    },
  });
  return { ...render(<RouterProvider router={router} />), lobbyClient, gameClient, setPseudonym };
};

afterEach(() => vi.restoreAllMocks());

describe('Lobby route loader', () => {
  it('renders the lobby id and player count from the loader payload', async () => {
    const getLobby = vi.fn().mockResolvedValue(baseLobby);
    renderLobby({ lobbyClient: { getLobby } });
    await screen.findByRole('heading', { name: /WordSparrow/ });
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
    expect(screen.queryByRole('heading', { name: /WordSparrow/ })).toBeNull();
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
    await screen.findByRole('heading', { name: /WordSparrow/ });
    expect(gameClient.connectCalls).toEqual([{ lobbyId }]);
  });

  it('disconnects the GameClient on unmount', async () => {
    const gameClient = makeFakeGameClient();
    const { unmount } = renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    expect(gameClient.disconnectCalls.count).toBe(0);
    unmount();
    expect(gameClient.disconnectCalls.count).toBe(1);
  });
});

// `applyEvent` is the lobby route's local-state reducer: every inbound
// `GameEvent` is folded into the loader-bootstrapped `Lobby` snapshot.
// It is unreachable from the outside (defined inside the route module),
// so we drive it through the public seam — the `subscribe` callback the
// route registers — and observe state via the rendered DOM.
describe('Lobby route applyEvent reducer', () => {
  const newPlayerSessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6d' as SessionId;
  const newPlayerPseudonym = 'Joueur 9012' as Pseudonym;

  it('appends a new player and updates the count on playerJoined', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    expect(screen.getByText('2 joueurs')).toBeInTheDocument();

    act(() => {
      gameClient.dispatch({
        type: 'playerJoined',
        sessionId: newPlayerSessionId,
        pseudonym: newPlayerPseudonym,
        joinedAt: '2026-05-02T15:30:02Z',
      });
    });

    expect(screen.getByText('3 joueurs')).toBeInTheDocument();
  });

  it('removes the player and decrements the count on playerLeft', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    expect(screen.getByText('2 joueurs')).toBeInTheDocument();

    act(() => {
      gameClient.dispatch({ type: 'playerLeft', sessionId });
    });

    expect(screen.getByText('1 joueur')).toBeInTheDocument();
  });

  it('reflects the rename in player count text continuity on playerRenamed', async () => {
    // The current shell only renders the count, so we assert the rename
    // keeps the player slot intact (count unchanged) and a re-dispatched
    // `lobbyState` afterwards reflects the new pseudonym implicitly via
    // unchanged membership semantics.
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    act(() => {
      gameClient.dispatch({
        type: 'playerRenamed',
        sessionId,
        newPseudonym: 'Joueur Renomme' as Pseudonym,
      });
    });

    // Membership unchanged — rename does not add or remove a slot.
    expect(screen.getByText('2 joueurs')).toBeInTheDocument();
  });

  it('keeps state unchanged when playerJoined repeats an existing sessionId (dedupe guard)', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    expect(screen.getByText('2 joueurs')).toBeInTheDocument();

    // Re-dispatch a `playerJoined` for a sessionId already in the lobby:
    // the reducer keys its dedupe on `sessionId`, so the player count
    // must stay at 2.
    act(() => {
      gameClient.dispatch({
        type: 'playerJoined',
        sessionId, // already present in baseLobby
        pseudonym,
        joinedAt: '2026-05-02T15:30:99Z',
      });
    });

    expect(screen.getByText('2 joueurs')).toBeInTheDocument();
  });

  it('replaces the entire snapshot on lobbyState', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    expect(screen.getByText('2 joueurs')).toBeInTheDocument();

    act(() => {
      gameClient.dispatch({
        type: 'lobbyState',
        players: [
          { sessionId, pseudonym, joinedAt: '2026-05-02T15:30:00Z' },
          {
            sessionId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId,
            pseudonym: 'Joueur 5678' as Pseudonym,
            joinedAt: '2026-05-02T15:30:01Z',
          },
          {
            sessionId: newPlayerSessionId,
            pseudonym: newPlayerPseudonym,
            joinedAt: '2026-05-02T15:30:02Z',
          },
        ],
        ownerSessionId: sessionId,
        state: 'WAITING',
        gridConfig: { width: 7, height: 7 },
        game: null,
      });
    });

    expect(screen.getByText('3 joueurs')).toBeInTheDocument();
  });

  it('detaches the subscriber on unmount so events no longer mutate state', async () => {
    const gameClient = makeFakeGameClient();
    const { unmount } = renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    expect(gameClient.subscriberCount()).toBe(1);

    unmount();
    expect(gameClient.subscriberCount()).toBe(0);
  });
});

// Minimal `GamePuzzle` fixture for the IN_PROGRESS / COMPLETED tests.
// Layout (D = definition cell with text + arrow, X = letter, B = block):
//   D→  X    X       across-1 from (0,0)
//   X   X    X       letters
//   B   X    X       blocked top-left of last row
//
// `GameDefinitionCell` carries `clues: array (1..2)` per
// game/api/asyncapi.yaml. The 1-clue case below is the common shape; a
// 2-clue corner cell would carry both an across and a down clue at the
// same position.
const buildGamePuzzle = (): GamePuzzle => ({
  id: 'test-puzzle',
  title: 'Test',
  language: 'fr',
  width: 3,
  height: 3,
  cells: [
    {
      kind: 'definition',
      position: { row: 0, column: 0 },
      clues: [{ id: 'c1', text: 'a clue', arrow: 'right' }],
    },
    { kind: 'letter', position: { row: 0, column: 1 }, letter: null },
    { kind: 'letter', position: { row: 0, column: 2 }, letter: null },
    { kind: 'letter', position: { row: 1, column: 0 }, letter: null },
    { kind: 'letter', position: { row: 1, column: 1 }, letter: null },
    { kind: 'letter', position: { row: 1, column: 2 }, letter: null },
    { kind: 'block', position: { row: 2, column: 0 } },
    { kind: 'letter', position: { row: 2, column: 1 }, letter: null },
    { kind: 'letter', position: { row: 2, column: 2 }, letter: null },
  ],
  clues: [
    { id: 'c1', direction: 'across', start: { row: 0, column: 1 }, length: 2, text: 'a clue' },
  ],
  createdAt: '2026-05-02T15:30:05Z',
});

describe('Lobby route Wave H integration', () => {
  it('mounts WaitingRoom in WAITING state and fires sendStartGame on Start click', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    // Owner is the current session (baseLobby.ownerSessionId === sessionId)
    // and there are 2 players, so the Start button is enabled.
    const startButton = screen.getByRole('button', { name: /Démarrer la partie/i });
    expect(startButton).toBeEnabled();
    fireEvent.click(startButton);

    expect(gameClient.startGameCalls.count).toBe(1);
  });

  it('forwards onSetGridConfig clicks to gameClient.setGridConfig with both axes', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    // The square-only picker emits (n, n). 9×9 isn't the current
    // gridConfig (which is 7×7), so picking it triggers a write.
    // The picker is now an Ark `RadioGroup`; the hidden radio input's
    // onClick reads `event.currentTarget.checked` to commit, so use the
    // native `HTMLInputElement.click()` (jsdom toggles `checked` first)
    // rather than `fireEvent.click` (which dispatches without toggling).
    const ninePicker = screen.getByRole('radio', { name: /9×9/ }) as HTMLInputElement;
    await act(async () => { ninePicker.click(); });

    expect(gameClient.setGridConfigCalls).toEqual([{ width: 9, height: 9 }]);
  });

  it('persists the new pseudonym via setPseudonym and forwards to renameSelf', async () => {
    const gameClient = makeFakeGameClient();
    const setPseudonymSpy = vi.fn();
    renderLobby({ gameClient, setPseudonym: setPseudonymSpy });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    // The pseudonym editor shows the current name as a button; click
    // reveals the input.
    const pseudonymButton = screen.getByRole('button', {
      name: /Modifier votre pseudonyme/i,
    });
    fireEvent.click(pseudonymButton);
    const input = screen.getByLabelText(/Votre pseudonyme/i) as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'Nouveau' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(gameClient.renameCalls).toEqual(['Nouveau']);
    expect(setPseudonymSpy).toHaveBeenCalledWith('Nouveau');
  });

  it('writes the current page URL to the clipboard on Copier le lien', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    fireEvent.click(screen.getByRole('button', { name: /Copier le lien/i }));

    // We pass `window.location.href` verbatim so a player landing on
    // the route via a real URL gets the shareable link. jsdom does
    // not propagate TanStack Router's memory history into
    // `window.location`, so we just assert the call happened with the
    // host's current href (the route's actual share URL is verified
    // end-to-end on the preview deploy).
    expect(writeText).toHaveBeenCalledTimes(1);
    expect(writeText).toHaveBeenCalledWith(window.location.href);
  });

  it('unmounts WaitingRoom and mounts Grid + Timer on gameStarted', async () => {
    const gameClient = makeFakeGameClient();
    const { container } = renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    // Sanity: WaitingRoom present.
    expect(screen.queryByRole('button', { name: /Démarrer la partie/i })).not.toBeNull();

    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: buildGamePuzzle(),
        startedAt: '2026-05-02T15:30:00Z',
      });
    });

    // WaitingRoom is gone; Grid + Timer are mounted.
    expect(screen.queryByRole('button', { name: /Démarrer la partie/i })).toBeNull();
    expect(screen.getByRole('timer', { name: /temps écoulé/i })).toBeInTheDocument();
    expect(container.querySelector('[role="grid"]')).not.toBeNull();
    // A letter cell from the fixture is rendered as an uncontrolled input.
    expect(
      container.querySelector('[data-cell-kind="letter"][data-row="0"][data-col="1"]'),
    ).not.toBeNull();
  });

  it('freezes the Timer and opens EndGameModal on gameSolved', async () => {
    const gameClient = makeFakeGameClient();
    const { container } = renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: buildGamePuzzle(),
        startedAt: '2026-05-02T15:30:00Z',
      });
    });
    expect(container.querySelector('[role="grid"]')).not.toBeNull();

    act(() => {
      gameClient.dispatch({
        type: 'gameSolved',
        durationMs: 65_000,
        finalEntries: [],
      });
    });

    // Modal mounts with the formatted duration (01:05 for 65_000 ms).
    const modal = await screen.findByTestId('end-game-modal');
    expect(modal).toBeInTheDocument();
    expect(screen.getByTestId('end-game-modal-duration')).toHaveTextContent('01:05');
  });

  it('dismisses the EndGameModal on Fermer without leaving the page', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: buildGamePuzzle(),
        startedAt: '2026-05-02T15:30:00Z',
      });
    });
    act(() => {
      gameClient.dispatch({ type: 'gameSolved', durationMs: 12_000, finalEntries: [] });
    });
    await screen.findByTestId('end-game-modal');

    fireEvent.click(screen.getByTestId('end-game-modal-close'));
    expect(screen.queryByTestId('end-game-modal')).toBeNull();
    // Heading is still visible — modal close did not navigate away.
    expect(screen.getByRole('heading', { name: /WordSparrow/ })).toBeInTheDocument();
  });

  it('renders the ConnectionBanner only while the connection is unhealthy', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    // Initial state is `connecting` so the banner is visible.
    expect(screen.queryByTestId('connection-banner')).not.toBeNull();

    act(() => {
      gameClient.dispatchConnectionState('connected');
    });
    expect(screen.queryByTestId('connection-banner')).toBeNull();

    act(() => {
      gameClient.dispatchConnectionState('disconnected');
    });
    const banner = screen.getByTestId('connection-banner');
    expect(banner).toHaveAttribute('data-state', 'disconnected');

    act(() => {
      gameClient.dispatchConnectionState('reconnecting');
    });
    expect(screen.getByTestId('connection-banner')).toHaveAttribute('data-state', 'reconnecting');
  });

  it('renders the player roster during IN_PROGRESS with vous + propriétaire badges on the right rows', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: buildGamePuzzle(),
        startedAt: '2026-05-02T15:30:00Z',
      });
    });

    // The roster is mounted alongside the Grid+Timer. Both names appear.
    const roster = screen.getByRole('list', { name: /Liste des joueurs/i });
    expect(roster).toBeInTheDocument();
    expect(roster).toHaveTextContent('Joueur 1234');
    expect(roster).toHaveTextContent('Joueur 5678');

    // Owner row (current session is the owner in baseLobby) carries
    // both badges; the other player carries neither.
    const rows = roster.querySelectorAll('li');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('Joueur 1234');
    expect(rows[0]).toHaveTextContent('vous');
    expect(rows[0]).toHaveTextContent('propriétaire');
    expect(rows[1]).toHaveTextContent('Joueur 5678');
    expect(rows[1]).not.toHaveTextContent('vous');
    expect(rows[1]).not.toHaveTextContent('propriétaire');
  });

  it('updates the roster on playerJoined / playerLeft fired during IN_PROGRESS', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: buildGamePuzzle(),
        startedAt: '2026-05-02T15:30:00Z',
      });
    });
    expect(screen.getByRole('list', { name: /Liste des joueurs/i })).toHaveTextContent('Joueur 5678');

    const lateJoinerSessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6e' as SessionId;
    act(() => {
      gameClient.dispatch({
        type: 'playerJoined',
        sessionId: lateJoinerSessionId,
        pseudonym: 'Joueur Tardif' as Pseudonym,
        joinedAt: '2026-05-02T15:31:00Z',
      });
    });
    expect(screen.getByRole('list', { name: /Liste des joueurs/i })).toHaveTextContent('Joueur Tardif');

    act(() => {
      gameClient.dispatch({ type: 'playerLeft', sessionId: lateJoinerSessionId });
    });
    expect(
      screen.getByRole('list', { name: /Liste des joueurs/i }),
    ).not.toHaveTextContent('Joueur Tardif');
  });

  it('renders letter cells empty even when the wire defensively carries a `letter`', async () => {
    // Regression: PR #146 mapped wire `letter` into UI `entry`, so any
    // server frame carrying a non-null `letter` (whether by accident or
    // by a future pre-fill use case) rendered the grid pre-solved on
    // every client. Per game/api/asyncapi.yaml `GameLetterCell`, the
    // server emits `null` here in v1, but the route MUST stay defensive:
    // `entry` is local player input — never the wire's letter slot.
    const gameClient = makeFakeGameClient();
    const { container } = renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    const puzzleWithLeak: GamePuzzle = {
      ...buildGamePuzzle(),
      cells: [
        {
          kind: 'definition',
          position: { row: 0, column: 0 },
          clues: [{ id: 'c1', text: 'a clue', arrow: 'right' }],
        },
        // Simulate a hypothetical server slip-up: the wire carries a
        // would-be answer letter. The route adapter must still render
        // these cells blank.
        { kind: 'letter', position: { row: 0, column: 1 }, letter: 'A' as Letter },
        { kind: 'letter', position: { row: 0, column: 2 }, letter: 'B' as Letter },
      ],
    };
    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: puzzleWithLeak,
        startedAt: '2026-05-02T15:30:00Z',
      });
    });

    const inputs = container.querySelectorAll<HTMLInputElement>(
      'input[data-cell-kind="letter"]',
    );
    expect(inputs.length).toBeGreaterThan(0);
    for (const input of inputs) {
      expect(input.value).toBe('');
    }
  });

  it('rehydrates already-typed letters into the grid when the loader returns an IN_PROGRESS lobby with entries', async () => {
    // Refresh-during-IN_PROGRESS regression: before the fix, the
    // AsyncAPI `GameSession` schema omitted `entries`, so the snapshot
    // a reconnecting client received had no record of what had been
    // typed. The route now reads `lobby.game.entries` from the loader
    // payload and hands them to Grid, which writes each letter into
    // the matching uncontrolled <input> via the same imperative path
    // a live `cellUpdated` frame would use (per ADR-0002 §4).
    const gameClient = makeFakeGameClient();
    const otherSessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6f' as SessionId;
    const entries: readonly CellEntry[] = [
      {
        sessionId,
        row: 0,
        column: 1,
        letter: 'A' as Letter,
        writtenAt: '2026-05-02T15:35:42Z',
      },
      {
        sessionId: otherSessionId,
        row: 1,
        column: 2,
        letter: 'Z' as Letter,
        writtenAt: '2026-05-02T15:35:43Z',
      },
    ];
    const inProgressLobby: Lobby = {
      ...baseLobby,
      state: 'IN_PROGRESS',
      game: {
        puzzle: buildGamePuzzle(),
        entries,
        startedAt: '2026-05-02T15:30:00Z',
        completedAt: null,
      },
    };
    const { container } = renderLobby({ gameClient, initialLobby: inProgressLobby });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    const cellA = container.querySelector<HTMLInputElement>(
      '[data-cell-kind="letter"][data-row="0"][data-col="1"]',
    );
    const cellZ = container.querySelector<HTMLInputElement>(
      '[data-cell-kind="letter"][data-row="1"][data-col="2"]',
    );
    expect(cellA).not.toBeNull();
    expect(cellZ).not.toBeNull();
    expect(cellA!.value).toBe('A');
    expect(cellZ!.value).toBe('Z');
    // An untyped cell stays empty — initialEntries only fills the
    // positions present in the list.
    const blankCell = container.querySelector<HTMLInputElement>(
      '[data-cell-kind="letter"][data-row="0"][data-col="2"]',
    );
    expect(blankCell).not.toBeNull();
    expect(blankCell!.value).toBe('');
  });

  it('renders the current-clue panel placeholder beside the grid on gameStarted', async () => {
    const gameClient = makeFakeGameClient();
    const { container } = renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: buildGamePuzzle(),
        startedAt: '2026-05-02T15:30:00Z',
      });
    });
    // The Grid component mounts a `CurrentCluePanel` next to the grid;
    // its placeholder copy is the visible cue that clue text renders
    // alongside (rather than baked into) the cells.
    const panel = container.querySelector('[data-testid="current-clue-panel"]');
    expect(panel).not.toBeNull();
    expect(panel?.textContent).toMatch(/Sélectionnez une case/i);
  });

  it('forwards a typed letter to gameClient.cellUpdate with row/column/letter', async () => {
    const gameClient = makeFakeGameClient();
    const { container } = renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: buildGamePuzzle(),
        startedAt: '2026-05-02T15:30:00Z',
      });
    });

    const cell = container.querySelector<HTMLInputElement>(
      '[data-cell-kind="letter"][data-row="0"][data-col="1"]',
    );
    expect(cell).not.toBeNull();
    // Mirror grid-input.test.tsx: focus + click before typing so the
    // navigation hook records the active cell, then keyDown delivers
    // the keystroke through the same path the soft keyboard uses.
    cell!.focus();
    fireEvent.click(cell!);
    fireEvent.keyDown(cell!, { key: 'a' });

    expect(gameClient.cellUpdateCalls).toEqual([{ row: 0, column: 1, letter: 'A' }]);
  });

  it('forwards local focus changes to gameClient.cellFocus with row/column/direction', async () => {
    const gameClient = makeFakeGameClient();
    const { container } = renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: buildGamePuzzle(),
        startedAt: '2026-05-02T15:30:00Z',
      });
    });
    const cell = container.querySelector<HTMLInputElement>(
      '[data-cell-kind="letter"][data-row="0"][data-col="1"]',
    );
    cell!.focus();
    fireEvent.click(cell!);
    // The route wires Grid.onLocalFocusChange to gameClient.cellFocus.
    // The hook fires every transition; the adapter (not under test
    // here) is the single source of truth for the 200 ms debounce.
    const last = gameClient.cellFocusCalls[gameClient.cellFocusCalls.length - 1]!;
    expect(last.row).toBe(0);
    expect(last.column).toBe(1);
    expect(last.direction).toBe('across');
  });

  it('renders a peer presence chip after a presenceUpdated dispatch during IN_PROGRESS', async () => {
    const gameClient = makeFakeGameClient();
    const peerSessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6c' as SessionId;
    const { container } = renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });
    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: buildGamePuzzle(),
        startedAt: '2026-05-02T15:30:00Z',
      });
    });
    act(() => {
      gameClient.dispatch({
        type: 'presenceUpdated',
        sessionId: peerSessionId,
        row: 0,
        column: 1,
        direction: 'across',
      });
    });
    const chip = container.querySelector('[data-testid="presence-chip"]');
    expect(chip).not.toBeNull();
    // The fake's session 5678 player is in baseLobby.players; the chip
    // text is the matching pseudonym.
    expect(chip?.textContent).toContain('Joueur 5678');
  });
});

describe('Lobby route error boundary', () => {
  it('renders the generic retry copy on kind=validation', async () => {
    const validation = new LobbyClientError({
      kind: 'validation', status: 400, problem: null, message: 'bad lobby id',
    });
    renderLobby({ lobbyClient: { getLobby: vi.fn().mockRejectedValue(validation) } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Une erreur est survenue. Réessayez.');
  });

  it('renders the generic retry copy on kind=transient', async () => {
    const transient = new LobbyClientError({
      kind: 'transient', status: 503, problem: null, message: 'upstream 503',
    });
    renderLobby({ lobbyClient: { getLobby: vi.fn().mockRejectedValue(transient) } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Une erreur est survenue. Réessayez.');
  });

  it('renders the fallback copy when the loader rejects with a non-LobbyClientError', async () => {
    renderLobby({ lobbyClient: { getLobby: vi.fn().mockRejectedValue(new Error('boom')) } });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Une erreur est survenue. Réessayez.');
  });

  // Both `not-found` and `upstream-unavailable` strand the user on a
  // page that cannot recover on its own — surface a CTA back to `/` so
  // they can spin up a new lobby. The other two kinds (validation,
  // transient) suggest a retry is meaningful, so they keep the simpler
  // layout for now.
  it('renders a "Retour à l\'accueil" button on kind=not-found', async () => {
    const notFound = new LobbyClientError({
      kind: 'not-found', status: 404, problem: null, message: 'No lobby with id 7gQ2xK9p',
    });
    renderLobby({ lobbyClient: { getLobby: vi.fn().mockRejectedValue(notFound) } });
    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: /retour à l'accueil/i })).toBeInTheDocument();
  });

  it('renders a "Retour à l\'accueil" button on kind=upstream-unavailable', async () => {
    const unavailable = new LobbyClientError({
      kind: 'upstream-unavailable', status: null, problem: null, message: 'fetch failed',
    });
    renderLobby({ lobbyClient: { getLobby: vi.fn().mockRejectedValue(unavailable) } });
    await screen.findByRole('alert');
    expect(screen.getByRole('button', { name: /retour à l'accueil/i })).toBeInTheDocument();
  });

  it('navigates to "/" when the back-home button is clicked from the not-found error', async () => {
    const notFound = new LobbyClientError({
      kind: 'not-found', status: 404, problem: null, message: 'No lobby with id 7gQ2xK9p',
    });
    renderLobby({ lobbyClient: { getLobby: vi.fn().mockRejectedValue(notFound) } });
    await screen.findByRole('alert');
    const back = screen.getByRole('button', { name: /retour à l'accueil/i });
    fireEvent.click(back);
    // `/` mounts the Index route which would attempt the puzzle loader;
    // the stub rejects, so the route renders its alert. We just need
    // proof the navigation left the lobby's error screen — assert the
    // alert copy is no longer the lobby's "Salon introuvable" string.
    await screen.findByRole('alert');
    expect(screen.queryByText('Salon introuvable.')).toBeNull();
  });
});

describe('Lobby route Start button loading feedback', () => {
  it('flips the Start button label to "Démarrage…" after click and back to default on gameStarted', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    const startButton = screen.getByRole('button', { name: /démarrer la partie/i });
    expect(startButton).toBeEnabled();
    fireEvent.click(startButton);

    // The very next render carries the `isStarting` flag down to
    // WaitingRoom; the button label and disabled state confirm the
    // user-visible feedback is in place.
    const busy = await screen.findByRole('button', { name: /démarrage…/i });
    expect(busy).toBeDisabled();
    expect(busy).toHaveAttribute('aria-busy', 'true');
    expect(gameClient.startGameCalls.count).toBe(1);

    // The `gameStarted` frame swings the lobby into IN_PROGRESS, which
    // unmounts WaitingRoom entirely — the loading button should be
    // gone from the DOM.
    act(() => {
      gameClient.dispatch({
        type: 'gameStarted',
        puzzle: buildGamePuzzle(),
        startedAt: '2026-05-02T15:30:00Z',
      });
    });
    expect(screen.queryByRole('button', { name: /démarrage…/i })).toBeNull();
  });

  it('clears the loading state when an error frame arrives so the owner can retry', async () => {
    const gameClient = makeFakeGameClient();
    renderLobby({ gameClient });
    await screen.findByRole('heading', { name: /WordSparrow/ });

    fireEvent.click(screen.getByRole('button', { name: /démarrer la partie/i }));
    expect(await screen.findByRole('button', { name: /démarrage…/i })).toBeDisabled();

    act(() => {
      gameClient.dispatch({
        type: 'error',
        errorType: 'https://bliss.example/errors/start-rejected',
        title: 'Could not start',
        detail: 'reason',
      });
    });

    // Server-side rejection ⇒ flag clears, button reverts to its
    // default label, and the owner can click again.
    const reset = await screen.findByRole('button', { name: /démarrer la partie/i });
    expect(reset).toBeEnabled();
  });
});
