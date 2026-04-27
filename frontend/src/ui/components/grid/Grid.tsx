import { useMemo } from 'react';
import { css } from 'styled-system/css';
import type { Cell, Position, Puzzle } from '@/domain';
import { BlockCellView, DefinitionCellView, LetterCellView } from './Cell';
import { CurrentCluePanel } from './CurrentCluePanel';
import { useGridNavigation } from './useGridNavigation';

const layout = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
  width: '100%',
});
const gridContainer = css({
  display: 'grid',
  gap: '0',
  bg: 'border',
  border: '1px solid',
  borderColor: 'border',
  // Kills the iOS 300ms tap delay (ADR-0002 §4).
  touchAction: 'manipulation',
  userSelect: 'none',
  width: '100%',
  maxWidth: '480px',
  margin: '0 auto',
});

const rowStyles = css({ display: 'contents' });

const positionKey = (p: Position) => `${p.row},${p.col}`;

// v1 interactive grid. Letter inputs are uncontrolled (ADR-0002 §4).
// `useGridNavigation` orchestrates focus, direction, and highlighting
// via stable handlers that read row/col from data attributes.
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
    <div className={layout}>
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
                      onPointerDown={nav.handlePointerDown}
                      onFocus={nav.handleFocus}
                      onKeyDown={nav.handleKeyDown}
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
    </div>
  );
}
