import { createRoute, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { css } from 'styled-system/css';
import type { Puzzle } from '@/domain';
import { LobbyClientError } from '@/application/game';
import { Grid } from '@/ui/components/grid';
import { Button } from '@/ui/components/primitives';
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

// Multiplayer call-to-action — only mounted when
// `VITE_FEATURE_MULTIPLAYER === 'true'` (ADR-0018 §10). Solid `leaf.700`
// `Button` (primary variant) keeps the brand primary in line with the
// other call sites; the wider `paddingInline` matches the legacy
// `lg` padding so the CTA still reads as the page's hero affordance.
const createLobbyButtonStyles = css({
  paddingInline: 'lg',
});

const createLobbyErrorStyles = css({
  fontSize: 'body',
  margin: 0,
  color: 'blossom.700',
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
