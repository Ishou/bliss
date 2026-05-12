import { createRoute, useNavigate } from '@tanstack/react-router';
import { useEffect, useLayoutEffect, useState } from 'react';
import { css } from 'styled-system/css';
import type { Puzzle } from '@/domain';
import { LobbyClientError, type LobbySummary } from '@/application/game';
import { LOBBY_CODE_PATTERN, extractLobbyCode } from '@/domain/game/lobbyCode';
import { EyeIcon, EyeOffIcon } from '@/ui/components/icons';
import { Button } from '@/ui/components/primitives';
import { PinInput } from '@/ui/components/primitives/PinInput';
import { ContentPage, ProgressBar } from '@/ui/components/layout';
import { MyLobbiesSection } from '@/ui/components/lobby/MyLobbiesSection';
import { buildHead, INDEXABLE_ROUTES, SITE_BASE_URL, organizationJsonLd } from '@/ui/seo';
import { Route as RootRoute } from './__root';

// Loader return shape — only the calling session's lobby list
// (ADR-0039 "Mes parties"). The daily-puzzle fetch is intentionally
// NOT in the loader: a slow daily endpoint must not block the
// Multijoueur card from rendering. The Grille du jour card fetches
// the daily client-side via `useEffect` and manages its own
// loading / error / ready state — the rest of the page paints
// immediately with whatever lobbies came back.
export interface AccueilLoaderData {
  readonly lobbies: readonly LobbySummary[];
}

// Internal state machine for the daily card. The `error` variant is
// reached either when `fetchDaily` rejects or when the user clicks
// Réessayer while another attempt is in flight (handled inside the
// card). Mirrors a Result union with an explicit `loading` arm so the
// component renders deterministically without a separate boolean.
type DailyState =
  | { readonly status: 'loading' }
  | { readonly status: 'ok'; readonly puzzle: Puzzle }
  | { readonly status: 'error' };

// Accueil (home) — landing page introduced after the action-bar
// revamp. Two cards, side-by-side on desktop, stacked on mobile:
// Grille du jour (date + progress + resume CTA) and Multijoueur
// (create + disabled join-by-code).
//
// Backend gaps the UI defers to follow-up PRs:
//  - No archive route — the "Anciennes grilles" link renders disabled.
//  - No join-by-code endpoint — input + Rejoindre render disabled.

const isMultiplayerEnabled = (): boolean =>
  import.meta.env.VITE_FEATURE_MULTIPLAYER === 'true';

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

// Two-column on md+ (mockup desktop), single column on mobile.
//
// `align-items: start` (rather than the default `stretch`): when one
// card grows — typically the Multijoueur card surfacing a "code
// introuvable" error below the Rejoindre button — we do NOT want the
// Grille card to grow with it. Stretch would propagate the new
// height to the sibling and (combined with `marginTop: auto` on the
// Grille card's footer) shift the Reprendre button down. Top-align
// keeps each card at its natural height; minor visual asymmetry is
// the trade we accept for a stable layout under error states.
//
// `minmax(0, 1fr)` instead of plain `1fr`: `1fr` resolves to
// `minmax(auto, 1fr)`, which refuses to shrink a track below its
// min-content width. The Multijoueur card's PIN row + eye-toggle has
// a wider min-content than the viewport on narrow phones (≤375 px),
// so plain `1fr` would inflate the mobile track and overflow the
// page-shell paddingInline. Forcing the min to 0 lets the track shrink
// below its children's min-content, and the children (PinInput root,
// flex slots) absorb the squeeze via their own `minWidth: 0`.
const cardsGridStyles = css({
  display: 'grid',
  gridTemplateColumns: {
    base: 'minmax(0, 1fr)',
    md: 'minmax(0, 1fr) minmax(0, 1fr)',
  },
  alignItems: 'start',
  gap: 'md',
});

const cardStyles = css({
  bg: 'surface',
  borderRadius: 'md',
  padding: 'lg',
  border: '1px solid token(colors.border)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'md',
});

