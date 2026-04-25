import { createRoute } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import { SAMPLE_PUZZLE } from '@/domain';
import { Grid } from '@/ui/components/grid';
import { Route as RootRoute } from './__root';

const pageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'flex-start',
  gap: 'md',
  padding: 'lg',
  bg: 'bg',
  color: 'fg',
  fontFamily: 'sans',
  textAlign: 'center',
});

const titleStyles = css({
  fontSize: { base: 'xl', md: '2xl' },
  fontWeight: 'bold',
  letterSpacing: '-0.02em',
  margin: 0,
});

const subtitleStyles = css({
  fontSize: 'md',
  margin: 0,
  color: 'muted',
});

function HomePage() {
  return (
    <main className={pageStyles}>
      <h1 className={titleStyles}>Bliss</h1>
      <p className={subtitleStyles}>{SAMPLE_PUZZLE.title}</p>
      <Grid puzzle={SAMPLE_PUZZLE} />
    </main>
  );
}

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/',
  component: HomePage,
  head: () => ({
    meta: [{ title: 'Bliss' }],
  }),
});
