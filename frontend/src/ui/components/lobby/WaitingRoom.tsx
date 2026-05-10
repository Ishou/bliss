import { useEffect, useRef, useState } from 'react';
import { css } from 'styled-system/css';
import { MAX_PSEUDONYM_LENGTH, type Lobby, type Pseudonym, type SessionId } from '@/domain/game';
import { EyeIcon, EyeOffIcon } from '@/ui/components/icons';
import { Button, TextField, ToggleGroup } from '@/ui/components/primitives';
import { PinInput } from '@/ui/components/primitives/PinInput';
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
// All interactive controls (Button, TextField, ToggleGroup) are project-
// local primitives wrapping Ark UI per ADR-0002 §2.
//
// Grid presets: square sizes 5–11 and the 15×12 landscape default. The
// option `value` doubles as a stable serialisable id (`<W>x<H>`); the
// picker resolves it back to (width, height) before invoking
// `onSetGridConfig`.
const GRID_SIZE_OPTIONS = [
  { value: '5x5',   label: '5×5',   width: 5,  height: 5 },
  { value: '7x7',   label: '7×7',   width: 7,  height: 7 },
  { value: '9x9',   label: '9×9',   width: 9,  height: 9 },
  { value: '11x11', label: '11×11', width: 11, height: 11 },
  { value: '15x12', label: '15×12', width: 15, height: 12 },
] as const;
type GridSize = (typeof GRID_SIZE_OPTIONS)[number]['value'];

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
  // True between the moment the owner clicks "Démarrer la partie" and
  // the server-side confirmation (`gameStarted` event flips the lobby
  // to IN_PROGRESS, at which point this component unmounts) or an
  // `error` frame clears it. While true the Start button is disabled
  // and the label flips to "Démarrage…" so the click is acknowledged
  // even though the WS round-trip can take several hundred ms.
  readonly isStarting?: boolean;
  // ADR-0029: owner-only join-code rotation. Button only rendered
  // when `onRotateCode` is supplied AND the caller is the owner.
  // `isRotating` mirrors `isStarting`'s busy-state posture.
  readonly onRotateCode?: () => void;
  readonly isRotating?: boolean;
}

const styles = {
  container: css({
    display: 'flex', flexDirection: 'column', gap: 'md',
    width: '100%', maxWidth: '480px', margin: '0 auto',
    padding: 'md', bg: 'bg', color: 'fg', fontFamily: 'body',
  }),
  sectionTitle: css({
    fontSize: 'md', fontWeight: 'bold', color: 'accent', margin: 0,
  }),
  row: css({ display: 'flex', alignItems: 'center', gap: 'sm' }),
  inlineField: css({ flex: 1 }),
  // Inline confirmation surfaced next to the share button after a
  // successful clipboard write. `role="status"` + `aria-live="polite"`
  // so screen readers announce the change without stealing focus.
  copyFeedback: css({
    fontSize: 'sm', color: 'accent', fontWeight: 'medium',
  }),
  // Code surface — readonly `PinInput` flanked by an eye toggle.
  // Same primitive the Accueil "Rejoindre" input uses, in `readOnly`
  // mode, so mask vs reveal swaps the masking glyph without changing
  // the slot widths. Single visibility affordance: clicking the eye
  // is what reveals the code; "Copier le lien" copies the share URL
  // (which contains the code, like `/join/A2B3C4`).
  codeSection: css({
    display: 'flex',
    flexDirection: 'column',
    gap: 'xs',
  }),
  codeRow: css({
    display: 'flex',
    alignItems: 'center',
    gap: 'sm',
  }),
  codeLabel: css({ fontSize: 'sm', color: 'fgMuted' }),
  // Plain icon button — no border / fill until hover, so it reads as
  // a tertiary affordance next to the PIN slots.
  iconToggle: css({
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
  }),
};

// How long the inline "Lien copié !" feedback stays on screen after a
// successful clipboard write. 2 s is long enough to read without
// becoming a distraction; matches the "toast" timing patterns used
// elsewhere in the design system without pulling in a global toast
// store (deliberately out of scope — this is a single call site).
const COPY_FEEDBACK_MS = 2000;

