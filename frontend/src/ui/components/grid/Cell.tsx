import { memo, useRef, type FocusEvent, type FormEvent, type KeyboardEvent, type MouseEvent, type Ref } from 'react';
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

// Arrow shapes — straight triangles + bent L-shapes.
//
// A def cell's arrow originates at one of two borders:
//   * RIGHT border  — answer's first cell is the right neighbour.
//                     `right` (straight) and `right-down` (bent) are both
//                     right-origin: the arrow ENTERS the right neighbour.
//                     For `right-down` the path bends DOWN inside that
//                     neighbour (answer continues downward from there).
//   * BOTTOM border — answer's first cell is the bottom neighbour.
//                     `down` (straight) and `down-right` (bent) are both
//                     bottom-origin: arrow enters the bottom neighbour.
//                     For `down-right` the path bends RIGHT inside that
//                     neighbour (answer continues rightward).
//
// The earlier code keyed arrow placement on flow axis (`HorizontalArrow`
// vs `VerticalArrow`), which puts `right-down` on the bottom border and
// `down-right` on the right border — wrong: those bent variants share
// the *origin* of `right` / `down` respectively, not the flow.
//
// `arrowOriginOf` is the single source of truth used by every arrow site
// (single-clue, stacked-clue, current-clue highlight stripe).
type ArrowOrigin = 'right' | 'bottom';
const arrowOriginOf = (a: ArrowDirection): ArrowOrigin =>
  a === 'right' || a === 'right-down' ? 'right' : 'bottom';

// Slot = which physical position on a def cell's border an arrow occupies.
// Single-clue cells use `*Center`. Two-clue cells use `*Top`/`*Bottom`
// (right border) or `*Left`/`*Right` (bottom border) when both clues
// share an origin, or `*Top` (right) + `*Center` (bottom) for mixed
// origins (matches the existing visual: horizontal-flow text on top
// with right-arrow at top half; vertical-flow text on bottom with
// down-arrow centered).
type ArrowSlot =
  | 'rightCenter' | 'rightTop' | 'rightBottom'
  | 'bottomCenter' | 'bottomLeft' | 'bottomRight';

const SLOT_POSITION: Record<ArrowSlot, React.CSSProperties> = {
  rightCenter:  { right: 0, top: '50%', transform: 'translate(100%, -50%)' },
  rightTop:     { right: 0, top: '25%', transform: 'translate(100%, -50%)' },
  rightBottom:  { right: 0, top: '75%', transform: 'translate(100%, -50%)' },
  bottomCenter: { bottom: 0, left: '50%', transform: 'translate(-50%, 100%)' },
  bottomLeft:   { bottom: 0, left: '25%', transform: 'translate(-50%, 100%)' },
  bottomRight:  { bottom: 0, left: '75%', transform: 'translate(-50%, 100%)' },
};

// Straight (10cqi × 10cqi) triangles whose BASE sits exactly on the cell
// border, tip pointing into the adjacent answer cell. pointer-events:none
// so clicks reach the adjacent letter cell. zIndex:2 renders above the
// defCell's z-index:1 stacking context.
const arrowStraightBase = css({
  position: 'absolute',
  width: '10cqi',
  height: '10cqi',
  bg: 'leaf.700',
  pointerEvents: 'none',
  zIndex: 2,
});
const triangleRightClip = css({ clipPath: 'polygon(0 0, 100% 50%, 0 100%)' });
const triangleDownClip = css({ clipPath: 'polygon(0 0, 100% 0, 50% 100%)' });

// Bent L-arrows are rendered as SVG paths inside a 30cqi × 30cqi box
// anchored at the same slot position (entry point at the box's left edge
// for right-origin, top edge for bottom-origin). The path traces the
// vertical stroke + horizontal stroke + triangle tip as a single polygon
// so the fill produces a single solid shape with no seams.
//
// The 30cqi size is enough to push the bend partway into the entry
// neighbour and keep the triangle tip clear of the def cell border.
// Smaller boxes hide the L; larger boxes intrude on the next neighbour.
const arrowBentBase = css({
  position: 'absolute',
  width: '30cqi',
  height: '30cqi',
  color: 'leaf.700',
  pointerEvents: 'none',
  zIndex: 2,
});
// `right-down`: stroke goes RIGHT from the box's left-center, then bends
// DOWN inside the entry neighbour. Triangle tip points DOWN.
// 100×100 viewBox; entry is at (0, 50) — the box's left-center.
const PATH_RIGHT_DOWN = 'M 0 42 L 65 42 L 65 75 L 75 75 L 58 95 L 40 75 L 50 75 L 50 58 L 0 58 Z';
// `down-right`: stroke goes DOWN from the box's top-center, then bends
// RIGHT inside the entry neighbour. Triangle tip points RIGHT.
// 100×100 viewBox; entry is at (50, 0) — the box's top-center.
const PATH_DOWN_RIGHT = 'M 42 0 L 58 0 L 58 50 L 75 50 L 75 40 L 95 58 L 75 75 L 75 65 L 42 65 Z';

