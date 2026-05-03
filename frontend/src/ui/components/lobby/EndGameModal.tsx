import { css } from 'styled-system/css';
import { Button, Dialog, DialogDescription } from '@/ui/components/primitives';

// End-of-game modal shown when the lobby receives a `gameSolved` event.
// Pure prop-driven so the lobby route owns the open/close lifecycle and
// passes the final `durationMs` from the event payload.
//
// Accessibility: handled by the `Dialog` primitive (Ark UI). The
// underlying `Dialog.Content` gets `role="dialog"` + `aria-modal="true"`
// + `aria-labelledby` linked to `Dialog.Title`. Focus trap, ESC-to-close,
// outside-click close, and focus restoration on close come from
// `@zag-js/dialog` — see `ui/components/primitives/Dialog.tsx` for the
// full prop surface.
//
// Color contrast: leaf.700 / blossom.700 on cream surfaces ≥ 4.5:1
// (cf. ADR-0005 §4 escape-hatch ramp shades).

export interface EndGameModalProps {
  readonly durationMs: number;
  readonly onPlayAgain: () => void;
  readonly onClose: () => void;
}

const HOUR_MS = 60 * 60 * 1000;
const MINUTE_MS = 60 * 1000;
const SECOND_MS = 1000;

const twoDigitFormatter = new Intl.NumberFormat('fr-FR', {
  minimumIntegerDigits: 2,
  useGrouping: false,
});

function formatDuration(ms: number): string {
  const safe = Math.max(0, ms);
  const totalSeconds = Math.floor(safe / SECOND_MS);
  const hours = Math.floor(safe / HOUR_MS);
  const minutes = Math.floor((safe % HOUR_MS) / MINUTE_MS);
  const seconds = totalSeconds % 60;
  const mm = twoDigitFormatter.format(minutes);
  const ss = twoDigitFormatter.format(seconds);
  if (hours > 0) return `${twoDigitFormatter.format(hours)}:${mm}:${ss}`;
  return `${mm}:${ss}`;
}

const durationStyles = css({
  fontSize: 'display',
  fontWeight: 'black',
  color: 'fg',
  fontVariantNumeric: 'tabular-nums',
  letterSpacing: '0.02em',
  margin: 0,
  textAlign: 'center',
});

const actionsStyles = css({
  display: 'flex',
  gap: 'sm',
  flexWrap: 'wrap',
  justifyContent: 'flex-end',
  marginTop: 'sm',
});

export function EndGameModal({ durationMs, onPlayAgain, onClose }: EndGameModalProps) {
  return (
    <Dialog
      open
      onClose={onClose}
      title={<>Bravo&nbsp;! Grille terminée</>}
      backdropTestId="end-game-modal-backdrop"
      contentTestId="end-game-modal"
    >
      <DialogDescription>Temps final&nbsp;:</DialogDescription>
      <p className={durationStyles} data-testid="end-game-modal-duration">
        {formatDuration(durationMs)}
      </p>
      <div className={actionsStyles}>
        <Button
          variant="secondary"
          onClick={onClose}
          data-testid="end-game-modal-close"
        >
          Fermer
        </Button>
        <Button
          variant="primary"
          onClick={onPlayAgain}
          data-testid="end-game-modal-play-again"
        >
          Rejouer
        </Button>
      </div>
    </Dialog>
  );
}
