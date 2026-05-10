import { useEffect, useState } from 'react';
import { css } from 'styled-system/css';
import type { ArrowDirection, LetterCell } from '@/domain';
import { GRID_TRACK_WIDTH } from './layout';
import type { Clue } from './useGridNavigation';

// Sticky-pinned to the top of the page-level scroll. Three visual states
// (ADR-0005 §6 redesign):
//
//   1. Empty — pointer-icon banner prompting the player to tap a cell.
//   2. Single direction — rose chip + clue text + a per-cell letter
//      preview (uppercase letter for filled cells, "·" for empty;
//      focused cell is underlined in rose).
//   3. Intersection — adds the alternate-direction sub-block plus a
//      "kbd-styled" Espace chip (clear that this is a keyboard hint,
//      not a clickable affordance — though the chip remains tappable
//      for parity with the keyboard).
//
// Responsive behaviour: the inner content wraps to a second line when
// the active clue + letter preview + alt block exceed the panel width.
// The chip stays anchored to the start; everything else flows.

// Shared sticky chrome — both empty and active panels share top / zIndex
// / centring. Per-state visual differences (height, fill, padding) are
// applied on top of this base.
const panelBase = css({
  position: 'sticky',
  top: 0,
  zIndex: 10,
  width: '100%',
  margin: '0 auto',
  bg: 'surfaceElevated',
  color: 'fg',
  textAlign: 'left',
  fontFamily: 'body',
  borderRadius: '12px',
  display: 'flex',
  alignItems: 'center',
});

const emptyPanel = css({
  paddingInline: { base: '14px', md: '16px' },
  gap: '10px',
  // Single, fixed mini height — the empty banner reads as a label.
  minHeight: '52px',
  color: 'neutral.200',
  fontSize: 'sm',
});

// Active panel — variable height. Single-line on wide viewports, wraps
// to two lines when the active clue + alt clue + Espace chip exceed
// the available width (per the brief: "when clue & word are too long
// → 2 lines").
const activePanel = css({
  paddingInline: { base: '8px', md: '12px' },
  paddingBlock: '8px',
  gap: '12px',
  minHeight: '52px',
  flexWrap: 'wrap',
});

// Rose chip on the left — light-rose tint at 18 % alpha (the brief's
// exact `rgba(232,163,179,0.18)`) with the same `#e8a3b3` rose driving
// the arrow glyph. `color-mix` keeps the alpha math at the CSS layer.
const arrowChip = css({
  flexShrink: 0,
  width: '28px',
  height: '28px',
  borderRadius: '6px',
  bg: 'color-mix(in srgb, token(colors.secondary.400) 18%, transparent)',
  color: 'secondary.400',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  '& svg': { width: '14px', height: '14px' },
});

// `clueGroup` ties the chip + clue text + letter preview together so
// they wrap as one unit when the panel reflows to two lines.
const clueGroup = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '10px',
  minWidth: 0,
  // Allows the group to grow past its natural content width on wide
  // viewports while still wrapping cleanly on narrow ones.
  flex: '0 1 auto',
});

const clueText = css({
  fontSize: '15px',
  fontWeight: 'medium',
  color: 'fg',
  lineHeight: '1.3',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  // Cap the clue text width per group so very long clues clip with
  // ellipsis rather than push the letter preview off-screen.
  maxWidth: { base: '180px', md: '260px' },
});

// Subdued style for the alt clue text — the player's eye is on the
// active group; the alt is just a reminder that another path exists.
const altClueText = css({
  fontSize: '15px',
  fontWeight: 'medium',
  color: 'fgMuted',
  lineHeight: '1.3',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  maxWidth: { base: '140px', md: '200px' },
});

