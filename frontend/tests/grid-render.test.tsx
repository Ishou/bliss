import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { SAMPLE_PUZZLE } from '@/domain';
import { Grid } from '@/ui/components/grid';

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
});
