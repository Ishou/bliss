import { memo, type FocusEvent, type KeyboardEvent, type PointerEvent, type Ref } from 'react';
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
  fontFamily: 'body',
});
const letterCell = css({ bg: 'surface' });
// Current-word tint. ink-on-leaf.50 is 12.6:1 (passes AA at body
// sizes); leaf.500 is reserved for the focused cell only.
const letterCellInWord = css({ bg: 'leaf.50' });
const blockCell = css({ bg: 'block' });
const defCell = css({
  bg: 'definition',
  color: 'fg',
  fontSize: 'xs',
  lineHeight: '1.1',
  padding: 'xs',
  textAlign: 'left',
  overflow: 'hidden',
});
// Current-clue definition: leaf.700 anchor border + leaf.700 text on the
// sand bg. Border + color = two cues per WCAG. leaf.700-on-sand ≈ 5.4:1.
// The border sits on the side opposite the arrow (top for `down`, left
// for `right`), anchoring the definition where the answer is *not* —
// the arrow itself shows the direction the answer flows; the border
// shows where the clue starts. Style applied per arrow direction.
const defCellCurrentRight = css({ borderLeft: '3px solid token(colors.leaf.700)', color: 'leaf.700' });
const defCellCurrentDown = css({ borderTop: '3px solid token(colors.leaf.700)', color: 'leaf.700' });
// Letter input: cream/breath surface with ink foreground in the resting
// state, leaf-on-ink on focus. Per ADR-0005 §4, the focused background
// is `leaf` and the foreground is `ink` (never white) — the only
// WCAG-AA-compliant pairing for brand-color backgrounds.
const letterInput = css({
  width: '100%',
  height: '100%',
  border: 'none',
  outline: 'none',
  bg: 'transparent',
  color: 'fg',
  textAlign: 'center',
  textTransform: 'uppercase',
  fontFamily: 'body',
  fontWeight: 'bold',
  fontSize: 'cell',
  caretColor: 'accent',
  padding: 0,
  _focus: { bg: 'leaf.500', color: 'ink' },
});
const defText = css({ flex: 1, alignSelf: 'flex-start', paddingRight: 'xs', wordBreak: 'break-word' });
const defArrow = css({ position: 'absolute', bottom: '2px', right: '4px', fontSize: 'md', color: 'accent', lineHeight: 1 });

const arrowGlyph: Record<ArrowDirection, string> = { right: '→', down: '↓' };
const arrowLabel: Record<ArrowDirection, string> = { right: 'horizontal', down: 'vertical' };

// Letter cell. `memo` prevents re-renders when other cells change
// (ADR-0002 §4). The input is uncontrolled; keyboard/focus/highlight
// wiring lives in `useGridNavigation`.
export const LetterCellView = memo(function LetterCellView({
  cell, ariaLabel, inWord, inputRef, onPointerDown, onKeyDown, onFocus,
}: {
  cell: LetterCell;
  ariaLabel: string;
  inWord: boolean;
  inputRef: Ref<HTMLInputElement>;
  onPointerDown: (e: PointerEvent<HTMLInputElement>) => void;
  onKeyDown: (e: KeyboardEvent<HTMLInputElement>) => void;
  onFocus: (e: FocusEvent<HTMLInputElement>) => void;
}) {
  return (
    <div
      role="gridcell"
      className={`${cellBase} ${inWord ? letterCellInWord : letterCell}`}
      data-in-word={inWord ? 'true' : 'false'}
    >
      <input
        ref={inputRef}
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
        onPointerDown={onPointerDown}
        onKeyDown={onKeyDown}
        onFocus={onFocus}
      />
    </div>
  );
});

export const DefinitionCellView = memo(function DefinitionCellView({
  cell, isCurrent,
}: { cell: DefinitionCell; isCurrent: boolean }) {
  const currentClass = isCurrent
    ? cell.arrow === 'down' ? defCellCurrentDown : defCellCurrentRight
    : '';
  return (
    <div
      role="gridcell"
      className={`${cellBase} ${defCell}${currentClass ? ` ${currentClass}` : ''}`}
      data-row={cell.position.row}
      data-col={cell.position.col}
      data-cell-kind="definition"
      data-current-clue={isCurrent ? 'true' : 'false'}
    >
      <span className={defText}>{cell.text}</span>
      <span aria-label={`définition ${arrowLabel[cell.arrow]}`} className={defArrow}>
        {arrowGlyph[cell.arrow]}
      </span>
    </div>
  );
});

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
