import { render, screen } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
} from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import { ContentPage, ViewportPage } from '@/ui/components/layout';

// AppHeader uses `useRouterState` to resolve the active nav id from the
// pathname. We need a real router around the primitive so that hook
// resolves; the simplest setup is a one-route memory router whose path
// is the page being tested.
function renderPage(node: React.ReactNode, pathname: string = '/test') {
  const rootRoute = createRootRoute();
  const testRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: pathname,
    component: () => <>{node}</>,
  });
  const router = createRouter({
    routeTree: rootRoute.addChildren([testRoute]),
    history: createMemoryHistory({ initialEntries: [pathname] }),
  });
  return render(<RouterProvider router={router} />);
}

describe('<ContentPage>', () => {
  it('renders exactly one <main id="main-content" tabIndex="-1">', () => {
    renderPage(<ContentPage>body</ContentPage>);
    const mains = document.querySelectorAll('main#main-content');
    expect(mains).toHaveLength(1);
    expect(mains[0].getAttribute('tabindex')).toBe('-1');
  });

  it('renders <AppHeader> and <Footer>', () => {
    renderPage(<ContentPage>body</ContentPage>);
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
  });

  it('forwards headerActiveNavId to AppHeader (via aria-current)', () => {
    renderPage(
      <ContentPage headerActiveNavId="aide">body</ContentPage>,
    );
    const aideLink = screen.getByRole('link', { name: 'Aide' });
    expect(aideLink.getAttribute('aria-current')).toBe('page');
  });

  it('renders children inside the main', () => {
    renderPage(<ContentPage><p>hello world</p></ContentPage>);
    const main = document.querySelector('main#main-content')!;
    expect(main.textContent).toContain('hello world');
  });
});

describe('<ViewportPage>', () => {
  it('renders exactly one <main id="main-content" tabIndex="-1">', () => {
    renderPage(<ViewportPage>body</ViewportPage>);
    const mains = document.querySelectorAll('main#main-content');
    expect(mains).toHaveLength(1);
    expect(mains[0].getAttribute('tabindex')).toBe('-1');
  });

  it('forwards headerActiveNavId to AppHeader', () => {
    renderPage(
      <ViewportPage headerActiveNavId="grille">body</ViewportPage>,
    );
    const grilleLink = screen.getByRole('link', { name: 'Grille' });
    expect(grilleLink.getAttribute('aria-current')).toBe('page');
  });

  it('renders an optional skip link with the supplied handler', () => {
    const onActivate = vi.fn();
    renderPage(
      <ViewportPage skipLink={{ label: 'Aller à la grille', onActivate }}>
        body
      </ViewportPage>,
    );
    const link = screen.getByRole('link', { name: 'Aller à la grille' });
    link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    expect(onActivate).toHaveBeenCalledTimes(1);
  });

  it('omits the skip link when no slot is provided', () => {
    renderPage(<ViewportPage>body</ViewportPage>);
    expect(
      screen.queryByRole('link', { name: /Aller au mot/ }),
    ).toBeNull();
  });
});
