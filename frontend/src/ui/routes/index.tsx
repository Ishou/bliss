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
  fontFamily: 'body',
  textAlign: 'center',
});

// Wordmark — ADR-0005 §6. Nunito Variable at the `display` size, weight
// 800, color `ink`, letter-spacing slightly tightened. Mobile-first
// 2.5rem; desktop scales 1.125× per ADR-0005 §5. The `lang="en"` on the
// element is required by ADR-0005 §7 so screen readers pronounce the
// English brand name correctly when the surrounding page is French.
const wordmarkStyles = css({
  fontFamily: 'heading',
  fontSize: { base: 'display', md: '2.8125rem' },
  fontWeight: 'black',
  letterSpacing: '-0.02em',
  color: 'fg',
  margin: 0,
});

const subtitleStyles = css({
  fontSize: 'body',
  fontWeight: 'regular',
  margin: 0,
  color: 'accent',
});

function HomePage() {
  return (
    <main className={pageStyles}>
      <h1 lang="en" className={wordmarkStyles}>
        WordSparrow
      </h1>
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
    meta: [{ title: 'WordSparrow' }],
  }),
});
