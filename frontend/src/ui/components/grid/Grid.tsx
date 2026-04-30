import { useMemo } from 'react';
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
  maxWidth: '480px',
  margin: '0 auto',
  // Allow border arrows on edge cells to bleed outside the grid border box.
  overflow: 'visible',
  // Omitted: touch-action:manipulation blocked pinch/pan; user-select:none blocked iOS magnifier on clue cells. See ADR-0002 §4.
});

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
    </>
  );
}
