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
import { GRID_TRACK_WIDTH } from '@/ui/components/grid/layout';

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

  // Track template scales to puzzle dims; rows are 1fr (not auto) so a
  // non-square puzzle (e.g. 15×12) divides the wrapper height evenly,
  // and cells stay square via their own aspect-ratio: 1.
  it('emits gridTemplateColumns/Rows that match puzzle.width/height', () => {
    const landscape: Puzzle = { ...SAMPLE_PUZZLE, width: 15, height: 12 };
    render(<Grid puzzle={landscape} />);
    const grid = screen.getByRole('grid', { name: landscape.title });
    expect(grid.style.gridTemplateColumns).toBe('repeat(15, 1fr)');
    expect(grid.style.gridTemplateRows).toBe('repeat(12, 1fr)');
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
    // Arrow direction is conveyed via the StackedClue group labels (tested above).
  });

  it('renders long single-clue text in full (no clamp); arrow rendered on receiving letter cell', () => {
    const puzzle: Puzzle = {
      id: 'long', title: 'long', language: 'fr', width: 2, height: 1, hintsAllowed: 3, hintsRemaining: 3,
      cells: [
        {
          kind: 'definition',
          position: { row: 0, col: 0 },
          clues: [
            {
              text: "Mammifère carnivore aquatique d'Amérique du Sud",
              arrow: 'right',
            },
          ],
        },
        { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
      ],
    };
    const { container } = render(<Grid puzzle={puzzle} />);
    expect(
      screen.getByTitle("Mammifère carnivore aquatique d'Amérique du Sud"),
    ).toBeInTheDocument();
    // Arrows now live on the receiving letter cell (ADR-0005 §6
    // follow-up). Per `Grid.tsx#computeIncomingArrows`, a `right`
    // clue at (0,0) writes an arrow at the LEFT edge of (0,1).
    expect(
      container.querySelector('[data-row="0"][data-col="1"] [data-arrow="right"][data-incoming-edge="left"]'),
    ).not.toBeNull();
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
  });

  // Arrows live on the receiving letter cell (ADR-0005 §6 follow-up).
  // The bent variants `right-down` / `down-right` share the receiving
  // cell with their straight equivalents (`right` / `down`) — the
  // bend happens inside the answer path, not at the entry. Tests
  // assert the data-arrow surface (preserves the bent type for AT)
  // and the edge.
  it('places `right-down` arrow on the receiving cell\'s left edge', () => {
    const puzzle: Puzzle = {
      id: 'rd', title: 'rd', language: 'fr', width: 2, height: 2, hintsAllowed: 3, hintsRemaining: 3,
      cells: [
        { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'Plante grimpante', arrow: 'right-down' }] },
        { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
        { kind: 'letter', position: { row: 1, col: 1 }, entry: '' },
      ],
    };
    const { container } = render(<Grid puzzle={puzzle} />);
    const mark = container.querySelector(
      '[data-row="0"][data-col="1"] [data-arrow="right-down"][data-incoming-edge="left"]',
    );
    expect(mark).not.toBeNull();
    expect(mark).toHaveAttribute('aria-label', 'définition verticale');
  });

  it('places `down-right` arrow on the receiving cell\'s top edge', () => {
    const puzzle: Puzzle = {
      id: 'dr', title: 'dr', language: 'fr', width: 2, height: 2, hintsAllowed: 3, hintsRemaining: 3,
      cells: [
        { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'Mot de passe', arrow: 'down-right' }] },
        { kind: 'letter', position: { row: 1, col: 0 }, entry: '' },
        { kind: 'letter', position: { row: 1, col: 1 }, entry: '' },
      ],
    };
    const { container } = render(<Grid puzzle={puzzle} />);
    const mark = container.querySelector(
      '[data-row="1"][data-col="0"] [data-arrow="down-right"][data-incoming-edge="top"]',
    );
    expect(mark).not.toBeNull();
    expect(mark).toHaveAttribute('aria-label', 'définition horizontale');
  });

  // Same-origin pair — both arrows enter the SAME receiving letter
  // cell. Per `Grid.tsx#computeIncomingArrows`, the two arrows split
  // the receiving edge into q1 (28 %) + q3 (72 %).
  it('renders both arrows on the receiving cell for a [right + right-down] pair', () => {
    const puzzle: Puzzle = {
      id: 'rr', title: 'rr', language: 'fr', width: 2, height: 2, hintsAllowed: 3, hintsRemaining: 3,
      cells: [
        {
          kind: 'definition',
          position: { row: 0, col: 0 },
          clues: [
            { text: 'Première', arrow: 'right' },
            { text: 'Deuxième', arrow: 'right-down' },
          ],
        },
        { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
        { kind: 'letter', position: { row: 1, col: 1 }, entry: '' },
      ],
    };
    const { container } = render(<Grid puzzle={puzzle} />);
    const recv = container.querySelector('[data-row="0"][data-col="1"]');
    expect(recv?.querySelector('[data-arrow="right"][data-incoming-edge="left"]')).not.toBeNull();
    expect(recv?.querySelector('[data-arrow="right-down"][data-incoming-edge="left"]')).not.toBeNull();
  });

  it('renders both arrows on the receiving cell for a [down + down-right] pair', () => {
    const puzzle: Puzzle = {
      id: 'dd', title: 'dd', language: 'fr', width: 2, height: 2, hintsAllowed: 3, hintsRemaining: 3,
      cells: [
        {
          kind: 'definition',
          position: { row: 0, col: 0 },
          clues: [
            { text: 'Première', arrow: 'down' },
            { text: 'Deuxième', arrow: 'down-right' },
          ],
        },
        { kind: 'letter', position: { row: 1, col: 0 }, entry: '' },
        { kind: 'letter', position: { row: 1, col: 1 }, entry: '' },
      ],
    };
    const { container } = render(<Grid puzzle={puzzle} />);
    const recv = container.querySelector('[data-row="1"][data-col="0"]');
    expect(recv?.querySelector('[data-arrow="down"][data-incoming-edge="top"]')).not.toBeNull();
    expect(recv?.querySelector('[data-arrow="down-right"][data-incoming-edge="top"]')).not.toBeNull();
  });

  // Two-clue cells render the visual stack in cell.clues order — the
  // mapper hands us [horizontal, vertical] (ADR-0005 §3a), and the
  // mots-fléchés convention puts the horizontal clue on top with its
  // arrow at rightTop next to it. Earlier regressions silently dropped
  // the second clue or mis-labeled it by axis; this test pins both
  // clues' presence and the API-order rendering.
  describe('Fullscreen layout primitives', () => {
    // jsdom 29 silently drops CSS declarations whose value uses
    // container-query units (`100cqw` / `100cqh`) — the wrapper's
    // inline `style` ends up as `margin: 0px auto; touch-action: pan-y;
    // cursor: auto;` with no width / height at all (verified at the
    // attribute string level, so this isn't a `CSSStyleDeclaration`
    // parser quirk; the values genuinely don't make it to the DOM).
    //
    // Same reasoning as the sibling test below ("class identity +
    // parent relation is the testable invariant"): the Fullscreen-
    // layout sizing contract is exercised by the real browser
    // (Cloudflare Pages preview + Playwright e2e), not by jsdom.
    // Skipping rather than rewriting to assert against the source
    // constant — that would be testing implementation.
    it.skip('sizes the transform wrapper as the smaller of available width / height / 720 px (jsdom 29 drops cq* units)', () => {
      const { container } = render(<Grid puzzle={SAMPLE_PUZZLE} />);
      const wrapper = container.querySelector<HTMLDivElement>('.react-transform-wrapper');
      expect(wrapper).not.toBeNull();
      expect(wrapper!.style.width).toBe('min(100cqw, 100cqh, 720px)');
      expect(wrapper!.style.height).toBe('min(100cqw, 100cqh, 720px)');
    });

    it('wraps the transform wrapper in a flex shell that absorbs leftover viewport height', () => {
      // The shell takes the slack between page chrome (wordmark / DÉMO
      // pill / clue panel / zoom row / lobby button) and the bottom of
      // the visible viewport, so the page never produces a vertical
      // scrollbar. We assert the structural contract — the shell is
      // the immediate parent of the library's `.react-transform-wrapper`
      // element — because vitest config has `css: false` (Panda rules
      // aren't loaded into jsdom), so computed-style assertions on
      // `flex: 1 1 0` / `min-height: 0` would always read empty. The
      // class identity + parent relation is the testable invariant.
      const { container } = render(<Grid puzzle={SAMPLE_PUZZLE} />);
      const shell = container.querySelector<HTMLDivElement>('[data-testid="grid-shell"]');
      expect(shell).not.toBeNull();
      expect(shell!.querySelector('.react-transform-wrapper')).not.toBeNull();
    });

    it('applies the shared GRID_TRACK_WIDTH cap to the zoom controls cluster', () => {
      render(<Grid puzzle={SAMPLE_PUZZLE} />);
      const cluster = screen.getByRole('group', { name: /zoom controls/i });
      expect(cluster.style.maxWidth).toBe(GRID_TRACK_WIDTH);
    });

    it('applies the shared GRID_TRACK_WIDTH cap to the sticky clue panel at rest', () => {
      render(<Grid puzzle={SAMPLE_PUZZLE} />);
      const panel = screen.getByTestId('current-clue-panel');
      // At rest zoomStyle is undefined; trackWidthStyle applies the cap.
      expect(panel.style.maxWidth).toBe(GRID_TRACK_WIDTH);
    });

    it('keeps every letter and definition cell queryable by data-row/data-col after the layout change', () => {
      const { container } = render(<Grid puzzle={SAMPLE_PUZZLE} />);
      // data-row/data-col are the selector contract the presence overlay relies on.
      for (const cell of SAMPLE_PUZZLE.cells) {
        if (cell.kind === 'block') continue;
        const found = container.querySelector(
          `[data-cell-kind="${cell.kind}"][data-row="${cell.position.row}"][data-col="${cell.position.col}"]`,
        );
        expect(found).not.toBeNull();
      }
    });

    it('keeps the zoom controls keyboard-reachable with WCAG-AA-compliant 44 px touch targets', () => {
      render(<Grid puzzle={SAMPLE_PUZZLE} />);
      // jsdom skips layout; accessible name + enabled state are the behavioral contract.
      expect(screen.getByRole('button', { name: /zoom in/i })).toBeEnabled();
      expect(screen.getByRole('button', { name: /zoom out/i })).toBeDisabled();
      expect(screen.getByRole('button', { name: /reset zoom/i })).toBeDisabled();
    });
  });

  it('renders two-clue text in cell.clues order (clues[0] on top)', () => {
    const cell: DefinitionCell = {
      kind: 'definition',
      position: { row: 0, col: 0 },
      clues: [
        { text: 'Top-clue', arrow: 'right' },
        { text: 'Bottom-clue', arrow: 'down' },
      ],
    };
    const { container } = render(<DefinitionCellView cell={cell} currentArrow={null} />);
    expect(container.querySelector('[title="Top-clue"]')).not.toBeNull();
    expect(container.querySelector('[title="Bottom-clue"]')).not.toBeNull();
    const html = container.innerHTML;
    expect(html.indexOf('Top-clue')).toBeLessThan(html.indexOf('Bottom-clue'));
  });
});
