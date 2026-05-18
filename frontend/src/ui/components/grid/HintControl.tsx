import { useCallback } from 'react';
import { css, cx } from 'styled-system/css';
import { HintGate } from '@/ui/components/auth';
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
//
// Visual layer: a single pill button (bulb + "Indice" label + thin
// divider + counter) sized to match the toolbar's other 36-px
// affordances. On mobile the label collapses so the pill reads as
// `bulb 2/3` only — the divider hides with it.

const containerStyles = css({
  position: 'relative',
  display: 'inline-flex',
  alignItems: 'center',
});

const pillStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '8px',
  height: { base: '32px', md: '36px' },
  paddingInline: { base: '10px', md: '14px' },
  borderRadius: 'md',
  bg: 'transparent',
  color: 'accent',
  border: '1px solid token(colors.border)',
  fontFamily: 'body',
  fontWeight: 'medium',
  fontSize: 'sm',
  lineHeight: '1',
  cursor: 'pointer',
  transition:
    'background-color 120ms ease-out, border-color 120ms ease-out, color 120ms ease-out, transform 120ms ease-out',
  _hover: {
    borderColor: 'accent',
    bg: 'color-mix(in srgb, token(colors.accent) 10%, transparent)',
  },
  _active: { transform: 'scale(0.97)' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
  _disabled: {
    opacity: 0.5,
    cursor: 'not-allowed',
    _hover: { borderColor: 'border', bg: 'transparent' },
  },
  '& svg': {
    width: { base: '16px', md: '18px' },
    height: { base: '16px', md: '18px' },
  },
});

const labelStyles = css({
  display: { base: 'none', md: 'inline' },
});

const dividerStyles = css({
  display: { base: 'none', md: 'inline-block' },
  width: '1px',
  height: '16px',
  bg: 'color-mix(in srgb, token(colors.border) 80%, transparent)',
});

const counterStyles = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontFamily: 'mono',
  fontSize: 'xs',
  lineHeight: '1',
  color: 'fgMuted',
  fontVariantNumeric: 'tabular-nums',
  letterSpacing: '0.02em',
  // Reserve space for the two-character ratio (e.g. "0/3") so the
  // pill layout doesn't reflow as the count drops; centred so the
  // glyph sits visually balanced inside the pill instead of left-
  // anchored against the divider.
  minWidth: '2.4em',
  textAlign: 'center',
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
      <HintGate>
        <button
          type="button"
          className={pillStyles}
          aria-label="Demander un indice"
          title="Demander un indice"
          onClick={handleClick}
          onMouseDown={handleMouseDown}
          disabled={exhausted || pending}
        >
          <HintIcon />
          <span className={labelStyles}>Indice</span>
          <span className={dividerStyles} aria-hidden="true" />
          <span
            className={counterStyles}
            aria-label={`${hintsRemaining} sur ${hintsAllowed} indices restants`}
          >
            {hintsRemaining}/{hintsAllowed}
          </span>
        </button>
      </HintGate>
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
