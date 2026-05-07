import { memo, useRef, type FocusEvent, type FormEvent, type KeyboardEvent, type MouseEvent, type Ref } from 'react';
import { css } from 'styled-system/css';
import type {
  ArrowDirection,
  BlockCell,
  DefinitionCell,
  DefinitionClue,
  LetterCell,
} from '@/domain';
import { FitText } from './FitText';

// Layout preprocessing for FitText. Two distinct paths:
//
//  - Multi-word clues (≥ 2 alphabetic tokens of length ≥ 2): insert
//    ONE newline at the most-balanced split. Rendered via the
//    containers' `whiteSpace: 'pre-line'`, so FitText reasons about a
//    2-line layout from the start and picks a font sized by the
//    longer of the two lines.
//
//  - Otherwise (single tokens, arithmetic-style "D - C", "C + C"):
//    replace every regular space with ` ` (non-breaking space).
//    This stops CSS auto-wrap from splitting "D - C" into a 2-line
//    layout, which FitText would otherwise prefer (2 short lines fit
//    a much bigger font than 1 unwrapped line). Result: clue stays on
//    one line and the font is bound by width — smaller, proportional.
//
// Examples:
//   "Gaz noble"           → "Gaz\nnoble"           (2 lines)
//   "Vitesses du rythme"  → "Vitesses\ndu rythme"  (2 lines, balanced)
//   "Carnets de notes quotidiennes" → "Carnets de notes\nquotidiennes"
//   "D - C", "C + C"      → "D - C"      (1 line, no wrap)
//   "à l'œil"             → "à l'œil"          (1 line, only one real word)
const REAL_WORD = /[A-Za-zÀ-ÿ]/;
function smartLineBreak(text: string): string {
  const realWords = text
    .split(/\s+/)
    .filter((t) => t.length >= 2 && REAL_WORD.test(t));
  if (realWords.length < 2) {
    return text.replace(/ /g, ' ');
  }
  const spaces: number[] = [];
  for (let i = 0; i < text.length; i++) {
    if (text[i] === ' ') spaces.push(i);
  }
  let bestIdx = -1;
  let bestMax = Infinity;
  for (const i of spaces) {
    const leftLen = i;
    const rightLen = text.length - i - 1;
    const longer = Math.max(leftLen, rightLen);
    if (longer < bestMax) {
      bestMax = longer;
      bestIdx = i;
    }
  }
  if (bestIdx < 0) return text;
  return text.slice(0, bestIdx) + '\n' + text.slice(bestIdx + 1);
}

// Clue text size bounds, expressed as a fraction of the cell's
// `clientWidth`. FitText scales font linearly with cell width, so a
// clue that fits at one cell size fits at every cell size
// (zoom-invariance — validated against `scripts/eval/clue_metrics.py`).
//
// MIN ratios (0.18) are the Phase-1 search floor. The offline gate uses
// a lower floor (`GATE_RATIO_FLOOR = 0.14` in clue_metrics.py), so
// clues needing ratio 0.14–0.17 pass the gate and fall through to
// Phase-2 bisection at runtime — they do not fit Phase 1. The prior
// absolute pixel floor (`ABSOLUTE_MIN_PX = 11`) broke zoom-invariance
// below ~55 px cells — font stayed pinned at 11 px while the cell kept
// shrinking, so the effective ratio grew and clue text overflowed
// small mobile cells. Removing it restores the contract: identical
// visual layout at any screen size.
//
// MAX ratios are the visual ceiling for short clues. SINGLE 0.32 and
// STACK 0.28 keep "déco"-class one-word clues from ballooning past
// their neighbours; STACK is one notch lower because stacked half-
// cells have ~½ the vertical room and a 0.32-of-width font would
// crowd the top/bottom edges.
//
// Visual delta inside a single grid widens to 0.32 / 0.18 ≈ 1.78×
// (vs PR-#195's tighter 0.22–0.32 band). Trade accepted in exchange
// for full zoom-invariance.
const SINGLE_RATIO_MIN = 0.18;
const SINGLE_RATIO_MAX = 0.32;
const STACK_RATIO_MIN = 0.18;
const STACK_RATIO_MAX = 0.28;

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
  lineHeight: '1.05',
  padding: '3px',
  textAlign: 'center',
  zIndex: 1,
});

