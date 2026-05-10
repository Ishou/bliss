import { render, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import type { Cell, Puzzle } from '@/domain';
import { Grid } from '@/ui/components/grid';

// 5×4 grid matching the harness in grid-input.test.tsx. Cell (1,1)-(1,4)
// are the letter cells of across-2; cells (1,2),(2,2),(3,2) are the letters
// of down-1. Good enough to exercise Home / End on a 4-cell across word.
//
// Layout — D = definition, B = block, X = letter:
//   D→  X   D↓  X   X
//   D→  X   X   X   X   ← across-2: (1,1)..(1,4)
//   X   X   X   X   X
//   X   B   X   X   X
const L = (row: number, col: number): Cell =>
  ({ kind: 'letter', position: { row, col }, entry: '' });

const TEST_PUZZLE: Puzzle = {
  id: 'test', title: 'test', language: 'fr', width: 5, height: 4, hintsAllowed: 3,
  cells: [
    { kind: 'definition', position: { row: 0, col: 0 }, clues: [{ text: 'across-1', arrow: 'right' }] },
    L(0, 1),
    { kind: 'definition', position: { row: 0, col: 2 }, clues: [{ text: 'down-1', arrow: 'down' }] },
    L(0, 3), L(0, 4),
    { kind: 'definition', position: { row: 1, col: 0 }, clues: [{ text: 'across-2', arrow: 'right' }] },
    L(1, 1), L(1, 2), L(1, 3), L(1, 4),
    L(2, 0), L(2, 1), L(2, 2), L(2, 3), L(2, 4),
    L(3, 0),
    { kind: 'block', position: { row: 3, col: 1 } },
    L(3, 2), L(3, 3), L(3, 4),
  ],
};

const inputAt = (root: HTMLElement, row: number, col: number) =>
  root.querySelector<HTMLInputElement>(`[data-cell-kind="letter"][data-row="${row}"][data-col="${col}"]`);

// jsdom does not auto-focus on fireEvent.click — mirror the click helper
// from grid-input.test.tsx.
const click = (el: HTMLElement) => { el.focus(); fireEvent.click(el); };

describe('Grid keyboard interactions — Home / End word-boundary keys', () => {
  // Home on a mid-word cell jumps to the first cell of the current word.
  it('Home moves focus to the first cell of the current word', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Focus (1,3) — third letter of across-2 (cells (1,1)..(1,4)).
    const mid = inputAt(container, 1, 3)!;
    click(mid);
    expect(document.activeElement).toBe(mid);
    // Home — should move to (1,1).
    const notDefaulted = fireEvent.keyDown(mid, { key: 'Home' });
    expect(notDefaulted).toBe(false); // preventDefault called
    expect(document.activeElement).toBe(inputAt(container, 1, 1));
  });

  // End on a mid-word cell jumps to the last cell of the current word.
  it('End moves focus to the last cell of the current word', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Focus (1,3) — third letter of across-2 (cells (1,1)..(1,4)).
    // Using (1,3) rather than (1,2) because (1,2) is the first letter of
    // down-1; the starting-clue preference would set direction='down' and
    // End would jump to (3,2) instead of (1,4).
    const mid = inputAt(container, 1, 3)!;
    click(mid);
    expect(document.activeElement).toBe(mid);
    // End — should move to (1,4).
    const notDefaulted = fireEvent.keyDown(mid, { key: 'End' });
    expect(notDefaulted).toBe(false); // preventDefault called
    expect(document.activeElement).toBe(inputAt(container, 1, 4));
  });

  // Home / End are no-ops (no crash) when no cell is focused.
  it('Home and End are no-ops when no cell is focused', () => {
    const { container } = render(<Grid puzzle={TEST_PUZZLE} />);
    // Fire on an arbitrary input without focusing it first — `focused` is null.
    expect(() => {
      fireEvent.keyDown(inputAt(container, 2, 0)!, { key: 'Home' });
      fireEvent.keyDown(inputAt(container, 2, 0)!, { key: 'End' });
    }).not.toThrow();
    // Document focus should still be on body (nothing focused in grid).
    expect(document.activeElement).toBe(document.body);
  });
});
