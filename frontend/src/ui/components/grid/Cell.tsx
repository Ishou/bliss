import { memo, type FocusEvent, type FormEvent, type KeyboardEvent, type MouseEvent, type Ref } from 'react';
import { css } from 'styled-system/css';
import type {
  ArrowDirection,
  BlockCell,
  DefinitionCell,
  DefinitionClue,
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
  // Required so border arrows (position:absolute, translate outside) are not clipped.
  overflow: 'visible',
});
const letterCell = css({ bg: 'surface' });
const letterCellInWord = css({ bg: 'leaf.50' });
const blockCell = css({ bg: 'block' });

// Definition cell: container-type so child cqi values resolve against cell width.
// zIndex:1 ensures the absolutely-positioned border arrows render above adjacent cells.
const defCell = css({
  bg: 'definition',
  color: 'fg',
  containerType: 'inline-size',
  lineHeight: '1.1',
  padding: '3px',
  textAlign: 'left',
  zIndex: 1,
});

// Text fills the full cell now that the arrow is outside the flow.
const defSingle = css({
  display: 'flex',
  flexDirection: 'column',
  width: '100%',
  height: '100%',
});

// Current-clue highlight: colored border on the side opposite the arrow so it
// doesn't compete with the arrow badge. leaf.700-on-sand ≈ 5.4:1 (WCAG AA).
const defCellCurrentRight = css({ borderLeft: '3px solid token(colors.leaf.700)', color: 'leaf.700' });
const defCellCurrentDown = css({ borderTop: '3px solid token(colors.leaf.700)', color: 'leaf.700' });

// Single-clue text: 19cqi scales with cell width.
//   96px cell (5-col) → ~18px  ·  68px cell (7-col) → ~13px
const defText = css({
  flex: 1,
  alignSelf: 'stretch',
  fontSize: '19cqi',
  overflowWrap: 'break-word',
  wordBreak: 'normal',
});

// CSS clip-path triangles whose BASE sits exactly on the cell border, tip
// pointing into the adjacent answer cell (classic mots fléchés convention).
// translate(100%/-50%) for right arrow: right:0 puts the right edge of the
// box on the border, then translateX(100%) shifts it so the LEFT edge (base
// of the triangle) lands on the border line. Mirror logic for the down arrow.
// pointerEvents:none so clicks reach the adjacent letter cell.
// zIndex:2 renders above the defCell's z-index:1 stacking context.
const arrowShared = {
  position: 'absolute' as const,
  width: '10cqi' as const,
  height: '10cqi' as const,
  bg: 'leaf.700' as const,
  pointerEvents: 'none' as const,
  zIndex: 2,
};
// Single-clue: base on the full right edge.
const defArrowRight = css({
  ...arrowShared,
  right: 0,
  top: '50%',
  transform: 'translate(100%, -50%)',
  clipPath: 'polygon(0 0, 100% 50%, 0 100%)',
});
// Stacked: base on the TOP HALF of the right edge.
const defArrowRightStack = css({
  ...arrowShared,
  right: 0,
  top: '25%',
  transform: 'translate(100%, -50%)',
  clipPath: 'polygon(0 0, 100% 50%, 0 100%)',
});
const defArrowDown = css({
  ...arrowShared,
  bottom: 0,
  left: '50%',
  transform: 'translate(-50%, 100%)',
  clipPath: 'polygon(0 0, 100% 0, 50% 100%)',
});

// Stacked layout: two clues share the cell vertically.
// Arrows are outside the flow (border-positioned), so text gets the full area.
const defStack = css({
  display: 'flex',
  flexDirection: 'column',
  width: '100%',
  height: '100%',
  gap: '1px',
  lineHeight: '1.1',
});
const defStackClue = css({
  display: 'flex',
  alignItems: 'flex-start',
  flex: 1,
  wordBreak: 'break-word',
  '&:not(:first-child)': { borderTop: '1px solid token(colors.border)', paddingTop: '1px' },
});
const defStackClueCurrent = css({ color: 'leaf.700' });
const defStackText = css({
  flex: 1,
  fontSize: '13cqi',
  overflowWrap: 'break-word',
  wordBreak: 'normal',
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
  fontFamily: 'body',
  fontWeight: 'bold',
  fontSize: 'cell',
  caretColor: 'accent',
  padding: 0,
  _focus: { bg: 'leaf.500', color: 'ink' },
});

const arrowLabel: Record<ArrowDirection, string> = { right: 'horizontale', down: 'verticale', 'down-right': 'horizontale', 'right-down': 'verticale' };

