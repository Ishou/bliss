import { css } from 'styled-system/css';
import type { Lobby, SessionId } from '@/domain/game';

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
const MAX_PLAYERS = 8;

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
    display: 'flex', alignItems: 'center', gap: 'sm',
    padding: 'sm', border: '1px solid token(colors.border)',
    borderRadius: 'sm', bg: 'surface',
  }),
  emptySlot: css({
    display: 'flex', alignItems: 'center', padding: 'sm',
    border: '1px dashed token(colors.border)', borderRadius: 'sm',
    bg: 'bg', color: 'accent', fontStyle: 'italic',
  }),
  stackedPseudonym: css({ flex: 1, fontWeight: 'medium', color: 'fg' }),
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
    bg: 'surface', fontSize: 'sm',
  }),
  inlinePseudonym: css({ fontWeight: 'medium', color: 'fg' }),
  badge: css({
    fontSize: 'xs', fontWeight: 'bold', letterSpacing: '0.06em',
    textTransform: 'uppercase', paddingInline: 'sm', paddingBlock: 'xs',
    borderRadius: '9999px', bg: 'leaf.50', color: 'leaf.700',
  }),
  ownerBadge: css({
    fontSize: 'xs', fontWeight: 'bold', letterSpacing: '0.06em',
    textTransform: 'uppercase', paddingInline: 'sm', paddingBlock: 'xs',
    borderRadius: '9999px', bg: 'blossom.50', color: 'blossom.700',
  }),
};

export function PlayerList({
  players, ownerSessionId, currentSessionId, variant,
}: PlayerListProps): React.ReactElement {
  if (variant === 'inline') {
    return (
      <ul className={styles.inlineList} aria-label="Liste des joueurs">
        {players.map((player) => (
          <li key={player.sessionId} className={styles.inlineRow}>
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
        <li key={player.sessionId} className={styles.stackedRow}>
          <span className={styles.stackedPseudonym}>{player.pseudonym}</span>
          {player.sessionId === currentSessionId
            ? <span className={styles.badge}>vous</span> : null}
          {player.sessionId === ownerSessionId
            ? <span className={styles.ownerBadge}>propriétaire</span> : null}
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
