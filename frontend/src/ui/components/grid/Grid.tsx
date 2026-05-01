import { useMemo } from 'react';
import { TransformComponent, TransformWrapper } from 'react-zoom-pan-pinch';
import { css } from 'styled-system/css';
import type { Cell, Position, Puzzle } from '@/domain';
import { BlockCellView, DefinitionCellView, LetterCellView } from './Cell';
import { CurrentCluePanel } from './CurrentCluePanel';
import { useGridNavigation } from './useGridNavigation';

const gridContainer = css({
  display: 'grid',
  gap: '0',
  bg: 'border',
  border: '1px solid',
  borderColor: 'border',
  width: '100%',
  // Width is bounded by the TransformComponent wrapper below (maxWidth: 480px).
  // Allow border arrows on edge cells to bleed outside the grid border box.
  overflow: 'visible',
  // Omitted: touch-action:manipulation blocked pinch/pan; user-select:none blocked iOS magnifier on clue cells.
});

// `transformWrapperStyle` makes the zoom-pan viewport behave like the old
// raw `<div role="grid">` did: full available width, capped at 480px,
// centered under the clue panel. The library's stylesheet defaults this
// box to `width: fit-content`, which would collapse around `width: 100%`
// children — we override here so the outer box drives the layout and the
// grid inside fills it.
// `touchAction: 'none'` scopes native-pinch suppression to this element only,
// keeping native browser zoom available on the clue panel and page chrome
// (required for WCAG 1.4.4 on mobile — pinch IS browser zoom on touch devices).
const transformWrapperStyle = { width: '100%', maxWidth: '480px', margin: '0 auto', touchAction: 'none' as const };
// `transformContentStyle.width: 100%` defeats the same library default on
// the inner (transformed) plane so the grid actually spans the wrapper.
// The library composes its `transform: translate3d() scale()` on top of
// any inline style we pass, so width here is preserved.
const transformContentStyle = { width: '100%' };

const rowStyles = css({ display: 'contents' });

const positionKey = (p: Position) => `${p.row},${p.col}`;

// v1 interactive grid. Letter inputs are uncontrolled (ADR-0002 §4).
// `useGridNavigation` orchestrates focus, direction, and highlighting
// via stable handlers that read row/col from data attributes.
//
// Returns a fragment so `CurrentCluePanel` lives at the route layer's flex
// column (not nested inside an extra `<div>`). That matters for sticky:
// the panel's containing block becomes `<main>`, which is taller than the
// viewport, so `position: sticky` actually has room to stick. The previous
// nested-flex revision had the panel inside a `layout` div whose height
// equaled its content — sticky un-stuck instantly.
//
// Pinch-zoom: the grid (and only the grid) is wrapped in
// `react-zoom-pan-pinch`'s `TransformWrapper`. Native pinch is suppressed
// only on the wrapper element (`touch-action: none` in transformWrapperStyle),
// not at the page level — preserving native browser zoom on the clue panel
// and page chrome for WCAG 1.4.4 compliance (see ADR-0016). Because the page
// itself never zooms, the layout viewport never diverges from the visual
// viewport, so `position: sticky` on the clue panel works without JS.
export function Grid({ puzzle }: { puzzle: Puzzle }) {
  const cellByPosition = useMemo(() => {
    const m = new Map<string, Cell>();
    for (const c of puzzle.cells) m.set(positionKey(c.position), c);
    return m;
  }, [puzzle.cells]);

  const templateStyle = useMemo(
    () => ({
      gridTemplateColumns: `repeat(${puzzle.width}, 1fr)`,
      gridTemplateRows: `repeat(${puzzle.height}, auto)`,
    }),
    [puzzle.height, puzzle.width],
  );

  const nav = useGridNavigation(puzzle);

  const rows: { row: number; cells: (Cell | null)[] }[] = [];
  for (let row = 0; row < puzzle.height; row++) {
    const cellsInRow: (Cell | null)[] = [];
    for (let col = 0; col < puzzle.width; col++) {
      cellsInRow.push(cellByPosition.get(positionKey({ row, col })) ?? null);
    }
    rows.push({ row, cells: cellsInRow });
  }

  return (
    <>
      <CurrentCluePanel clue={nav.currentClue} />
      {/*
        TransformWrapper config rationale:
        - `minScale={1}` — never zoom out below 100%, so the grid always
          fills its 480px-cap container at rest. Zooming out further would
          leave empty padding around the grid for no benefit.
        - `maxScale={4}` — caps zoom at 4× so a single cell on a 5-col
          puzzle (~96px at base) renders ~384px. Plenty for thumb-distance
          reading without making the grid uselessly large.
        - `centerOnInit` — first paint puts the grid centered in the
          wrapper. Without this the library can leave a small offset from
          its bounds-padding logic on certain initial sizes.
        - `wheel.disabled` — desktop mouse wheel scrolls the page (the
          natural expectation), not zooms the grid. Pinch on a trackpad
          still zooms, which is the desktop equivalent of mobile pinch.
        - `doubleClick.disabled` — a double-tap on a cell would otherwise
          zoom-in on that cell, fighting the focus + cursor behavior we
          rely on for letter input. Disabled keeps taps purely about focus.
        - `panning.velocityDisabled` — momentum/inertia after a flick
          feels disorienting in a fixed-content puzzle (you expect the
          grid to land where your finger lifts).
        - `panning.allowLeftClickPan: false` — prevents desktop left-click
          drag from initiating a pan (would conflict with future
          drag-to-select features and is unnecessary at scale 1, the
          default desktop state). One-finger touch panning still works
          on mobile because that flows through the touch handlers, not
          the mouse handlers.
      */}
      <TransformWrapper
        minScale={1}
        maxScale={4}
        initialScale={1}
        centerOnInit
        wheel={{ disabled: true }}
        doubleClick={{ disabled: true }}
        panning={{ velocityDisabled: true, allowLeftClickPan: false }}
      >
        <TransformComponent
          wrapperStyle={transformWrapperStyle}
          contentStyle={transformContentStyle}
        >
          <div
            role="grid"
            aria-label={puzzle.title}
            lang={puzzle.language}
            className={gridContainer}
            style={templateStyle}
          >
            {rows.map(({ row, cells }) => (
              <div key={row} role="row" className={rowStyles}>
                {cells.map((cell, col) => {
                  if (cell === null) {
                    return (
                      <BlockCellView
                        key={`empty-${row}-${col}`}
                        cell={{ kind: 'block', position: { row, col } }}
                      />
                    );
                  }
                  const key = positionKey(cell.position);
                  switch (cell.kind) {
                    case 'letter': {
                      const highlight = nav.highlightFor(cell.position);
                      return (
                        <LetterCellView
                          key={key}
                          cell={cell}
                          ariaLabel={`Case ligne ${cell.position.row + 1}, colonne ${cell.position.col + 1}`}
                          inWord={highlight.currentWord}
                          inputRef={nav.registerCellRef}
                          onClick={nav.handleClick}
                          onFocus={nav.handleFocus}
                          onKeyDown={nav.handleKeyDown}
                          onInput={nav.handleInput}
                        />
                      );
                    }
                    case 'definition': {
                      const highlight = nav.highlightFor(cell.position);
                      return (
                        <DefinitionCellView
                          key={key}
                          cell={cell}
                          currentArrow={highlight.currentArrow}
                        />
                      );
                    }
                    case 'block':
                      return <BlockCellView key={key} cell={cell} />;
                  }
                })}
              </div>
            ))}
          </div>
        </TransformComponent>
      </TransformWrapper>
    </>
  );
}
