import { render, screen } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { describe, expect, it } from 'vitest';
import { ContentPage } from '@/ui/components/layout';

function renderAtPath(pathname: string) {
  const rootRoute = createRootRoute();
  const route = createRoute({
    getParentRoute: () => rootRoute,
    path: pathname,
    component: () => <ContentPage>body</ContentPage>,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([route]),
    history: createMemoryHistory({ initialEntries: [pathname] }),
  });
  return render(<RouterProvider router={router} />);
}

describe('AppHeader alpha indicator', () => {
  it('renders the alpha badge inside the banner', async () => {
    renderAtPath('/grille');
    await screen.findByText('body');
    const banner = screen.getByRole('banner');
    const badge = screen.getByLabelText('version alpha');
    expect(banner.contains(badge)).toBe(true);
    expect(badge.textContent).toBe('Alpha');
  });
});
