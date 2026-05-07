import { createRoute, useNavigate, useRouter } from '@tanstack/react-router';
import { useCallback, useState } from 'react';
import { css } from 'styled-system/css';
import type { Puzzle } from '@/domain';
import { LobbyClientError } from '@/application/game';
import { Grid, useValidation } from '@/ui/components/grid';
import { Button } from '@/ui/components/primitives';
import {
  AppHeader,
  ProgressBar,
  PuzzleToolbar,
} from '@/ui/components/layout';
import { Route as RootRoute } from './__root';

// Top-level page shell. The header sits above the puzzle area, the
// puzzle area takes the remaining viewport (`flex: 1 1 0; minHeight: 0`
// so the inner grid shell can absorb leftover height — see Grid.tsx for
// the rationale).
const pageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  color: 'fg',
  fontFamily: 'body',
});

// `<main>` carries the charbon background so e2e probes of the page bg
// (`getComputedStyle(main).backgroundColor`) resolve through the
// semantic role token, not the parent shell's inheritance. The header
// (above) sets its own `bg: 'bg'`, so the whole viewport reads
// charbon-on-charbon without depending on body / html paint.
const mainStyles = css({
  flex: '1 1 0',
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  width: '100%',
  bg: 'bg',
});

// Inner content column — bounds the toolbar / grid / progress row to
// the brief's 720 px desktop ceiling without putting the cap on
// `<main>` itself (which has to span the full viewport so the bg
// paints edge-to-edge).
const contentStyles = css({
  width: '100%',
  maxWidth: '720px',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  margin: '0 auto',
  paddingInline: { base: '16px', md: '20px' },
  paddingBlock: { base: '12px', md: '20px' },
  gap: { base: '12px', md: '18px' },
  flex: '1 1 0',
  minHeight: 0,
});

// Lighter charcoal panel behind the grid — mockup §5 shows the grid
// sitting inside an elevated dark surface that visually separates the
// puzzle from the page background. `surfaceElevated` (= neutral.600)
// is the brand's pre-defined raised-surface role; padding keeps the
// grid off the panel edge without colliding with the grid's own
// container-query sizing (the inner Grid still squares against
// `min(100cqw, 100cqh, …)` of THIS box).
const gridPanelStyles = css({
  width: '100%',
  flex: '1 1 0',
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column',
  bg: 'surfaceElevated',
  borderRadius: '12px',
  // Mobile padding stays minimal so the grid doesn't lose any cell
  // width — the FitText algorithm (Cell.tsx) needs every pixel on
  // mobile-tiny viewports to keep clue ratios above the e2e gate.
  padding: { base: '4px', md: '12px' },
});

// Bottom row groups the progress bar and the Vérifier CTA. On mobile
// the brief makes the button full-width and stacks it under the bar; on
// desktop the bar takes the available width and the button sits inline
// to its right.
const bottomRowStyles = css({
  display: 'flex',
  width: '100%',
  alignItems: { base: 'stretch', md: 'flex-end' },
  flexDirection: { base: 'column', md: 'row' },
  gap: { base: '12px', md: '20px' },
});

const progressSlotStyles = css({ flex: 1, minWidth: 0 });

const verifyButtonStyles = css({
  width: { base: '100%', md: 'auto' },
  paddingInline: '28px',
  paddingBlock: '12px',
});

// Shared visually-hidden style — used by the page-level h1 (the visible
// brand mark is the styled Lockup in the header; the h1 keeps the WCAG
// heading hierarchy a real one) and the aria-live status region.
const srOnly = css({
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
});

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

function PageShell({ children }: { children: React.ReactNode }) {
  return (
    <div className={pageStyles}>
      <AppHeader activeNavId="grilles" />
      <main className={mainStyles}>
        <div className={contentStyles}>{children}</div>
      </main>
    </div>
  );
}

function HomePage() {
  const puzzle = Route.useLoaderData() as Puzzle;
  const router = useRouter();
  const validation = useValidation(puzzle);
  // Refresh counter — bumped on every refresh and used as Grid's `key`
  // so React remounts the cell tree. Letter cells are uncontrolled
  // (ADR-0002 §4: values live in the DOM), so a re-render alone leaves
  // the player's typed letters in place. Remounting via key forces a
  // fresh `defaultValue={cell.entry}` pass on each `<input>`, which is
  // the only seam that clears the DOM.
  const [refreshCount, setRefreshCount] = useState(0);
  const handleRefresh = useCallback(() => {
    setRefreshCount((n) => n + 1);
    // The Grid API is stateless (per the §404 note in
    // `grid/api/openapi.yaml`) — invalidating refetches and yields a
    // fresh puzzle. Remount + new puzzle in one beat.
    void router.invalidate();
  }, [router]);

  const isComplete =
    validation.totalLetterCells > 0 &&
    validation.validated.size === validation.totalLetterCells;

  // Auto-trigger validation after every cell write so a word locks
  // (or shakes) the moment the player completes its last letter — no
  // explicit `Vérifier` click needed. The `onCellChange` fires from
  // useGridNavigation after the DOM input value is already updated,
  // so `verify()`'s DOM reads see the just-typed letter. Wrapped in
  // a microtask to keep the navigation handler synchronous (focus +
  // direction changes finish before validation runs).
  const verify = validation.verify;
  const handleCellChange = useCallback(() => {
    queueMicrotask(verify);
  }, [verify]);

  return (
    <PageShell>
      <h1 lang="en" className={srOnly}>
        WordSparrow
      </h1>
      <PuzzleToolbar metadata={puzzle.title} onRefresh={handleRefresh} />
      <div className={gridPanelStyles}>
        <Grid
          key={refreshCount}
          puzzle={puzzle}
          validatedPositions={validation.validated}
          errorPositions={validation.errors}
          onCellChange={handleCellChange}
        />
      </div>
      <div className={bottomRowStyles}>
        <div className={progressSlotStyles}>
          <ProgressBar
            value={validation.validated.size}
            total={validation.totalLetterCells}
          />
        </div>
        <Button
          variant="primary"
          className={verifyButtonStyles}
          onClick={validation.verify}
          disabled={isComplete}
          aria-label="Vérifier la grille"
        >
          Vérifier
        </Button>
      </div>
      <p className={srOnly} role="status" aria-live="polite">
        {validation.announce}
      </p>
      {isMultiplayerEnabled() ? <CreateLobbyButton /> : null}
    </PageShell>
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

const HomeStatusStyles = css({
  fontSize: 'body',
  margin: 0,
  color: 'accent',
  textAlign: 'center',
});

const HomeStatus = ({ role, text }: { role: 'status' | 'alert'; text: string }) => (
  <PageShell>
    <p className={HomeStatusStyles} role={role}>{text}</p>
  </PageShell>
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
