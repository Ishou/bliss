import { render, screen, act } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Timer } from '@/ui/components/lobby/Timer';

// Fake-timers contract: `vi.useFakeTimers()` mocks both `setInterval`
// AND `Date.now()` (the latter via the `Date` mock that ships with
// Vitest's modern fake-timers preset). `vi.setSystemTime(...)` advances
// the mocked clock; `vi.advanceTimersByTime(ms)` flushes interval ticks
// AND moves the system time forward by `ms` ms in lockstep.

const ISO_START = '2026-05-02T10:00:00.000Z';
const START_MS = new Date(ISO_START).getTime();

describe('Timer', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(START_MS));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders 00:00 immediately at start', () => {
    render(<Timer startedAt={ISO_START} />);
    expect(screen.getByTestId('game-timer')).toHaveTextContent('00:00');
  });

  it('advances every second while not frozen', () => {
    render(<Timer startedAt={ISO_START} />);
    expect(screen.getByTestId('game-timer')).toHaveTextContent('00:00');

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.getByTestId('game-timer')).toHaveTextContent('00:01');

    act(() => {
      vi.advanceTimersByTime(4000);
    });
    expect(screen.getByTestId('game-timer')).toHaveTextContent('00:05');

    act(() => {
      vi.advanceTimersByTime(55_000);
    });
    expect(screen.getByTestId('game-timer')).toHaveTextContent('01:00');
  });

  it('switches to HH:MM:SS past 1 hour', () => {
    render(<Timer startedAt={ISO_START} />);
    act(() => {
      vi.advanceTimersByTime(60 * 60 * 1000 + 23 * 60 * 1000 + 45 * 1000);
    });
    expect(screen.getByTestId('game-timer')).toHaveTextContent('01:23:45');
  });

  it('clamps a negative skew (server clock ahead) to 00:00', () => {
    // System time is 2 seconds BEFORE startedAt.
    vi.setSystemTime(new Date(START_MS - 2000));
    render(<Timer startedAt={ISO_START} />);
    expect(screen.getByTestId('game-timer')).toHaveTextContent('00:00');
  });

  it('renders frozen value and does not tick when frozenAtMs is set', () => {
    render(<Timer startedAt={ISO_START} frozenAtMs={125_000} />);
    expect(screen.getByTestId('game-timer')).toHaveTextContent('02:05');

    act(() => {
      vi.advanceTimersByTime(10_000);
    });
    // Still frozen — no ticks, same value.
    expect(screen.getByTestId('game-timer')).toHaveTextContent('02:05');
  });

  it('cleans up the interval on unmount (no setInterval leak)', () => {
    const { unmount } = render(<Timer startedAt={ISO_START} />);
    // Vitest tracks how many timers are pending; after unmount the
    // interval must be cleared so the count drops to 0.
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('exposes role="timer" with a French aria-label', () => {
    render(<Timer startedAt={ISO_START} />);
    const timer = screen.getByRole('timer');
    expect(timer).toHaveAttribute('aria-label', 'temps écoulé');
  });
});
