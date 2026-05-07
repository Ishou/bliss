import { css } from 'styled-system/css';
import type { Lobby, SessionId } from '@/domain/game';
import { playerColorVars } from '@/ui/lib/playerColor';

// Pure prop-driven roster. Used in two layouts:
//   - `stacked`: vertical list with empty-slot placeholders, mounted by
//     WaitingRoom while `lobby.state === 'WAITING'`.
//   - `inline`:  horizontal pill row with no empty slots, mounted by the
//     lobby route during `IN_PROGRESS` / `COMPLETED` so players keep
//     visibility on who they are playing with without eating the
//     vertical real-estate the Grid + Timer need.
//
// Owns no network, no router context, no localStorage — every variant
// stays trivially unit-testable. The eight-player cap (game/api
// ServerLobbyState `maxItems: 8`) is enforced server-side; the
// `stacked` variant pads with placeholder rows so the list height
// stays stable while peers join.
export const MAX_PLAYERS = 8;

export interface PlayerListProps {
  readonly players: Lobby['players'];
  readonly ownerSessionId: SessionId;
  readonly currentSessionId: SessionId;
  /**
   * `stacked` — vertical list with "Place libre" placeholder rows
   * (waiting-room layout). `inline` — horizontal pill row, no
   * placeholders (in-game layout).
   */
  readonly variant: 'stacked' | 'inline';
}

const styles = {
  stackedList: css({
    display: 'flex', flexDirection: 'column', gap: 'xs',
    listStyle: 'none', padding: 0, margin: 0,
  }),
  stackedRow: css({
    // `space-between` keeps the pseudonym hard-left and the badges hard-
    // right regardless of the row width; the surrounding LobbyShell
    // applies `text-align: center` to the page, which previously bled
    // into the row and stretched the centered name across the row when
    // combined with `flex: 1`. Anchoring the name to the start with an
    // explicit `text-align: left` is the durable fix.
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    gap: 'sm', padding: 'sm', border: '1px solid token(colors.border)',
    // Per-player colour accent (ADR-0018 §"Presence"): a 4 px left
    // border tinted by `--player-color`. Inline `style` per row sets
    // the var so the same hue the in-game cursor / chip use also
    // tags the row in the roster — a single visual signature per peer.
    borderLeft: '4px solid var(--player-color, token(colors.border))',
    borderRadius: 'sm', bg: 'surface',
  }),
  emptySlot: css({
    // Same flex shape as a filled row so empty + filled rows align
    // identically — placeholder copy reads from the left edge instead of
    // inheriting the page's centered text-align.
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    gap: 'sm', padding: 'sm', border: '1px dashed token(colors.border)',
    borderRadius: 'sm', bg: 'bg', color: 'accent', fontStyle: 'italic',
    textAlign: 'left',
  }),
  stackedPseudonym: css({ flex: 1, textAlign: 'left', fontWeight: 'medium', color: 'fg' }),
  badgeGroup: css({
    display: 'flex', alignItems: 'center', gap: 'xs',
  }),
  inlineList: css({
    display: 'flex', flexDirection: 'row', flexWrap: 'wrap',
    alignItems: 'center', justifyContent: 'center', gap: 'sm',
    listStyle: 'none', padding: 0, margin: 0,
    width: '100%',
  }),
  inlineRow: css({
    display: 'inline-flex', alignItems: 'center', gap: 'xs',
    paddingBlock: 'xs', paddingInline: 'sm',
    border: '1px solid token(colors.border)', borderRadius: '9999px',
    // Per-player colour accent: a 3 px left stripe via inline-style
    // `--player-color`. Same hue the in-game cursor uses, so the
    // pill chip in the roster matches the cursor on the grid at a
    // glance. Decorative — no contrast requirement (the stripe
    // doesn't carry semantic content).
    borderLeft: '3px solid var(--player-color, token(colors.border))',
    bg: 'surface', fontSize: 'sm',
  }),
  inlinePseudonym: css({ fontWeight: 'medium', color: 'fg' }),
  badge: css({
    fontSize: 'xs', fontWeight: 'bold', letterSpacing: '0.06em',
    textTransform: 'uppercase', paddingInline: 'sm', paddingBlock: 'xs',
    borderRadius: '9999px', bg: 'primary.50', color: 'primary.700',
  }),
  ownerBadge: css({
    fontSize: 'xs', fontWeight: 'bold', letterSpacing: '0.06em',
    textTransform: 'uppercase', paddingInline: 'sm', paddingBlock: 'xs',
    borderRadius: '9999px', bg: 'secondary.50', color: 'secondary.700',
  }),
};

export function PlayerList({
  players, ownerSessionId, currentSessionId, variant,
}: PlayerListProps): React.ReactElement {
  if (variant === 'inline') {
    return (
      <ul className={styles.inlineList} aria-label="Liste des joueurs">
        {players.map((player) => (
          <li
            key={player.sessionId}
            className={styles.inlineRow}
            style={playerColorVars(player.sessionId)}
            data-testid="player-row"
            data-session-id={player.sessionId}
          >
            <span className={styles.inlinePseudonym}>{player.pseudonym}</span>
            {player.sessionId === currentSessionId
              ? <span className={styles.badge}>vous</span> : null}
            {player.sessionId === ownerSessionId
              ? <span className={styles.ownerBadge}>propriétaire</span> : null}
          </li>
        ))}
      </ul>
    );
  }

  const emptySlots = Math.max(0, MAX_PLAYERS - players.length);
  return (
    <ul className={styles.stackedList} aria-label="Liste des joueurs">
      {players.map((player) => (
        <li
          key={player.sessionId}
          className={styles.stackedRow}
          style={playerColorVars(player.sessionId)}
          data-testid="player-row"
          data-session-id={player.sessionId}
        >
          <span className={styles.stackedPseudonym}>{player.pseudonym}</span>
          <span className={styles.badgeGroup}>
            {player.sessionId === currentSessionId
              ? <span className={styles.badge}>vous</span> : null}
            {player.sessionId === ownerSessionId
              ? <span className={styles.ownerBadge}>propriétaire</span> : null}
          </span>
        </li>
      ))}
      {Array.from({ length: emptySlots }).map((_, idx) => (
        <li key={`empty-${idx}`} className={styles.emptySlot} aria-label="Place libre">
          Place libre
        </li>
      ))}
    </ul>
  );
}
