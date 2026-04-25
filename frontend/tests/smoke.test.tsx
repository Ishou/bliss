import { render, screen } from '@testing-library/react';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { describe, it, expect } from 'vitest';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/index';

// Smoke test: the root route renders the "Bliss" heading as a top-level
// landmark. This is the minimum behavior the v1 scaffold must preserve.
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
});
