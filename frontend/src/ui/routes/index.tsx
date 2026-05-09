import { createRoute, useNavigate, useRouter } from '@tanstack/react-router';
import { useCallback, useMemo, useRef, useState } from 'react';
import { css } from 'styled-system/css';
import { normalizeAnswerLetter, type Position, type Puzzle } from '@/domain';
import { LobbyClientError } from '@/application/game';
import {
  Grid,
  HintControl,
  useHintRequest,
  useWordAutoValidation,
} from '@/ui/components/grid';
import { wordRange } from '@/ui/components/grid/wordRange';
import { Button } from '@/ui/components/primitives';
import {
  AppHeader,
  Footer,
  ProgressBar,
  PuzzleToolbar,
} from '@/ui/components/layout';
import { SoloTour, useSoloTour } from '@/ui/components/tour';
import { Route as RootRoute } from './__root';

type ActiveFocus = { readonly position: Position; readonly direction: 'across' | 'down' };

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
      <AppHeader />
      <main className={mainStyles}>
        <div className={contentStyles}>{children}</div>
      </main>
      <Footer />
    </div>
  );
}

function HomePage() {
  const puzzle = Route.useLoaderData() as Puzzle;
  const router = useRouter();
  const navigate = useNavigate();
  const { puzzleSolver, soloEntriesStore, tourSeenStore } = Route.useRouteContext();
  const hint = useHintRequest(puzzle.id, puzzle.hintsAllowed, puzzleSolver);

  // Onboarding tour. `?tour=1` (set by the Aide page button) forces it
  // open even after first visit; the flag is then stripped from the URL
  // once the tour closes so a reload doesn't replay it forever.
  const tourSearch = Route.useSearch();
  const forcedOpen = tourSearch.tour === 1;
  const tour = useSoloTour({
    tourSeenStore,
    forcedOpen,
    onForcedOpenConsumed: () => {
      void navigate({ to: '/', search: {}, replace: true });
    },
  });

  // Active word seam: the Grid emits `onLocalFocusChange(position,
  // direction)` whenever focus or direction changes. We stash it in a
  // ref so the hint button can read the current word from the DOM at
  // click time without forcing a re-render on every keystroke (the
  // uncontrolled-input contract per ADR-0002 §4 — keystrokes never
  // touch React state in the typing path).
  const activeFocusRef = useRef<ActiveFocus | null>(null);
  const handleLocalFocusChange = useCallback(
    (position: Position | null, direction: 'across' | 'down' | null) => {
      // Keep the last non-null focus. The toolbar's hint button is
      // outside the grid, so clicking it blurs the cell input — and
      // React 18 flushes the resulting (null, null) focus-change effect
      // *between* the blur and the click. Clearing here would race
      // against `getCurrentWord` reading this ref in `onClick`.
      if (position && direction) {
        activeFocusRef.current = { position, direction };
      }
    },
    [],
  );

  // Refresh counter — bumped on every refresh and used as Grid's `key`
  // so React remounts the cell tree. Letter cells are uncontrolled
  // (ADR-0002 §4: values live in the DOM), so a re-render alone leaves
  // the player's typed letters in place. Remounting via key forces a
  // fresh `defaultValue={cell.entry}` pass on each `<input>`, which is
  // the only seam that clears the DOM.
  const [refreshCount, setRefreshCount] = useState(0);
  const handleRefresh = useCallback(() => {
    // Must clear before bumping refreshCount — initialEntries reads storage on the next render.
    soloEntriesStore.clearForPuzzle(puzzle.id);
    setRefreshCount((n) => n + 1);
    void router.invalidate();
  }, [router, soloEntriesStore, puzzle.id]);

  // void refreshCount forces a storage re-read after "Actualiser" clears — without depending on the value.
  const initialEntries = useMemo(() => {
    void refreshCount;
    return soloEntriesStore.load(puzzle.id);
  }, [puzzle.id, refreshCount, soloEntriesStore]);

  // Word-by-word auto-validation: when the player completes a word,
  // its cells lock if every letter matches. Wrong words drop silently
  // (the product decision per ADR-0005 §6: incorrect fills must be
  // visually indistinguishable from in-progress ones). The progress
  // bar reflects the running tally of locked cells.
  //
  // Passing `initialEntries` here rehydrates locks earned in a prior
  // session: without it, a page reload re-paints the typed letters
  // (cells are uncontrolled, populated via `defaultValue` from
  // `initialEntries`) but every word would be back to editable and the
  // progress bar would read zero. The hook walks the persisted entries
  // once per puzzle and POSTs `validate` in a single round-trip.
  const autoValidation = useWordAutoValidation(puzzle, puzzleSolver, initialEntries);

  const handleCellChange = useCallback(
    (row: number, col: number, letter: string | null) => {
      soloEntriesStore.save(puzzle.id, row, col, letter);
    },
    [soloEntriesStore, puzzle.id],
  );

  const totalLetterCells = useMemo<number>(
    () => puzzle.cells.reduce((n, c) => (c.kind === 'letter' ? n + 1 : n), 0),
    [puzzle.cells],
  );

  // Build the current word from the focused cell's word-range, reading
  // each cell's value from the DOM. Returns `null` when no focus, or
  // when any cell of the word is empty / non-letter (a partial word
  // can't be checked against the corpus).
  const getCurrentWord = useCallback((): string | null => {
    const focus = activeFocusRef.current;
    if (!focus) return null;
    const range = wordRange(puzzle, focus.position, focus.direction);
    if (range.length < 2) return null;
    let out = '';
    for (const pos of range) {
      const input = document.querySelector<HTMLInputElement>(
        `input[data-cell-kind="letter"][data-row="${pos.row}"][data-col="${pos.col}"]`,
      );
      const normalized = normalizeAnswerLetter(input?.value ?? '');
      if (!normalized) return null;
      out += normalized;
    }
    return out.toLowerCase();
  }, [puzzle]);

  return (
    <PageShell>
      <h1 lang="en" className={srOnly}>
        WordSparrow
      </h1>
      <PuzzleToolbar
        metadata={puzzle.title}
        onRefresh={handleRefresh}
        hintSlot={
          <HintControl
            hintsRemaining={hint.hintsRemaining}
            hintsAllowed={puzzle.hintsAllowed}
            exhausted={hint.exhausted}
            pending={hint.pending}
            lastResult={hint.lastResult}
            errorMessage={hint.errorMessage}
            getCurrentWord={getCurrentWord}
            onRequest={hint.request}
          />
        }
      />
      <div className={gridPanelStyles}>
        <Grid
          key={refreshCount}
          puzzle={puzzle}
          validatedPositions={autoValidation.validated}
          onCellFilled={autoValidation.onCellFilled}
          onLocalFocusChange={handleLocalFocusChange}
          initialEntries={initialEntries}
          onCellChange={handleCellChange}
        />
      </div>
      <ProgressBar
        value={autoValidation.validated.size}
        total={totalLetterCells}
      />
      {isMultiplayerEnabled() ? <CreateLobbyButton /> : null}
      <SoloTour tour={tour} />
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

// CSS-only skeleton for the home route. Mirrors the real page rhythm
// (toolbar row, grid panel, bottom row) so the layout doesn't jump when
// the loader resolves. `prefers-reduced-motion` disables the pulse for
// users with vestibular sensitivity.
const skeletonPulse = css({
  bg: 'surfaceElevated',
  borderRadius: '6px',
  animation: 'wordsparrow-skeleton-pulse 1.4s ease-in-out infinite',
});

const skeletonToolbarStyles = css({
  width: '100%',
  height: { base: '36px', md: '44px' },
});

const skeletonGridStyles = css({
  width: '100%',
  flex: '1 1 0',
  minHeight: 0,
  display: 'grid',
  // 10×10 grid of placeholder cells reads as "puzzle is on its way".
  gridTemplateColumns: 'repeat(10, 1fr)',
  gridTemplateRows: 'repeat(10, 1fr)',
  gap: '2px',
  borderRadius: '12px',
  overflow: 'hidden',
});

const skeletonCellStyles = css({
  bg: 'bg',
  opacity: 0.6,
});

const skeletonBottomRowStyles = css({
  display: 'flex',
  width: '100%',
  alignItems: { base: 'stretch', md: 'flex-end' },
  flexDirection: { base: 'column', md: 'row' },
  gap: { base: '12px', md: '20px' },
});

const skeletonProgressStyles = css({
  flex: 1,
  minWidth: 0,
  height: '32px',
});

const skeletonButtonStyles = css({
  width: { base: '100%', md: '120px' },
  height: '40px',
});

function HomeSkeleton() {
  // 100 placeholder cells (10×10) reads cheaply on first paint and matches
  // the typical puzzle density — the real grid replaces it in place.
  const cells = Array.from({ length: 100 });
  return (
    <PageShell>
      <div className={`${skeletonPulse} ${skeletonToolbarStyles}`} aria-hidden />
      <div className={gridPanelStyles}>
        <div className={skeletonGridStyles} aria-hidden>
          {cells.map((_, i) => (
            <div key={i} className={skeletonCellStyles} />
          ))}
        </div>
      </div>
      <div className={skeletonBottomRowStyles} aria-hidden>
        <div className={`${skeletonPulse} ${skeletonProgressStyles}`} />
        <div className={`${skeletonPulse} ${skeletonButtonStyles}`} />
      </div>
      <p className={srOnly} role="status">
        Chargement de la grille…
      </p>
    </PageShell>
  );
}

// `?tour=1` re-opens the onboarding tour from the Aide page. Any other
// value (or absence) parses to `undefined`, which the home page reads
// as "not forced open".
export interface IndexSearch {
  readonly tour?: 1;
}

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/',
  validateSearch: (search: Record<string, unknown>): IndexSearch =>
    search.tour === 1 || search.tour === '1' ? { tour: 1 } : {},
  loader: ({ context }): Promise<Puzzle> =>
    context.puzzleRepository.fetchById(DEFAULT_PUZZLE_ID),
  component: HomePage,
  pendingComponent: HomeSkeleton,
  errorComponent: ({ error }) => (
    <HomeStatus role="alert" text={`Échec du chargement de la grille : ${error.message}`} />
  ),
  head: () => ({ meta: [{ title: 'WordSparrow' }] }),
});
