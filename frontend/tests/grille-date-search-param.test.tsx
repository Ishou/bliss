import { render } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRouter,
} from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { PuzzleRepository, PuzzleSolver } from '@/application';
import type { Puzzle } from '@/domain';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as GrilleRoute } from '@/ui/routes/grille';

// Minimal 1×1 puzzle the loader can resolve; the test only checks the
// repository call's argument, not the rendered grid.
const puzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 't',
  language: 'fr',
  width: 1,
  height: 1,
  hintsAllowed: 3,
  cells: [{ kind: 'letter', position: { row: 0, col: 0 }, entry: '' }],
};

const stubSolver: PuzzleSolver = {
  validate: vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] }),
  requestHint: vi.fn().mockRejectedValue(new Error('not used')),
};

const renderAt = (path: string) => {
  const fetchDaily = vi.fn().mockResolvedValue(puzzle);
  const repository: PuzzleRepository = {
    fetchById: vi.fn().mockRejectedValue(new Error('unused')),
    fetchDaily,
    listDailySummaries: vi.fn().mockResolvedValue({ items: [], hasMore: false }),
  };
  const routeTree = RootRoute.addChildren([GrilleRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [path] }),
    context: {
      puzzleRepository: repository,
      puzzleSolver: stubSolver,
      sessionClient: {
        eraseSession: () => Promise.resolve({ deleted: 0 }),
        getSessionId: () => 'test-session-id',
        clearLocalSession: () => {},
      },
      soloEntriesStore: {
        load: () => [],
        save: () => {},
        loadLockedCells: () => [],
        lockCell: () => {},
        loadHintsUsed: () => 0,
        recordHintUsed: () => {},
        clearForPuzzle: () => {},
      },
      tourSeenStore: { get: () => true, set: () => {}, clear: () => {} },
    },
  });
  return { fetchDaily, ...render(<RouterProvider router={router} />) };
};

describe('/grille ?date= search param', () => {
  it('forwards a well-formed ISO date to fetchDaily', async () => {
    const { fetchDaily } = renderAt('/grille?date=2026-05-05');
    await vi.waitFor(() => {
      expect(fetchDaily).toHaveBeenCalledWith('2026-05-05');
    });
  });

  it('falls back to undefined when ?date= is malformed', async () => {
    const { fetchDaily } = renderAt('/grille?date=not-a-date');
    await vi.waitFor(() => {
      expect(fetchDaily).toHaveBeenCalledWith(undefined);
    });
  });
});
