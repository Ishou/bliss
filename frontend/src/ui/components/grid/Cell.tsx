import { memo } from 'react';
import { css } from 'styled-system/css';
import type {
  ArrowDirection,
  BlockCell,
  DefinitionCell,
  LetterCell,
} from '@/domain';

const cellBase = css({
  position: 'relative',
  width: '100%',
  aspectRatio: '1 / 1',
  border: '1px solid token(colors.border)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontFamily: 'sans',
});
const letterCell = css({ bg: 'surface' });
const blockCell = css({ bg: 'block' });
const defCell = css({
  bg: 'definition',
  color: 'muted',
  fontSize: 'xs',
  lineHeight: '1.1',
  padding: 'xs',
  textAlign: 'left',
  overflow: 'hidden',
});
const letterInput = css({
  width: '100%',
  height: '100%',
  border: 'none',
  outline: 'none',
  bg: 'transparent',
  color: 'fg',
  textAlign: 'center',
  textTransform: 'uppercase',
  fontFamily: 'sans',
  fontWeight: 'bold',
  fontSize: 'lg',
  caretColor: 'accent',
  padding: 0,
  _focus: { bg: 'accent', color: 'bg' },
});
const defText = css({ flex: 1, alignSelf: 'flex-start', paddingRight: 'xs', wordBreak: 'break-word' });
const defArrow = css({ position: 'absolute', bottom: '2px', right: '4px', fontSize: 'md', color: 'accent', lineHeight: 1 });

const arrowGlyph: Record<ArrowDirection, string> = { right: '→', down: '↓' };
const arrowLabel: Record<ArrowDirection, string> = { right: 'horizontal', down: 'vertical' };

// Letter cell. `memo` prevents re-renders when other cells change (ADR-0002
// §4); the input is uncontrolled — the DOM owns the current value. Keyboard
// navigation lands in a follow-up workstream.
export const LetterCellView = memo(function LetterCellView({
  cell, ariaLabel,
}: { cell: LetterCell; ariaLabel: string }) {
  return (
    <div role="gridcell" className={`${cellBase} ${letterCell}`}>
      <input
        type="text"
        inputMode="text"
        autoComplete="off"
        autoCapitalize="characters"
        spellCheck={false}
        maxLength={1}
        aria-label={ariaLabel}
        defaultValue={cell.entry}
        className={letterInput}
        data-row={cell.position.row}
        data-col={cell.position.col}
        data-cell-kind="letter"
      />
    </div>
  );
});

export function DefinitionCellView({ cell }: { cell: DefinitionCell }) {
  return (
    <div
      role="gridcell"
      className={`${cellBase} ${defCell}`}
      data-row={cell.position.row}
      data-col={cell.position.col}
      data-cell-kind="definition"
    >
      <span className={defText}>{cell.text}</span>
      <span aria-label={`définition ${arrowLabel[cell.arrow]}`} className={defArrow}>
        {arrowGlyph[cell.arrow]}
      </span>
    </div>
  );
}

export function BlockCellView({ cell }: { cell: BlockCell }) {
  return (
    <div
      role="presentation"
      aria-hidden="true"
      className={`${cellBase} ${blockCell}`}
      data-row={cell.position.row}
      data-col={cell.position.col}
      data-cell-kind="block"
    />
  );
}