// Letter preview row — one cell per letter slot. Filled letters paint
// in the active group's fg; empty slots show a centered dot. The
// focused cell underlines its letter (or dot) in rose to mirror the
// grid's active-cell ring.
const letterRow = css({
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

const letterRowMuted = css({ color: 'fgMuted' });

const letterSlot = css({
  display: 'inline-block',
  minWidth: '8px',
  textAlign: 'center',
});

const letterDot = css({
  // Slightly dimmer dot — empty slots read as placeholders, not letters.
  color: 'neutral.400',
});

const letterFocused = css({
  borderBottom: '2px solid token(colors.secondary.400)',
  paddingBottom: '1px',
  color: 'fg',
});

// Alt block — direction arrow + alt clue text + alt letter preview.
// `flex: 1 0 auto` lets it claim the remaining row width on a single-
// line panel and naturally wrap to its own row when content overflows.
const altGroup = css({
  display: 'inline-flex',
  alignItems: 'center',
  gap: '10px',
  minWidth: 0,
  flex: '0 1 auto',
});

const altDirectionGlyph = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  width: '20px',
  height: '20px',
  color: 'fgMuted',
  flexShrink: 0,
  '& svg': { width: '14px', height: '14px' },
});

// Pushes the kbd chip to the row's far end on wide layouts, leaves it
// trailing on wrapped layouts (where the alt block flows to row 2 and
// the chip can sit beside it).
const spacer = css({ flex: 1, minWidth: '8px' });

