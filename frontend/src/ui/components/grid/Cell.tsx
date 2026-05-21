import { memo, useRef, type FocusEvent, type FormEvent, type KeyboardEvent, type MouseEvent, type Ref } from 'react';
import { css } from 'styled-system/css';
import type {
  ArrowDirection,
  BlockCell,
  DefinitionCell,
  DefinitionClue,
  LetterCell,
} from '@/domain';
import { ARROW_COLOR } from './ClueArrowIcon';
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
//    replace every regular space with ` ` (non-breaking space).
//    This stops CSS auto-wrap from splitting "D - C" into a 2-line
//    layout, which FitText would otherwise prefer (2 short lines fit
//    a much bigger font than 1 unwrapped line). Result: clue stays on
//    one line and the font is bound by width — smaller, proportional.
//
// Examples:
//   "Gaz noble"           → "Gaz\nnoble"           (2 lines)
//   "Vitesses du rythme"  → "Vitesses\ndu rythme"  (2 lines, balanced)
//   "Carnets de notes quotidiennes" → "Carnets de notes\nquotidiennes"
//   "D - C", "C + C"      → "D - C"      (1 line, no wrap)
//   "à l'œil"             → "à l'œil"          (1 line, only one real word)
const REAL_WORD = /[A-Za-zÀ-ÿ]/;
function smartLineBreak(text: string): string {
  const realWords = text
    .split(/\s+/)
    .filter((t) => t.length >= 2 && REAL_WORD.test(t));
  if (realWords.length < 2) {
    return text.replace(/ /g, '\u00a0');
  }
  if (realWords.length > 2) {
    // 3+-word clues: leave wrapping to CSS. The previous "single
    // balanced \n" rule produced awkward 3-/4-line stairs ("Pronom
    // de la / 2e personne" \u2192 "Pronom de / la / 2e / personne") when
    // the chosen FitText fontSize couldn't fit the longer half on
    // one line; CSS then secondarily wrapped that half. Letting CSS
    // own the wrap means every break lands at a real word boundary.
    return text;
  }
  // Two-word case: split at the only meaningful space so FitText can
  // size the longer of the two halves.
  const firstSpace = text.indexOf(' ');
  if (firstSpace < 0) return text;
  return text.slice(0, firstSpace) + '\n' + text.slice(firstSpace + 1);
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
// Floor lowered with the ceiling so short clues don't all sit at the
// max — same visual rhythm but tighter overall.
const SINGLE_RATIO_MIN = 0.16;
// Lowered iteratively from the original 0.32 → 0.26 → 0.22 at the
// player's request: the prior ceilings let one-word clues like "déco"
// balloon past their neighbours; this cap keeps the grid's rhythm
// calm while still giving short clues a touch more weight than long
// ones. Visual delta inside a single grid is ~1.38× (0.22 / 0.16)
// for single and ~1.19× for stack — well within FitText's
// zoom-invariance contract.
const SINGLE_RATIO_MAX = 0.22;
const STACK_RATIO_MIN = 0.16;
const STACK_RATIO_MAX = 0.19;

const cellBase = css({
  position: 'relative',
  width: '100%',
  aspectRatio: '1 / 1',
  // No border here — gap: 1px + bg: gridLine on the container paints every grid line (no double-up at shared edges).
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontFamily: 'body',
  // Required so border arrows (position:absolute, translate outside) are not clipped.
  overflow: 'visible',
});
const letterCell = css({ bg: 'surface' });
// Letters belonging to the active word/clue carry a ROSE tint — same
// family as the focused cell's `focusBg` (#2A1C22) but applied as a
// flat fill (no inset ring) so the actively-focused cell still
// stands out via its 1.5 px rose ring on top. The previous tint
// (`accentBg` = sage) was visually identical to the validated state,
// which made the "you're solving this word" cue indistinguishable
// from "this letter is locked in".
//
// Used by SOLO play AND by the local player in multiplayer — the
// local user's active word always reads as the same rose tint they
// see in single-player so the cue is consistent across modes. Remote
// peers paint via `letterCellPlayerWord` (per-hash hue).
const letterCellInWord = css({ bg: 'focusBg' });
// Solo-mode focused-cell ring driven by React state (not the input's
// `:focus` pseudo-class). Lets the visual persist when DOM focus
// leaves the input — e.g. the user taps the hint button or page
// chrome. Suppresses the input's own `:focus` styling so the two
// don't double up while DOM focus is still present.
const letterCellSoloFocused = css({
  bg: 'focusBg',
  boxShadow: 'inset 0 0 0 1.5px token(colors.focusRing)',
  '& input:focus': {
    bg: 'transparent',
    boxShadow: 'none',
  },
});
// Player-aware modifiers consume CSS vars that the Grid spreads onto
// the cell wrapper via inline `style={...}` (`playerColorVars(sessionId)`).
// The wrapper's bg + inset ring are the sole visual cues — the inner
// `<input>` gets its `_focus` bg/ring suppressed so the two don't
// double up at the cell edges.
const letterCellPlayerActive = css({
  bg: 'var(--player-active-bg)',
  boxShadow: 'inset 0 0 0 1.5px var(--player-color)',
  '& input:focus': {
    bg: 'transparent',
    boxShadow: 'none',
  },
});
const letterCellPlayerWord = css({
  bg: 'var(--player-word-bg)',
});
// Active-cell badge — small circular chip pinned to the cell's top-right.
// Renders ONLY for remote players (the local player's own active cell is
// disambiguated by the wrapper's ring; you don't badge yourself).
// Background uses the player's accent (`--player-color`); text uses the
// matching `--player-on` shade for AA contrast at the recipe's 16 %
// lightness floor.
//
// `data-typing="true"` opts into the keystroke-pulse animation defined in
// `index.css` (`wordsparrow-badge-pulse`). The Grid sets it when the
// peer is actively receiving keystrokes; absent or false, the badge is
// static. The keyframe is reduced-motion-aware.
const playerBadge = css({
  position: 'absolute',
  top: '3px',
  right: '3px',
  width: '13px',
  height: '13px',
  borderRadius: '50%',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  fontSize: '8px',
  fontWeight: 600,
  background: 'var(--player-color)',
  color: 'var(--player-on)',
  zIndex: 5,
  pointerEvents: 'none',
  userSelect: 'none',
  '&[data-typing="true"]': {
    animation: 'wordsparrow-badge-pulse 1.4s ease-in-out infinite',
  },
});
// Validated cell — locked, sage-on-sage. Spec §6: bg `primary.800`
// (`successBg`), letter `primary.500` (`accent`). The `successBg`
// semantic alias is identical to `accentBg` in the current palette but
// the role-token graph keeps the two intents distinct should a future
// theme split them.
const letterCellValidated = css({
  bg: 'successBg',
  '& input': {
    color: 'success',
    // Validated cells become read-only but a focused validated cell
    // still gets the rose inset ring — it tells the player "you're
    // here" even though they cannot edit. The previous full-neutral
    // override made focus invisible on validated cells, which made
    // arrow-key navigation through a partly-solved word feel broken.
    _focus: {
      bg: 'successBg',
      color: 'success',
      boxShadow: 'inset 0 0 0 1.5px token(colors.focusRing)',
      outline: 'none',
    },
  },
});
// Error cell — wrong letter detected by `Vérifier`. The brief says shake
// 200 ms then revert to filled, with a 1.5 px error-coloured inset ring
// during the shake. Letter colour also flips to error so colour is one
// of two signals (the second being the shake itself, per §8).
const letterCellError = css({
  '& input': {
    color: 'errorText',
    boxShadow: 'inset 0 0 0 1.5px token(colors.error)',
    animation: 'wordsparrow-cell-shake 200ms ease-out',
  },
});
const blockCell = css({ bg: 'surfaceMuted' });

// Definition cell: container-type so child cqi values resolve against cell width.
// zIndex:1 ensures the absolutely-positioned border arrows render above adjacent cells.
// Text colour is `onSurfaceVariant` (dark plum) — `surfaceVariant` is
// the light-pink clue surface in the charbon palette, so light `fg`
// would fail AA. Through the role-token graph, on a future light
// theme `onSurfaceVariant` re-resolves automatically.
const defCell = css({
  bg: 'surfaceVariant',
  color: 'onSurfaceVariant',
  containerType: 'inline-size',
  lineHeight: '1.05',
  // overflow: clip — iOS WebKit hyphenates lang="fr" more aggressively; clips the extra line without a new stacking context.
  overflow: 'clip',
  // Slightly larger padding now that the arrows have moved off the
  // def cell — the freed-up real estate goes to breathing room
  // around the clue text rather than back into FitText's font sizing.
  // Mobile-tiny (320 px viewport) cells are ~25 px wide; a 6 px
  // padding swallowed the stack-cell half-text and dragged the
  // measured clue ratio under 0.10. 4 px is the largest value that
  // keeps the e2e clue-ratio gate green at all four breakpoints.
  padding: '4px',
  // Top-left text alignment cascades to FitText's spans below
  // (ADR-0005 §6 "Text sits top-left").
  textAlign: 'left',
  // No `zIndex` — the spec relies on DOM order (letter cells render
  // after their preceding clue cells, so the arrow children of the
  // letter cell naturally paint above the clue surface). A previous
  // revision set `zIndex: 1` here for the now-removed border-spanning
  // arrows on the def cell; that elevated stacking context defeated
  // the source-order trick and clipped the new letter-cell arrows.
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

// Current-clue highlight: per the player's preference, the active clue
// recolours its TEXT + ARROW to sage (accent) instead of carrying a
// border stripe. Cascades through `color`, so the inline arrow SVG
// (which paints `currentColor`) picks up the same hue without needing
// its own state class.
const defCellCurrent = css({ color: 'accent' });

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
// Phase-2 bisection (floor = min × PHASE2_FLOOR_FACTOR × cellWidth)
// can't make it fit.
const defText = css({
  flex: 1,
  alignSelf: 'stretch',
  display: 'flex',
  // ADR-0005 §6: "Text sits top-left; the arrow sits bottom-right."
  // Text anchors to the top-left corner of the cell. `safe` qualifier
  // keeps the start-edge alignment honest when content overflows past
  // the available box (Chrome 87+ / Firefox 63+ / Safari 16+).
  alignItems: 'safe flex-start',
  justifyContent: 'safe flex-start',
  textAlign: 'left',
  // `fontFamily: 'mono'` (Lekton) is the load-bearing piece of the
  // gate-decoupling refactor: Lekton's constant glyph advance lets
  // `scripts/eval/clue_metrics.py` be a deterministic predicate on
  // `len(clue)` rather than mirroring the browser's PIL layout. See
  // ADR-0005 §5 amendment. Bold (700) is the only Lekton weight
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

// French direction label per arrow type — surfaced via aria-label on
// every arrow render site (def cells, stacked clues, and the new
// letter-cell arrows). Hoisted above `LetterArrow` because the latter
// references it.
const arrowLabel: Record<ArrowDirection, string> = {
  right: 'horizontale',
  down: 'verticale',
  'down-right': 'horizontale',
  'right-down': 'verticale',
};

// ADR-0005 §6 follow-up: arrows live ON THE LETTER CELL, not the clue
// cell. A small SVG with `position: absolute` and `left: -7px` (for
// incoming →) or `top: -7px` (for incoming ↓) straddles the 1 px grid
// gap — half on the wine clue surface, half on the dark letter cell.
//
// Why on the letter cell? Source order. Cells render in DOM order, so
// the letter cell paints AFTER the clue cell — its absolute children
// naturally render above the clue cell's territory. No `z-index`
// gymnastics needed.
//
// The shape is a classic mots-fléchés glyph: a short rectangular stem
// plus a triangular head. Colour is `#f0bac4` (half a step lighter
// than the clue text) so it stays visible against both the wine clue
// surface and the dark letter cell. See `IncomingArrow` and
// `LetterCellArrows` below.

export type IncomingArrowEdge = 'left' | 'top';
// Cross-axis position on the receiving edge. `center` for 1-clue
// source cells; `q1` (28 %) and `q3` (72 %) for the two slots a 2-
// clue source paints — see `Grid.tsx#computeIncomingArrows` for the
// rules. Mixed-origin pairs use `q1` for both arrows (per the spec
// "left:28% / top:28%" alignment).
export type IncomingArrowOffset = 'center' | 'q1' | 'q3';

export interface IncomingArrow {
  readonly edge: IncomingArrowEdge;
  readonly offset: IncomingArrowOffset;
  // The original clue arrow (`right` / `down` / bent). Surfaced for
  // tests and aria-label so we don't lose the bent variants from the
  // domain layer; the visual glyph itself stays simple (→ / ↓).
  readonly arrow: ArrowDirection;
}

// Color applied inline below since Panda's static extraction only emits CSS for token / literal values — see ClueArrowIcon.tsx for the shared rose constant.
const letterArrowBase = css({
  position: 'absolute',
  pointerEvents: 'none',
  zIndex: 3,
  '& svg': { display: 'block', width: '100%', height: '100%' },
});

// Straight `right` arrow — viewBox 11 × 9 (1:1 with rendered px so
// the stem thickness stays independent of the stem's length). The
// 6 px shaft + 5 px head keeps the arrow's overall length close to
// the original ~11 px while letting the stem run thicker (2 px) than
// the prior aspect-locked version (~1.2 px).
const ARROW_RIGHT_PATH = 'M0 3.5 L6 3.5 L6 1.5 L11 4.5 L6 7.5 L6 5.5 L0 5.5 Z';
// Straight `down` arrow — viewBox 9 × 11 (mirrors the right arrow on
// the perpendicular axis).
const ARROW_DOWN_PATH = 'M3.5 0 L5.5 0 L5.5 6 L7.5 6 L4.5 11 L1.5 6 L3.5 6 Z';
// Bent `right-down` — viewBox 18 × 22. Enters from the LEFT edge
// at the box's vertical centre (y ≈ 11), runs RIGHT inside the
// receiving cell, BENDS DOWN at the inner edge, exits with a
// triangular head pointing DOWN. Mirrors the classic mots-fléchés
// L-arrow for an answer that enters then turns downward.
const ARROW_RIGHT_DOWN_PATH = 'M0 10 L12 10 L12 16 L16 16 L11 22 L6 16 L10 16 L10 12 L0 12 Z';
// Bent `down-right` — viewBox 22 × 18. Enters from the TOP edge
// at the box's horizontal centre (x ≈ 11), runs DOWN inside the
// receiving cell, BENDS RIGHT, exits with a triangular head
// pointing RIGHT.
const ARROW_DOWN_RIGHT_PATH = 'M10 0 L12 0 L12 10 L16 10 L16 6 L22 11 L16 16 L16 12 L10 12 Z';

const ARROW_SIZE_PX = 14;
// Half of the arrow size — used to translate the centred edge anchor
// back into a centred glyph (cross-axis only).
const ARROW_HALF_PX = ARROW_SIZE_PX / 2;
// Slightly past the spec's `-7 px` half-and-half straddle — the
// arrow leans into the receiving letter cell so the head reads as
// "this letter is the clue's first letter". With a 14 px glyph
// the box extends from -4 to +10 of the cell edge: ~4 px on the
// clue surface, ~10 px on the letter cell.
const EDGE_OFFSET_PX = -4;

// Per-arrow visual descriptor. Bent variants ride taller / wider boxes
// because they fit a corner + head as well as the entry stem.
interface ArrowGlyph {
  readonly viewBox: string;
  readonly width: number;
  readonly height: number;
  readonly path: string;
}

// Straight arrows render at their viewBox 1:1 — 6 px stem + 5 px
// head, 11 px length × 9 px thickness-axis. Bent variants stay at
// the larger ~75 % scale so the L corner + head are still legible.
const GLYPH_BY_ARROW: Record<ArrowDirection, ArrowGlyph> = {
  right:        { viewBox: '0 0 11 9',  width: 11, height: 9,  path: ARROW_RIGHT_PATH },
  down:         { viewBox: '0 0 9 11',  width: 9,  height: 11, path: ARROW_DOWN_PATH },
  'right-down': { viewBox: '0 0 18 22', width: 14, height: 17, path: ARROW_RIGHT_DOWN_PATH },
  'down-right': { viewBox: '0 0 22 18', width: 17, height: 14, path: ARROW_DOWN_RIGHT_PATH },
};

function leftEdgeStyle(
  offset: IncomingArrowOffset,
  glyph: ArrowGlyph,
): React.CSSProperties {
  const cross =
    offset === 'center' ? '50%' : offset === 'q1' ? '28%' : '72%';
  return {
    left: `${EDGE_OFFSET_PX}px`,
    top: cross,
    transform: 'translateY(-50%)',
    width: `${glyph.width}px`,
    height: `${glyph.height}px`,
  };
}

function topEdgeStyle(
  offset: IncomingArrowOffset,
  glyph: ArrowGlyph,
): React.CSSProperties {
  const cross =
    offset === 'center' ? '50%' : offset === 'q1' ? '28%' : '72%';
  return {
    top: `${EDGE_OFFSET_PX}px`,
    left: cross,
    transform: 'translateX(-50%)',
    width: `${glyph.width}px`,
    height: `${glyph.height}px`,
  };
}

function LetterArrow({ arrow }: { arrow: IncomingArrow }) {
  const glyph = GLYPH_BY_ARROW[arrow.arrow];
  const positionStyle =
    arrow.edge === 'left'
      ? leftEdgeStyle(arrow.offset, glyph)
      : topEdgeStyle(arrow.offset, glyph);
  return (
    <span
      className={letterArrowBase}
      style={{ ...positionStyle, color: ARROW_COLOR }}
      data-arrow={arrow.arrow}
      data-incoming-edge={arrow.edge}
      role="img"
      aria-label={`définition ${arrowLabel[arrow.arrow]}`}
    >
      <svg viewBox={glyph.viewBox} aria-hidden focusable="false">
        <path d={glyph.path} fill="currentColor" />
      </svg>
    </span>
  );
}

void ARROW_HALF_PX; // referenced only via transform; kept for clarity.

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
  // Hairline rose divider between stacked clues — replaces the prior
  // `gridLine` neutral so the split reads as part of the rose clue
  // surface rather than a continuation of the charcoal grid line. A
  // light tint (1 px @ ~25 % alpha) is enough to part the two halves
  // without competing visually with the clue text.
  '&:not(:first-child)': {
    borderTop: '1px solid color-mix(in srgb, token(colors.secondary.400) 25%, transparent)',
  },
});
// Stack-clue current marker: same sage `accent` as the single-clue
// path so the half-cell's text and arrow read as a single recoloured
// unit. Aesthetic preference over the previous deep-sage primary.800
// — see ADR-0005 §6 follow-up note on the brand state cue.
const defStackClueCurrent = css({ color: 'accent' });
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
  // Top-left, per ADR-0005 §6 — see `defText` for rationale.
  alignItems: 'safe flex-start',
  justifyContent: 'safe flex-start',
  // Flex items default to `min-height: auto` (i.e. content-sized),
  // which lets a multi-line span stretch *beyond* its flex parent's
  // allotted height — defeating the half-cell's overflow:hidden by
  // making the span itself the leaking element. `minHeight: 0` opts
  // out so the span shrinks to its parent's height regardless of
  // content; overflow:hidden then clips the excess inside the span,
  // invisibly. Same trick lives on defStackClue's `minHeight: 0`.
  minHeight: 0,
  minWidth: 0,
  textAlign: 'left',
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
  // Brand brief §3 caps the type system at weights 400 / 500 — letters
  // sit at `medium` (500). The previous `bold` (700) was a holdover
  // from the pre-charbon palette.
  fontWeight: 'medium',
  // 19 px desktop / 16 px mobile, per the brief. Using literal values
  // here rather than the `cell` token because the spec's two sizes
  // straddle the project's existing scale stops (`body` 16 / `md` 18 /
  // `lg` 24); a token with two breakpoint values would only be
  // referenced from this single site.
  fontSize: { base: '16px', md: '19px' },
  // The wrapping <div> is the touch target — see LetterCellView below for
  // why. The input itself is `pointer-events: none` so taps fall through
  // to the div, and the `caretColor: transparent` + `userSelect: none`
  // stay as belt-and-braces in case any browser still routes touch to a
  // focused input via legacy paths.
  pointerEvents: 'none',
  caretColor: 'transparent',
  userSelect: 'none',
  padding: 0,
  // Focus visual: warm `focusBg` (charcoal with a pink hint) + a 1.5 px
  // inset `focusRing` (secondary pink). Letter stays at `fg` for full
  // contrast. Reads as "you can type here" without flooding the cell
  // with a solid accent colour.
  _focus: {
    bg: 'focusBg',
    color: 'fg',
    boxShadow: 'inset 0 0 0 1.5px token(colors.focusRing)',
    outline: 'none',
  },
  // type="search" exposes a webkit clear-X button on focus + a search-results
  // decoration affordance. Hide both — the cell is a single-character input,
  // not a real search box. (See `<input type="search">` below for why we use
  // search instead of text on Android.)
  '&::-webkit-search-cancel-button': { display: 'none' },
  '&::-webkit-search-decoration': { display: 'none' },
  '&::-webkit-search-results-button': { display: 'none' },
});

// `arrowLabel` is hoisted above `LetterArrow` (search for it earlier
// in this file) so both call sites share the single declaration.

// Per-cell player presence resolved by the Grid (most-recent-wins across
// all sessions, validated cells subtracted upstream). The Grid composes
// this from local-cursor state + remote presences and passes it down per
// cell. Solo play omits `presence` entirely.
export interface CellPresence {
  // CSS vars from `playerColorVars(sessionId)` — spread on the wrapper
  // div's inline `style` so `letterCellPlayerActive` /
  // `letterCellPlayerWord` resolve. Unused (and not spread) when
  // `isLocal` is true since the local player paints with solo classes.
  readonly vars: Record<string, string>;
  // 'active' = this is the player's cursor cell; 'word' = the cell is
  // part of the player's active word but not their cursor.
  readonly role: 'active' | 'word';
  // Single-character initial. Set ONLY for remote players whose active
  // cell this is — local-player cells and word-tint cells leave it
  // undefined and the badge is skipped.
  readonly badge?: string;
  // Animate the badge with the keystroke-pulse keyframe. Toggled true
  // by the Grid when the peer is actively typing (within ~1.5 s of the
  // last incoming letter), false otherwise. Static badge by default.
  readonly typing?: boolean;
  // True when this presence belongs to the local player — Cell paints
  // it with the solo classes (rose focusBg + rose ring) so the local
  // user sees the same cue in multiplayer as in single-player. Remote
  // peers keep their hash-derived hues (ADR-0018 §Presence).
  readonly isLocal?: boolean;
}

export const LetterCellView = memo(function LetterCellView({
  cell, ariaLabel, inWord, focused, validated, error, presence, incomingArrows, inputRef, touchPrimary, onClick, onKeyDown, onFocus, onBlur, onInput,
}: {
  cell: LetterCell;
  ariaLabel: string;
  inWord: boolean;
  // Solo-mode "this is the focused cell" — driven by React state so the
  // ring persists when DOM focus leaves the grid. Multiplayer ignores
  // this prop and uses `presence?.role === 'active'` instead.
  focused: boolean;
  // Validated state takes precedence over `inWord` for backgrounds —
  // a locked correct cell stays sage-tinted regardless of which word
  // the player is currently solving.
  validated: boolean;
  // Transient error state set by `Vérifier`; the parent flips it back
  // off once the shake animation finishes (see PuzzlePage).
  error: boolean;
  // Multiplayer presence resolved at the Grid layer. Validated cells
  // are filtered out upstream so this never paints over a sage cell.
  presence?: CellPresence;
  // Arrows pointing INTO this letter cell from neighbouring def cells
  // (ADR-0005 §6 follow-up). Empty/undefined when the cell sits
  // mid-word and has no entry arrows. Computed once at the Grid layer
  // — see `Grid.tsx#computeIncomingArrows`.
  incomingArrows?: readonly IncomingArrow[];
  inputRef: Ref<HTMLInputElement>;
  touchPrimary: boolean;
  onClick: (e: MouseEvent<HTMLDivElement>) => void;
  onKeyDown: (e: KeyboardEvent<HTMLInputElement>) => void;
  onFocus: (e: FocusEvent<HTMLInputElement>) => void;
  // onBlur snaps DOM focus back to the cell when blur would otherwise
  // leak to a button / page chrome — keeps typing reachable and the
  // mobile soft keyboard up. See `useGridNavigation.handleBlur`.
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

  // Class precedence: validated > error > presence.active > presence.word
  // > soloFocused > inWord > default. Validated is terminal (the cell is
  // locked); error is transient and only composes with the regular
  // surface so the shake reads against the unhighlighted background.
  // The presence branches paint REMOTE peers with their hash-derived
  // hue; the LOCAL player falls through to the solo classes so their
  // own cue stays the rose focusBg + ring they see in single-player.
  // `soloFocused` paints the focused-cell ring from React state in
  // single-player so the visual persists when DOM focus leaves the
  // input (hint button, page chrome); reused for local presence too.
  const presenceClass =
    presence?.role === 'active'
      ? presence.isLocal
        ? letterCellSoloFocused
        : letterCellPlayerActive
      : presence?.role === 'word'
      ? presence.isLocal
        ? letterCellInWord
        : letterCellPlayerWord
      : null;
  const stateClass = validated
    ? letterCellValidated
    : error
    ? `${letterCell} ${letterCellError}`
    : presenceClass
    ? presenceClass
    : focused && !presence
    ? letterCellSoloFocused
    : inWord
    ? letterCellInWord
    : letterCell;

  // Spread player CSS vars on the wrapper so the cell's modifier class
  // resolves them. Skipped for the local player (solo classes don't
  // read `--player-*` vars) and for unvalidated cells without a
  // presence — leaves untouched cells free of stale vars.
  const wrapperStyle =
    !validated && presence && !presence.isLocal ? presence.vars : undefined;
  const showBadge =
    !validated &&
    presence?.role === 'active' &&
    presence.isLocal !== true &&
    presence.badge !== undefined;

  return (
    <div
      role="gridcell"
      className={`${cellBase} ${stateClass}`}
      style={wrapperStyle}
      data-in-word={inWord ? 'true' : 'false'}
      data-validated={validated ? 'true' : undefined}
      data-error={error ? 'true' : undefined}
      data-player-active={
        !validated && presence?.role === 'active' ? 'true' : undefined
      }
      data-player-word={
        !validated && presence?.role === 'word' ? 'true' : undefined
      }
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
        inputMode={touchPrimary ? 'none' : 'text'}
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
        // Validated cells become read-only — the spec locks them as
        // soon as `Vérifier` confirms the letter. The visual state
        // already reads as "done" via `letterCellValidated`; readOnly
        // hardens the contract against keyboard input. We deliberately
        // keep the input mounted (vs unmounted text) so focus management
        // and arrow-key navigation stay consistent across states.
        readOnly={validated}
        aria-readonly={validated || undefined}
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
      {incomingArrows?.map((a) => (
        <LetterArrow
          key={`${a.edge}-${a.offset}-${a.arrow}`}
          arrow={a}
        />
      ))}
      {showBadge && (
        <span
          className={playerBadge}
          aria-hidden="true"
          data-player-badge="true"
          data-typing={presence!.typing ? 'true' : undefined}
        >
          {presence!.badge}
        </span>
      )}
    </div>
  );
});

// Stacked clue: text only. The arrow lives on the receiving letter
// cell (per ADR-0005 §6 follow-up) — the def cell only carries the
// label. The hairline divider between halves uses a rose tint so the
// stacked-clue split reads as part of the rose surface, not the
// charcoal grid line.
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

export const DefinitionCellView = memo(function DefinitionCellView({
  cell, currentArrow,
}: { cell: DefinitionCell; currentArrow: ArrowDirection | null }) {
  if (cell.clues.length === 1) {
    const clue = cell.clues[0];
    const isCurrent = currentArrow === clue.arrow;
    // The active clue recolours its text + arrow to sage (accent) via
    // `defCellCurrent`'s single `color` declaration; the SVG glyph
    // picks the new hue up through `currentColor`.
    const currentClass = isCurrent ? defCellCurrent : '';
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
      </div>
    );
  }

  // Two-clue branch. The visual stack pairs each clue with its arrow
  // *inside* its own half-cell — see `StackedClue` and
  // `defStackClue`'s `position: relative`. Visual order: right-origin
  // clue on top when origins differ (so the top half's arrow sits in
  // the natural corner adjacent to the entry letter), otherwise keep
  // API order. Domain order is untouched (ADR-0005 §3a).
  const [domFirst, domSecond] = cell.clues;
  const o1 = arrowOriginOf(domFirst.arrow);
  const o2 = arrowOriginOf(domSecond.arrow);
  const sameOrigin = o1 === o2;
  const topClue = sameOrigin || o1 === 'right' ? domFirst : domSecond;
  const bottomClue = sameOrigin || o1 === 'right' ? domSecond : domFirst;
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
