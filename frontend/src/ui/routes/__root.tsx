import { HeadContent, Outlet, createRootRouteWithContext } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import type { PuzzleRepository } from '@/application';

// Router context surface — every route loader receives this object as
// `ctx.context`. The composition root (`main.tsx`) is the only place
// that wires a concrete `PuzzleRepository`, keeping `ui/` free of
// `infrastructure/` imports (ADR-0002 §7).
export interface AppRouterContext {
  readonly puzzleRepository: PuzzleRepository;
}

const errorPageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  padding: 'lg',
  bg: 'bg',
  color: 'fg',
  fontFamily: 'body',
  textAlign: 'center',
});

const errorTitleStyles = css({
  fontSize: { base: 'xl', md: 'display' },
  fontWeight: 'bold',
  letterSpacing: '-0.02em',
  margin: 0,
});

const errorMessageStyles = css({
  marginTop: 'sm',
  fontSize: 'body',
  opacity: 0.8,
});

function RootErrorBoundary() {
  return (
    <main className={errorPageStyles}>
      <h1 className={errorTitleStyles}>Something went wrong.</h1>
      <p className={errorMessageStyles}>Please reload the page to try again.</p>
    </main>
  );
}

export const Route = createRootRouteWithContext<AppRouterContext>()({
  component: () => (
    <>
      <HeadContent />
      <Outlet />
    </>
  ),
  errorComponent: RootErrorBoundary,
});
