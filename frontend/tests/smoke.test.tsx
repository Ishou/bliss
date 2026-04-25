import { render, screen, waitFor } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { describe, it, expect } from 'vitest';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/index';

// Smoke test: the root route renders the "WordSparrow" wordmark as a
// top-level landmark and sets the document title (WCAG 2.4.2). These
// are the minimum behaviors the v1 scaffold must preserve.
describe('App smoke test', () => {
  it('renders the WordSparrow heading on the root route', async () => {
    const routeTree = RootRoute.addChildren([IndexRoute]);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/'] }),
    });

    render(<RouterProvider router={router} />);

    const heading = await screen.findByRole('heading', { level: 1, name: /wordsparrow/i });
    expect(heading).toBeInTheDocument();
    // ADR-0005 §7: the wordmark carries lang="en" so screen readers
    // pronounce the English brand name correctly under a fr-FR root.
    expect(heading).toHaveAttribute('lang', 'en');
  });

  it('sets the document title to "WordSparrow" on the root route', async () => {
    const routeTree = RootRoute.addChildren([IndexRoute]);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/'] }),
    });

    render(<RouterProvider router={router} />);

    await waitFor(() => {
      expect(document.title).toBe('WordSparrow');
    });
  });

  it('renders the mots fléchés grid with at least one letter cell', async () => {
    const routeTree = RootRoute.addChildren([IndexRoute]);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/'] }),
    });

    const { container } = render(<RouterProvider router={router} />);

    const grid = await screen.findByRole('grid');
    expect(grid).toBeInTheDocument();
    expect(
      container.querySelectorAll('[data-cell-kind="letter"]').length,
    ).toBeGreaterThan(0);
  });
});