const cardTitleStyles = css({
  fontSize: 'xl',
  fontWeight: 'semibold',
  margin: 0,
  color: 'fg',
});

const cardSubtitleStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
  margin: 0,
});

// Pushes the trailing CTA / link group to the bottom of the card so
// both cards visually balance at equal heights on desktop.
const cardFooterStyles = css({
  marginTop: 'auto',
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
});

const tertiaryLinkStyles = css({
  alignSelf: 'center',
  bg: 'transparent',
  border: 'none',
  padding: 'xs',
  fontFamily: 'body',
  fontSize: 'sm',
  color: 'fgMuted',
  cursor: 'pointer',
  transition: 'color 120ms ease-out',
  _hover: { color: 'fg' },
  _disabled: { opacity: 0.5, cursor: 'not-allowed', _hover: { color: 'fgMuted' } },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
    borderRadius: '2px',
  },
});

const dividerStyles = css({
  display: 'flex',
  alignItems: 'center',
  gap: 'sm',
  color: 'fgMuted',
  fontSize: 'sm',
  // The two flanking lines render via `::before` / `::after` so the
  // label stays a real text node (selectable, screen-reader-friendly)
  // without nesting decorative elements in the markup.
  _before: { content: '""', flex: 1, height: '1px', bg: 'border' },
  _after: { content: '""', flex: 1, height: '1px', bg: 'border' },
});

// Two-row layout — PIN slots + eye toggle on row one, Rejoindre on
// row two — keeps the slots full-width without the wrap-collapse bug
// that overlaid the button on top of the slots when the row was
// allowed to wrap.
const joinFormStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
});

const pinWrapStyles = css({
  display: 'flex',
  alignItems: 'center',
  gap: 'xs',
  width: '100%',
  // The PIN itself is `flex: 1; minWidth: 0` (see PinInput.tsx) so
  // the eye toggle sits flush right and the slots share the
  // remaining width via their own `flex: 1` distribution.
});

const eyeToggleStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: '2.25em',
  height: '2.25em',
  bg: 'transparent',
  color: 'fgMuted',
  border: 'none',
  borderRadius: 'sm',
  cursor: 'pointer',
  transition: 'color 120ms ease-out, background-color 120ms ease-out',
  _hover: { color: 'fg', bg: 'surface' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

const helperTextStyles = css({
  fontSize: 'xs',
  color: 'fgMuted',
  margin: 0,
});

const errorTextStyles = css({
  fontSize: 'sm',
  color: 'errorText',
  margin: 0,
});

// Capitalised French long-date — Intl returns "mardi 11 mai" in fr-FR;
// we want sentence case ("Mardi 11 mai") to match the brief mock.
function formatTodayFr(now: Date): string {
  const formatted = new Intl.DateTimeFormat('fr-FR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  }).format(now);
  return formatted.charAt(0).toUpperCase() + formatted.slice(1);
}

// Container — fetches the daily puzzle on mount and renders one of
// three card states (loading / error / ready). Decoupling the fetch
// from the route loader means a slow `/v1/puzzles/daily` does NOT
// hold the Multijoueur card hostage: Accueil renders both cards
// immediately, and this card alone shows its own loading chrome.
//
// Réessayer simply bumps `tick` to re-run the effect — cheaper than
// `router.invalidate()` (which would also re-fetch lobbies for no
// reason), and self-contained inside the card.
// Container — fetches the daily puzzle on mount and renders the
// matching body for the current state (loading / error / ready). The
// outer `<section>` + the `<h2>` stay mounted across state
// transitions so the heading element keeps its identity in the DOM
// (test queries returning a detached node on a state flip is a real
// gotcha here) and so screen-reader focus on the heading is not lost
// when the card swaps from loading to ready.
function GrilleDuJourCard() {
  const { puzzleRepository } = Route.useRouteContext();
  const [state, setState] = useState<DailyState>({ status: 'loading' });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setState({ status: 'loading' });
    puzzleRepository
      .fetchDaily()
      .then((puzzle) => {
        if (!cancelled) setState({ status: 'ok', puzzle });
      })
      .catch(() => {
        if (!cancelled) setState({ status: 'error' });
      });
    return () => { cancelled = true; };
  }, [puzzleRepository, tick]);

  return (
    <section className={cardStyles} aria-labelledby="accueil-grille-title">
      <h2 id="accueil-grille-title" className={cardTitleStyles}>Grille du jour</h2>
      {state.status === 'loading' ? (
        <GrilleDuJourLoadingBody />
      ) : state.status === 'error' ? (
        <GrilleDuJourErrorBody onRetry={() => setTick((n) => n + 1)} />
      ) : (
        <GrilleDuJourReadyBody puzzle={state.puzzle} />
      )}
    </section>
  );
}

