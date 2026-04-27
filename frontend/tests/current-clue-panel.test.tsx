import { render, fireEvent, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import type { Cell, Puzzle } from '@/domain';
import { Grid } from '@/ui/components/grid';
import { CurrentCluePanel } from '@/ui/components/grid/CurrentCluePanel';
import type { Clue } from '@/ui/components/grid/useGridNavigation';

// 5×4 grid mirroring tests/grid-input.test.tsx so focus + clue lookup
// behave identically. Cell (1,1) sits on `across-2`; cell (1,2) is the
// intersection of `across-2` and `down-1`.
const L = (row: number, col: number, answer: string): Cell =>
  ({ kind: 'letter', position: { row, col }, answer, entry: '' });

const TEST_PUZZLE: Puzzle = {
  id: 'test', title: 'test', language: 'fr', width: 5, height: 4,
  cells: [
    { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'across-1', arrow: 'right' }] },
    L(0, 1, 'A'),
    { kind: 'definition', position: { row: 0, col: 2 }, clues: [{ text: 'down-1', arrow: 'down' }] },
    L(0, 3, 'B'), L(0, 4, 'C'),
    { kind: 'definition', position: { row: 1, col: 0 }, clues: [{ text: 'across-2', arrow: 'right' }] },
    L(1, 1, 'D'), L(1, 2, 'E'), L(1, 3, 'F'), L(1, 4, 'G'),
    L(2, 0, 'H'), L(2, 1, 'I'), L(2, 2, 'J'), L(2, 3, 'K'), L(2, 4, 'L'),
    L(3, 0, 'M'),
    { kind: 'block', position: { row: 3, col: 1 } },
    L(3, 2, 'N'), L(3, 3, 'O'), L(3, 4, 'P'),
  ],
};

const inputAt = (root: HTMLElement, row: number, col: number) =>
  root.querySelector<HTMLInputElement>(`[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`);
const click = (el: HTMLElement) => { fireEvent.pointerDown(el); fireEvent.focus(el); };

describe('CurrentCluePanel (standalone)', () => {
  it('shows a placeholder when no clue is selected', () => {
    render(<CurrentCluePanel clue={null} />);
    expect(
      screen.getByText(/sélectionnez une case pour afficher la définition/i),
    ).toBeInTheDocument();
  });

  it('renders clue text in full plus the arrow direction glyph', () => {
    const clue: Clue = {
      definition: {
        kind: 'definition', position: { row: 0, col: 0 },
        clues: [{ text: 'Astre nocturne', arrow: 'right' }],
      },
      clue: { text: 'Astre nocturne', arrow: 'right' },
      direction: 'across',
      cells: [],
    };
    render(<CurrentCluePanel clue={clue} />);
    expect(screen.getByText('Astre nocturne')).toBeInTheDocument();
    expect(screen.getByLabelText('définition horizontale')).toBeInTheDocument();
  });
});

describe('CurrentCluePanel (wired into Grid)', () => {
  it('updates when a letter cell is focused', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Initial render: panel shows placeholder.
    expect(
      screen.getByText(/sélectionnez une case pour afficher la définition/i),
    ).toBeInTheDocument();
    // Focus a cell on `across-2`. Panel reflects the active across clue.
    const target = inputAt(container, 1, 1)!;
    click(target);
    const panel = screen.getByTestId('current-clue-panel');
    expect(panel).toHaveTextContent('across-2');
    expect(panel.querySelector('[aria-label="définition horizontale"]')).not.toBeNull();
  });
});
