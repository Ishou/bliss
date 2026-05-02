import { useState } from 'react';
import { css } from 'styled-system/css';
import type { Lobby, Pseudonym, SessionId } from '@/domain/game';

// Pure prop-driven WaitingRoom rendered while `lobby.state === 'WAITING'`.
// Owns no network, no router context, no localStorage — every side effect
// is delegated to one of the four `on*` callbacks the parent route wires
// to the `GameClient` port. That keeps `ui/components/` free of any
// `infrastructure/` import (eslint-plugin-boundaries) and makes the
// component trivially unit-testable.
//
// Slot model: the lobby caps at 8 players (game/api ServerLobbyState
// schema `maxItems: 8`). Empty slots render as placeholder rows so the
// list keeps a stable height while peers join.
const MAX_PLAYERS = 8;
const GRID_SIZES: readonly number[] = [5, 7, 9, 11];

export interface WaitingRoomProps {
  readonly lobby: Lobby;
  readonly currentSessionId: SessionId;
  readonly onRename: (newPseudonym: Pseudonym) => void;
  readonly onSetGridConfig: (width: number, height: number) => void;
  readonly onStart: () => void;
  readonly onCopyShareUrl: () => void;
}

const styles = {
  container: css({
    display: 'flex', flexDirection: 'column', gap: 'md',
    width: '100%', maxWidth: '480px', margin: '0 auto',
    padding: 'md', bg: 'bg', color: 'fg', fontFamily: 'body',
  }),
  sectionTitle: css({
    fontSize: 'md', fontWeight: 'bold', color: 'leaf.700', margin: 0,
  }),
  list: css({
    display: 'flex', flexDirection: 'column', gap: 'xs',
    listStyle: 'none', padding: 0, margin: 0,
  }),
  playerRow: css({
    display: 'flex', alignItems: 'center', gap: 'sm',
    padding: 'sm', border: '1px solid token(colors.border)',
    borderRadius: 'sm', bg: 'surface',
  }),
  emptySlot: css({
    display: 'flex', alignItems: 'center', padding: 'sm',
    border: '1px dashed token(colors.border)', borderRadius: 'sm',
    bg: 'bg', color: 'accent', fontStyle: 'italic',
  }),
  pseudonym: css({ flex: 1, fontWeight: 'medium', color: 'fg' }),
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
  button: css({
    paddingBlock: 'sm', paddingInline: 'md', borderRadius: 'sm',
    border: '1px solid token(colors.border)', bg: 'surface', color: 'fg',
    fontFamily: 'body', fontSize: 'body', fontWeight: 'semibold',
    cursor: 'pointer',
    _disabled: { opacity: 0.5, cursor: 'not-allowed' },
  }),
  primaryButton: css({
    paddingBlock: 'sm', paddingInline: 'md', borderRadius: 'sm',
    border: 'none', bg: 'leaf.700', color: 'breath',
    fontFamily: 'body', fontSize: 'body', fontWeight: 'bold',
    cursor: 'pointer',
    _disabled: { opacity: 0.5, cursor: 'not-allowed' },
  }),
  input: css({
    flex: 1, paddingBlock: 'sm', paddingInline: 'sm', borderRadius: 'sm',
    border: '1px solid token(colors.border)', bg: 'surface', color: 'fg',
    fontFamily: 'body', fontSize: 'body',
  }),
  row: css({ display: 'flex', alignItems: 'center', gap: 'sm' }),
  radioGroup: css({ display: 'flex', flexWrap: 'wrap', gap: 'sm' }),
  radioLabel: css({
    display: 'inline-flex', alignItems: 'center', gap: 'xs',
    paddingBlock: 'xs', paddingInline: 'sm', borderRadius: 'sm',
    border: '1px solid token(colors.border)', bg: 'surface',
    cursor: 'pointer', fontSize: 'sm',
  }),
  fieldset: css({ border: 'none', padding: 0, margin: 0 }),
};

