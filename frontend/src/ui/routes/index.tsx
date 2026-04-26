import { createRoute } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import type { Puzzle } from '@/domain';
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

// Wordmark — ADR-0005 §6 (amended). Nunito Variable at the `display`
// size, weight 800, color `leaf.700`, letter-spacing slightly tightened.
// `leaf.700` on `cream` is 6.6:1 (passes AA at display sizes); see the
// ADR's amendment note for why this changed from `ink`.
const wordmarkStyles = css({
  fontFamily: 'heading',
  fontSize: { base: 'display', md: '2.8125rem' },
  fontWeight: 'black',
  letterSpacing: '-0.02em',
  color: 'leaf.700',
  margin: 0,
});

const subtitleStyles = css({
  fontSize: 'body',
  fontWeight: 'regular',
  margin: 0,
  color: 'accent',
});

// "DÉMO" pill — ADR-0005 §4: the only place `blossom` shows up in v1
// (until win states / badges accumulate). `blossom.700` foreground on a
// soft `blossom.50` background gives ≥ 4.5:1 contrast and signals
// "this is a placeholder build" without competing with the wordmark.
const demoBadgeStyles = css({
  fontSize: 'xs',
  fontWeight: 'bold',
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: 'blossom.700',
  bg: 'blossom.50',
  paddingInline: 'sm',
  paddingBlock: 'xs',
  borderRadius: '9999px',
  margin: 0,
});

const statusStyles = css({
  fontSize: 'body',
  margin: 0,
  color: 'accent',
});

// Hard-coded UUID v7 for v1: the Grid API is stateless (per the §404
// note in `grid/api/openapi.yaml`) — every well-formed id yields a
// freshly generated puzzle. Replaced with a route param or a
// client-minted UUID v7 once persistence lands.
const DEFAULT_PUZZLE_ID = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';

function HomeShell({ children }: { children: React.ReactNode }) {
  return (
    <main className={pageStyles}>
      <h1 lang="en" className={wordmarkStyles}>
        WordSparrow
      </h1>
      <span className={demoBadgeStyles} aria-label="version démo">
        Démo
      </span>
      {children}
    </main>
  );
}

function HomePage() {
  const puzzle = Route.useLoaderData() as Puzzle;
  return (
    <HomeShell>
      <p className={subtitleStyles}>{puzzle.title}</p>
      <Grid puzzle={puzzle} />
    </HomeShell>
  );
}

const HomeStatus = ({ role, text }: { role: 'status' | 'alert'; text: string }) => (
  <HomeShell>
    <p className={statusStyles} role={role}>{text}</p>
  </HomeShell>
);

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/',
  loader: ({ context }): Promise<Puzzle> =>
    context.puzzleRepository.fetchById(DEFAULT_PUZZLE_ID),
  component: HomePage,
  pendingComponent: () => <HomeStatus role="status" text="Chargement de la grille…" />,
  errorComponent: ({ error }) => (
    <HomeStatus role="alert" text={`Échec du chargement de la grille : ${error.message}`} />
  ),
  head: () => ({ meta: [{ title: 'WordSparrow' }] }),
});
