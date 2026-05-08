import { css } from 'styled-system/css';
import type { Lobby, SessionId } from '@/domain/game';
import { playerColorVars, playerInitial } from '@/ui/lib/playerColor';

// Pure prop-driven roster. Used in two layouts:
//   - `stacked`: vertical list with empty-slot placeholders, mounted by
//     WaitingRoom while `lobby.state === 'WAITING'`.
//   - `inline`:  horizontal pill row with no empty slots, mounted by the
//     lobby route during `IN_PROGRESS` / `COMPLETED` so players keep
//     visibility on who they are playing with without eating grid real estate.
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
  /**
   * Optional set of session ids whose owner is currently typing. The
   * row's typing-dot animates with the `wordsparrow-presence-pulse`
   * keyframe when the session is in the set.
   * Inline variant only — the stacked (waiting-room) variant ignores it.
   */
  readonly typingSessionIds?: ReadonlySet<SessionId>;
  /**
   * Optional set of session ids whose owner crossed the inactivity
   * threshold. The row gets `data-idle="true"` so the avatar /
   * pseudonym desaturate. Cleared on the server's falling `idle` edge.
   */
  readonly idleSessionIds?: ReadonlySet<SessionId>;
  /**
   * Optional set of session ids whose WebSocket dropped (graceful
   * disconnect) and the slot has not yet been removed by `playerLeft`.
   * The row gets `data-disconnecting="true"` so the pill greys out
   * while the server's reconnect grace runs.
   */
  readonly disconnectingSessionIds?: ReadonlySet<SessionId>;
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
    alignItems: 'center', justifyContent: 'center', gap: '8px',
    listStyle: 'none', padding: 0, margin: 0,
    width: '100%',
  }),
  // Pill design (in-game roster). Very low-chrome pill: hairline border
  // and 2.5 % white wash so the chip floats over the page background
  // without competing with the grid. The local player's pill is tinted
  // with their own `--player-color` (10 % alpha bg, 20 % alpha border)
  // so a glance at the roster confirms which pill is "you" without
  // reading the pseudonym. Pseudonym colour stays muted by default and
  // brightens on the local pill for legibility on the tinted bg.
  inlineRow: css({
    display: 'inline-flex', alignItems: 'center', gap: '6px',
    paddingBlock: '3px', paddingInlineStart: '3px', paddingInlineEnd: '10px',
    background: 'rgba(255, 255, 255, 0.025)',
    border: '0.5px solid rgba(255, 255, 255, 0.05)',
    borderRadius: '999px',
    fontSize: '12px',
    color: 'fgMuted',
    transition: 'filter 200ms ease-out, opacity 200ms ease-out',
    '&[data-you="true"]': {
      background:
        'color-mix(in srgb, var(--player-color) 10%, transparent)',
      borderColor:
        'color-mix(in srgb, var(--player-color) 20%, transparent)',
      color: 'fg',
    },
    // Idle (>30s of no activity from this peer): drop chroma 40 % so
    // the avatar reads as "still here, not active".
    '&[data-idle="true"]': {
      filter: 'saturate(0.6)',
    },
    // Graceful disconnect: greyscale + half opacity until either a
    // `presenceUpdated` brings the session back (clears `data-disconnecting`)
    // or `playerLeft` removes the row entirely.
    '&[data-disconnecting="true"]': {
      filter: 'grayscale(1)',
      opacity: 0.5,
    },
  }),
  inlineAvatar: css({
    width: '22px', height: '22px', borderRadius: '50%',
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    fontSize: '11px', fontWeight: 600,
    background: 'var(--player-color)', color: 'var(--player-on)',
    flexShrink: 0,
  }),
  inlinePseudonym: css({ fontWeight: 'medium' }),
  // Typing-pulse dot. Visible only when the row's `data-typing="true"`
  // — set by the inline list when the player's sessionId is in the
  // `typingSessionIds` prop. Mirrors the cell-badge pulse so a player
  // who is typing pulses in BOTH places.
  inlineTypingDot: css({
    width: '6px', height: '6px', borderRadius: '50%',
    backgroundColor: 'var(--player-color)',
    animation: 'wordsparrow-presence-pulse 1.4s ease-in-out infinite',
    flexShrink: 0,
  }),
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
  typingSessionIds, idleSessionIds, disconnectingSessionIds,
}: PlayerListProps): React.ReactElement {
  if (variant === 'inline') {
    return (
      <ul className={styles.inlineList} aria-label="Liste des joueurs">
        {players.map((player) => {
          const isYou = player.sessionId === currentSessionId;
          const isTyping = typingSessionIds?.has(player.sessionId) ?? false;
          const isIdle = idleSessionIds?.has(player.sessionId) ?? false;
          const isDisconnecting =
            disconnectingSessionIds?.has(player.sessionId) ?? false;
          const ariaLabel = [
            player.pseudonym,
            isYou ? 'vous' : null,
            player.sessionId === ownerSessionId ? 'propriétaire' : null,
            isTyping ? 'en train d\'écrire' : null,
            isIdle ? 'inactif' : null,
            isDisconnecting ? 'déconnecté' : null,
          ]
            .filter(Boolean)
            .join(' — ');
          return (
            <li
              key={player.sessionId}
              className={styles.inlineRow}
              style={playerColorVars(player.sessionId)}
              data-testid="player-row"
              data-session-id={player.sessionId}
              data-you={isYou ? 'true' : undefined}
              data-typing={isTyping ? 'true' : undefined}
              data-idle={isIdle ? 'true' : undefined}
              data-disconnecting={isDisconnecting ? 'true' : undefined}
              aria-label={ariaLabel}
            >
              <span className={styles.inlineAvatar} aria-hidden="true">
                {playerInitial(player.pseudonym)}
              </span>
              <span className={styles.inlinePseudonym}>{player.pseudonym}</span>
              {isTyping ? (
                <span
                  className={styles.inlineTypingDot}
                  aria-hidden="true"
                  data-testid="player-typing-dot"
                />
              ) : null}
            </li>
          );
        })}
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
