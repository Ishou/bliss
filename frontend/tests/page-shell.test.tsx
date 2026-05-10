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
//
// TanStack Router renders asynchronously (Transitioner), so every test
// awaits `screen.findBy*` for the first assertion before issuing
// synchronous `screen.getBy*` follow-ups.
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
  it('renders exactly one <main id="main-content" tabIndex="-1">', async () => {
    renderPage(<ContentPage>body</ContentPage>);
    // Wait for the router to finish its async transition before querying.
    await screen.findByText('body');
    const mains = document.querySelectorAll('main#main-content');
    expect(mains).toHaveLength(1);
    expect(mains[0].getAttribute('tabindex')).toBe('-1');
  });

  it('renders <AppHeader> and <Footer>', async () => {
    renderPage(<ContentPage>body</ContentPage>);
    await screen.findByText('body');
    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('contentinfo')).toBeInTheDocument();
  });

  it('forwards headerActiveNavId to AppHeader (via aria-current)', async () => {
    renderPage(
      <ContentPage headerActiveNavId="aide">body</ContentPage>,
    );
    const aideLink = await screen.findByRole('link', { name: 'Aide' });
    expect(aideLink.getAttribute('aria-current')).toBe('page');
  });

  it('renders children inside the main', async () => {
    renderPage(<ContentPage><p>hello world</p></ContentPage>);
    await screen.findByText('hello world');
    const main = document.querySelector('main#main-content')!;
    expect(main.textContent).toContain('hello world');
  });
});

describe('<ViewportPage>', () => {
  it('renders exactly one <main id="main-content" tabIndex="-1">', async () => {
    renderPage(<ViewportPage>body</ViewportPage>);
    await screen.findByText('body');
    const mains = document.querySelectorAll('main#main-content');
    expect(mains).toHaveLength(1);
    expect(mains[0].getAttribute('tabindex')).toBe('-1');
  });

  it('forwards headerActiveNavId to AppHeader', async () => {
    renderPage(
      <ViewportPage headerActiveNavId="grille">body</ViewportPage>,
    );
    const grilleLink = await screen.findByRole('link', { name: 'Grille' });
    expect(grilleLink.getAttribute('aria-current')).toBe('page');
  });

  it('renders an optional skip link with the supplied handler', async () => {
    const onActivate = vi.fn();
    renderPage(
      <ViewportPage skipLink={{ label: 'Aller à la grille', onActivate }}>
        body
      </ViewportPage>,
    );
    const link = await screen.findByRole('link', { name: 'Aller à la grille' });
    link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
    expect(onActivate).toHaveBeenCalledTimes(1);
  });

  it('omits the skip link when no slot is provided', async () => {
    renderPage(<ViewportPage>body</ViewportPage>);
    await screen.findByText('body');
    expect(
      document.querySelector('main#main-content a[href="#main-content"]'),
    ).toBeNull();
  });
});
