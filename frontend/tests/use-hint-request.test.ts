import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useHintRequest } from '@/ui/components/grid/useHintRequest';
import { HintRequestError, type PuzzleSolver } from '@/application';

const PUZZLE_ID = 'puzzle-x';

function makeSolver(): PuzzleSolver {
  return {
    validate: vi.fn().mockRejectedValue(new Error('not used here')),
    requestHint: vi.fn(),
  };
}

describe('useHintRequest', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('writes hintsRemaining and lastResult on a successful reveal', async () => {
    const solver = makeSolver();
    (solver.requestHint as ReturnType<typeof vi.fn>).mockResolvedValue({
      row: 3,
      column: 5,
      letter: 'P',
      hintsRemaining: 2,
    });
    const { result } = renderHook(() =>
      useHintRequest(PUZZLE_ID, 3, solver),
    );

    await act(async () => {
      result.current.request(3, 5);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(result.current.hintsRemaining).toBe(2);
    expect(result.current.lastResult).toEqual({ row: 3, column: 5, letter: 'P' });
    expect(result.current.exhausted).toBe(false);
    expect(result.current.errorMessage).toBeNull();
  });

  it('fires the onReveal callback so the parent can apply the letter', async () => {
    const solver = makeSolver();
    (solver.requestHint as ReturnType<typeof vi.fn>).mockResolvedValue({
      row: 3,
      column: 5,
      letter: 'P',
      hintsRemaining: 2,
    });
    const onReveal = vi.fn();
    const { result } = renderHook(() =>
      useHintRequest(PUZZLE_ID, 3, solver, onReveal),
    );

    await act(async () => {
      result.current.request(3, 5);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onReveal).toHaveBeenCalledWith(3, 5, 'P');
  });

  it('flips exhausted on HintRequestError(budget-exhausted)', async () => {
    const solver = makeSolver();
    (solver.requestHint as ReturnType<typeof vi.fn>).mockRejectedValue(
      new HintRequestError('budget-exhausted', 0, 'Indices épuisés'),
    );
    const { result } = renderHook(() =>
      useHintRequest(PUZZLE_ID, 3, solver),
    );

    await act(async () => {
      result.current.request(3, 5);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(result.current.exhausted).toBe(true);
    expect(result.current.hintsRemaining).toBe(0);
    expect(result.current.errorMessage).toBe('Indices épuisés');
  });

  it('treats invalid-coord as a silent no-op without surfacing an error', async () => {
    const solver = makeSolver();
    (solver.requestHint as ReturnType<typeof vi.fn>).mockRejectedValue(
      new HintRequestError('invalid-coord', null, 'out of bounds'),
    );
    const { result } = renderHook(() =>
      useHintRequest(PUZZLE_ID, 3, solver),
    );

    await act(async () => {
      result.current.request(99, 99);
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(result.current.errorMessage).toBeNull();
    expect(result.current.exhausted).toBe(false);
    expect(result.current.hintsRemaining).toBe(3);
  });

  it('clears lastResult after the linger interval', async () => {
    vi.useFakeTimers();
    const solver = makeSolver();
    (solver.requestHint as ReturnType<typeof vi.fn>).mockResolvedValue({
      row: 0,
      column: 0,
      letter: 'A',
      hintsRemaining: 2,
    });
    const { result } = renderHook(() =>
      useHintRequest(PUZZLE_ID, 3, solver),
    );

    await act(async () => {
      result.current.request(0, 0);
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(result.current.lastResult).not.toBeNull();

    await act(async () => {
      vi.advanceTimersByTime(4_000);
    });
    expect(result.current.lastResult).toBeNull();
  });
});
