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
  rotateCode: () => {},
  disconnect: () => {},
  subscribe: () => () => {},
  subscribeConnectionState: () => () => {},
};

interface RenderOptions {
  readonly soloStore?: SoloEntriesStore;
  readonly lobbyClient?: Partial<LobbyClient>;
  readonly initialEntry?: string;
  readonly puzzle?: Puzzle;
  readonly puzzleRepository?: Partial<PuzzleRepository>;
}

const emptyStore: SoloEntriesStore = {
  load: () => [],
  save: () => {},
  loadLockedCells: () => [],
  lockCell: () => {},
  loadHintsUsed: () => 0,
  recordHintUsed: () => {},
  clearForPuzzle: () => {},
};

// In-memory `LobbyJoinCodeStash` (real impl, not a mock — manifesto's
// "no mocks of own code"). Per-render Map so each test starts with a
// clean stash regardless of what the production sessionStorage adapter
// might be carrying across other tests.
const makeInMemoryStash = () => {
  const map = new Map<string, string>();
  return {
    stash(lobbyId: string, code: string) {
      map.set(lobbyId, code);
    },
    read(lobbyId: string) {
      return map.get(lobbyId) ?? null;
    },
    clear(lobbyId: string) {
      map.delete(lobbyId);
    },
  };
};

const renderAccueil = (options: RenderOptions = {}) => {
  const lobbyClient: LobbyClient = {
    createLobby: vi.fn().mockResolvedValue(baseCreatedLobby),
    getLobby: vi.fn().mockResolvedValue(baseCreatedLobby),
    findByCode: vi.fn().mockResolvedValue(baseCreatedLobby),
    listMyLobbies: vi.fn().mockResolvedValue([]),
    ...options.lobbyClient,
  };
  const puzzleRepository: PuzzleRepository = {
    fetchById: () => Promise.resolve(options.puzzle ?? samplePuzzle),
    fetchDaily: () => Promise.resolve(options.puzzle ?? samplePuzzle),
    ...options.puzzleRepository,
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
      lobbyJoinCodeStash: makeInMemoryStash(),
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

  // Keystroke-by-keystroke input through zag's keyboard machine is
  // unreliable in jsdom (zag machines are brittle outside a real browser).
  // Paste events bypass zag and are reliable — they're intercepted at the
  // React level before zag's onBeforeInput filter runs. The two handleJoin
  // integration tests below use that mechanism. The remaining tests cover
  // rendering, flag-gating, the eye toggle, and the Créer-une-partie button.

  it('disables join controls when the multiplayer flag is off', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'false');
    renderAccueil();
    const slots = await screen.findAllByLabelText(/code de partie/i);
    expect(slots[0]).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Rejoindre' })).toBeDisabled();
    expect(screen.getByText('Disponible bientôt')).toBeInTheDocument();
  });

  it('renders the PIN input enabled with Rejoindre disabled when the flag is on and no code is typed', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true');
    renderAccueil();
    const slots = await screen.findAllByLabelText(/code de partie/i);
    expect(slots[0]).not.toBeDisabled();
    // Initial state: empty value → pattern fails → Rejoindre disabled.
    expect(screen.getByRole('button', { name: 'Rejoindre' })).toBeDisabled();
  });

  it('toggles the PIN mask when the eye button is clicked', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true');
    renderAccueil();
    await screen.findAllByLabelText(/code de partie/i);
    // Default-masked: the eye button reads "Afficher le code".
    const showButton = screen.getByRole('button', { name: /afficher le code/i });
    expect(showButton).toHaveAttribute('aria-pressed', 'false');
    fireEvent.click(showButton);
    // After click, label flips and aria-pressed = true.
    const hideButton = screen.getByRole('button', { name: /masquer le code/i });
    expect(hideButton).toHaveAttribute('aria-pressed', 'true');
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

  it('resolves a pasted code and navigates to the matching lobby on success', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true');
    const { router, lobbyClient, container } = renderAccueil();
    await screen.findAllByLabelText(/code de partie/i);
    const slot = container.querySelector<HTMLInputElement>('input[data-part="input"]')!;
    fireEvent.paste(slot, {
      clipboardData: { getData: () => 'A2B3C4' },
    });
    await vi.waitFor(() => {
      expect(screen.getByRole('button', { name: 'Rejoindre' })).not.toBeDisabled();
    });
    await act(async () => {
      screen.getByRole('button', { name: 'Rejoindre' }).click();
    });
    await vi.waitFor(() => {
      expect(lobbyClient.findByCode).toHaveBeenCalledWith('A2B3C4');
      expect(router.state.location.pathname).toBe(`/lobby/${createdLobbyId}`);
    });
  });

  describe('when fetchDaily fails', () => {
    it('renders the Grille card error state without replacing the whole page', async () => {
      const fetchDaily = vi.fn().mockRejectedValue(new Error('boom'));
      renderAccueil({ puzzleRepository: { fetchDaily } });
      // Multijoueur card heading must still render — the failure is
      // isolated to the Grille card, not the whole route.
      expect(await screen.findByRole('heading', { name: 'Multijoueur', level: 2 }))
        .toBeInTheDocument();
      // The Grille card surfaces the error inline with a Réessayer
      // affordance.
      expect(screen.getByRole('heading', { name: 'Grille du jour', level: 2 }))
        .toBeInTheDocument();
      expect(screen.getByText(/grille du jour indisponible/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Réessayer' })).toBeInTheDocument();
      // No whole-page replacement: the "Une erreur est survenue." root
      // boundary is never rendered.
      expect(screen.queryByText(/une erreur est survenue/i)).not.toBeInTheDocument();
    });

    it('re-runs the loader and renders the puzzle when Réessayer is clicked after a recovery', async () => {
      const fetchDaily = vi.fn()
        .mockRejectedValueOnce(new Error('boom'))
        .mockResolvedValue(samplePuzzle);
      renderAccueil({ puzzleRepository: { fetchDaily } });
      const retry = await screen.findByRole('button', { name: 'Réessayer' });
      await act(async () => { retry.click(); });
      await vi.waitFor(() => {
        expect(screen.getByRole('progressbar', { name: 'Nouvelle grille' }))
          .toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Commencer' })).toBeInTheDocument();
      });
      expect(fetchDaily).toHaveBeenCalledTimes(2);
    });
  });

  it('shows "Aucune partie pour ce code" error when findByCode returns 404', async () => {
    vi.stubEnv('VITE_FEATURE_MULTIPLAYER', 'true');
    const notFound = new LobbyClientError({
      kind: 'not-found',
      status: 404,
      problem: null,
      message: '404',
    });
    const { container } = renderAccueil({
      lobbyClient: { findByCode: vi.fn().mockRejectedValue(notFound) },
    });
    await screen.findAllByLabelText(/code de partie/i);
    const slot = container.querySelector<HTMLInputElement>('input[data-part="input"]')!;
    fireEvent.paste(slot, {
      clipboardData: { getData: () => 'A2B3C4' },
    });
    await vi.waitFor(() => {
      expect(screen.getByRole('button', { name: 'Rejoindre' })).not.toBeDisabled();
    });
    await act(async () => {
      screen.getByRole('button', { name: 'Rejoindre' }).click();
    });
    await vi.waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent(
        'Aucune partie pour ce code. Vérifiez la saisie.',
      );
    });
  });
});
