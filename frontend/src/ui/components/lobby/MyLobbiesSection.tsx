import { Link } from '@tanstack/react-router';
import { useEffect, useRef, useState } from 'react';
import { css } from 'styled-system/css';
import type { LobbySummary } from '@/application/game';
import { EyeIcon, EyeOffIcon } from '@/ui/components/icons';
import { MAX_PLAYERS } from './PlayerList';

// "Mes parties" surface (ADR-0039). Read-only list of the calling
// session's lobbies, rendered inside the Accueil Multijoueur card below
// the create/join controls. The parent route's loader fetches the data
// in parallel with the daily puzzle (Option A — TanStack Router loader
// pattern, ADR-0002), so this component is pure-presentational: no
// fetch, no state, no effect. An empty list renders an empty-state
// blurb rather than collapsing to nothing so the surface remains
// discoverable.

export interface MyLobbiesSectionProps {
  readonly lobbies: readonly LobbySummary[];
}

const sectionStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
  marginTop: 'sm',
});

const headingStyles = css({
  fontSize: 'md',
  fontWeight: 'semibold',
  margin: 0,
  color: 'fg',
});

const emptyTextStyles = css({
  fontSize: 'sm',
  color: 'fgMuted',
  margin: 0,
});

const listStyles = css({
  listStyle: 'none',
  margin: 0,
  padding: 0,
  display: 'flex',
  flexDirection: 'column',
  gap: 'xs',
});

const itemLinkStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'xs',
  padding: 'sm',
  borderRadius: 'sm',
  border: '1px solid token(colors.border)',
  bg: 'surface',
  color: 'fg',
  textDecoration: 'none',
  transition: 'background-color 120ms ease-out, border-color 120ms ease-out',
  _hover: { bg: 'surfaceElevated', borderColor: 'fgMuted' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

const itemTitleStyles = css({
  fontSize: 'sm',
  fontWeight: 'semibold',
  color: 'fg',
});

const itemMetaStyles = css({
  fontSize: 'xs',
  color: 'fgMuted',
  display: 'flex',
  alignItems: 'center',
  gap: 'xs',
  flexWrap: 'wrap',
});

const codeGroupStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '2xs',
});

const codeTextStyles = css({
  fontFamily: 'mono',
  letterSpacing: '0.08em',
  color: 'fg',
});

const iconButtonStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: '1.5em',
  height: '1.5em',
  bg: 'transparent',
  color: 'fgMuted',
  border: 'none',
  borderRadius: 'sm',
  cursor: 'pointer',
  padding: 0,
  transition: 'color 120ms ease-out, background-color 120ms ease-out',
  _hover: { color: 'fg', bg: 'surfaceElevated' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

const copyFeedbackStyles = css({
  fontSize: 'xs',
  color: 'accent',
});

// Six bullet glyphs — matches the canonical join-code length used
// elsewhere (PinInput's LOBBY_CODE_LENGTH). Kept local to avoid
// reaching across layers for a single rendering constant.
const MASK_GLYPHS = '••••••';

// Inline feedback duration after a successful clipboard write. Mirrors
// the WaitingRoom "Lien copié !" timing.
const COPY_FEEDBACK_MS = 2000;

// Date-only fr-FR rendering — matches `formatTodayFr` in accueil.tsx in
// spirit (Intl.DateTimeFormat, no third-party date lib). Year omitted
// because the typical "Mes parties" surface shows recent lobbies; we
// can add it back if we ever surface lobbies older than ~1 year.
function formatActivityDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return new Intl.DateTimeFormat('fr-FR', {
    day: 'numeric',
    month: 'long',
  }).format(d);
}

function titleFor(lobby: LobbySummary): string {
  if (lobby.title != null && lobby.title.length > 0) return lobby.title;
  const when = formatActivityDate(lobby.lastActivityAt);
  return when.length > 0 ? `Partie du ${when}` : 'Partie';
}

export function MyLobbiesSection({ lobbies }: MyLobbiesSectionProps) {
  if (lobbies.length === 0) {
    return (
      <section className={sectionStyles} aria-labelledby="my-lobbies-heading">
        <h3 id="my-lobbies-heading" className={headingStyles}>Mes parties</h3>
        <p className={emptyTextStyles}>
          Vos parties multijoueur en cours apparaîtront ici.
        </p>
      </section>
    );
  }
  return (
    <section className={sectionStyles} aria-labelledby="my-lobbies-heading">
      <h3 id="my-lobbies-heading" className={headingStyles}>Mes parties</h3>
      <ul className={listStyles}>
        {lobbies.map((lobby) => (
          <li key={lobby.id}>
            <LobbyRow lobby={lobby} />
          </li>
        ))}
      </ul>
    </section>
  );
}

// Per-row subcomponent so the reveal / copied state is local to each
// card rather than promoted to a Map keyed by lobby id.
function LobbyRow({ lobby }: { readonly lobby: LobbySummary }) {
  const [revealed, setRevealed] = useState(false);
  const [justCopied, setJustCopied] = useState(false);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
    },
    [],
  );

  // Stop propagation so clicking either icon button does not trigger
  // the surrounding `Link` navigation to `/lobby/$lobbyId`.
  const handleToggle = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setRevealed((v) => !v);
  };
  const handleCopy = (e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    void navigator.clipboard?.writeText(lobby.code);
    if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
    setJustCopied(true);
    copyTimerRef.current = setTimeout(() => {
      setJustCopied(false);
      copyTimerRef.current = null;
    }, COPY_FEEDBACK_MS);
  };

  return (
    <Link
      to="/lobby/$lobbyId"
      params={{ lobbyId: lobby.id }}
      className={itemLinkStyles}
    >
      <span className={itemTitleStyles}>{titleFor(lobby)}</span>
      <span className={itemMetaStyles}>
        <span className={codeGroupStyles}>
          <span
            className={codeTextStyles}
            data-testid="lobby-code"
            data-masked={revealed ? 'false' : 'true'}
          >
            {revealed ? lobby.code : MASK_GLYPHS}
          </span>
          <button
            type="button"
            className={iconButtonStyles}
            aria-label={revealed ? 'Masquer le code' : 'Afficher le code'}
            aria-pressed={revealed}
            onClick={handleToggle}
          >
            {revealed ? <EyeOffIcon /> : <EyeIcon />}
          </button>
          <button
            type="button"
            className={iconButtonStyles}
            aria-label="Copier le code"
            onClick={handleCopy}
          >
            <CopyGlyph />
          </button>
          {justCopied ? (
            <span role="status" aria-live="polite" className={copyFeedbackStyles}>
              Copié
            </span>
          ) : null}
        </span>
        <span>·</span>
        <span>
          {lobby.gridConfig.width}×{lobby.gridConfig.height}
        </span>
        <span>·</span>
        <span data-testid="lobby-players">
          {lobby.playerCount} / {MAX_PLAYERS} joueurs
        </span>
      </span>
    </Link>
  );
}

// Minimal copy glyph kept local — adding a project-wide CopyIcon to
// `components/icons.tsx` would be a separate concern and we only need
// it here today.
function CopyGlyph() {
  return (
    <svg
      width="1em"
      height="1em"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <rect x="5" y="5" width="8" height="9" rx="1.5" />
      <path d="M3 11V3.5A1.5 1.5 0 0 1 4.5 2H10" />
    </svg>
  );
}