function GrilleDuJourReadyBody({ puzzle }: { readonly puzzle: Puzzle }) {
  const navigate = useNavigate();
  const { soloEntriesStore } = Route.useRouteContext();

  const totalLetterCells = puzzle.cells.reduce(
    (n, c) => (c.kind === 'letter' ? n + 1 : n),
    0,
  );
  // `loadLockedCells` is the same source the in-puzzle ProgressBar
  // counts: validated words + revealed-hint cells. Mirrors what the
  // player sees inside `/grille` so the count never disagrees.
  const lockedCount = soloEntriesStore.loadLockedCells(puzzle.id).length;
  const entriesCount = soloEntriesStore.load(puzzle.id).length;
  const hasStarted = lockedCount > 0 || entriesCount > 0;

  // Meta row: date `· n°X · facile`. Number and difficulty are optional —
  // rendered only when populated.
  const metaParts: string[] = [formatTodayFr(new Date())];
  if (puzzle.gridNumber != null) metaParts.push(`n°${puzzle.gridNumber}`);
  if (puzzle.difficulty != null) metaParts.push(puzzle.difficulty);
  const metaLabel = metaParts.join(' · ');

  return (
    <>
      <p className={cardSubtitleStyles}>{metaLabel}</p>
      <ProgressBar
        value={lockedCount}
        total={totalLetterCells}
        label={hasStarted ? 'Reprise' : 'Nouvelle grille'}
      />
      <div className={cardFooterStyles}>
        <Button
          variant="ghost"
          onClick={() => { void navigate({ to: '/grille' }); }}
        >
          {hasStarted ? 'Reprendre' : 'Commencer'}
        </Button>
        <button
          type="button"
          disabled
          title="Bientôt"
          className={tertiaryLinkStyles}
        >
          Voir les anciennes grilles →
        </button>
      </div>
    </>
  );
}

