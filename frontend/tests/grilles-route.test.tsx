import { act, render, screen } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRouter,
} from '@tanstack/react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  DailySummariesPage,
  DailySummary,
  PuzzleRepository,
  PuzzleSolver,
} from '@/application';
import type { SoloEntriesStore } from '@/application/solo/SoloEntriesStore';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as GrilleRoute } from '@/ui/routes/grille';
import { Route as GrillesRoute } from '@/ui/routes/grilles';

// Three summaries spanning May 2026; total letter cells set so the
// progress arithmetic reads cleanly (3/3, 1/3, 0/3).
const COMPLETE_ID = '00000000-0000-7000-8000-000000000003';
const PARTIAL_ID = '00000000-0000-7000-8000-000000000002';
const UNTOUCHED_ID = '00000000-0000-7000-8000-000000000001';

const summaries: ReadonlyArray<DailySummary> = [
  { id: COMPLETE_ID, date: '2026-05-05', gridNumber: 125, difficulty: 'facile', totalLetterCells: 3 },
  { id: PARTIAL_ID, date: '2026-05-04', gridNumber: 124, difficulty: 'facile', totalLetterCells: 3 },
  { id: UNTOUCHED_ID, date: '2026-05-03', gridNumber: 123, difficulty: 'facile', totalLetterCells: 3 },
];

interface BuildOptions {
  readonly initialPage?: DailySummariesPage;
  readonly listDailySummaries?: PuzzleRepository['listDailySummaries'];
  readonly soloStore?: SoloEntriesStore;
}

const stubSolver: PuzzleSolver = {
  validate: () => Promise.resolve({ solved: false, incorrectCells: [] }),
  requestHint: () => Promise.reject(new Error('not used')),
};

const emptyStore: SoloEntriesStore = {
  load: () => [],
  save: () => {},
  loadLockedCells: () => [],
  lockCell: () => {},
  loadHintsUsed: () => 0,
  recordHintUsed: () => {},
  clearForPuzzle: () => {},
};

