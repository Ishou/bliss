import { useState } from 'react';
import { css } from 'styled-system/css';
import { MAX_PSEUDONYM_LENGTH, type Lobby, type Pseudonym, type SessionId } from '@/domain/game';
import { Button, RadioGroup, TextField } from '@/ui/components/primitives';
import { PlayerList, MAX_PLAYERS } from './PlayerList';

// Pure prop-driven WaitingRoom rendered while `lobby.state === 'WAITING'`.
// Owns no network, no router context, no localStorage — every side effect
// is delegated to one of the four `on*` callbacks the parent route wires
// to the `GameClient` port. That keeps `ui/components/` free of any
// `infrastructure/` import (eslint-plugin-boundaries) and makes the
// component trivially unit-testable.
//
// Roster rendering (with empty-slot placeholders) lives in PlayerList so
// the in-game route can mount the same component in its `inline` variant
// during IN_PROGRESS / COMPLETED.
//
// All interactive controls (Button, TextField, RadioGroup) are project-
// local primitives wrapping Ark UI per ADR-0002 §2.
const GRID_SIZES = ['5', '7', '9', '11'] as const;
type GridSize = (typeof GRID_SIZES)[number];

const GRID_SIZE_OPTIONS = GRID_SIZES.map((size) => ({
  value: size,
  label: `${size}×${size}`,
}));

export interface WaitingRoomProps {
  readonly lobby: Lobby;
  readonly currentSessionId: SessionId;
  readonly onRename: (newPseudonym: Pseudonym) => void;
  readonly onSetGridConfig: (width: number, height: number) => void;
  readonly onStart: () => void;
  readonly onCopyShareUrl: () => void;
  // Server-side rename rejection surfaced inline below the editor.
  // The parent route subscribes to `gameClient` `error` frames with
  // `errorType === 'https://bliss.example/errors/invalid-pseudonym'`
  // and threads the `detail` string here. `null` (or omitted) hides
  // the message. Defensive: the `maxLength` HTML attribute on the
  // input is the primary UX guard, but this surfaces any drift between
  // the client cap and the server's `Pseudonym` invariants.
  readonly pseudonymError?: string | null;
  // Called when the editor opens or the user types — gives the parent
  // a hook to clear stale `pseudonymError` so a successful retry does
  // not leave the previous failure rendered. Optional; if omitted, the
  // error stays visible until the parent's own clearing logic fires.
  readonly onClearPseudonymError?: () => void;
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
  row: css({ display: 'flex', alignItems: 'center', gap: 'sm' }),
  inlineField: css({ flex: 1 }),
};

export function WaitingRoom({
  lobby, currentSessionId, onRename, onSetGridConfig, onStart, onCopyShareUrl,
  pseudonymError, onClearPseudonymError,
}: WaitingRoomProps): React.ReactElement {
  const isOwner = lobby.ownerSessionId === currentSessionId;
  // Solo-through-the-multiplayer-flow is supported (handy while waiting for
  // friends or for testing): the owner can Start as soon as there is at
  // least one player, which is always true since the owner is a member.
  const canStart = isOwner && lobby.players.length >= 1;
  const me = lobby.players.find((p) => p.sessionId === currentSessionId);

  return (
    <section className={styles.container} aria-label="Salle d'attente">
      <div>
        <h2 className={styles.sectionTitle}>
          Joueurs ({lobby.players.length}/{MAX_PLAYERS})
        </h2>
        <PlayerList
          players={lobby.players}
          ownerSessionId={lobby.ownerSessionId}
          currentSessionId={currentSessionId}
          variant="stacked"
        />
      </div>

      <div className={styles.row}>
        <Button variant="ghost" onClick={onCopyShareUrl}>
          Copier le lien
        </Button>
      </div>

      {me ? (
        <PseudonymEditor
          currentPseudonym={me.pseudonym}
          onRename={onRename}
          pseudonymError={pseudonymError ?? null}
          onClearPseudonymError={onClearPseudonymError}
        />
      ) : null}

      {isOwner ? (
        <GridSizePicker gridConfig={lobby.gridConfig} onSetGridConfig={onSetGridConfig} />
      ) : null}

      {isOwner ? (
        <Button
          variant="primary"
          onClick={onStart}
          disabled={!canStart}
        >
          Démarrer la partie
        </Button>
      ) : null}
    </section>
  );
}

// Inline edit-on-click. Click the displayed pseudonym to reveal the
// `TextField`; Enter or blur fires `onRename` and exits edit mode. Empty /
// unchanged / over-cap values are no-ops so a stray click does not
// wipe a name AND a paste of a too-long string never sends a frame the
// server is going to reject. The `maxLength` HTML attribute on the
// underlying input prevents typing past `MAX_PSEUDONYM_LENGTH` (32) in
// the first place — the commit-time guard is belt-and-braces for the
// paste-then-Enter path and for any future drift between the FE constant
// and the server cap (which surfaces inline via `pseudonymError`).
function PseudonymEditor({
  currentPseudonym, onRename, pseudonymError, onClearPseudonymError,
}: {
  readonly currentPseudonym: Pseudonym;
  readonly onRename: (newPseudonym: Pseudonym) => void;
  readonly pseudonymError: string | null;
  readonly onClearPseudonymError?: () => void;
}): React.ReactElement {
  const [isEditing, setIsEditing] = useState(false);
  const [draft, setDraft] = useState<string>(currentPseudonym);

  const trimmed = draft.trim();
  const isOverCap = trimmed.length > MAX_PSEUDONYM_LENGTH;
  const isCommittable = trimmed.length > 0 && trimmed !== currentPseudonym && !isOverCap;

  const commit = () => {
    if (isCommittable) onRename(trimmed as Pseudonym);
    setIsEditing(false);
  };

  if (!isEditing) {
    return (
      <div className={styles.row}>
        <h2 className={styles.sectionTitle}>Votre pseudonyme</h2>
        <Button
          variant="ghost"
          onClick={() => {
            setDraft(currentPseudonym);
            onClearPseudonymError?.();
            setIsEditing(true);
          }}
          aria-label={`Modifier votre pseudonyme : ${currentPseudonym}`}
        >
          {currentPseudonym}
        </Button>
      </div>
    );
  }

  return (
    <TextField
      label="Votre pseudonyme"
      value={draft}
      maxLength={MAX_PSEUDONYM_LENGTH}
      invalid={pseudonymError != null || isOverCap || undefined}
      errorText={pseudonymError ?? undefined}
      onChange={(e) => {
        setDraft(e.target.value);
        // Clear the previous server-side error as soon as the user
        // starts editing — a fresh attempt deserves a fresh slate.
        if (pseudonymError != null) onClearPseudonymError?.();
      }}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') { e.preventDefault(); commit(); }
        if (e.key === 'Escape') { setIsEditing(false); }
      }}
      autoFocus
    />
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
  const currentValue = (gridConfig.width === gridConfig.height
    ? String(gridConfig.width)
    : '') as GridSize;
  return (
    <RadioGroup<GridSize>
      label="Taille de la grille"
      name="grid-size"
      value={currentValue}
      onValueChange={(value) => {
        const n = Number(value);
        onSetGridConfig(n, n);
      }}
      options={GRID_SIZE_OPTIONS}
    />
  );
}
