import { act, fireEvent, render, screen } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRouter,
} from '@tanstack/react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PuzzleRepository, PuzzleSolver } from '@/application';
import {
  LobbyClientError,
  type GameClient,
  type LobbyClient,
} from '@/application/game';
import type { SoloEntriesStore } from '@/application/solo/SoloEntriesStore';
import type { Puzzle } from '@/domain';
import type { Lobby, LobbyId, Pseudonym, SessionId } from '@/domain/game';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as AccueilRoute } from '@/ui/routes/accueil';
import { Route as GrilleRoute } from '@/ui/routes/grille';
import { Route as LobbyRoute } from '@/ui/routes/lobby.$lobbyId';

// 5×3 fixture with 9 letter cells and 6 black cells — small enough to
// reason about by hand, big enough that progress counts read cleanly.
const samplePuzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 'Petite grille',
  language: 'fr',
  width: 5,
  height: 3,
  hintsAllowed: 3,
  difficulty: null,
  gridNumber: null,
  cells: [
    { kind: 'letter', position: { row: 0, col: 0 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 2 }, entry: '' },
    { kind: 'block', position: { row: 0, col: 3 } },
    { kind: 'block', position: { row: 0, col: 4 } },
    { kind: 'letter', position: { row: 1, col: 0 }, entry: '' },
    { kind: 'letter', position: { row: 1, col: 1 }, entry: '' },
    { kind: 'letter', position: { row: 1, col: 2 }, entry: '' },
    { kind: 'block', position: { row: 1, col: 3 } },
    { kind: 'block', position: { row: 1, col: 4 } },
    { kind: 'letter', position: { row: 2, col: 0 }, entry: '' },
    { kind: 'letter', position: { row: 2, col: 1 }, entry: '' },
    { kind: 'letter', position: { row: 2, col: 2 }, entry: '' },
    { kind: 'block', position: { row: 2, col: 3 } },
    { kind: 'block', position: { row: 2, col: 4 } },
  ],
};

const sessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b' as SessionId;
const pseudonym = 'Joueur 1234' as Pseudonym;
const createdLobbyId = '7gQ2xK9p' as LobbyId;

const stubPuzzleSolver: PuzzleSolver = {
  validate: () => Promise.resolve({ solved: false, incorrectCells: [] }),
  requestHint: () => Promise.reject(new Error('not used')),
};

const baseCreatedLobby: Lobby & { readonly id: LobbyId } = {
  id: createdLobbyId,
  ownerSessionId: sessionId,
  players: [{ sessionId, pseudonym, joinedAt: '2026-05-02T15:30:00Z' }],
  state: 'WAITING',
  gridConfig: { width: 7, height: 7 },
  game: null,
};

const stubGameClient: GameClient = {
  connect: () => Promise.resolve(),
  joinLobby: () => {},
  renameSelf: () => {},
  setGridConfig: () => {},
  startGame: () => {},
  cellUpdate: () => {},
  cellFocus: () => {},
  leaveLobby: () => {},
  disconnect: () => {},
  subscribe: () => () => {},
  subscribeConnectionState: () => () => {},
};

interface RenderOptions {
  readonly soloStore?: SoloEntriesStore;
  readonly lobbyClient?: Partial<LobbyClient>;
  readonly initialEntry?: string;
  readonly puzzle?: Puzzle;
}

const emptyStore: SoloEntriesStore = {
  load: () => [],
  save: () => {},
  loadLockedCells: () => [],
  lockCell: () => {},
  clearForPuzzle: () => {},
};