function MultijoueurCard({ lobbies }: { readonly lobbies: readonly LobbySummary[] }) {
  const navigate = useNavigate();
  const ctx = Route.useRouteContext();
  const flagOn = isMultiplayerEnabled();
  const lobbyClient = ctx.lobbyClient;
  const getSession = ctx.getSession;
  const lobbyJoinCodeStash = ctx.lobbyJoinCodeStash;
  const canCreate = flagOn && lobbyClient != null && getSession != null;
  const canJoin = flagOn && lobbyClient != null && lobbyJoinCodeStash != null;

  const [pending, setPending] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [codeRevealed, setCodeRevealed] = useState(false);
  const [joinPending, setJoinPending] = useState(false);
  const [joinError, setJoinError] = useState<string | null>(null);

  const codeMatches = LOBBY_CODE_PATTERN.test(code);

  const handleCreate = async () => {
    if (!canCreate) return;
    setPending(true);
    setErrorMessage(null);
    try {
      const { sessionId, pseudonym } = getSession!();
      const created = await lobbyClient!.createLobby({
        ownerSessionId: sessionId,
        ownerPseudonym: pseudonym,
      });
      await navigate({ to: '/lobby/$lobbyId', params: { lobbyId: created.id } });
    } catch (err) {
      setErrorMessage(messageForError(err));
      setPending(false);
    }
  };

  const handleJoin = async () => {
    if (!canJoin || !codeMatches) return;
    setJoinPending(true);
    setJoinError(null);
    try {
      const lobby = await lobbyClient!.findByCode(code);
      // ADR-0027: stash the code so the lobby route's WS-open consumes
      // it on mount. The address bar shows only `/lobby/$lobbyId`; the
      // code never appears in the URL.
      lobbyJoinCodeStash!.stash(lobby.id, code);
      await navigate({
        to: '/lobby/$lobbyId',
        params: { lobbyId: lobby.id },
        replace: true, // keep Accueil out of the back-stack
      });
    } catch (err) {
      setJoinError(messageForJoinError(err));
      setJoinPending(false);
    }
  };

  // Accepts both bare codes and full share-link URLs — pasting
  // `https://wordsparrow.io/join/A2B3C4` extracts the code and fills the
  // input. Otherwise normalises typed chars (uppercase + Crockford
  // alphabet + 6-char cap). One source of truth in `domain/game/lobbyCode`.
  const handleCodeChange = (raw: string) => {
    const normalised = extractLobbyCode(raw);
    setCode(normalised);
    if (joinError != null) setJoinError(null);
  };

  return (
    <section className={cardStyles} aria-labelledby="accueil-multi-title">
      <h2 id="accueil-multi-title" className={cardTitleStyles}>Multijoueur</h2>
      <p className={cardSubtitleStyles}>Jouez avec des amis sur la grille du jour</p>
      <div className={cardFooterStyles}>
        <Button
          variant="ghost"
          disabled={!canCreate || pending}
          title={!canCreate ? 'Bientôt' : undefined}
          onClick={() => { void handleCreate(); }}
        >
          {pending ? 'Création…' : 'Créer une partie'}
        </Button>
        {errorMessage != null ? (
          <p className={errorTextStyles} role="alert">{errorMessage}</p>
        ) : null}
        <div className={dividerStyles} aria-hidden="true">ou avec un code</div>
        <form
          className={joinFormStyles}
          onSubmit={(event) => {
            event.preventDefault();
            void handleJoin();
          }}
          // `autoComplete="off"` on the form is the second belt-and-
          // braces pass against browser password / form save: the per-
          // slot props in `PinInput` are the primary defence, this
          // covers any browser that walks up to the form.
          autoComplete="off"
        >
          <div className={pinWrapStyles}>
            <PinInput
              label="Code de partie"
              value={code}
              onValueChange={handleCodeChange}
              mask={!codeRevealed}
              disabled={!canJoin}
            />
            <button
              type="button"
              className={eyeToggleStyles}
              aria-label={codeRevealed ? 'Masquer le code' : 'Afficher le code'}
              aria-pressed={codeRevealed}
              onClick={() => setCodeRevealed((v) => !v)}
            >
              {codeRevealed ? <EyeOffIcon /> : <EyeIcon />}
            </button>
          </div>
          <Button
            type="submit"
            variant="ghost"
            disabled={!canJoin || !codeMatches || joinPending}
            title={!canJoin ? 'Bientôt' : undefined}
          >
            {joinPending ? 'Recherche…' : 'Rejoindre'}
          </Button>
        </form>
        {joinError != null ? (
          <p className={errorTextStyles} role="alert">{joinError}</p>
        ) : !canJoin ? (
          <p className={helperTextStyles}>Disponible bientôt</p>
        ) : null}
      </div>
      {flagOn ? <MyLobbiesSection lobbies={lobbies} /> : null}
    </section>
  );
}

// Same `messageForError` mapping as `routes/grille.tsx` so create-lobby
// failure copy stays consistent across the two surfaces that mint a
// lobby (Accueil and the legacy grille button).
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

// Distinct copy for the join-by-code path: the user typed a code, so a
// 404 is meaningfully different from a transient 5xx and earns specific
// guidance. Validation should not reach the wire (we filter to the
// allowed alphabet on input), but if the server tightens its pattern
// later we surface a helpful message instead of a generic retry hint.
function messageForJoinError(err: unknown): string {
  if (err instanceof LobbyClientError) {
    switch (err.kind) {
      case 'not-found':
        return 'Aucune partie pour ce code. Vérifiez la saisie.';
      case 'validation':
        return 'Code invalide.';
      case 'upstream-unavailable':
        return 'Service indisponible. Réessayez dans un instant.';
      case 'transient':
        return 'Une erreur est survenue. Réessayez.';
    }
  }
  return 'Une erreur est survenue. Réessayez.';
}

