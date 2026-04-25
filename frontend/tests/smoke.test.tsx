import { render, screen, waitFor } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { describe, it, expect } from 'vitest';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/index';

// Smoke test: the root route renders the "Bliss" heading as a top-level
// landmark and sets the document title (WCAG 2.4.2). These are the minimum
// behaviors the v1 scaffold must preserve.
describe('App smoke test', () => {
  it('renders the Bliss heading on the root route', async () => {
    const routeTree = RootRoute.addChildren([IndexRoute]);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/'] }),
    });

    render(<RouterProvider router={router} />);

    const heading = await screen.findByRole('heading', { level: 1, name: /bliss/i });
    expect(heading).toBeInTheDocument();
  });

  it('sets the document title to "Bliss" on the root route', async () => {
    const routeTree = RootRoute.addChildren([IndexRoute]);
    const router = createRouter({
      routeTree,
      history: createMemoryHistory({ initialEntries: ['/'] }),
    });

    render(<RouterProvider router={router} />);

    await waitFor(() => {
      expect(document.title).toBe('Bliss');
    });
  });
});