export function WaitingRoom({
  lobby, currentSessionId, onRename, onSetGridConfig, onStart, onCopyShareUrl,
  pseudonymError, onClearPseudonymError, isStarting = false,
  onRotateCode, isRotating = false,
}: WaitingRoomProps): React.ReactElement {
  const isOwner = lobby.ownerSessionId === currentSessionId;
  // Solo-through-the-multiplayer-flow is supported (handy while waiting for
  // friends or for testing): the owner can Start as soon as there is at
  // least one player, which is always true since the owner is a member.
  const canStart = isOwner && lobby.players.length >= 1 && !isStarting;
  const me = lobby.players.find((p) => p.sessionId === currentSessionId);

  // Inline feedback for the "Copier le lien" button. State is held here
  // (rather than promoted to a global toast store) because this is the
  // only call site that needs it; a full toast pipeline would be a
  // separate PR if more surfaces start needing the same UX.
  const [justCopied, setJustCopied] = useState(false);
  const copyTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(
    () => () => {
      if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
    },
    [],
  );
  const handleCopyClick = () => {
    onCopyShareUrl();
    if (copyTimerRef.current !== null) clearTimeout(copyTimerRef.current);
    setJustCopied(true);
    copyTimerRef.current = setTimeout(() => {
      setJustCopied(false);
      copyTimerRef.current = null;
    }, COPY_FEEDBACK_MS);
  };
  // ADR-0027 §"WaitingRoom code visibility toggle" — default-masked
  // (streamer-safe). The eye toggle is the single visibility
  // affordance; the share button below copies the `/join/$code`
  // URL which already contains the code.
  const [codeRevealed, setCodeRevealed] = useState(false);

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

      {lobby.code != null ? (
        <div className={styles.codeSection}>
          <span className={styles.codeLabel}>Code de partie</span>
          <div className={styles.codeRow}>
            <PinInput
              label="Code de partie"
              value={lobby.code}
              onValueChange={() => { /* readonly — no edits accepted */ }}
              mask={!codeRevealed}
              readOnly
            />
            <button
              type="button"
              className={styles.iconToggle}
              aria-label={codeRevealed ? 'Masquer le code' : 'Afficher le code'}
              aria-pressed={codeRevealed}
              onClick={() => setCodeRevealed((v) => !v)}
            >
              {codeRevealed ? <EyeOffIcon /> : <EyeIcon />}
            </button>
          </div>
          {isOwner && onRotateCode ? (
            <Button
              variant="ghost"
              onClick={onRotateCode}
              disabled={isRotating}
              aria-busy={isRotating || undefined}
            >
              {isRotating ? 'Régénération…' : 'Régénérer le code'}
            </Button>
          ) : null}
        </div>
      ) : null}

      <div className={styles.row}>
        <Button variant="ghost" onClick={handleCopyClick}>
          Copier le lien
        </Button>
        {justCopied ? (
          <span role="status" aria-live="polite" className={styles.copyFeedback}>
            Lien copié !
          </span>
        ) : null}
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
          aria-busy={isStarting || undefined}
        >
          {isStarting ? 'Démarrage…' : 'Démarrer la partie'}
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

// Five presets: four squares (5×5 / 7×7 / 9×9 / 11×11) and the 15×12
// landscape default. Selection is keyed by the `<W>x<H>` value string;
// `currentValue` resolves the lobby's actual gridConfig back to the
// matching option, with the empty string used when the lobby holds a
// custom (W,H) outside the preset set so the toggle group renders
// nothing-selected rather than misattributing.
function GridSizePicker({
  gridConfig, onSetGridConfig,
}: {
  readonly gridConfig: Lobby['gridConfig'];
  readonly onSetGridConfig: (width: number, height: number) => void;
}): React.ReactElement {
  const match = GRID_SIZE_OPTIONS.find(
    (o) => o.width === gridConfig.width && o.height === gridConfig.height,
  );
  const currentValue = (match?.value ?? '') as GridSize;
  return (
    <ToggleGroup<GridSize>
      label="Taille de la grille"
      name="grid-size"
      value={currentValue}
      onValueChange={(value) => {
        const option = GRID_SIZE_OPTIONS.find((o) => o.value === value);
        if (option) onSetGridConfig(option.width, option.height);
      }}
      options={GRID_SIZE_OPTIONS.map((o) => ({ value: o.value, label: o.label }))}
    />
  );
}