// One-stop arrow renderer. Picks the right shape (straight triangle or
// bent SVG L) and the right border position (slot) for a given arrow
// direction. Single source of truth — both single-clue and two-clue
// branches go through it so the rendering rules stay consistent.
function ArrowMark({
  arrow, slot, ariaLabel, ariaHidden = false,
}: {
  arrow: ArrowDirection;
  slot: ArrowSlot;
  ariaLabel?: string;
  ariaHidden?: boolean;
}) {
  const positionStyle = SLOT_POSITION[slot];
  const isBent = arrow === 'right-down' || arrow === 'down-right';
  if (isBent) {
    return (
      <span
        className={arrowBentBase}
        style={positionStyle}
        role={ariaHidden ? undefined : 'img'}
        aria-hidden={ariaHidden || undefined}
        aria-label={ariaHidden ? undefined : ariaLabel}
        data-arrow={arrow}
      >
        <svg viewBox="0 0 100 100" preserveAspectRatio="none" width="100%" height="100%">
          <path
            d={arrow === 'right-down' ? PATH_RIGHT_DOWN : PATH_DOWN_RIGHT}
            fill="currentColor"
          />
        </svg>
      </span>
    );
  }
  const clipClass = arrow === 'right' ? triangleRightClip : triangleDownClip;
  return (
    <span
      className={`${arrowStraightBase} ${clipClass}`}
      style={positionStyle}
      role={ariaHidden ? undefined : 'img'}
      aria-hidden={ariaHidden || undefined}
      aria-label={ariaHidden ? undefined : ariaLabel}
      data-arrow={arrow}
    />
  );
}

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
  // The wrapping <div> is the touch target — see LetterCellView below for
  // why. The input itself is `pointer-events: none` so taps fall through
  // to the div, and the `caretColor: transparent` + `userSelect: none`
  // stay as belt-and-braces in case any browser still routes touch to a
  // focused input via legacy paths.
  pointerEvents: 'none',
  caretColor: 'transparent',
  userSelect: 'none',
  padding: 0,
  _focus: { bg: 'leaf.500', color: 'ink' },
});

const arrowLabel: Record<ArrowDirection, string> = { right: 'horizontale', down: 'verticale', 'down-right': 'horizontale', 'right-down': 'verticale' };