const renderAccueil = (options: RenderOptions = {}) => {
  const lobbyClient: LobbyClient = {
    createLobby: vi.fn().mockResolvedValue(baseCreatedLobby),
    getLobby: vi.fn().mockResolvedValue(baseCreatedLobby),
    findByCode: vi.fn().mockResolvedValue(baseCreatedLobby),
    ...options.lobbyClient,
  };
  const puzzleRepository: PuzzleRepository = {
    fetchById: () => Promise.resolve(options.puzzle ?? samplePuzzle),
    fetchDaily: () => Promise.resolve(options.puzzle ?? samplePuzzle),
  };
  const routeTree = RootRoute.addChildren([AccueilRoute, GrilleRoute, LobbyRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [options.initialEntry ?? '/'] }),
    context: {
      puzzleRepository,
      puzzleSolver: stubPuzzleSolver,
      sessionClient: {
        eraseSession: () => Promise.resolve({ deleted: 0 }),
        getSessionId: () => 'test-session-id',
        clearLocalSession: () => {},
      },
      soloEntriesStore: options.soloStore ?? emptyStore,
      tourSeenStore: { get: () => true, set: () => {}, clear: () => {} },
      lobbyClient,
      gameClient: stubGameClient,
      getSession: () => ({ sessionId, pseudonym }),
    },
  });
  return { router, lobbyClient, ...render(<RouterProvider router={router} />) };
};

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe('Accueil route', () => {
  it('renders both card titles', async () => {
    renderAccueil();
    expect(await screen.findByRole('heading', { name: 'Grille du jour', level: 2 }))
      .toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Multijoueur', level: 2 }))
      .toBeInTheDocument();
  });

  it('shows "Nouvelle grille" + "Commencer" when nothing is solved yet', async () => {
    renderAccueil();
    expect(await screen.findByRole('progressbar', { name: 'Nouvelle grille' }))
      .toBeInTheDocument();
    expect(screen.getByText('0 / 9 cases')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Commencer' })).toBeInTheDocument();
  });

  it('shows "Reprise" + "Reprendre" when locked cells exist', async () => {
    const soloStore: SoloEntriesStore = {
      ...emptyStore,
      load: () => [
        { row: 0, column: 0, letter: 'C' },
        { row: 0, column: 1, letter: 'A' },
        { row: 0, column: 2, letter: 'T' },
      ],
      loadLockedCells: () => [
        { row: 0, column: 0 },
        { row: 0, column: 1 },
        { row: 0, column: 2 },
      ],
    };
    renderAccueil({ soloStore });
    expect(await screen.findByRole('progressbar', { name: 'Reprise' }))
      .toBeInTheDocument();
    expect(screen.getByText('3 / 9 cases')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reprendre' })).toBeInTheDocument();
  });

  it('navigates to /grille when the primary CTA is clicked', async () => {
    const { router } = renderAccueil();
    const button = await screen.findByRole('button', { name: 'Commencer' });
    await act(async () => { button.click(); });
    await vi.waitFor(() => {
      expect(router.state.location.pathname).toBe('/grille');
    });
  });

  it('disables join controls when the multiplayer flag is off', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'false');
    renderAccueil();
    const codeInput = await screen.findByRole('textbox', { name: 'Code de partie' });
    expect(codeInput).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Rejoindre' })).toBeDisabled();
    expect(screen.getByText('Disponible bientôt')).toBeInTheDocument();
  });

  it('keeps Rejoindre disabled until the typed code matches the pattern', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true');
    renderAccueil();
    const codeInput = await screen.findByRole('textbox', { name: 'Code de partie' }) as HTMLInputElement;
    expect(codeInput).not.toBeDisabled();
    const rejoindre = screen.getByRole('button', { name: 'Rejoindre' });
    expect(rejoindre).toBeDisabled();
    // Five chars — still under the six-char pattern.
    fireEvent.change(codeInput, { target: { value: 'A2B3C' } });
    expect(rejoindre).toBeDisabled();
    fireEvent.change(codeInput, { target: { value: 'A2B3C4' } });
    expect(rejoindre).not.toBeDisabled();
  });

  it('uppercases input and strips chars outside the Crockford alphabet', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true');
    renderAccueil();
    const codeInput = await screen.findByRole('textbox', { name: 'Code de partie' }) as HTMLInputElement;
    // Lowercase + the excluded chars `0`, `1`, `O`, `I`, `L` must drop
    // out; the remaining `a2b3c4` uppercases to a valid code.
    fireEvent.change(codeInput, { target: { value: 'a2b3c4-0OIL1' } });
    expect(codeInput.value).toBe('A2B3C4');
  });

  it('resolves the typed code, navigates to the matching lobby on success', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true');
    const { router, lobbyClient } = renderAccueil();
    const codeInput = await screen.findByRole('textbox', { name: 'Code de partie' }) as HTMLInputElement;
    fireEvent.change(codeInput, { target: { value: 'A2B3C4' } });
    const rejoindre = screen.getByRole('button', { name: 'Rejoindre' });
    await act(async () => { rejoindre.click(); });
    await vi.waitFor(() => {
      expect(lobbyClient.findByCode).toHaveBeenCalledWith('A2B3C4');
      expect(router.state.location.pathname).toBe(`/lobby/${createdLobbyId}`);
    });
  });

  it('shows a "code introuvable" message when the server returns 404', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true');
    const notFound = new LobbyClientError({
      kind: 'not-found',
      status: 404,
      problem: null,
      message: 'lobby not found',
    });
    renderAccueil({ lobbyClient: { findByCode: vi.fn().mockRejectedValue(notFound) } });
    const codeInput = await screen.findByRole('textbox', { name: 'Code de partie' }) as HTMLInputElement;
    fireEvent.change(codeInput, { target: { value: 'A2B3C4' } });
    await act(async () => {
      screen.getByRole('button', { name: 'Rejoindre' }).click();
    });
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toMatch(/Aucune partie pour ce code/);
  });

  it('disables "Créer une partie" when the multiplayer flag is off', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'false');
    renderAccueil();
    const button = await screen.findByRole('button', { name: 'Créer une partie' });
    expect(button).toBeDisabled();
  });

  it('creates a lobby and navigates to /lobby/:lobbyId when the flag is on', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true');
    const { router, lobbyClient } = renderAccueil();
    const button = await screen.findByRole('button', { name: 'Créer une partie' });
    await act(async () => { button.click(); });
    await vi.waitFor(() => {
      expect(lobbyClient.createLobby).toHaveBeenCalledWith({
        ownerSessionId: sessionId,
        ownerPseudonym: pseudonym,
      });
      expect(router.state.location.pathname).toBe(`/lobby/${createdLobbyId}`);
    });
  });

  it('disables the "Anciennes grilles" link', async () => {
    renderAccueil();
    const link = await screen.findByRole('button', { name: 'Voir les anciennes grilles →' });
    expect(link).toBeDisabled();
  });

  it('omits the gridNumber and difficulty meta when both are null', async () => {
    renderAccueil();
    // Sub-row sits directly under the card title; with both fields null
    // it must not contain a `·` separator (only the date).
    const title = await screen.findByRole('heading', { name: 'Grille du jour' });
    const meta = title.nextElementSibling;
    expect(meta).not.toBeNull();
    expect(meta?.textContent).not.toContain('·');
    expect(meta?.textContent).not.toContain('n°');
  });

  it('renders `· n°X · difficulty` when puzzle metadata is populated', async () => {
    const populated: Puzzle = { ...samplePuzzle, gridNumber: 142, difficulty: 'facile' };
    renderAccueil({ puzzle: populated });
    const title = await screen.findByRole('heading', { name: 'Grille du jour' });
    const meta = title.nextElementSibling;
    expect(meta?.textContent).toMatch(/· n°142 · facile$/);
  });
});
