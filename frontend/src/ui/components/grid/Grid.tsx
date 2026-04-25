import { useMemo } from 'react';
import { css } from 'styled-system/css';
import type { Cell, Position, Puzzle } from '@/domain';
import { BlockCellView, DefinitionCellView, LetterCellView } from './Cell';

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

// v1 visual grid. Renders one DOM node per cell with the appropriate
// variant; letter inputs are uncontrolled (defaultValue) so the DOM owns
// keystrokes per ADR-0002 §4. Keyboard navigation, focus management, and
// direction tracking are deliberately deferred to a follow-up workstream
// to keep this PR within the 400-line cap (ADR-0001 §4).
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

  const rows: { row: number; cells: (Cell | null)[] }[] = [];
  for (let row = 0; row < puzzle.height; row++) {
    const cellsInRow: (Cell | null)[] = [];
    for (let col = 0; col < puzzle.width; col++) {
      cellsInRow.push(cellByPosition.get(positionKey({ row, col })) ?? null);
    }
    rows.push({ row, cells: cellsInRow });
  }

  return (
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
              case 'letter':
                return (
                  <LetterCellView
                    key={key}
                    cell={cell}
                    ariaLabel={`Case ligne ${cell.position.row + 1}, colonne ${cell.position.col + 1}`}
                  />
                );
              case 'definition':
                return <DefinitionCellView key={key} cell={cell} />;
              case 'block':
                return <BlockCellView key={key} cell={cell} />;
            }
          })}
        </div>
      ))}
    </div>
  );
}
