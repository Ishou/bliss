import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { SAMPLE_PUZZLE } from '@/domain';
import type { Cell, DefinitionCell, Puzzle } from '@/domain';
import { Grid } from '@/ui/components/grid';

// Walks the answer path of a definition cell: starting from the cell
// adjacent to it (per arrow direction) and continuing while the next
// position is still a letter cell. Returns the set of "row,col" keys
// the clue's answer covers. This mirrors how a renderer would highlight
// a clue path; here we only need it to verify cell coverage.
const positionKey = (row: number, col: number) => `${row},${col}`;
const walkClue = (puzzle: Puzzle, def: DefinitionCell): string[] => {
  const byKey = new Map<string, Cell>();
  for (const c of puzzle.cells) byKey.set(positionKey(c.position.row, c.position.col), c);
  const dRow = def.arrow === 'down' ? 1 : 0;
  const dCol = def.arrow === 'right' ? 1 : 0;
  const path: string[] = [];
  let row = def.position.row + dRow;
  let col = def.position.col + dCol;
  while (row < puzzle.height && col < puzzle.width) {
    const cell = byKey.get(positionKey(row, col));
    if (!cell || cell.kind !== 'letter') break;
    path.push(positionKey(row, col));
    row += dRow;
    col += dCol;
  }
  return path;
};

// Behavior: rendering the sample puzzle produces the expected mix of
// letter and definition cells, with each clue's text in the DOM (so
// screen readers can pick it up — ADR-0002 §4 / WCAG AA).
describe('Grid render', () => {
  it('renders one DOM node per cell variant in the sample puzzle', () => {
    const expected = SAMPLE_PUZZLE.cells.reduce(
      (acc, c) => ({ ...acc, [c.kind]: acc[c.kind] + 1 }),
      { letter: 0, definition: 0, block: 0 },
    );
    const { container } = render(<Grid puzzle={SAMPLE_PUZZLE} />);
    expect(container.querySelectorAll('[data-cell-kind="letter"]')).toHaveLength(expected.letter);
    expect(container.querySelectorAll('[data-cell-kind="definition"]')).toHaveLength(expected.definition);
    // Empty positions also render as blocks, so the rendered count is >=
    // the number of explicit BlockCells.
    expect(container.querySelectorAll('[data-cell-kind="block"]').length).toBeGreaterThanOrEqual(expected.block);
  });

  it('exposes role="grid", the puzzle title, and the puzzle language', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    const grid = screen.getByRole('grid', { name: SAMPLE_PUZZLE.title });
    expect(grid).toHaveAttribute('lang', SAMPLE_PUZZLE.language);
  });

  it('renders each definition cell with its clue text in the DOM', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    for (const cell of SAMPLE_PUZZLE.cells) {
      if (cell.kind === 'definition') {
        expect(screen.getByText(cell.text)).toBeInTheDocument();
      }
    }
  });

  // The headline property of a real *mots fléchés*: every fillable cell
  // is reachable from at least one clue. A cell that no clue traces over
  // is unsolvable visual noise; the demo grid must not contain any.
  it('covers every letter cell with at least one clue answer path', () => {
    const covered = new Set<string>();
    for (const cell of SAMPLE_PUZZLE.cells) {
      if (cell.kind !== 'definition') continue;
      for (const key of walkClue(SAMPLE_PUZZLE, cell)) covered.add(key);
    }
    const orphans: string[] = [];
    for (const cell of SAMPLE_PUZZLE.cells) {
      if (cell.kind !== 'letter') continue;
      const key = positionKey(cell.position.row, cell.position.col);
      if (!covered.has(key)) orphans.push(key);
    }
    expect(orphans).toEqual([]);

    // Every clue must trace at least one letter — a definition with an
    // empty path is dead weight on the grid.
    for (const cell of SAMPLE_PUZZLE.cells) {
      if (cell.kind !== 'definition') continue;
      expect(walkClue(SAMPLE_PUZZLE, cell).length).toBeGreaterThan(0);
    }
  });
});