export const LetterCellView = memo(function LetterCellView({
  cell, ariaLabel, inWord, inputRef, onClick, onKeyDown, onFocus, onBlur, onInput,
}: {
  cell: LetterCell;
  ariaLabel: string;
  inWord: boolean;
  inputRef: Ref<HTMLInputElement>;
  onClick: (e: MouseEvent<HTMLDivElement>) => void;
  onKeyDown: (e: KeyboardEvent<HTMLInputElement>) => void;
  onFocus: (e: FocusEvent<HTMLInputElement>) => void;
  // onBlur clears the word-highlight when focus leaves the grid; see
  // `useGridNavigation.handleBlur` for the batching rationale.
  onBlur: (e: FocusEvent<HTMLInputElement>) => void;
  // onInput covers Android soft keyboards, which emit key==="Unidentified" on keydown.
  onInput: (e: FormEvent<HTMLInputElement>) => void;
}) {
  // We need a local handle to the <input> so the div's onClick can
  // programmatically focus it (the browser can't do click-to-focus
  // because the input is `pointer-events: none`). Forward the same
  // node to `inputRef` (a callback ref from useGridNavigation), so
  // the existing focus-by-position machinery keeps working.
  const localInputRef = useRef<HTMLInputElement | null>(null);
  const setInputRef = (el: HTMLInputElement | null) => {
    localInputRef.current = el;
    if (typeof inputRef === 'function') inputRef(el);
    else if (inputRef) (inputRef as React.MutableRefObject<HTMLInputElement | null>).current = el;
  };

  // Tap target = the <div>, not the <input>. Why:
  //   * On Android Chrome, a focused <input> draws a cursor handle (the
  //     teardrop) that drag-captures one-finger horizontal pans
  //     regardless of `touch-action`, `caret-color: transparent`, or
  //     `user-select: none`. Same with the iOS caret-drag magnifier.
  //   * Routing the touch through the `<div>` (which has none of those
  //     text-input gestures) lets the browser's native visual-viewport
  //     panning fire when the page is pinch-zoomed in.
  //   * We still need the <input> to receive keyboard events
  //     (`onKeyDown` / `onInput` / `onFocus`), so it stays in the DOM
  //     and gets focused programmatically here. The focus call lives
  //     inside the click handler (a user-gesture context), so Android
  //     and iOS still pop the soft keyboard.
  const handleClick = (e: MouseEvent<HTMLDivElement>) => {
    onClick(e);
    localInputRef.current?.focus();
  };

  return (
    <div
      role="gridcell"
      className={`${cellBase} ${inWord ? letterCellInWord : letterCell}`}
      data-in-word={inWord ? 'true' : 'false'}
      data-row={cell.position.row}
      data-col={cell.position.col}
      onClick={handleClick}
    >
      {/*
        No `maxLength={1}`: when the cell is already full, mobile soft keyboards
        (Android Gboard, iOS) block the insertion at the browser layer and never
        fire `InputEvent`, so `handleInput` can't replace the letter. Truncation
        is enforced in `handleInput` instead, which sets `target.value` to the
        single new character.
      */}
      <input
        ref={setInputRef}
        type="text"
        inputMode="text"
        autoComplete="off"
        autoCapitalize="characters"
        spellCheck={false}
        aria-label={ariaLabel}
        defaultValue={cell.entry}
        className={letterInput}
        // Belt-and-braces against any browser still routing touch to a
        // focused input via legacy paths (iOS caret-drag, etc).
        style={{ WebkitTouchCallout: 'none', WebkitUserSelect: 'none' }}
        data-row={cell.position.row}
        data-col={cell.position.col}
        data-cell-kind="letter"
        onKeyDown={onKeyDown}
        onFocus={onFocus}
        onBlur={onBlur}
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

// Picks the arrow slots for a two-clue cell. Same-origin pairs share a
// border and split slot1/slot2 along it; mixed-origin pairs each take
// their own border (right-origin → top-half of right; bottom-origin →
// centered on bottom — matches the existing mixed-axis visual).
//
// The clue order in `cell.clues` is taken as-given. The mapper at
// `infrastructure/api/grid/mapper.ts` already normalizes mixed-axis
// pairs to [horizontal-flow, vertical-flow] for the text stack; for
// same-origin pairs the API order is preserved (per the comment in
// `Cell.ts`: top-row inner skeleton cells produce RIGHT_DOWN + DOWN,
// left-col cells produce DOWN_RIGHT + RIGHT — the renderer must keep
// that order so each clue's arrow stays paired with its text).
function twoClueSlots(
  a: ArrowDirection,
  b: ArrowDirection,
): { slotA: ArrowSlot; slotB: ArrowSlot } {
  const oa = arrowOriginOf(a);
  const ob = arrowOriginOf(b);
  if (oa === ob) {
    return oa === 'right'
      ? { slotA: 'rightTop', slotB: 'rightBottom' }
      : { slotA: 'bottomLeft', slotB: 'bottomRight' };
  }
  // Mixed: each clue on its own border. Right-origin gets the top-half
  // slot (visual continuity with the existing layout where the
  // horizontal-flow clue sat at right-top); bottom-origin stays centered.
  return {
    slotA: oa === 'right' ? 'rightTop' : 'bottomCenter',
    slotB: ob === 'right' ? 'rightTop' : 'bottomCenter',
  };
}

export const DefinitionCellView = memo(function DefinitionCellView({
  cell, currentArrow,
}: { cell: DefinitionCell; currentArrow: ArrowDirection | null }) {
  if (cell.clues.length === 1) {
    const clue = cell.clues[0];
    const isCurrent = currentArrow === clue.arrow;
    // Highlight stripe on the side OPPOSITE the arrow so it doesn't
    // compete with the arrow badge. Origin border drives the choice
    // (matches `right-down` → right border → left-side stripe, and
    // `down-right` → bottom border → top stripe).
    const origin = arrowOriginOf(clue.arrow);
    const currentClass = isCurrent
      ? origin === 'right' ? defCellCurrentRight : defCellCurrentDown
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
        <ArrowMark
          arrow={clue.arrow}
          slot={origin === 'right' ? 'rightCenter' : 'bottomCenter'}
          ariaLabel={`définition ${arrowLabel[clue.arrow]}`}
        />
      </div>
    );
  }

  // Two-clue branch — text stack (top: clues[0], bottom: clues[1])
  // matches the API/mapper order. Arrow slots come from `twoClueSlots`,
  // which routes each clue to its origin border.
  const [first, second] = cell.clues;
  const { slotA, slotB } = twoClueSlots(first.arrow, second.arrow);
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
        <StackedClue clue={first} isCurrent={currentArrow === first.arrow} />
        <StackedClue clue={second} isCurrent={currentArrow === second.arrow} />
      </div>
      <ArrowMark arrow={first.arrow} slot={slotA} ariaHidden />
      <ArrowMark arrow={second.arrow} slot={slotB} ariaHidden />
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
