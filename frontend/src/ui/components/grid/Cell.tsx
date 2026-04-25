import { memo, type FocusEvent, type KeyboardEvent, type PointerEvent, type Ref } from 'react';
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
// Single-clue layout: text fills the cell, arrow anchors at the corner.
const defText = css({ flex: 1, alignSelf: 'flex-start', paddingRight: 'xs', wordBreak: 'break-word' });
const defArrow = css({ position: 'absolute', bottom: '2px', right: '4px', fontSize: 'md', color: 'accent', lineHeight: 1 });

// Stacked layout: two clues share the cell vertically, each with its own
// arrow inline at the end of the text. The font shrinks one step so two
// 6-8 character French clues fit without overflow; the sand background
// against ink foreground keeps contrast at 13.6:1, well above WCAG AA at
// the smaller size (ADR-0005 §4 / §3a).
const defStack = css({
  display: 'flex',
  flexDirection: 'column',
  width: '100%',
  height: '100%',
  padding: '2px',
  gap: '1px',
  fontSize: '0.625rem',
  lineHeight: '1.05',
});
const defStackClue = css({
  display: 'flex',
  alignItems: 'flex-start',
  justifyContent: 'space-between',
  flex: 1,
  wordBreak: 'break-word',
  // Thin divider between the two stacked clues — inherited border color
  // gives the same hairline weight as the cell grid lines.
  '&:not(:first-child)': { borderTop: '1px solid token(colors.border)', paddingTop: '1px' },
});
// Highlighted stacked clue when the cursor is on its answer.
const defStackClueCurrent = css({ color: 'leaf.700' });
const defStackText = css({ flex: 1, paddingRight: '2px' });
const defStackArrow = css({ color: 'accent', fontSize: 'xs', lineHeight: 1, flexShrink: 0 });

const arrowGlyph: Record<ArrowDirection, string> = { right: '→', down: '↓' };
const arrowLabel: Record<ArrowDirection, string> = { right: 'horizontale', down: 'verticale' };

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

// Renders one clue in stacked layout: text + inline arrow indicator.
// `isCurrent` tints the clue text with `leaf.700` when its answer is the
// focused word — same anchor logic as single-clue cells, scoped per clue.
function StackedClue({ clue, isCurrent }: { clue: DefinitionClue; isCurrent: boolean }) {
  return (
    <div
      className={`${defStackClue}${isCurrent ? ` ${defStackClueCurrent}` : ''}`}
      role="group"
      aria-label={`définition ${arrowLabel[clue.arrow]}`}
      data-arrow={clue.arrow}
      data-current-clue={isCurrent ? 'true' : 'false'}
    >
      <span className={defStackText}>{clue.text}</span>
      <span className={defStackArrow} aria-hidden="true">
        {arrowGlyph[clue.arrow]}
      </span>
    </div>
  );
}

// Definition cell view. `currentArrow` is the arrow direction of the
// clue currently being solved when the focused cell sits on this def's
// answer path; `null` when no clue here is current. The single-clue and
// two-clue branches both apply the leaf.700 anchor border / tint — but
// stacked cells highlight only the matching sub-clue, since the two
// sides of a stacked cell may belong to different clue paths.
export const DefinitionCellView = memo(function DefinitionCellView({
  cell, currentArrow,
}: { cell: DefinitionCell; currentArrow: ArrowDirection | null }) {
  if (cell.clues.length === 1) {
    const clue = cell.clues[0];
    const isCurrent = currentArrow === clue.arrow;
    const currentClass = isCurrent
      ? clue.arrow === 'down' ? defCellCurrentDown : defCellCurrentRight
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
        <span className={defText}>{clue.text}</span>
        <span aria-label={`définition ${arrowLabel[clue.arrow]}`} className={defArrow}>
          {arrowGlyph[clue.arrow]}
        </span>
      </div>
    );
  }
  // Two-clue branch — horizontal clue (right arrow) on top, vertical
  // clue (down arrow) below. The wrapping `role="group"` lets screen
  // readers announce "deux définitions" before walking each clue.
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
        <StackedClue clue={horizontal} isCurrent={currentArrow === 'right'} />
        <StackedClue clue={vertical} isCurrent={currentArrow === 'down'} />
      </div>
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