const renderGrilles = (options: BuildOptions = {}) => {
  const initialPage =
    options.initialPage ?? ({ items: summaries, hasMore: false } satisfies DailySummariesPage);
  const listDailySummaries =
    options.listDailySummaries ?? vi.fn().mockResolvedValue(initialPage);
  const puzzleRepository: PuzzleRepository = {
    fetchById: vi.fn().mockRejectedValue(new Error('unused')),
    fetchDaily: vi.fn().mockRejectedValue(new Error('unused')),
    listDailySummaries,
  };
  const routeTree = RootRoute.addChildren([GrilleRoute, GrillesRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/grilles'] }),
    context: {
      puzzleRepository,
      puzzleSolver: stubSolver,
      sessionClient: {
        eraseSession: () => Promise.resolve({ deleted: 0 }),
        getSessionId: () => 'test-session-id',
        clearLocalSession: () => {},
      },
      soloEntriesStore: options.soloStore ?? emptyStore,
      tourSeenStore: { get: () => true, set: () => {}, clear: () => {} },
    },
  });
  return {
    router,
    listDailySummaries,
    ...render(<RouterProvider router={router} />),
  };
};

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Grilles archive route', () => {
  it('renders month section with three rows and the per-state CTAs', async () => {
    const soloStore: SoloEntriesStore = {
      ...emptyStore,
      load: (puzzleId) => {
        if (puzzleId === COMPLETE_ID) {
          return [
            { row: 0, column: 0, letter: 'A' },
            { row: 0, column: 1, letter: 'B' },
            { row: 0, column: 2, letter: 'C' },
          ];
        }
        if (puzzleId === PARTIAL_ID) {
          return [{ row: 0, column: 0, letter: 'A' }];
        }
        return [];
      },
      loadLockedCells: (puzzleId) => {
        if (puzzleId === COMPLETE_ID) {
          return [
            { row: 0, column: 0 },
            { row: 0, column: 1 },
            { row: 0, column: 2 },
          ];
        }
        return [];
      },
    };
    renderGrilles({ soloStore });
    expect(await screen.findByRole('heading', { name: /mai 2026/i, level: 2 }))
      .toBeInTheDocument();
    // CTAs render in DESC order (newest first).
    expect(screen.getByRole('link', { name: /^Revoir/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /^Reprendre/ })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /^Commencer/ })).toBeInTheDocument();
  });

  it('renders a calm role="status" empty state when no summaries exist', async () => {
    renderGrilles({ initialPage: { items: [], hasMore: false } });
    const status = await screen.findByRole('status');
    expect(status).toHaveTextContent(/aucune grille disponible/i);
    expect(screen.queryByRole('button', { name: /charger mois précédent/i })).toBeNull();
  });

  it('shows an alert and keeps the button after listDailySummaries rejects', async () => {
    const listDailySummaries = vi
      .fn()
      .mockResolvedValueOnce({ items: summaries, hasMore: true })
      .mockRejectedValueOnce(new Error('boom'));
    renderGrilles({ listDailySummaries });
    const button = await screen.findByRole('button', { name: /charger mois précédent/i });
    await act(async () => { button.click(); });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/impossible de charger/i);
    // Button stays visible so the player can retry.
    expect(screen.getByRole('button', { name: /charger mois précédent/i })).toBeInTheDocument();
  });

  it('appends older summaries and focuses the first newly-appended CTA', async () => {
    const olderSummary: DailySummary = {
      id: '00000000-0000-7000-8000-000000000091',
      date: '2026-04-30',
      gridNumber: 120,
      difficulty: 'facile',
      totalLetterCells: 3,
    };
    const listDailySummaries = vi
      .fn()
      .mockResolvedValueOnce({ items: summaries, hasMore: true })
      .mockResolvedValueOnce({ items: [olderSummary], hasMore: false });
    renderGrilles({ listDailySummaries });
    const button = await screen.findByRole('button', { name: /charger mois précédent/i });
    await act(async () => { button.click(); });
    // New month section appears after the older fetch resolves.
    await screen.findByRole('heading', { name: /avril 2026/i, level: 2 });
    expect(listDailySummaries).toHaveBeenCalledTimes(2);
    // First row of the appended (April) bucket gets focus on the next effect tick.
    // Locate it via the article whose heading is "Jeudi 30 avril · n°120".
    await vi.waitFor(() => {
      const heading = screen.getByRole('heading', { name: /jeudi 30 avril/i, level: 3 });
      const article = heading.closest('article')!;
      const link = article.querySelector('a')!;
      expect(document.activeElement).toBe(link);
    });
  });

  it('hides "Charger mois précédent" when server returns an empty-items range', async () => {
    // Reproduces the infinite-loop bug: oldest > launchAnchor so canLoadOlder
    // stays true, but the server acknowledges the gap is empty. The exhausted
    // sentinel must flip the button off after the first empty response.
    const initial: DailySummary = {
      id: '00000000-0000-7000-8000-000000000010',
      date: '2026-01-15',
      gridNumber: 15,
      difficulty: null,
      totalLetterCells: 3,
    };
    const listDailySummaries = vi
      .fn()
      .mockResolvedValueOnce({ items: [initial], hasMore: false } satisfies DailySummariesPage)
      .mockResolvedValueOnce({ items: [], hasMore: false } satisfies DailySummariesPage);
    renderGrilles({ listDailySummaries });
    const button = await screen.findByRole('button', { name: /charger mois précédent/i });
    await act(async () => { button.click(); });
    // After the empty response, the button must disappear (no infinite loop).
    await vi.waitFor(() => {
      expect(screen.queryByRole('button', { name: /charger mois précédent/i })).toBeNull();
    });
    expect(listDailySummaries).toHaveBeenCalledTimes(2);
  });

  it('hides "Charger mois précédent" when hasMore=false AND oldest is at launch anchor', async () => {
    const anchor: DailySummary = {
      id: '00000000-0000-7000-8000-000000000001',
      date: '2026-01-01',
      gridNumber: 1,
      difficulty: null,
      totalLetterCells: 3,
    };
    renderGrilles({ initialPage: { items: [anchor], hasMore: false } });
    await screen.findByRole('heading', { name: /janvier 2026/i, level: 2 });
    expect(screen.queryByRole('button', { name: /charger mois précédent/i })).toBeNull();
  });
});
