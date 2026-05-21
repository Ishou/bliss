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

export const letterSlot = css({
  display: 'inline-block',
  minWidth: '8px',
  textAlign: 'center',
});

// Slightly dimmer dot — empty slots read as placeholders, not letters.
export const letterDot = css({ color: 'neutral.400' });

// Focused slot is underlined in rose to mirror the grid's active-cell ring.
export const letterFocused = css({
  borderBottom: '2px solid token(colors.secondary.400)',
  paddingBottom: '1px',
  color: 'fg',
});

export function LetterPreview({
  cells,
  focusedPosition,
  getEntryAt,
  muted,
}: {
  cells: readonly LetterCell[];
  focusedPosition: { row: number; col: number } | null;
  getEntryAt: (row: number, col: number) => string;
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
        return (
          <span
            key={`${c.position.row},${c.position.col}`}
            className={
              isFocused
                ? `${letterSlot} ${letterFocused}`
                : filled
                ? letterSlot
                : `${letterSlot} ${letterDot}`
            }
          >
            {filled ? entry.toUpperCase() : '·'}
          </span>
        );
      })}
    </span>
  );
}