function AccueilPage() {
  const { lobbies } = Route.useLoaderData();
  return (
    <ContentPage>
      <h1 lang="fr" className={srOnly}>
        Mots fléchés français en ligne — <span lang="en">WordSparrow</span>
      </h1>
      <div className={cardsGridStyles}>
        <GrilleDuJourCard />
        <MultijoueurCard lobbies={lobbies} />
      </div>
    </ContentPage>
  );
}

// Body for the daily-puzzle failure path. Réessayer re-runs only the
// daily fetch (the parent container bumps `tick`) — never invalidates
// the router, so the Multijoueur card's in-flight create / join state
// is preserved through a retry.
function GrilleDuJourErrorBody({ onRetry }: { readonly onRetry: () => void }) {
  return (
    <>
      <p className={errorTextStyles} role="alert">
        Grille du jour indisponible. Réessayez dans un instant.
      </p>
      <div className={cardFooterStyles}>
        <Button variant="ghost" onClick={onRetry}>Réessayer</Button>
      </div>
    </>
  );
}

// Body for the daily-puzzle loading path. The visible "Chargement…"
// copy with `role="status"` doubles as the announce trigger for
// screen readers and the prerender sentinel that
// `scripts/prerender.ts` already waits on before dumping HTML.
function GrilleDuJourLoadingBody() {
  return (
    <p className={cardSubtitleStyles} role="status">Chargement…</p>
  );
}

function AccueilStatus({ role, text }: { role: 'status' | 'alert'; text: string }) {
  return (
    <ContentPage>
      <p className={css({ fontSize: 'body', margin: 0, color: 'accent', textAlign: 'center' })} role={role}>
        {text}
      </p>
    </ContentPage>
  );
}

// Card-shaped skeleton for the pending state. Mirrors the two-card grid
// (Grille du jour + Multijoueur) so the layout doesn't jump when the
// loader resolves. Used as the route's pendingComponent AND baked into
// the prerendered HTML — the prerender script leaves the puzzle endpoint
// hanging so this skeleton dumps to dist/index.html instead of a
// fixture-puzzle render that would otherwise display stale cosmetic data
// (puzzle number, difficulty, "Reprendre" vs "Commencer") on F5.
const skeletonPulse = css({
  bg: 'surfaceElevated',
  borderRadius: '6px',
  animation: 'wordsparrow-skeleton-pulse 1.4s ease-in-out infinite',
});

const skeletonCardStyles = css({
  bg: 'surface',
  borderRadius: 'md',
  padding: 'lg',
  border: '1px solid token(colors.border)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'md',
  // Approximate the real card height so the swap is visually neutral.
  minHeight: '220px',
});

const skeletonTitleStyles = css({ width: '50%', height: '24px' });
const skeletonSubtitleStyles = css({ width: '70%', height: '14px' });
const skeletonRowStyles = css({ width: '100%', height: '32px' });
const skeletonButtonStyles = css({
  width: '100%',
  height: '40px',
  marginTop: 'auto',
});

