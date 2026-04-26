import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { PuzzleRepository } from '@/application';
import type { Puzzle } from '@/domain';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/index';

const puzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 't', language: 'fr', width: 1, height: 1,
  cells: [{ kind: 'letter', position: { row: 0, col: 0 }, entry: '' }],
};

const renderWith = (repository: PuzzleRepository) => {
  const routeTree = RootRoute.addChildren([IndexRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/'] }),
    context: { puzzleRepository: repository },
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
});
