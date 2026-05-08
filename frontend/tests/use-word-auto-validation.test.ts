import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { useWordAutoValidation } from '@/ui/components/grid/useWordAutoValidation';
import type { Puzzle } from '@/domain';
import type { PuzzleSolver, ValidationResult } from '@/application';

// 4-letter across word at (0,1)..(0,4); the test mounts uncontrolled
// inputs the hook can read via `document.querySelector` — same DOM
// contract `usePuzzleValidation` exercises.
const puzzle: Puzzle = {
  id: 'auto-validate-puzzle',
  title: 't',
  language: 'fr',
  width: 5,
  height: 1,
  hintsAllowed: 3,
  cells: [
    {
      kind: 'definition',
      position: { row: 0, col: 0 },
      clues: [
        { text: 'demo', arrow: 'right' },
      ],
    },
    { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 2 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 3 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 4 }, entry: '' },
  ],
};

function mountInput(row: number, col: number, value: string) {
  const input = document.createElement('input');
  input.setAttribute('data-cell-kind', 'letter');
  input.setAttribute('data-row', String(row));
  input.setAttribute('data-col', String(col));
  input.value = value;
  document.body.appendChild(input);
  return input;
}

function makeSolver(result: ValidationResult): PuzzleSolver {
  return {
    validate: vi.fn().mockResolvedValue(result),
    requestHint: vi.fn().mockRejectedValue(new Error('not used here')),
  };
}

describe('useWordAutoValidation', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('locks every cell of the completed word when the server reports no incorrect cells in it', async () => {
    mountInput(0, 1, 'D');
    mountInput(0, 2, 'E');
    mountInput(0, 3, 'M');
    mountInput(0, 4, 'O');
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    const { result } = renderHook(() => useWordAutoValidation(puzzle, solver));

    await act(async () => {
      result.current.onCellFilled({ row: 0, col: 4 }, 'across');
    });
    await waitFor(() => expect(result.current.validated.size).toBeGreaterThan(0));

    expect(result.current.validated.has('0,1')).toBe(true);
    expect(result.current.validated.has('0,2')).toBe(true);
    expect(result.current.validated.has('0,3')).toBe(true);
    expect(result.current.validated.has('0,4')).toBe(true);
    expect(solver.validate).toHaveBeenCalledTimes(1);
  });

  it('does not lock when the server flags any cell of the word as incorrect', async () => {
    mountInput(0, 1, 'D');
    mountInput(0, 2, 'E');
    mountInput(0, 3, 'M');
    mountInput(0, 4, 'X');
    const solver = makeSolver({
      solved: false,
      incorrectCells: [{ row: 0, column: 4 }],
    });
    const { result } = renderHook(() => useWordAutoValidation(puzzle, solver));

    await act(async () => {
      result.current.onCellFilled({ row: 0, col: 4 }, 'across');
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(result.current.validated.size).toBe(0);
    expect(solver.validate).toHaveBeenCalledTimes(1);
  });

  it('does not call the solver when the word is not yet fully filled', () => {
    mountInput(0, 1, 'D');
    mountInput(0, 2, 'E');
    mountInput(0, 3, 'M');
    mountInput(0, 4, '');
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    const { result } = renderHook(() => useWordAutoValidation(puzzle, solver));

    act(() => {
      result.current.onCellFilled({ row: 0, col: 3 }, 'across');
    });

    expect(solver.validate).not.toHaveBeenCalled();
  });

  it('coalesces duplicate fills of the same word while a request is in flight', async () => {
    mountInput(0, 1, 'D');
    mountInput(0, 2, 'E');
    mountInput(0, 3, 'M');
    mountInput(0, 4, 'O');
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    const { result } = renderHook(() => useWordAutoValidation(puzzle, solver));

    // Two fast keystrokes on the same word should not double the
    // request — the second is dropped while the first is in flight.
    act(() => {
      result.current.onCellFilled({ row: 0, col: 4 }, 'across');
      result.current.onCellFilled({ row: 0, col: 4 }, 'across');
    });
    await waitFor(() =>
      expect((solver.validate as ReturnType<typeof vi.fn>).mock.calls.length).toBe(1),
    );
  });

  it('locks a perpendicular word that crosses an already-validated word', async () => {
    // 5×4 puzzle with two crossing words sharing (0,1):
    //   across (0,1)..(0,4) and down (0,1)..(3,1).
    // Reproduces the user-reported "validation does not happen when
    // crossing an already validated word": once the across word locks,
    // typing into the down word's last cell would hit the old
    // `validated.has(word[0])` short-circuit (because (0,1) is the
    // down word's first cell and is already in `validated` from the
    // across lock), and the hook silently skipped the new word.
    const crossingPuzzle: Puzzle = {
      id: 'crossing-puzzle',
      title: 't',
      language: 'fr',
      width: 5,
      height: 4,
      hintsAllowed: 3,
      cells: [
        {
          kind: 'definition',
          position: { row: 0, col: 0 },
          clues: [{ text: 'demo', arrow: 'right' }],
        },
        { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
        { kind: 'letter', position: { row: 0, col: 2 }, entry: '' },
        { kind: 'letter', position: { row: 0, col: 3 }, entry: '' },
        { kind: 'letter', position: { row: 0, col: 4 }, entry: '' },
        { kind: 'letter', position: { row: 1, col: 1 }, entry: '' },
        { kind: 'letter', position: { row: 2, col: 1 }, entry: '' },
        { kind: 'letter', position: { row: 3, col: 1 }, entry: '' },
      ],
    };
    mountInput(0, 1, 'D'); mountInput(0, 2, 'E'); mountInput(0, 3, 'M'); mountInput(0, 4, 'O');
    mountInput(1, 1, 'A'); mountInput(2, 1, 'R'); mountInput(3, 1, 'D');
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    const { result } = renderHook(() => useWordAutoValidation(crossingPuzzle, solver));

    // Step 1: lock the across word.
    await act(async () => { result.current.onCellFilled({ row: 0, col: 4 }, 'across'); });
    await waitFor(() => expect(result.current.validated.has('0,1')).toBe(true));

    // Step 2: complete the down word. (0,1) is already validated from
    // step 1; the new word's first cell is the crossing.
    await act(async () => { result.current.onCellFilled({ row: 3, col: 1 }, 'down'); });
    await waitFor(() => expect(result.current.validated.has('3,1')).toBe(true));

    expect(result.current.validated.has('1,1')).toBe(true);
    expect(result.current.validated.has('2,1')).toBe(true);
    expect(result.current.validated.has('3,1')).toBe(true);
  });

  it('resets validated set on puzzle reference change', async () => {
    mountInput(0, 1, 'D');
    mountInput(0, 2, 'E');
    mountInput(0, 3, 'M');
    mountInput(0, 4, 'O');
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    const { result, rerender } = renderHook(
      ({ p }: { p: Puzzle }) => useWordAutoValidation(p, solver),
      { initialProps: { p: puzzle } },
    );

    await act(async () => {
      result.current.onCellFilled({ row: 0, col: 4 }, 'across');
    });
    await waitFor(() => expect(result.current.validated.size).toBeGreaterThan(0));

    // Fresh puzzle reference -> validated set clears.
    rerender({ p: { ...puzzle } });
    expect(result.current.validated.size).toBe(0);
  });
});
