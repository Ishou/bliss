import { createRoute, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { css } from 'styled-system/css';
import type { Puzzle } from '@/domain';
import { LobbyClientError } from '@/application/game';
import { Grid } from '@/ui/components/grid';
import { Button } from '@/ui/components/primitives';
import { Route as RootRoute } from './__root';

// 100dvh: height tracks iOS Safari's visible viewport as the URL bar collapses.
const pageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'flex-start',
  gap: { base: 'sm', md: 'md' },
  paddingBlock: { base: 'sm', md: 'lg' },
  paddingInline: { base: 'sm', md: 'lg' },
  bg: 'bg',
  color: 'fg',
  fontFamily: 'body',
  textAlign: 'center',
});

// Wordmark — ADR-0005 §6. Nunito Variable at the `display` size,
// weight 800, color `accent` (= primary.400 in the dark twilight
// theme), letter-spacing slightly tightened.
const wordmarkStyles = css({
  fontFamily: 'heading',
  fontSize: { base: 'xl', md: '2.8125rem' },
  fontWeight: 'black',
  letterSpacing: '-0.02em',
  color: 'accent',
  margin: 0,
  lineHeight: '1.1',
});

const subtitleStyles = css({
  fontSize: 'body',
  fontWeight: 'regular',
  margin: 0,
  color: 'accent',
});

// "DÉMO" pill — uses the secondary brand colour (was `blossom`) for
// the only visual splash that isn't `accent` on the home page. Direct
// ramp shades (rather than the `secondary*` semantic tokens) so the
// pill keeps its current light-pink-on-pink visual under the dark
// twilight palette; the semantic `secondaryBg` resolves to a deeper
// surface that wouldn't pop the same way.
const demoBadgeStyles = css({
  fontSize: 'xs',
  fontWeight: 'bold',
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  color: 'secondary.700',
  bg: 'secondary.50',
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

// Multiplayer call-to-action — only mounted when
// `VITE_FEATURE_MULTIPLAYER === 'true'` (ADR-0018 §10). Solid primary
// `Button` (primary variant) keeps the brand colour in line with the
// other call sites; the wider `paddingInline` matches the legacy
// `lg` padding so the CTA still reads as the page's hero affordance.
const createLobbyButtonStyles = css({
  paddingInline: 'lg',
});

const createLobbyErrorStyles = css({
  fontSize: 'body',
  margin: 0,
  color: 'errorText',
});

// Hard-coded UUID v7 for v1: the Grid API is stateless (per the §404
// note in `grid/api/openapi.yaml`) — every well-formed id yields a
// freshly generated puzzle. Replaced with a route param or a
// client-minted UUID v7 once persistence lands.
const DEFAULT_PUZZLE_ID = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';

// Multiplayer feature flag (ADR-0018 §10). Read once per render — when
// off, the CTA is not mounted at all and the solo flow above is the
// only thing on `/`. Reading `import.meta.env` here keeps the seam
// inside `ui/` (no `infrastructure/` import); tests stub it via
// `vi.stubEnv('VITE_FEATURE_MULTIPLAYER', '...')`.
const isMultiplayerEnabled = (): boolean =>
  import.meta.env.VITE_FEATURE_MULTIPLAYER === 'true';

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
      {isMultiplayerEnabled() ? <CreateLobbyButton /> : null}
    </HomeShell>
  );
}

// Self-contained multiplayer entry-point. Reads `lobbyClient` and
// `getSession` from the route context (plumbed by the lobby-route wiring);
// on click, mints a lobby and navigates to `/lobby/:lobbyId`. Failures map
// to the same `LobbyClientError.kind` switch used by the lobby route's
// error boundary so copy stays consistent across the multiplayer surface.
function CreateLobbyButton() {
  const navigate = useNavigate();
  const ctx = Route.useRouteContext();
  // Adapters are guaranteed present when the multiplayer flag is on:
  // the composition root in `main.tsx` wires them in that branch only.
  const lobbyClient = ctx.lobbyClient!;
  const getSession = ctx.getSession!;
  const [pending, setPending] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const handleClick = async () => {
    setPending(true);
    setErrorMessage(null);
    try {
      const { sessionId, pseudonym } = getSession();
      const created = await lobbyClient.createLobby({
        ownerSessionId: sessionId,
        ownerPseudonym: pseudonym,
      });
      await navigate({ to: '/lobby/$lobbyId', params: { lobbyId: created.id } });
    } catch (err) {
      setErrorMessage(messageForError(err));
      setPending(false);
    }
  };

  return (
    <>
      <Button
        variant="primary"
        className={createLobbyButtonStyles}
        disabled={pending}
        onClick={() => {
          void handleClick();
        }}
      >
        {pending ? 'Création…' : 'Créer une partie multijoueur'}
      </Button>
      {errorMessage != null ? (
        <p className={createLobbyErrorStyles} role="alert">
          {errorMessage}
        </p>
      ) : null}
    </>
  );
}

// Mirrors the lobby route's `LobbyErrorComponent` so the user sees
// consistent copy whether the failure happens at create-time on
// `/` or at load-time on `/lobby/:id`. `not-found` is unreachable for
// `createLobby` (the server never returns 404) but is included for
// totality so future kinds light up the type-checker.
function messageForError(err: unknown): string {
  if (err instanceof LobbyClientError) {
    switch (err.kind) {
      case 'upstream-unavailable':
        return 'Service indisponible. Réessayez dans un instant.';
      case 'validation':
      case 'transient':
      case 'not-found':
        return 'Une erreur est survenue. Réessayez.';
    }
  }
  return 'Une erreur est survenue. Réessayez.';
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
