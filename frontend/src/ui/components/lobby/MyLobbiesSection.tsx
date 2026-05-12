import { Link } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import type { LobbySummary } from '@/application/game';

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
});

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
        {lobbies.map((lobby) => {
          const playerSuffix = lobby.playerCount > 1 ? 'joueurs' : 'joueur';
          return (
            <li key={lobby.id}>
              <Link
                to="/lobby/$lobbyId"
                params={{ lobbyId: lobby.id }}
                className={itemLinkStyles}
              >
                <span className={itemTitleStyles}>{titleFor(lobby)}</span>
                <span className={itemMetaStyles}>
                  {lobby.code} · {lobby.gridConfig.width}×{lobby.gridConfig.height}
                  {' · '}
                  {lobby.playerCount} {playerSuffix}
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
