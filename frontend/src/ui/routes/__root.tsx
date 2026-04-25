import { HeadContent, Outlet, createRootRoute } from '@tanstack/react-router';
import { css } from 'styled-system/css';

const errorPageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  padding: 'lg',
  bg: 'bg',
  color: 'fg',
  fontFamily: 'sans',
  textAlign: 'center',
});

const errorTitleStyles = css({
  fontSize: { base: 'xl', md: '2xl' },
  fontWeight: 'bold',
  letterSpacing: '-0.02em',
  margin: 0,
});

const errorMessageStyles = css({
  marginTop: 'sm',
  fontSize: 'md',
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

export const Route = createRootRoute({
  component: () => (
    <>
      <HeadContent />
      <Outlet />
    </>
  ),
  errorComponent: RootErrorBoundary,
});
