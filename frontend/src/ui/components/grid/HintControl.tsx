import { useCallback } from 'react';
import { css, cx } from 'styled-system/css';
import { IconButton } from '@/ui/components/primitives';
import { HintIcon } from '@/ui/components/icons';
import type { HintLastResult } from './useHintRequest';

// Toolbar affordance for the per-puzzle hint budget. Clicking spends one
// credit to reveal the canonical letter at the currently focused cell;
// the status pill (aria-live) reports `Lettre révélée` / `Indices
// épuisés`. The button is disabled while pending or once the budget hit
// zero (exhausted = server 429 or 200 with hintsRemaining 0, locked for
// the rest of the puzzle). Focus / locked-cell guards happen in the
// click handler itself rather than the disabled prop: the focused cell
// changes on every keystroke (auto-advance), and reflecting that into
// React state would break the uncontrolled-input contract from
// ADR-0002 §4. A click on a locked cell is a silent no-op.

const containerStyles = css({
  position: 'relative',
  display: 'inline-flex',
  alignItems: 'center',
  gap: '6px',
});

const badgeStyles = css({
  fontFamily: 'mono',
  fontSize: 'xs',
  color: 'fgMuted',
  fontVariantNumeric: 'tabular-nums',
  letterSpacing: '0.02em',
  // Reserve space for the two-character ratio (e.g. "0/3") so the
  // toolbar layout doesn't reflow as the count drops.
  minWidth: '2.4em',
  textAlign: 'left',
});

const statusBaseStyles = css({
  position: 'absolute',
  // Sit just below the toolbar; the parent panel positions relatively.
  top: 'calc(100% + 4px)',
  right: 0,
  fontFamily: 'body',
  fontSize: 'xs',
  paddingX: '8px',
  paddingY: '4px',
  borderRadius: 'sm',
  whiteSpace: 'nowrap',
  pointerEvents: 'none',
});

const statusSuccessStyles = css({
  bg: 'successBg',
  color: 'successText',
});

const statusFailureStyles = css({
  bg: 'surface',
  color: 'fgMuted',
  border: '1px solid token(colors.border)',
});

const statusErrorStyles = css({
  bg: 'errorBg',
  color: 'errorText',
});

const liveRegionStyles = css({
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0,0,0,0)',
  whiteSpace: 'nowrap',
  border: 0,
});

export interface FocusedCell {
  readonly row: number;
  readonly column: number;
  /** True iff the cell has already been revealed via a previous hint. */
  readonly isLocked: boolean;
}

export interface HintControlProps {
  readonly hintsRemaining: number;
  readonly hintsAllowed: number;
  readonly exhausted: boolean;
  readonly pending: boolean;
  readonly lastResult: HintLastResult | null;
  readonly errorMessage: string | null;
  /** Returns the focused letter cell, or `null` if no cell has focus. */
  readonly getFocusedCell: () => FocusedCell | null;
  readonly onRequest: (row: number, column: number) => void;
}

export function HintControl({
  hintsRemaining,
  hintsAllowed,
  exhausted,
  pending,
  lastResult,
  errorMessage,
  getFocusedCell,
  onRequest,
}: HintControlProps) {
  const handleClick = useCallback(() => {
    const cell = getFocusedCell();
    if (!cell || cell.isLocked) return;
    onRequest(cell.row, cell.column);
  }, [getFocusedCell, onRequest]);

  // Keep focus on the active cell so the user doesn't have to tab back
  // into the grid after spending a hint. `mousedown`'s default focuses
  // the button; preventing it suppresses the focus shift while still
  // letting the synthesized `click` fire.
  const handleMouseDown = useCallback((event: React.MouseEvent) => {
    event.preventDefault();
  }, []);

  const status = renderStatus({ lastResult, errorMessage, exhausted });

  return (
    <div className={containerStyles}>
      <IconButton
        aria-label="Demander un indice"
        tone="accent"
        onClick={handleClick}
        onMouseDown={handleMouseDown}
        disabled={exhausted || pending}
      >
        <HintIcon />
      </IconButton>
      <span
        className={badgeStyles}
        aria-label={`${hintsRemaining} sur ${hintsAllowed} indices restants`}
      >
        {hintsRemaining}/{hintsAllowed}
      </span>
      {status ? (
        <span
          className={cx(statusBaseStyles, status.className)}
          role="status"
        >
          {status.text}
        </span>
      ) : null}
      <span className={liveRegionStyles} aria-live="polite">
        {status?.text ?? ''}
      </span>
    </div>
  );
}

function renderStatus({
  lastResult,
  errorMessage,
  exhausted,
}: {
  lastResult: HintLastResult | null;
  errorMessage: string | null;
  exhausted: boolean;
}): { text: string; className: string } | null {
  if (errorMessage) {
    return {
      text: errorMessage,
      className: exhausted ? statusErrorStyles : statusFailureStyles,
    };
  }
  if (lastResult) {
    return {
      text: `Lettre révélée : ${lastResult.letter}`,
      className: statusSuccessStyles,
    };
  }
  return null;
}
