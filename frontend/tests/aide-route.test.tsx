import { fireEvent, render, screen } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRouter,
} from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { Puzzle } from '@/domain';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as AideRoute } from '@/ui/routes/aide';
import { Route as IndexRoute } from '@/ui/routes/grille';

const stubPuzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 't',
  language: 'fr',
  width: 1,
  height: 1,
  hintsAllowed: 3,
  cells: [{ kind: 'letter', position: { row: 0, col: 0 }, entry: '' }],
};

const buildContext = () => ({
  puzzleRepository: { fetchById: vi.fn().mockResolvedValue(stubPuzzle) },
  puzzleSolver: {
    validate: vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] }),
    requestHint: vi.fn().mockRejectedValue(new Error('not used')),
  },
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
    clearForPuzzle: () => {},
  },
  tourSeenStore: {
    get: () => true,
    set: () => {},
    clear: () => {},
  },
});

const renderAide = (initialEntry = '/aide') => {
  const routeTree = RootRoute.addChildren([IndexRoute, AideRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialEntry] }),
    context: buildContext(),
  });
  return { router, ...render(<RouterProvider router={router} />) };
};

describe('Aide route', () => {
  it('renders the page heading and tour launcher card', async () => {
    renderAide();
    expect(await screen.findByRole('heading', { name: 'Aide', level: 1 }))
      .toBeInTheDocument();
    expect(
      screen.getByRole('heading', { name: "Lancer le tour d'accueil", level: 2 }),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lancer le tour' }))
      .toBeInTheDocument();
  });

  it('renders all five help-section triggers', async () => {
    renderAide();
    await screen.findByRole('button', { name: 'Comment jouer' });
    expect(screen.getByRole('button', { name: 'Raccourcis clavier' }))
      .toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Validation et indices' }))
      .toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Multijoueur' }))
      .toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Nous contacter' }))
      .toBeInTheDocument();
  });

  it('navigates to "/grille" with ?tour=1 when the launcher button is clicked', async () => {
    const { router } = renderAide();
    const launcher = await screen.findByRole('button', {
      name: 'Lancer le tour',
    });
    fireEvent.click(launcher);
    // TanStack Router resolves the navigation asynchronously; wait until
    // the location reflects the new search param.
    await vi.waitFor(() => {
      expect(router.state.location.pathname).toBe('/grille');
      expect(router.state.location.search).toMatchObject({ tour: 1 });
    });
  });
});
