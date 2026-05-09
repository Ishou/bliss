import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { PuzzleRepository, PuzzleSolver } from '@/application';
import type { Puzzle } from '@/domain';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/index';

const puzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 't', language: 'fr', width: 1, height: 1, hintsAllowed: 3,
  cells: [{ kind: 'letter', position: { row: 0, col: 0 }, entry: '' }],
};

const stubSolver: PuzzleSolver = {
  validate: vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] }),
  requestHint: vi.fn().mockRejectedValue(new Error('not used')),
};

const renderWith = (repository: PuzzleRepository) => {
  const routeTree = RootRoute.addChildren([IndexRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/'] }),
    // Multiplayer context fields are unused on `/` and remain absent
    // here, mirroring the production root when the flag is off.
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
        clearForPuzzle: () => {},
      },
      tourSeenStore: {
        get: () => true,
        set: () => {},
        clear: () => {},
      },
    },
  });
  return render(<RouterProvider router={router} />);
};

describe('Index route loader', () => {
  it('asks the repository for a UUID-shaped default puzzle id exactly once', async () => {
    const fetchById = vi.fn().mockResolvedValue(puzzle);
    renderWith({ fetchById });
    await screen.findByRole('grid');
    expect(fetchById).toHaveBeenCalledTimes(1);
    expect(fetchById).toHaveBeenCalledWith(expect.stringMatching(/^[0-9a-f-]{36}$/));
  });

  it('renders the error component when the repository rejects', async () => {
    renderWith({
      fetchById: vi.fn().mockRejectedValue(new Error('puzzle fetch failed: Out of attempts')),
    });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/Out of attempts/);
    expect(screen.queryByRole('grid')).toBeNull();
  });

  it('exposes the hint affordance with the per-puzzle budget badge', async () => {
    const fetchById = vi.fn().mockResolvedValue(puzzle);
    renderWith({ fetchById });
    await screen.findByRole('grid');
    expect(
      screen.getByRole('button', { name: 'Demander un indice' }),
    ).toBeInTheDocument();
    expect(
      screen.getByLabelText('3 sur 3 indices restants'),
    ).toHaveTextContent('3/3');
  });
});
