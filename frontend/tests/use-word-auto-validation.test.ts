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

  it('locks a 2-letter word ("AI" for "Forme du verbe avoir")', async () => {
    // The user reported typing "AI" for "Forme du verbe avoir" and
    // seeing no validation. Smallest possible word — the
    // `wordRange.length >= 2` gate is exactly the boundary case.
    const tinyPuzzle: Puzzle = {
      id: 'tiny-puzzle',
      title: 't',
      language: 'fr',
      width: 4,
      height: 1,
      hintsAllowed: 3,
      cells: [
        {
          kind: 'definition',
          position: { row: 0, col: 0 },
          clues: [{ text: 'Forme du verbe avoir', arrow: 'right' }],
        },
        { kind: 'letter', position: { row: 0, col: 1 }, entry: '' },
        { kind: 'letter', position: { row: 0, col: 2 }, entry: '' },
      ],
    };
    mountInput(0, 1, 'A');
    mountInput(0, 2, 'I');
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    const { result } = renderHook(() => useWordAutoValidation(tinyPuzzle, solver));

    // After typing the second letter the word is fully filled.
    await act(async () => { result.current.onCellFilled({ row: 0, col: 2 }, 'across'); });
    await waitFor(() => expect(result.current.validated.has('0,1')).toBe(true));

    expect(result.current.validated.has('0,2')).toBe(true);
    expect(solver.validate).toHaveBeenCalledTimes(1);
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

  it('rehydrates locked words from persisted entries on mount', async () => {
    // Persisted-letter rehydration: solo entries live in localStorage,
    // so a page reload (or a router invalidate that returns a fresh
    // Puzzle reference) re-paints the cells with `defaultValue` from
    // the entries — but the in-memory `validated` set is gone. Without
    // this rehydration the player sees their letters but every word is
    // editable again and the progress bar reads zero. The hook walks
    // every word against the persisted letters and POSTs once.
    const initialFilled = [
      { row: 0, column: 1, letter: 'D' },
      { row: 0, column: 2, letter: 'E' },
      { row: 0, column: 3, letter: 'M' },
      { row: 0, column: 4, letter: 'O' },
    ];
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    const { result } = renderHook(() =>
      useWordAutoValidation(puzzle, solver, initialFilled),
    );

    await waitFor(() =>
      expect(result.current.validated.size).toBe(4),
    );
    expect(result.current.validated.has('0,1')).toBe(true);
    expect(result.current.validated.has('0,4')).toBe(true);
    expect(solver.validate).toHaveBeenCalledTimes(1);
  });

  it('does not lock a rehydrated word the server flags as incorrect', async () => {
    const initialFilled = [
      { row: 0, column: 1, letter: 'D' },
      { row: 0, column: 2, letter: 'E' },
      { row: 0, column: 3, letter: 'M' },
      { row: 0, column: 4, letter: 'X' },
    ];
    const solver = makeSolver({
      solved: false,
      incorrectCells: [{ row: 0, column: 4 }],
    });
    const { result } = renderHook(() =>
      useWordAutoValidation(puzzle, solver, initialFilled),
    );

    await waitFor(() =>
      expect(solver.validate).toHaveBeenCalledTimes(1),
    );
    expect(result.current.validated.size).toBe(0);
  });

  it('does not POST when initialFilledCells is empty', () => {
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    renderHook(() => useWordAutoValidation(puzzle, solver, []));
    expect(solver.validate).not.toHaveBeenCalled();
  });

  // Bug 1 — the auto-validated set lives in React state and was lost on
  // route exit, so Accueil's "Grille du jour" progress (which reads
  // `loadLockedCells`) never saw it. The hook now fires `onWordValidated`
  // once per word that newly enters the `validated` set so the route
  // can persist via `soloEntriesStore.lockCell`.
  it('fires onWordValidated once per word that becomes locked (live fill)', async () => {
    mountInput(0, 1, 'D');
    mountInput(0, 2, 'E');
    mountInput(0, 3, 'M');
    mountInput(0, 4, 'O');
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    const onWordValidated = vi.fn();
    const { result } = renderHook(() =>
      useWordAutoValidation(puzzle, solver, undefined, onWordValidated),
    );
    await act(async () => {
      result.current.onCellFilled({ row: 0, col: 4 }, 'across');
    });
    await waitFor(() => expect(onWordValidated).toHaveBeenCalledTimes(1));
    const positions = onWordValidated.mock.calls[0]![0]!;
    expect(positions.map((p: { row: number; col: number }) => `${p.row},${p.col}`)).toEqual([
      '0,1',
      '0,2',
      '0,3',
      '0,4',
    ]);
  });

  it('fires onWordValidated on rehydration so legacy localStorage self-heals', async () => {
    const initialFilled = [
      { row: 0, column: 1, letter: 'D' },
      { row: 0, column: 2, letter: 'E' },
      { row: 0, column: 3, letter: 'M' },
      { row: 0, column: 4, letter: 'O' },
    ];
    const solver = makeSolver({ solved: false, incorrectCells: [] });
    const onWordValidated = vi.fn();
    renderHook(() =>
      useWordAutoValidation(puzzle, solver, initialFilled, onWordValidated),
    );
    await waitFor(() => expect(onWordValidated).toHaveBeenCalledTimes(1));
  });

  it('does not fire onWordValidated for an incorrect word', async () => {
    mountInput(0, 1, 'D');
    mountInput(0, 2, 'E');
    mountInput(0, 3, 'M');
    mountInput(0, 4, 'X');
    const solver = makeSolver({
      solved: false,
      incorrectCells: [{ row: 0, column: 4 }],
    });
    const onWordValidated = vi.fn();
    const { result } = renderHook(() =>
      useWordAutoValidation(puzzle, solver, undefined, onWordValidated),
    );
    await act(async () => {
      result.current.onCellFilled({ row: 0, col: 4 }, 'across');
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(onWordValidated).not.toHaveBeenCalled();
  });
});