export const LetterCellView = memo(function LetterCellView({
  cell, ariaLabel, inWord, inputRef, onClick, onKeyDown, onFocus, onInput,
}: {
  cell: LetterCell;
  ariaLabel: string;
  inWord: boolean;
  inputRef: Ref<HTMLInputElement>;
  // onClick, not pointerdown — browser suppresses `click` after a pan, giving free tap-vs-scroll detection.
  onClick: (e: MouseEvent<HTMLInputElement>) => void;
  onKeyDown: (e: KeyboardEvent<HTMLInputElement>) => void;
  onFocus: (e: FocusEvent<HTMLInputElement>) => void;
  // onInput covers Android soft keyboards, which emit key==="Unidentified" on keydown.
  onInput: (e: FormEvent<HTMLInputElement>) => void;
}) {
  return (
    <div
      role="gridcell"
      className={`${cellBase} ${inWord ? letterCellInWord : letterCell}`}
      data-in-word={inWord ? 'true' : 'false'}
    >
      {/*
        No `maxLength={1}`: when the cell is already full, mobile soft keyboards
        (Android Gboard, iOS) block the insertion at the browser layer and never
        fire `InputEvent`, so `handleInput` can't replace the letter. Truncation
        is enforced in `handleInput` instead, which sets `target.value` to the
        single new character.
      */}
      <input
        ref={inputRef}
        type="text"
        inputMode="text"
        autoComplete="off"
        autoCapitalize="characters"
        spellCheck={false}
        aria-label={ariaLabel}
        defaultValue={cell.entry}
        className={letterInput}
        data-row={cell.position.row}
        data-col={cell.position.col}
        data-cell-kind="letter"
        onClick={onClick}
        onKeyDown={onKeyDown}
        onFocus={onFocus}
        onInput={onInput}
      />
    </div>
  );
});

// Stacked clue: text only — the arrow badge is rendered at the cell level.
function StackedClue({ clue, isCurrent }: { clue: DefinitionClue; isCurrent: boolean }) {
  return (
    <div
      className={`${defStackClue}${isCurrent ? ` ${defStackClueCurrent}` : ''}`}
      role="group"
      aria-label={`définition ${arrowLabel[clue.arrow]}`}
      data-arrow={clue.arrow}
      data-current-clue={isCurrent ? 'true' : 'false'}
    >
      <span className={defStackText} title={clue.text}>
        {clue.text}
      </span>
    </div>
  );
}

export const DefinitionCellView = memo(function DefinitionCellView({
  cell, currentArrow,
}: { cell: DefinitionCell; currentArrow: ArrowDirection | null }) {
  if (cell.clues.length === 1) {
    const clue = cell.clues[0];
    const isCurrent = currentArrow === clue.arrow;
    const isVertical = clue.arrow === 'down' || clue.arrow === 'right-down';
    const currentClass = isCurrent
      ? isVertical ? defCellCurrentDown : defCellCurrentRight
      : '';
    return (
      <div
        role="gridcell"
        className={`${cellBase} ${defCell}${currentClass ? ` ${currentClass}` : ''}`}
        data-row={cell.position.row}
        data-col={cell.position.col}
        data-cell-kind="definition"
        data-clue-count="1"
        data-current-clue={isCurrent ? 'true' : 'false'}
      >
        <div className={defSingle}>
          <span className={defText} title={clue.text}>
            {clue.text}
          </span>
        </div>
        <span
          role="img"
          className={isVertical ? defArrowDown : defArrowRight}
          aria-label={`définition ${arrowLabel[clue.arrow]}`}
        />
      </div>
    );
  }

  // Two-clue branch — horizontal clue (right arrow) on top, vertical below.
  // Both arrows are placed at their respective cell borders.
  const [horizontal, vertical] = cell.clues;
  return (
    <div
      role="gridcell"
      className={`${cellBase} ${defCell}`}
      data-row={cell.position.row}
      data-col={cell.position.col}
      data-cell-kind="definition"
      data-clue-count="2"
      data-current-clue={currentArrow !== null ? 'true' : 'false'}
    >
      <div className={defStack} role="group" aria-label="deux définitions">
        <StackedClue clue={horizontal} isCurrent={currentArrow === horizontal.arrow} />
        <StackedClue clue={vertical} isCurrent={currentArrow === vertical.arrow} />
      </div>
      <span aria-hidden="true" className={defArrowRightStack} />
      <span aria-hidden="true" className={defArrowDown} />
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