// kbd-styled chip — small bordered rectangle with the keyboard label.
// Tappable for parity with mouse users (calls `onSwitchDirection`),
// but presented as a key, not a button — matches the brief's "clear
// it's a key, not a button" note.
const kbdChip = css({
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
  flexShrink: 0,
  paddingInline: '8px',
  height: '24px',
  borderRadius: '4px',
  border: '1px solid token(colors.border)',
  bg: 'transparent',
  color: 'fgMuted',
  fontFamily: 'body',
  fontSize: '12px',
  fontWeight: 'medium',
  cursor: 'pointer',
  transition: 'border-color 120ms ease-out, color 120ms ease-out, transform 120ms ease-out',
  _hover: { borderColor: 'fg', color: 'fg' },
  _active: { transform: 'scale(0.96)' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

// Small inline-SVG arrow glyphs — same stroke vocabulary as the grid
// arrows in `Cell.tsx`, scaled down for chip size.
const ARROW_PATHS: Record<ArrowDirection, string> = {
  right: 'M3 12h17 M14 7l6 5-6 5',
  down: 'M12 3v17 M7 14l5 6 5-6',
  'right-down': 'M3 7h14v13 M12 15l5 5 5-5',
  'down-right': 'M7 3v14h13 M15 12l5 5-5 5',
};

function ArrowIcon({ arrow }: { arrow: ArrowDirection }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d={ARROW_PATHS[arrow]} />
    </svg>
  );
}

function PointerIcon() {
  return (
    <svg
      viewBox="0 0 24 24"
      width={18}
      height={18}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      <path d="M5 4l5 14 2-6 6-2z" />
    </svg>
  );
}

const arrowLabel: Record<ArrowDirection, string> = {
  right: 'horizontale',
  down: 'verticale',
  'down-right': 'horizontale',
  'right-down': 'verticale',
};

// Letter preview row builder — one slot per cell in the clue. The
// `focusedRow`/`focusedCol` pair flags the slot that should carry the
// rose underline. Cells whose entry is empty show a centred dot.
function LetterPreview({
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

// See module header for why we override `position` to `fixed` when the
// page is pinch-zoomed. Math: at zoom N, every CSS px renders as N
// device px. `position: fixed; top: 0; left: 0; right: 0` re-anchors
// to the layout viewport top; `translate(offsetLeft, offsetTop)` shifts
// to visual viewport top-left; `scale(1/N)` keeps device size constant.
type ZoomedStyle = {
  position: 'fixed';
  top: 0;
  left: 0;
  right: 0;
  transform: string;
  transformOrigin: 'top left';
};

function useVisualViewportZoom(): ZoomedStyle | undefined {
  const [style, setStyle] = useState<ZoomedStyle | undefined>(undefined);
  useEffect(() => {
    if (typeof window === 'undefined' || !window.visualViewport) return;
    const vv = window.visualViewport;
    let raf = 0;
    const update = () => {
      raf = 0;
      if (vv.scale > 1.001) {
        setStyle({
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          transform: `translate(${vv.offsetLeft}px, ${vv.offsetTop}px) scale(${1 / vv.scale})`,
          transformOrigin: 'top left',
        });
      } else {
        setStyle(undefined);
      }
    };
    const schedule = () => {
      if (!raf) raf = requestAnimationFrame(update);
    };
    vv.addEventListener('scroll', schedule);
    vv.addEventListener('resize', schedule);
    update();
    return () => {
      cancelAnimationFrame(raf);
      vv.removeEventListener('scroll', schedule);
      vv.removeEventListener('resize', schedule);
    };
  }, []);
  return style;
}

const trackWidthStyle = { maxWidth: GRID_TRACK_WIDTH } as const;

export interface CurrentCluePanelProps {
  readonly clue: Clue | null;
  readonly cellIndex?: number | null;
  readonly alternateClue?: Clue | null;
  readonly onSwitchDirection?: () => void;
  readonly getEntryAt?: (row: number, col: number) => string;
}

const noEntries = () => '';

export function CurrentCluePanel({
  clue,
  cellIndex = null,
  alternateClue = null,
  onSwitchDirection,
  getEntryAt = noEntries,
}: CurrentCluePanelProps) {
  const zoomStyle = useVisualViewportZoom();
  const inlineStyle = zoomStyle ?? trackWidthStyle;

  if (!clue) {
    return (
      <div
        className={`${panelBase} ${emptyPanel}`}
        style={inlineStyle}
        data-testid="current-clue-panel"
      >
        <PointerIcon />
        <span>Sélectionnez une case pour afficher la définition</span>
      </div>
    );
  }

  // The focused position is wherever the active clue's `cellIndex`
  // points — already validated by the nav hook before the panel sees
  // it. The alt clue's letter preview shares the same focused position
  // (the player is sitting at an intersection by definition).
  const focusedPosition =
    cellIndex !== null && clue.cells[cellIndex] !== undefined
      ? clue.cells[cellIndex].position
      : null;

  return (
    <div
      className={`${panelBase} ${activePanel}`}
      style={inlineStyle}
      data-testid="current-clue-panel"
    >
      <span className={clueGroup}>
        <span
          className={arrowChip}
          aria-label={`définition ${arrowLabel[clue.clue.arrow]}`}
          role="img"
        >
          <ArrowIcon arrow={clue.clue.arrow} />
        </span>
        <span className={clueText} title={clue.clue.text}>
          {clue.clue.text}
        </span>
        <LetterPreview
          cells={clue.cells}
          focusedPosition={focusedPosition}
          getEntryAt={getEntryAt}
        />
      </span>
      {alternateClue ? (
        <>
          <span className={spacer} aria-hidden />
          <span className={altGroup}>
            <span
              className={altDirectionGlyph}
              aria-label={`alternative : définition ${arrowLabel[alternateClue.clue.arrow]}`}
              role="img"
            >
              <ArrowIcon arrow={alternateClue.clue.arrow} />
            </span>
            <span className={altClueText} title={alternateClue.clue.text}>
              {alternateClue.clue.text}
            </span>
            <LetterPreview
              cells={alternateClue.cells}
              focusedPosition={focusedPosition}
              getEntryAt={getEntryAt}
              muted
            />
          </span>
          <button
            type="button"
            className={kbdChip}
            onClick={onSwitchDirection}
            aria-label={`Basculer sur la définition ${arrowLabel[alternateClue.clue.arrow]} (raccourci : Espace)`}
            // mousedown.preventDefault keeps the focused cell focused so
            // typing can resume immediately after the toggle.
            onMouseDown={(e) => e.preventDefault()}
          >
            Espace
          </button>
        </>
      ) : null}
    </div>
  );
}
