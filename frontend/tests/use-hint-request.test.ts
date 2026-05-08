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

  it('writes hintsRemaining and lastResult on a successful request', async () => {
    const solver = makeSolver();
    (solver.requestHint as ReturnType<typeof vi.fn>).mockResolvedValue({
      word: 'forêt',
      exists: true,
      hintsRemaining: 2,
    });
    const { result } = renderHook(() =>
      useHintRequest(PUZZLE_ID, 3, solver),
    );

    await act(async () => {
      result.current.request('forêt');
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(result.current.hintsRemaining).toBe(2);
    expect(result.current.lastResult).toEqual({ word: 'forêt', exists: true });
    expect(result.current.exhausted).toBe(false);
    expect(result.current.errorMessage).toBeNull();
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
      result.current.request('forêt');
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(result.current.exhausted).toBe(true);
    expect(result.current.hintsRemaining).toBe(0);
    expect(result.current.errorMessage).toBe('Indices épuisés');
  });

  it('clears lastResult after the linger interval', async () => {
    vi.useFakeTimers();
    const solver = makeSolver();
    (solver.requestHint as ReturnType<typeof vi.fn>).mockResolvedValue({
      word: 'arc',
      exists: false,
      hintsRemaining: 2,
    });
    const { result } = renderHook(() =>
      useHintRequest(PUZZLE_ID, 3, solver),
    );

    await act(async () => {
      result.current.request('arc');
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(result.current.lastResult).not.toBeNull();

    await act(async () => {
      vi.advanceTimersByTime(4_000);
    });
    expect(result.current.lastResult).toBeNull();
  });

  it('skips short words without spending a credit', async () => {
    const solver = makeSolver();
    const { result } = renderHook(() =>
      useHintRequest(PUZZLE_ID, 3, solver),
    );

    await act(async () => {
      result.current.request('a');
    });

    expect(solver.requestHint).not.toHaveBeenCalled();
    expect(result.current.hintsRemaining).toBe(3);
  });
});