function AccueilSkeleton() {
  // TanStack Router's executeHead runs after loaders resolve, so the
  // route's head() (which sets <title>) never fires while we render in
  // pending state. Set the title imperatively here so the prerender
  // script's title check passes and crawlers / share previews still
  // see the correct page title. Mirrors RootNotFound (__root.tsx).
  useLayoutEffect(() => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/');
    if (!r) return;
    const previous = document.title;
    document.title = r.title;
    return () => { document.title = previous; };
  }, []);
  return (
    <ContentPage>
      <h1 lang="fr" className={srOnly}>
        Mots fléchés français en ligne — <span lang="en">WordSparrow</span>
      </h1>
      <div className={cardsGridStyles}>
        <section className={skeletonCardStyles} aria-hidden>
          <div className={`${skeletonPulse} ${skeletonTitleStyles}`} />
          <div className={`${skeletonPulse} ${skeletonSubtitleStyles}`} />
          <div className={`${skeletonPulse} ${skeletonRowStyles}`} />
          <div className={`${skeletonPulse} ${skeletonButtonStyles}`} />
        </section>
        <section className={skeletonCardStyles} aria-hidden>
          <div className={`${skeletonPulse} ${skeletonTitleStyles}`} />
          <div className={`${skeletonPulse} ${skeletonSubtitleStyles}`} />
          <div className={`${skeletonPulse} ${skeletonButtonStyles}`} />
        </section>
      </div>
      <p className={srOnly} role="status">Chargement…</p>
    </ContentPage>
  );
}

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/',
  // Parallel fetch: daily puzzle + "Mes parties" lobbies for the calling
  // session (ADR-0039). The lobbies fetch is best-effort — if the
  // multiplayer flag is off, the lobby client / session are absent and
  // we resolve to an empty list. If the lobbies fetch fails, we
  // similarly degrade to `[]` rather than failing the entire Accueil
  // load (the puzzle is the primary content; "Mes parties" is a side
  // surface that should never gate the home page).
  loader: async ({ context }): Promise<AccueilLoaderData> => {
    // The daily puzzle is intentionally NOT fetched here. The Grille du
    // jour card owns its own fetch (and its own loading / error /
    // ready state) so a slow `/v1/puzzles/daily` cannot delay the
    // Multijoueur card from rendering. The lobbies fetch is fast and
    // best-effort (any failure degrades to `[]`), so the loader still
    // resolves quickly — the route's `pendingComponent` rarely fires
    // in practice.
    if (context.lobbyClient == null || context.getSession == null) {
      return { lobbies: [] };
    }
    const lobbies = await context.lobbyClient
      .listMyLobbies(context.getSession().sessionId)
      .catch((): readonly LobbySummary[] => []);
    return { lobbies };
  },
  component: AccueilPage,
  // pendingMs: TanStack Router defaults to Infinity (pendingComponent
  // never renders). 200 ms is the sweet spot — fast navs (<200 ms)
  // skip the skeleton entirely; slow navs / cold loads show it. The
  // prerender script also relies on this firing (it waits for the
  // skeleton's status sentinel before dumping HTML).
  pendingMs: 200,
  pendingComponent: AccueilSkeleton,
  // Last-resort fallback for unexpected loader throws (e.g. a
  // composition-root wiring bug). Both first-party fetches in the
  // loader catch their own failures (`fetchDaily` degrades to
  // `{ ok: false }`, `listMyLobbies` degrades to `[]`), so this
  // boundary does NOT fire on transport/decode failures of the daily
  // puzzle — that path now renders an inline card error state in
  // `GrilleDuJourErrorCard` while keeping the Multijoueur card mounted.
  errorComponent: ({ error }) => (
    <AccueilStatus role="alert" text={messageForError(error)} />
  ),
  head: () => {
    const r = INDEXABLE_ROUTES.find((x) => x.path === '/')!;
    const base = buildHead({
      title: r.title,
      description: r.description,
      canonical: `${SITE_BASE_URL}/`,
      ogImage: `${SITE_BASE_URL}${r.ogImagePath}`,
    });
    return {
      ...base,
      scripts: [
        {
          type: 'application/ld+json',
          children: JSON.stringify({
            '@context': 'https://schema.org',
            '@type': 'WebApplication',
            name: 'WordSparrow',
            url: `${SITE_BASE_URL}/`,
            description: r.description,
            applicationCategory: 'GameApplication',
            inLanguage: 'fr',
          }),
        },
        {
          type: 'application/ld+json',
          children: organizationJsonLd({
            name: 'WordSparrow',
            url: `${SITE_BASE_URL}/`,
            logo: `${SITE_BASE_URL}/icon-512.png`,
          }),
        },
      ],
    };
  },
});
