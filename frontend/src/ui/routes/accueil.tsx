import { createRoute, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { css } from 'styled-system/css';
import type { Puzzle } from '@/domain';
import { LobbyClientError } from '@/application/game';
import { Button } from '@/ui/components/primitives';
import { AppHeader, Footer, ProgressBar } from '@/ui/components/layout';
import { Route as RootRoute } from './__root';

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

const pageStyles = css({
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  color: 'fg',
  fontFamily: 'body',
});

const mainStyles = css({
  // `1 0 auto`: grow to push the footer to the viewport bottom on
  // short pages, but never shrink below content. Plain `flex: 1` (=
  // `1 1 0`) collapses main to zero height when the cards' combined
  // height exceeds the viewport, dropping the cards under the footer.
  flex: '1 0 auto',
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  width: '100%',
  bg: 'bg',
});

const contentStyles = css({
  width: '100%',
  maxWidth: '720px',
  margin: '0 auto',
  paddingInline: { base: '16px', md: '20px' },
  paddingBlock: { base: '16px', md: '24px' },
  display: 'flex',
  flexDirection: 'column',
  gap: 'lg',
});

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

// Two-column on md+ (mockup desktop), single column on mobile. Grid
// stretching keeps both cards the same height in the desktop row even
// when the Multijoueur card has more content.
//
// `minmax(0, 1fr)` instead of plain `1fr`: `1fr` resolves to
// `minmax(auto, 1fr)`, which refuses to shrink a track below its
// min-content width. Multijoueur's input + "Rejoindre" row has a wider
// min-content than the Grille card, so plain `1fr` would let it eat
// into the other column. Forcing the min to 0 keeps the columns at a
// strict 50/50 regardless of content.
const cardsGridStyles = css({
  display: 'grid',
  gridTemplateColumns: { base: '1fr', md: 'minmax(0, 1fr) minmax(0, 1fr)' },
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

const joinRowStyles = css({
  display: 'flex',
  gap: 'sm',
  alignItems: 'stretch',
});

const codeInputStyles = css({
  flex: 1,
  minWidth: 0,
  paddingBlock: 'sm',
  paddingInline: 'sm',
  borderRadius: 'sm',
  border: '1px solid token(colors.border)',
  bg: 'surface',
  color: 'fg',
  fontFamily: 'body',
  fontSize: 'body',
  letterSpacing: '0.1em',
  textTransform: 'uppercase',
  _placeholder: { color: 'fgMuted', letterSpacing: '0.1em' },
  _disabled: { opacity: 0.6, cursor: 'not-allowed' },
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

function GrilleDuJourCard({ puzzle }: { readonly puzzle: Puzzle }) {
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
    <section className={cardStyles} aria-labelledby="accueil-grille-title">
      <h2 id="accueil-grille-title" className={cardTitleStyles}>Grille du jour</h2>
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
    </section>
  );
}

// Same Crockford-style alphabet as the OpenAPI `code` schema and the
// Kotlin LobbyCode regex — keep all three in sync. Excludes the
// ambiguous chars `0`/`O`, `1`/`I`/`L` so a player reading the code
// aloud cannot land on a different lobby.
const LOBBY_CODE_PATTERN = /^[A-HJKM-NP-Z2-9]{6}$/;
const LOBBY_CODE_ALLOWED_CHARS = /[A-HJKM-NP-Z2-9]/;

function MultijoueurCard() {
  const navigate = useNavigate();
  const ctx = Route.useRouteContext();
  const flagOn = isMultiplayerEnabled();
  const lobbyClient = ctx.lobbyClient;
  const getSession = ctx.getSession;
  const canCreate = flagOn && lobbyClient != null && getSession != null;
  const canJoin = flagOn && lobbyClient != null;

  const [pending, setPending] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [code, setCode] = useState('');
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
      await navigate({ to: '/lobby/$lobbyId', params: { lobbyId: lobby.id } });
    } catch (err) {
      setJoinError(messageForJoinError(err));
      setJoinPending(false);
    }
  };

  // Normalise typed input: uppercase + strip anything outside the allowed
  // alphabet, then cap at 6 chars. Keeps the input visually aligned with
  // what the server accepts, so a paste of "a-2b3c4" lands as "A2B3C4".
  const handleCodeChange = (raw: string) => {
    const normalised = Array.from(raw.toUpperCase())
      .filter((ch) => LOBBY_CODE_ALLOWED_CHARS.test(ch))
      .slice(0, 6)
      .join('');
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
          className={joinRowStyles}
          onSubmit={(event) => {
            event.preventDefault();
            void handleJoin();
          }}
        >
          <input
            type="text"
            disabled={!canJoin}
            aria-label="Code de partie"
            placeholder="A2B3C4"
            value={code}
            onChange={(event) => handleCodeChange(event.target.value)}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            className={codeInputStyles}
          />
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
  const puzzle = Route.useLoaderData() as Puzzle;
  return (
    <PageShell>
      <h1 lang="en" className={srOnly}>WordSparrow</h1>
      <div className={cardsGridStyles}>
        <GrilleDuJourCard puzzle={puzzle} />
        <MultijoueurCard />
      </div>
    </PageShell>
  );
}

function AccueilStatus({ role, text }: { role: 'status' | 'alert'; text: string }) {
  return (
    <PageShell>
      <p className={css({ fontSize: 'body', margin: 0, color: 'accent', textAlign: 'center' })} role={role}>
        {text}
      </p>
    </PageShell>
  );
}

export const Route = createRoute({
  getParentRoute: () => RootRoute,
  path: '/',
  loader: ({ context }): Promise<Puzzle> => context.puzzleRepository.fetchDaily(),
  component: AccueilPage,
  pendingComponent: () => <AccueilStatus role="status" text="Chargement…" />,
  errorComponent: ({ error }) => (
    <AccueilStatus role="alert" text={`Échec du chargement : ${error.message}`} />
  ),
  head: () => ({ meta: [{ title: 'WordSparrow — Accueil' }] }),
});
