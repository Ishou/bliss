import { act, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { PuzzleRepository, PuzzleSolver } from '@/application';
import type { Puzzle } from '@/domain';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute, buildPuzzleToolbarMetadata } from '@/ui/routes/grille';

// Smallest viable puzzle: one definition cell + two letter cells.
const buildPuzzle = (overrides: Partial<Puzzle> = {}): Puzzle => ({
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 'Grille du jour',
  language: 'fr',
  width: 3,
  height: 1,
  hintsAllowed: 3,
  cells: [
    {
      kind: 'definition',
      position: { row: 0, col: 0 },
      clues: [{ text: 'a', arrow: 'right' }],
    },
    { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 2 }, entry: '' },
  ],
  ...overrides,
});

const makeInMemoryStore = () => ({
  load: () => [],
  save: vi.fn(),
  loadLockedCells: () => [],
  lockCell: vi.fn(),
  loadHintsUsed: () => 0,
  recordHintUsed: vi.fn(),
  clearForPuzzle: vi.fn(),
});

const renderHomeRoute = (puzzle: Puzzle) => {
  const repository: PuzzleRepository = {
    fetchById: vi.fn().mockResolvedValue(puzzle),
    fetchDaily: vi.fn().mockResolvedValue(puzzle),
  };
  const solver: PuzzleSolver = {
    validate: vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] }),
    requestHint: vi.fn(),
  };
  const store = makeInMemoryStore();
  const routeTree = RootRoute.addChildren([IndexRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/grille'] }),
    context: {
      puzzleRepository: repository,
      puzzleSolver: solver,
      sessionClient: {
        eraseSession: () => Promise.resolve({ deleted: 0 }),
        getSessionId: () => 'test-session-id',
        clearLocalSession: () => {},
      },
      soloEntriesStore: store,
      tourSeenStore: { get: () => true, set: () => {}, clear: () => {} },
    },
  });
  const utils = render(<RouterProvider router={router} />);
  return { ...utils, store };
};

// Renders an arbitrary React subtree inside a single-route memory
// router so hooks that depend on the router context (`useRouterState`,
// `useNavigate`) resolve. Mirrors the `renderPage` helper in
// `tests/page-shell.test.tsx`.
const renderWithRouter = (node: ReactNode, pathname = '/grille') => {
  const rootRoute = createRootRoute();
  const testRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: pathname,
    component: () => <>{node}</>,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([testRoute]),
    history: createMemoryHistory({ initialEntries: [pathname] }),
  });
  return render(<RouterProvider router={router} />);
};

// 5a — skeleton renders square cells (aspect-ratio: 1 / 1).
describe('grille skeleton — square cells', () => {
  it('applies aspect-ratio 1 / 1 to every skeleton placeholder cell', async () => {
    const Skeleton = IndexRoute.options.pendingComponent!;
    const { container } = renderWithRouter(<Skeleton />);
    // Wait for the router transition before querying.
    await screen.findByText('Chargement de la grille…');
    const placeholders = container.querySelectorAll<HTMLElement>(
      '[aria-hidden="true"] > div',
    );
    expect(placeholders.length).toBeGreaterThanOrEqual(100);
    // Panda emits an atomic class for `aspectRatio: '1 / 1'` shaped
    // like `asp_1_/_1`. Assert at least 100 placeholders carry it.
    let withAspectClass = 0;
    placeholders.forEach((c) => {
      if (/\basp_1_\/_1\b/.test(c.className)) withAspectClass++;
    });
    expect(withAspectClass).toBeGreaterThanOrEqual(100);
  });
});

// 5b — Day number renders when puzzle has gridNumber.
describe('grille day number', () => {
  it('renders n°<gridNumber> alongside the title when present', async () => {
    renderHomeRoute(buildPuzzle({ gridNumber: 142 }));
    await screen.findByRole('grid');
    expect(screen.getByText('n°142')).toBeInTheDocument();
    expect(
      screen.getByText('Grille du jour · n°142'),
    ).toBeInTheDocument();
  });

  it('falls back to the title alone when gridNumber is null', async () => {
    renderHomeRoute(buildPuzzle({ gridNumber: null }));
    await screen.findByRole('grid');
    expect(screen.queryByText(/n°/)).toBeNull();
    expect(screen.getByText('Grille du jour')).toBeInTheDocument();
  });

  it('buildPuzzleToolbarMetadata returns the structured shape when gridNumber is set', () => {
    expect(buildPuzzleToolbarMetadata(buildPuzzle({ gridNumber: 7 }))).toEqual({
      short: 'n°7',
      full: 'Grille du jour · n°7',
    });
    expect(buildPuzzleToolbarMetadata(buildPuzzle({ gridNumber: null }))).toBe(
      'Grille du jour',
    );
  });
});

// 5c — Refresh button opens a confirm dialog; only confirm clears state.
describe('grille refresh confirmation', () => {
  it('does not clear the puzzle until the user confirms', async () => {
    const { store } = renderHomeRoute(buildPuzzle({ gridNumber: 12 }));
    await screen.findByRole('grid');

    await act(async () => {
      fireEvent.click(
        screen.getByRole('button', { name: 'Actualiser la grille' }),
      );
    });
    expect(store.clearForPuzzle).not.toHaveBeenCalled();
    expect(screen.getByTestId('refresh-confirm')).toBeInTheDocument();

    await act(async () => {
      fireEvent.click(screen.getByTestId('refresh-confirm-accept'));
    });
    expect(store.clearForPuzzle).toHaveBeenCalledTimes(1);
  });

  it('cancel closes the dialog without clearing the puzzle', async () => {
    const { store } = renderHomeRoute(buildPuzzle());
    await screen.findByRole('grid');

    await act(async () => {
      fireEvent.click(
        screen.getByRole('button', { name: 'Actualiser la grille' }),
      );
    });
    await act(async () => {
      fireEvent.click(screen.getByTestId('refresh-confirm-cancel'));
    });
    expect(store.clearForPuzzle).not.toHaveBeenCalled();
  });
});