export function WaitingRoom({
  lobby, currentSessionId, onRename, onSetGridConfig, onStart, onCopyShareUrl,
}: WaitingRoomProps): React.ReactElement {
  const isOwner = lobby.ownerSessionId === currentSessionId;
  const canStart = isOwner && lobby.players.length >= 2;
  const me = lobby.players.find((p) => p.sessionId === currentSessionId);
  const emptySlots = Math.max(0, MAX_PLAYERS - lobby.players.length);

  return (
    <section className={styles.container} aria-label="Salle d'attente">
      <div>
        <h2 className={styles.sectionTitle}>
          Joueurs ({lobby.players.length}/{MAX_PLAYERS})
        </h2>
        <ul className={styles.list} aria-label="Liste des joueurs">
          {lobby.players.map((player) => (
            <li key={player.sessionId} className={styles.playerRow}>
              <span className={styles.pseudonym}>{player.pseudonym}</span>
              {player.sessionId === currentSessionId
                ? <span className={styles.badge}>vous</span> : null}
              {player.sessionId === lobby.ownerSessionId
                ? <span className={styles.ownerBadge}>propriétaire</span> : null}
            </li>
          ))}
          {Array.from({ length: emptySlots }).map((_, idx) => (
            <li key={`empty-${idx}`} className={styles.emptySlot} aria-label="Place libre">
              Place libre
            </li>
          ))}
        </ul>
      </div>

      <div className={styles.row}>
        <button type="button" className={styles.button} onClick={onCopyShareUrl}>
          Copier le lien
        </button>
      </div>

      {me ? <PseudonymEditor currentPseudonym={me.pseudonym} onRename={onRename} /> : null}

      {isOwner ? (
        <GridSizePicker gridConfig={lobby.gridConfig} onSetGridConfig={onSetGridConfig} />
      ) : null}

      {isOwner ? (
        <button
          type="button" className={styles.primaryButton}
          onClick={onStart} disabled={!canStart}
        >
          Démarrer la partie
        </button>
      ) : null}
    </section>
  );
}

// Inline edit-on-click. Click the displayed pseudonym to reveal the
// input; Enter or blur fires `onRename` and exits edit mode. Empty /
// unchanged values are no-ops so a stray click does not wipe a name.
function PseudonymEditor({
  currentPseudonym, onRename,
}: {
  readonly currentPseudonym: Pseudonym;
  readonly onRename: (newPseudonym: Pseudonym) => void;
}): React.ReactElement {
  const [isEditing, setIsEditing] = useState(false);
  const [draft, setDraft] = useState<string>(currentPseudonym);

  const commit = () => {
    const trimmed = draft.trim();
    if (trimmed.length > 0 && trimmed !== currentPseudonym) {
      onRename(trimmed as Pseudonym);
    }
    setIsEditing(false);
  };

  if (!isEditing) {
    return (
      <div className={styles.row}>
        <h2 className={styles.sectionTitle}>Votre pseudonyme</h2>
        <button
          type="button" className={styles.button}
          onClick={() => { setDraft(currentPseudonym); setIsEditing(true); }}
          aria-label={`Modifier votre pseudonyme : ${currentPseudonym}`}
        >
          {currentPseudonym}
        </button>
      </div>
    );
  }

  return (
    <div className={styles.row}>
      <label htmlFor="pseudonym-input" className={styles.sectionTitle}>
        Votre pseudonyme
      </label>
      <input
        id="pseudonym-input" className={styles.input} type="text"
        value={draft} onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); commit(); }
          if (e.key === 'Escape') { setIsEditing(false); }
        }}
        autoFocus
      />
    </div>
  );
}

// Square-only picker for v1: the four supported sizes (5×5 / 7×7 / 9×9 /
// 11×11) all match `width === height`, so we emit `(n, n)`.
function GridSizePicker({
  gridConfig, onSetGridConfig,
}: {
  readonly gridConfig: Lobby['gridConfig'];
  readonly onSetGridConfig: (width: number, height: number) => void;
}): React.ReactElement {
  return (
    <fieldset className={styles.fieldset}>
      <legend className={styles.sectionTitle}>Taille de la grille</legend>
      <div className={styles.radioGroup}>
        {GRID_SIZES.map((size) => (
          <label key={size} className={styles.radioLabel}>
            <input
              type="radio" name="grid-size" value={size}
              checked={gridConfig.width === size && gridConfig.height === size}
              onChange={() => onSetGridConfig(size, size)}
            />
            {size}×{size}
          </label>
        ))}
      </div>
    </fieldset>
  );
}
