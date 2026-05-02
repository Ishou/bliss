import { useEffect, useRef } from 'react';
import { css } from 'styled-system/css';

// End-of-game modal shown when the lobby receives a `gameSolved` event.
// Pure prop-driven so the lobby route owns the open/close lifecycle and
// passes the final `durationMs` from the event payload.
//
// Accessibility:
//   * `role="dialog"` + `aria-modal="true"` + `aria-labelledby` per WAI-ARIA APG.
//   * Focus moves to the dialog on mount; the previously focused element
//     is restored on unmount so screen-reader users return to context.
//   * Focus is trapped: Tab/Shift+Tab cycle through focusable descendants.
//   * ESC fires `onClose`. Backdrop click also fires `onClose`.
//   * Color contrast: leaf.700 / blossom.700 on cream surfaces ≥ 4.5:1
//     (cf. ADR-0005 §4 escape-hatch ramp shades).

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

const backdropStyles = css({
  position: 'fixed',
  inset: 0,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  // Translucent ink scrim — keeps the page visible underneath while
  // dimming it enough for the dialog surface to read as elevated.
  bg: 'rgba(27, 40, 69, 0.6)',
  zIndex: 1000,
  padding: 'md',
});

const dialogStyles = css({
  bg: 'cream',
  color: 'fg',
  borderRadius: 'md',
  padding: 'lg',
  maxWidth: '420px',
  width: '100%',
  boxShadow: '0 12px 40px -8px rgba(0, 0, 0, 0.35)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'md',
  fontFamily: 'body',
  // Focusing the dialog itself on mount is part of the WAI-ARIA APG
  // pattern; keep the outline but soften it so it doesn't shout.
  outline: 'none',
  _focusVisible: { boxShadow: '0 12px 40px -8px rgba(0, 0, 0, 0.35), 0 0 0 3px token(colors.leaf.500)' },
});

const titleStyles = css({
  fontSize: 'xl',
  fontWeight: 'bold',
  color: 'leaf.700',
  margin: 0,
});

const durationStyles = css({
  fontSize: 'display',
  fontWeight: 'black',
  color: 'fg',
  fontVariantNumeric: 'tabular-nums',
  letterSpacing: '0.02em',
  margin: 0,
  textAlign: 'center',
});

const captionStyles = css({
  fontSize: 'body',
  color: 'fg',
  margin: 0,
  textAlign: 'center',
  opacity: 0.8,
});

const actionsStyles = css({
  display: 'flex',
  gap: 'sm',
  flexWrap: 'wrap',
  justifyContent: 'flex-end',
  marginTop: 'sm',
});

// Shared button shape; primary fills, secondary outlines. Both share the
// same focus-ring + body-font + padding rhythm, so the variant just flips
// bg/color/border. Keeps the diff small and the visual rhythm consistent.
const buttonBase = css({
  borderRadius: 'sm',
  paddingInline: 'md',
  paddingBlock: 'sm',
  fontSize: 'body',
  fontWeight: 'bold',
  fontFamily: 'body',
  cursor: 'pointer',
  _focusVisible: { outline: '3px solid token(colors.leaf.500)', outlineOffset: '2px' },
});
const primaryButton = css({
  bg: 'leaf.700', color: 'breath', border: 'none', _hover: { bg: 'leaf.800' },
});
const secondaryButton = css({
  bg: 'transparent', color: 'leaf.700',
  border: '1px solid token(colors.leaf.700)', _hover: { bg: 'leaf.50' },
});

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

export function EndGameModal({ durationMs, onPlayAgain, onClose }: EndGameModalProps) {
  const dialogRef = useRef<HTMLDivElement | null>(null);
  const titleId = 'end-game-modal-title';

  useEffect(() => {
    // Stash the element that had focus before the modal opened so we can
    // restore it on unmount (WAI-ARIA APG dialog pattern, focus return).
    const previouslyFocused = document.activeElement as HTMLElement | null;
    // Focus the dialog itself on open so a screen reader announces the
    // labelled dialog before any interactive control is reached.
    dialogRef.current?.focus();
    return () => {
      // Restore focus only if the previously focused element is still
      // attached and focusable — avoids a TypeError on element removal.
      if (previouslyFocused && document.contains(previouslyFocused)) {
        previouslyFocused.focus();
      }
    };
  }, []);

  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        event.stopPropagation();
        onClose();
        return;
      }
      if (event.key !== 'Tab') return;
      // Focus trap — keep Tab cycling inside the dialog. The dialog
      // itself has `tabIndex={-1}` for programmatic focus only; the real
      // tab stops are descendants matched by FOCUSABLE_SELECTOR.
      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusables = Array.from(
        dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR),
      ).filter((el) => !el.hasAttribute('disabled'));
      if (focusables.length === 0) {
        event.preventDefault();
        dialog.focus();
        return;
      }
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      const active = document.activeElement;
      if (event.shiftKey && (active === first || active === dialog)) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && active === last) {
        event.preventDefault();
        first.focus();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [onClose]);

  // Backdrop click closes; clicks inside the dialog stop at the inner
  // div's own onClick (a no-op stopPropagation handler).
  function handleBackdropClick(event: React.MouseEvent<HTMLDivElement>) {
    if (event.target === event.currentTarget) onClose();
  }

  return (
    <div
      className={backdropStyles}
      onClick={handleBackdropClick}
      data-testid="end-game-modal-backdrop"
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={dialogStyles}
        data-testid="end-game-modal"
      >
        <h2 id={titleId} className={titleStyles}>
          Bravo&nbsp;! Grille terminée
        </h2>
        <p className={captionStyles}>Temps final&nbsp;:</p>
        <p className={durationStyles} data-testid="end-game-modal-duration">
          {formatDuration(durationMs)}
        </p>
        <div className={actionsStyles}>
          <button
            type="button"
            className={`${buttonBase} ${secondaryButton}`}
            onClick={onClose}
            data-testid="end-game-modal-close"
          >
            Fermer
          </button>
          <button
            type="button"
            className={`${buttonBase} ${primaryButton}`}
            onClick={onPlayAgain}
            data-testid="end-game-modal-play-again"
          >
            Rejouer
          </button>
        </div>
      </div>
    </div>
  );
}
