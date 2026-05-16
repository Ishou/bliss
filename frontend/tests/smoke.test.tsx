import { render, screen, waitFor } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { describe, it, expect, vi } from 'vitest';
import type { PuzzleRepository, PuzzleSolver } from '@/application';
import type { Puzzle } from '@/domain';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/grille';

// Smoke test: the root route renders the "WordSparrow" wordmark as a
// top-level landmark and sets the document title (WCAG 2.4.2). These
// are the minimum behaviors the v1 scaffold must preserve. The route
// loader pulls its puzzle through the application-layer
// `PuzzleRepository` port; we inject an in-memory implementation so
// the test exercises the real loader → component path HTTP-free.
const samplePuzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 'WordSparrow', language: 'fr', width: 1, height: 1, hintsAllowed: 3,
  cells: [{ kind: 'letter', position: { row: 0, col: 0 }, entry: '' }],
};
const puzzleRepository: PuzzleRepository = { fetchById: () => Promise.resolve(samplePuzzle), fetchDaily: () => Promise.resolve(samplePuzzle), listDailySummaries: () => Promise.resolve({ items: [], hasMore: false }) };
const puzzleSolver: PuzzleSolver = {
  validate: vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] }),
  requestHint: vi.fn().mockRejectedValue(new Error('not used')),
};
// Multiplayer context fields are unused on `/` and remain absent
// here, mirroring the production composition root when the
// multiplayer flag is off.
const sessionClient = {
  eraseSession: vi.fn().mockResolvedValue({ deleted: 0 }),
  getSessionId: vi.fn().mockReturnValue('test-session-id'),
  clearLocalSession: vi.fn(),
};
const soloEntriesStore = {
  load: () => [],
  save: vi.fn(),
  loadLockedCells: () => [],
  lockCell: vi.fn(),
  loadHintsUsed: () => 0,
  recordHintUsed: vi.fn(),
  clearForPuzzle: vi.fn(),
};
const tourSeenStore = {
  get: () => true,
  set: vi.fn(),
  clear: vi.fn(),
};
const ctx = { puzzleRepository, puzzleSolver, sessionClient, soloEntriesStore, tourSeenStore };

describe('App smoke test', () => {
  it('renders the WordSparrow heading on the root route', async () => {
    const routeTree = RootRoute.addChildren([IndexRoute]);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/grille'] }),
      context: ctx,
    });

    render(<RouterProvider router={router} />);

    const heading = await screen.findByRole('heading', { level: 1, name: /mots fléchés.*wordsparrow/i });
    expect(heading).toBeInTheDocument();
    // SEO target query lives in French on the H1 itself; ADR-0005 §7's
    // English-pronunciation guarantee for the brand wordmark is preserved
    // by the inner <span lang="en">WordSparrow</span>.
    expect(heading).toHaveAttribute('lang', 'fr');
    const wordmark = heading.querySelector('span[lang="en"]');
    expect(wordmark?.textContent).toBe('WordSparrow');
  });

  it('sets the document title to the grille manifest title on the root route', async () => {
    const routeTree = RootRoute.addChildren([IndexRoute]);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/grille'] }),
      context: ctx,
    });

    render(<RouterProvider router={router} />);

    await waitFor(() => {
      expect(document.title).toBe('Grille du jour — WordSparrow');
    });
  });

  it('renders the mots fléchés grid with at least one letter cell', async () => {
    const routeTree = RootRoute.addChildren([IndexRoute]);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/grille'] }),
      context: ctx,
    });

    const { container } = render(<RouterProvider router={router} />);

    const grid = await screen.findByRole('grid');
    expect(grid).toBeInTheDocument();
    expect(
      container.querySelectorAll('[data-cell-kind="letter"]').length,
    ).toBeGreaterThan(0);
  });
});
