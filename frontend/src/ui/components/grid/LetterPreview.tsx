import { css } from 'styled-system/css';
import type { LetterCell } from '@/domain';

// Letter preview row — one slot per cell. Filled letters paint in fg; empty slots show a centred dot.
export const letterRow = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '6px',
  fontFamily: 'body',
  fontSize: '14px',
  fontWeight: 'medium',
  fontVariantNumeric: 'tabular-nums',
  color: 'fg',
  flexShrink: 0,
});

export const letterRowMuted = css({ color: 'fgMuted' });

// Every slot reserves underline space so focusing one slot never grows its height.
export const letterSlot = css({
  display: 'inline-block',
  minWidth: '8px',
  textAlign: 'center',
  borderBottom: '2px solid transparent',
  paddingBottom: '1px',
});

// Slightly dimmer dot — empty slots read as placeholders, not letters.
export const letterDot = css({ color: 'neutral.400' });

// Focused slot only overrides the border colour; geometry is reserved on every slot.
export const letterFocused = css({
  borderBottomColor: 'secondary.400',
  color: 'fg',
});

// Validated letter — green glyph mirrors the locked-in grid cell.
export const letterValidated = css({ color: 'success' });

export function LetterPreview({
  cells,
  focusedPosition,
  getEntryAt,
  isCellValidated,
  muted,
}: {
  cells: readonly LetterCell[];
  focusedPosition: { row: number; col: number } | null;
  getEntryAt: (row: number, col: number) => string;
  isCellValidated?: (row: number, col: number) => boolean;
  muted?: boolean;
}) {
  return (
    <span className={muted ? `${letterRow} ${letterRowMuted}` : letterRow} aria-hidden>
      {cells.map((c) => {
        const entry = getEntryAt(c.position.row, c.position.col);
        const isFocused =
          focusedPosition !== null &&
          c.position.row === focusedPosition.row &&
          c.position.col === focusedPosition.col;
        const filled = entry !== '';
        const validated = filled && (isCellValidated?.(c.position.row, c.position.col) ?? false);
        const classes = [letterSlot];
        if (isFocused) classes.push(letterFocused);
        if (validated) classes.push(letterValidated);
        else if (!filled && !isFocused) classes.push(letterDot);
        return (
          <span key={`${c.position.row},${c.position.col}`} className={classes.join(' ')}>
            {filled ? entry.toUpperCase() : '·'}
          </span>
        );
      })}
    </span>
  );
}
