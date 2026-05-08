import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { usePuzzleValidation } from '@/ui/components/grid/usePuzzleValidation';
import type { Puzzle } from '@/domain';
import type { PuzzleSolver, ValidationResult } from '@/application';

// Minimal puzzle: three letter cells in a row, one block. The hook
// reads cell values directly from the DOM via the `data-cell-kind`
// selector — we mount real `<input>` elements per test.
const puzzle: Puzzle = {
  id: 'test-puzzle',
  title: 't',
  language: 'fr',
  width: 4,
  height: 1,
  hintsAllowed: 3,
  cells: [
    { kind: 'block', position: { row: 0, col: 0 } },
    { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 2 }, entry: '' },
    { kind: 'letter', position: { row: 0, col: 3 }, entry: '' },
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

describe('usePuzzleValidation', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });
  afterEach(() => {
    document.body.innerHTML = '';
    vi.useRealTimers();
  });

  it('sends only filled cells to the solver, normalized to A–Z', async () => {
    mountInput(0, 1, 'é');
    mountInput(0, 2, '');
    mountInput(0, 3, 'B');
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    const { result } = renderHook(() => usePuzzleValidation(puzzle, solver));

    await act(async () => {
      result.current.verify();
    });

    expect(solver.validate).toHaveBeenCalledWith('test-puzzle', [
      { row: 0, column: 1, letter: 'E' },
      { row: 0, column: 3, letter: 'B' },
    ]);
  });

  it('marks every letter cell validated when the server reports solved', async () => {
    mountInput(0, 1, 'A');
    mountInput(0, 2, 'B');
    mountInput(0, 3, 'C');
    const solver = makeSolver({ solved: true, incorrectCells: [] });
    const { result } = renderHook(() => usePuzzleValidation(puzzle, solver));

    await act(async () => {
      result.current.verify();
    });

    expect(result.current.validated.size).toBe(3);
    expect(result.current.validated.has('0,1')).toBe(true);
    expect(result.current.validated.has('0,2')).toBe(true);
    expect(result.current.validated.has('0,3')).toBe(true);
    expect(result.current.announce).toBe('Grille terminée');
  });

  it('flags incorrect cells and clears them after the shake interval', async () => {
    vi.useFakeTimers();
    mountInput(0, 1, 'A');
    mountInput(0, 2, 'X');
    mountInput(0, 3, 'C');
    const solver = makeSolver({
      solved: false,
      incorrectCells: [{ row: 0, column: 2 }],
    });
    const { result } = renderHook(() => usePuzzleValidation(puzzle, solver));

    await act(async () => {
      result.current.verify();
      // flush the validate Promise.
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(result.current.errors.has('0,2')).toBe(true);
    // The other typed-correctly cells are now marked validated alongside
    // the error, instead of staying neutral — so a partial-grid Vérifier
    // both shakes the wrong cells AND locks the right ones.
    expect(result.current.validated.has('0,1')).toBe(true);
    expect(result.current.validated.has('0,3')).toBe(true);
    expect(result.current.validated.has('0,2')).toBe(false);

    await act(async () => {
      vi.advanceTimersByTime(250);
    });

    expect(result.current.errors.size).toBe(0);
    // Validation set persists past the error-shake interval — locks are
    // permanent, errors are transient.
    expect(result.current.validated.has('0,1')).toBe(true);
  });

  it('locks the correctly-typed cells in green even when the rest of the grid is wrong', async () => {
    // The user-reported "Vérifier shakes the whole grid except the word I
    // typed correctly, but that word is still not green". The grid validate
    // endpoint reports every wrong-letter AND every unfilled letter cell as
    // `incorrectCells`. Cells absent from that list are correct — Vérifier
    // must mark them validated regardless of whether the WHOLE grid is solved.
    mountInput(0, 1, 'A'); // correct
    mountInput(0, 2, 'B'); // correct
    mountInput(0, 3, 'X'); // wrong
    const solver = makeSolver({
      solved: false,
      incorrectCells: [{ row: 0, column: 3 }],
    });
    const { result } = renderHook(() => usePuzzleValidation(puzzle, solver));

    await act(async () => {
      result.current.verify();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(result.current.validated.has('0,1')).toBe(true);
    expect(result.current.validated.has('0,2')).toBe(true);
    expect(result.current.validated.has('0,3')).toBe(false);
    expect(result.current.errors.has('0,3')).toBe(true);
  });

  it('announces an unrecoverable failure on a network error', async () => {
    mountInput(0, 1, 'A');
    const solver: PuzzleSolver = {
      validate: vi.fn().mockRejectedValue(new Error('boom')),
      requestHint: vi.fn(),
    };
    const { result } = renderHook(() => usePuzzleValidation(puzzle, solver));

    await act(async () => {
      result.current.verify();
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(result.current.announce).toBe('Vérification impossible');
  });
});