// Text fills the full cell now that the arrow is outside the flow.
// `overflow: hidden` clips clue text that won't fit even at FitText's floor —
// without this, the cellBase `overflow: visible` (needed for arrow badges)
// lets long clues bleed into adjacent cells.
const defSingle = css({
  display: 'flex',
  flexDirection: 'column',
  width: '100%',
  height: '100%',
  overflow: 'hidden',
});

// Current-clue highlight: colored border on the side opposite the arrow so it
// doesn't compete with the arrow badge. leaf.700-on-sand ≈ 5.4:1 (WCAG AA).
const defCellCurrentRight = css({ borderLeft: '3px solid token(colors.leaf.700)', color: 'leaf.700' });
const defCellCurrentDown = css({ borderTop: '3px solid token(colors.leaf.700)', color: 'leaf.700' });

// Single-clue text: font size is auto-fit at runtime (FitText) — the inline
// font-size set by FitText overrides any value here. We still need flex:1 +
// alignSelf so the span fills the cell (FitText measures clientWidth/
// clientHeight on this very element).
//
// `hyphens: auto` enables CLDR-pattern French syllabic hyphenation
// ("pré-sen-ter", "syl-la-bique"), inherited from the `lang="fr"` set
// on `<html>` and on the grid root. With hyphenation the fits-test
// passes at larger fontSize than it would otherwise (a word that
// wouldn't fit on a line gets a syllabic break instead of being
// rejected), so the algorithm trades fewer hyphens for bigger text
// only when no hyphenated layout fits at the current size. Preferred
// over the older "shrink to fit" path that produced microscopic text;
// preferred over `overflowWrap: break-word` which breaks at arbitrary
// character boundaries (no awareness of syllables).
//
// `overflowWrap: break-word` is kept as a last-ditch fallback for
// content that has no hyphenation points (e.g. unusual proper nouns
// or all-caps acronyms). `overflow: hidden` clips honestly when even
// hyphenation can't make it fit at ABSOLUTE_MIN_PX.
const defText = css({
  flex: 1,
  alignSelf: 'stretch',
  display: 'flex',
  // `safe center` keeps centered text aligned to the start of the
  // axis when content is taller/wider than the container, instead of
  // the default `center` which symmetrically overflows past both edges
  // of the box and leaks visually past `overflow: hidden` ancestors
  // (defStack contains both halves, so a top-stacked clue's symmetric
  // bottom-leak would paint into the bottom-stack's space). With
  // `safe`: short text stays centered; tall text top-aligns and clips
  // honestly at the bottom. Browser support: Chrome 87+, Firefox 63+,
  // Safari 16+ — covered by the project's modern-browsers stance.
  alignItems: 'safe center',
  justifyContent: 'safe center',
  textAlign: 'center',
  // `fontFamily: 'mono'` (Lekton) is the load-bearing piece of the
  // gate-decoupling refactor: Lekton's constant glyph advance lets
  // `scripts/eval/clue_metrics.py` be a deterministic predicate on
  // `len(clue)` rather than mirroring the browser's PIL/Nunito layout.
  // See ADR-0005 §5 amendment. Bold (700) is the only Lekton weight
  // loaded — clues read more distinctly bold at the small fontSizes
  // dense grids force.
  fontFamily: 'mono',
  fontWeight: 700,
  hyphens: 'auto',
  overflowWrap: 'break-word',
  wordBreak: 'normal',
  overflow: 'hidden',
  whiteSpace: 'pre-line',
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
// `overflow: hidden` clips clue text past FitText's floor — see defSingle.
const defStack = css({
  display: 'flex',
  flexDirection: 'column',
  width: '100%',
  height: '100%',
  gap: '1px',
  lineHeight: '1.05',
  overflow: 'hidden',
});
const defStackClue = css({
  display: 'flex',
  // Default `alignItems: stretch` (cross-axis) on purpose — the
  // FitText span inside (defStackText) needs to fill the half-cell
  // vertically so its OWN `overflow: hidden` clips text taller than
  // the half. If we centered the span here, it would shrink to
  // content size and a multi-line clue's overflow would paint into
  // the sibling half-cell's slot via defStack's shared overflow box.
  // Inner text centering (safe) lives on defStackText.
  flex: 1,
  minHeight: 0,
  overflow: 'hidden',
  wordBreak: 'break-word',
  // Separator between the two stacked clues. `colors.border` is `sand`, the
  // same hue as the def cell bg, so a token-based rule was invisible — use
  // ink at low alpha for a subtle but legible divider. No top padding —
  // both halves share identical content boxes (1px border + flex
  // alignItems:center is enough vertical separation), so neither clue
  // looks shifted relative to the other.
  '&:not(:first-child)': { borderTop: '1px solid rgba(27, 40, 69, 0.25)' },
});
const defStackClueCurrent = css({ color: 'leaf.700' });
// Stacked-clue text: same wrap policy as defText. `hyphens: auto`
// (lang="fr" inherited) is even more important on stacked half-cells
// because they're vertically tight — without syllabic breaks, long
// words like "quotidiennes" or "présentation" force the algorithm to
// drop near ABSOLUTE_MIN_PX even when there's plenty of horizontal
// room. `whiteSpace: 'pre-line'` honours the explicit `\n` inserted
// by `smartLineBreak` (one balanced split for multi-word clues), so
// multi-word clues use the vertical space FitText would otherwise
// leave empty. Line-height inherits the cell's 1.1 for legibility.
const defStackText = css({
  flex: 1,
  display: 'flex',
  // `safe center` — see defText for rationale.
  alignItems: 'safe center',
  justifyContent: 'safe center',
  // Flex items default to `min-height: auto` (i.e. content-sized),
  // which lets a multi-line span stretch *beyond* its flex parent's
  // allotted height — defeating the half-cell's overflow:hidden by
  // making the span itself the leaking element. `minHeight: 0` opts
  // out so the span shrinks to its parent's height regardless of
  // content; overflow:hidden then clips the excess inside the span,
  // invisibly. Same trick lives on defStackClue's `minHeight: 0`.
  minHeight: 0,
  minWidth: 0,
  textAlign: 'center',
  // Same monospace + bold rationale as `defText` — see comment there
  // and ADR-0005 §5 amendment.
  fontFamily: 'mono',
  fontWeight: 700,
  hyphens: 'auto',
  overflowWrap: 'break-word',
  wordBreak: 'normal',
  overflow: 'hidden',
  whiteSpace: 'pre-line',
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
  // type="search" exposes a webkit clear-X button on focus + a search-results
  // decoration affordance. Hide both — the cell is a single-character input,
  // not a real search box. (See `<input type="search">` below for why we use
  // search instead of text on Android.)
  '&::-webkit-search-cancel-button': { display: 'none' },
  '&::-webkit-search-decoration': { display: 'none' },
  '&::-webkit-search-results-button': { display: 'none' },
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
  // mousedown.preventDefault stops the browser's default blur of the
  // currently-focused input when the user presses on a non-focusable
  // wrapper. We deliberately do NOT call focus() here: focusing on
  // mousedown would briefly highlight the drag-from cell's word
  // before the pan-end revert undoes it (the user-visible "wrong
  // word focus during pan" flicker). Focus moves on click instead —
  // the browser only synthesises click for click-without-drag, so
  // click is the right "no pan happened" signal.
  const handleMouseDown = (e: MouseEvent<HTMLDivElement>) => {
    e.preventDefault();
  };
  // onClick wires through to useGridNavigation.handleClick which
  // applies the isPanning gate AND does the focus call. Cell.tsx no
  // longer calls focus directly — the gate must wrap both setDirection
  // and the focus side-effect, otherwise a tail-of-pan synthesised
  // click would still focus the wrong cell.
  const handleClick = (e: MouseEvent<HTMLDivElement>) => {
    onClick(e);
  };

  return (
    <div
      role="gridcell"
      className={`${cellBase} ${inWord ? letterCellInWord : letterCell}`}
      data-in-word={inWord ? 'true' : 'false'}
      data-row={cell.position.row}
      data-col={cell.position.col}
      onMouseDown={handleMouseDown}
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
        // type="search" instead of "text" so Android Autofill (and Samsung
        // Browser's equivalent) does NOT fingerprint the field as a form
        // input — that's the toolbar with password / credit-card / GPS-pin
        // icons reported in the field. Search inputs are exempt from the
        // autofill heuristics. Identical keyboard behaviour to type="text"
        // otherwise; the webkit clear-X / decoration affordances are hidden
        // via the `letterInput` style above.
        type="search"
        // type="search" has implicit ARIA role "searchbox"; crossword cells are
        // not search boxes. Override so AT announces "text field" as before.
        role="textbox"
        inputMode="text"
        autoComplete="off"
        autoCapitalize="characters"
        autoCorrect="off"
        spellCheck={false}
        enterKeyHint="next"
        // Password-manager + autofill ignore hints. The crossword cells
        // are not credentials / addresses / cards / phone-numbers, but
        // mobile browsers + extensions surface password-key, credit-card,
        // GPS-pin, and contact icons in their suggestion bar / overlay
        // because they fingerprint the surrounding form context. These
        // attributes ask each major manager to ignore the field.
        // - 1Password: data-1p-ignore
        // - LastPass:  data-lpignore
        // - Bitwarden / Dashlane: data-form-type="other"
        data-1p-ignore=""
        data-lpignore="true"
        data-form-type="other"
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
      <FitText
        text={smartLineBreak(clue.text)}
        min={STACK_RATIO_MIN}
        max={STACK_RATIO_MAX}
        unit="ratio"
        className={defStackText}
        title={clue.text}
      />
    </div>
  );
}

// Picks the arrow slots for a two-clue cell, given the arrows in *visual*
// stack order (top first, bottom second). Each arrow is placed at the
// position on its own border that sits next to its own text:
//   * right-origin + top text  → rightTop
//   * right-origin + bottom text → rightBottom
//   * bottom-origin → bottomCenter (one position per edge in mixed cases)
// Same-border pairs split that border (both rightTop/rightBottom or
// bottomLeft/bottomRight) so the two arrows don't overlap.
function twoClueSlots(
  top: ArrowDirection,
  bottom: ArrowDirection,
): { slotA: ArrowSlot; slotB: ArrowSlot } {
  const ot = arrowOriginOf(top);
  const ob = arrowOriginOf(bottom);
  if (ot === 'right' && ob === 'right') return { slotA: 'rightTop', slotB: 'rightBottom' };
  if (ot === 'bottom' && ob === 'bottom') return { slotA: 'bottomLeft', slotB: 'bottomRight' };
  return {
    slotA: ot === 'right' ? 'rightTop' : 'bottomCenter',
    slotB: ob === 'right' ? 'rightBottom' : 'bottomCenter',
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
          <FitText
            text={smartLineBreak(clue.text)}
            min={SINGLE_RATIO_MIN}
            max={SINGLE_RATIO_MAX}
            unit="ratio"
            className={defText}
            title={clue.text}
          />
        </div>
        <ArrowMark
          arrow={clue.arrow}
          slot={origin === 'right' ? 'rightCenter' : 'bottomCenter'}
          ariaLabel={`définition ${arrowLabel[clue.arrow]}`}
        />
      </div>
    );
  }

  // Two-clue branch. The visual stack pairs each clue with its arrow:
  //   * mixed-origin pairs → right-origin clue on top (its arrow lives
  //     at the right edge, naturally near top text), bottom-origin
  //     clue on bottom (its arrow lives at the bottom edge, naturally
  //     near bottom text). Without this the bent-arrow combos
  //     (e.g. clues[0]=down-right + clues[1]=right-down) end up with
  //     BOTH arrows in the bottom half of the cell.
  //   * same-origin pairs → keep API order; their arrows split the
  //     shared edge (rightTop/rightBottom or bottomLeft/bottomRight).
  // Domain order is untouched (ADR-0005 §3a) — this is purely visual.
  const [domFirst, domSecond] = cell.clues;
  const o1 = arrowOriginOf(domFirst.arrow);
  const o2 = arrowOriginOf(domSecond.arrow);
  const sameOrigin = o1 === o2;
  const topClue = sameOrigin || o1 === 'right' ? domFirst : domSecond;
  const bottomClue = sameOrigin || o1 === 'right' ? domSecond : domFirst;
  const { slotA, slotB } = twoClueSlots(topClue.arrow, bottomClue.arrow);
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
        <StackedClue clue={topClue} isCurrent={currentArrow === topClue.arrow} />
        <StackedClue clue={bottomClue} isCurrent={currentArrow === bottomClue.arrow} />
      </div>
      <ArrowMark arrow={topClue.arrow} slot={slotA} ariaHidden />
      <ArrowMark arrow={bottomClue.arrow} slot={slotB} ariaHidden />
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
