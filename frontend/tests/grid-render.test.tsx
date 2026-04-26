import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { SAMPLE_PUZZLE } from '@/domain';
import type {
  ArrowDirection,
  Cell,
  DefinitionCell,
  DefinitionClue,
  Puzzle,
} from '@/domain';
import { Grid } from '@/ui/components/grid';
import { DefinitionCellView } from '@/ui/components/grid/Cell';

// Walks the answer path of one clue inside a definition cell: starting
// from the cell adjacent to the def (per arrow direction) and continuing
// while the next position is still a letter cell. Returns the set of
// "row,col" keys the clue's answer covers. Mirrors how a renderer would
// highlight a clue path; here we only need it to verify cell coverage
// and word integrity.
const positionKey = (row: number, col: number) => `${row},${col}`;
const walkClue = (
  puzzle: Puzzle,
  def: DefinitionCell,
  arrow: ArrowDirection,
): string[] => {
  const byKey = new Map<string, Cell>();
  for (const c of puzzle.cells) byKey.set(positionKey(c.position.row, c.position.col), c);
  const dRow = arrow === 'down' ? 1 : 0;
  const dCol = arrow === 'right' ? 1 : 0;
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

// Yields one (def, clue) pair per clue per definition cell. Stacked
// cells emit two pairs.
const eachClue = function* (puzzle: Puzzle): Generator<{ def: DefinitionCell; clue: DefinitionClue }> {
  for (const cell of puzzle.cells) {
    if (cell.kind !== 'definition') continue;
    for (const clue of cell.clues) yield { def: cell, clue };
  }
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

  it('renders every clue text in the DOM, including stacked clues', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    for (const { clue } of eachClue(SAMPLE_PUZZLE)) {
      expect(screen.getByText(clue.text)).toBeInTheDocument();
    }
  });

  it('covers every letter cell with at least one clue answer path', () => {
    const covered = new Set<string>();
    for (const { def, clue } of eachClue(SAMPLE_PUZZLE)) {
      for (const key of walkClue(SAMPLE_PUZZLE, def, clue.arrow)) covered.add(key);
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
    for (const { def, clue } of eachClue(SAMPLE_PUZZLE)) {
      expect(walkClue(SAMPLE_PUZZLE, def, clue.arrow).length).toBeGreaterThan(0);
    }
  });

  // Headline property of a fully-interlocking *mots fléchés*: every
  // contiguous run of letter cells in any direction (length ≥ 2) must
  // (a) match a clue's answer path and (b) read a real word. Walking
  // rows and columns and accumulating runs catches the PR #26 bug where
  // columns spelled LEMAR / UTEMU / NERIE — letter cells covered by
  // clues but column-direction nonsense.
  it('every contiguous letter run of length ≥ 2 is defined by a clue', () => {
    const byKey = new Map<string, Cell>();
    for (const c of SAMPLE_PUZZLE.cells) byKey.set(positionKey(c.position.row, c.position.col), c);

    // Build the set of {start key, direction} pairs that some clue
    // defines, where "start key" is the first letter cell after the def.
    const cluedStarts = new Set<string>();
    for (const { def, clue } of eachClue(SAMPLE_PUZZLE)) {
      const startRow = def.position.row + (clue.arrow === 'down' ? 1 : 0);
      const startCol = def.position.col + (clue.arrow === 'right' ? 1 : 0);
      cluedStarts.add(`${clue.arrow}:${positionKey(startRow, startCol)}`);
    }

    type RunKind = 'row' | 'col';
    const collectRuns = (kind: RunKind): { startKey: string; arrow: ArrowDirection; length: number }[] => {
      const out: { startKey: string; arrow: ArrowDirection; length: number }[] = [];
      const outerMax = kind === 'row' ? SAMPLE_PUZZLE.height : SAMPLE_PUZZLE.width;
      const innerMax = kind === 'row' ? SAMPLE_PUZZLE.width : SAMPLE_PUZZLE.height;
      const arrow: ArrowDirection = kind === 'row' ? 'right' : 'down';
      for (let outer = 0; outer < outerMax; outer++) {
        let runStart = -1;
        for (let inner = 0; inner <= innerMax; inner++) {
          const row = kind === 'row' ? outer : inner;
          const col = kind === 'row' ? inner : outer;
          const cell = inner < innerMax ? byKey.get(positionKey(row, col)) : undefined;
          const isLetter = cell?.kind === 'letter';
          if (isLetter && runStart === -1) runStart = inner;
          if ((!isLetter || inner === innerMax) && runStart !== -1) {
            const startRow = kind === 'row' ? outer : runStart;
            const startCol = kind === 'row' ? runStart : outer;
            out.push({ startKey: positionKey(startRow, startCol), arrow, length: inner - runStart });
            runStart = -1;
          }
        }
      }
      return out;
    };

    const runs = [...collectRuns('row'), ...collectRuns('col')];
    const longRuns = runs.filter((r) => r.length >= 2);

    // Every multi-letter run must be the path of exactly one clue.
    const unmatched = longRuns.filter((r) => !cluedStarts.has(`${r.arrow}:${r.startKey}`));
    expect(unmatched).toEqual([]);

    // Sanity: the puzzle is non-trivial — at least one row run AND at
    // least one column run of length ≥ 2 must exist. Without this,
    // a degenerate puzzle (e.g. a single horizontal word) would pass.
    expect(longRuns.some((r) => r.arrow === 'right')).toBe(true);
    expect(longRuns.some((r) => r.arrow === 'down')).toBe(true);
  });

  it('renders a stacked definition cell with both clues and aria labels', () => {
    const stacked: DefinitionCell = {
      kind: 'definition',
      position: { row: 0, col: 0 },
      clues: [
        { text: 'Astre nocturne', arrow: 'right' },
        { text: 'Saison chaude', arrow: 'down' },
      ],
    };
    render(<DefinitionCellView cell={stacked} currentArrow={null} />);
    const root = screen.getByRole('gridcell');
    expect(root).toHaveAttribute('data-clue-count', '2');
    // Both clue texts present, in DOM order matching stack order.
    expect(screen.getByText('Astre nocturne')).toBeInTheDocument();
    expect(screen.getByText('Saison chaude')).toBeInTheDocument();
    // The wrapping group announces "deux définitions" before the clues.
    expect(screen.getByRole('group', { name: 'deux définitions' })).toBeInTheDocument();
    // Each clue announces its arrow direction with a French label.
    expect(screen.getByRole('group', { name: 'définition horizontale' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'définition verticale' })).toBeInTheDocument();
    // Both arrow glyphs are in the rendered output.
    expect(root.textContent).toContain('→');
    expect(root.textContent).toContain('↓');
  });

  it('clamps long single-clue text and exposes the full text in title', () => {
    const longClue: DefinitionCell = {
      kind: 'definition',
      position: { row: 0, col: 0 },
      clues: [
        {
          text: "Mammifère carnivore aquatique d'Amérique du Sud",
          arrow: 'right',
        },
      ],
    };
    render(<DefinitionCellView cell={longClue} currentArrow={null} />);
    expect(
      screen.getByTitle("Mammifère carnivore aquatique d'Amérique du Sud"),
    ).toBeInTheDocument();
    expect(screen.getByText('→')).toBeInTheDocument();
  });

  it('keeps both clues visible in stacked cells, each with its own title and arrow', () => {
    const stacked: DefinitionCell = {
      kind: 'definition',
      position: { row: 0, col: 0 },
      clues: [
        { text: 'Volatile à long cou', arrow: 'right' },
        { text: 'Tracer des mots', arrow: 'down' },
      ],
    };
    render(<DefinitionCellView cell={stacked} currentArrow={null} />);
    // Both clue texts must be in the DOM (not just the first one).
    expect(screen.getByText('Volatile à long cou')).toBeInTheDocument();
    expect(screen.getByText('Tracer des mots')).toBeInTheDocument();
    // Each stacked clue exposes its full text via title + has its own arrow.
    expect(screen.getByTitle('Volatile à long cou')).toBeInTheDocument();
    expect(screen.getByTitle('Tracer des mots')).toBeInTheDocument();
    expect(screen.getByText('→')).toBeInTheDocument();
    expect(screen.getByText('↓')).toBeInTheDocument();
  });
});
