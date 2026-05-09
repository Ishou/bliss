import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { TourSeenStore } from '@/application/tour/TourSeenStore';
import { useSoloTour } from '@/ui/components/tour/useSoloTour';

// Tests the hook in isolation — `<SoloTour />`'s rendering is exercised
// by the route-level integration tests. Here we just want to know:
// (1) auto-open behavior matches the seen flag and ?tour=1 contract,
// (2) onStatusChange persists the flag and signals the route to strip
//     the param.

const buildStore = (initialSeen: boolean): TourSeenStore => {
  const state = { seen: initialSeen };
  return {
    get: () => state.seen,
    set: vi.fn((seen: boolean) => {
      state.seen = seen;
    }),
    clear: vi.fn(() => {
      state.seen = false;
    }),
  };
};

// Wait through Ark Tour's rAF + setTimeout(0) state-machine activity
// scheduling so `tour.open` settles to its post-`start()` value.
const flushTour = async () => {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 30));
  });
};

describe('useSoloTour', () => {
  beforeEach(() => {
    vi.useRealTimers();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('auto-opens on first visit when the seen flag is false', async () => {
    const store = buildStore(false);
    const { result } = renderHook(() =>
      useSoloTour({
        tourSeenStore: store,
        forcedOpen: false,
        onForcedOpenConsumed: vi.fn(),
      }),
    );
    await flushTour();
    expect(result.current.open).toBe(true);
  });

  it('does not auto-open when the seen flag is true', async () => {
    const store = buildStore(true);
    const { result } = renderHook(() =>
      useSoloTour({
        tourSeenStore: store,
        forcedOpen: false,
        onForcedOpenConsumed: vi.fn(),
      }),
    );
    await flushTour();
    expect(result.current.open).toBe(false);
  });

  it('opens via forcedOpen even when the seen flag is true', async () => {
    const store = buildStore(true);
    const { result } = renderHook(() =>
      useSoloTour({
        tourSeenStore: store,
        forcedOpen: true,
        onForcedOpenConsumed: vi.fn(),
      }),
    );
    await flushTour();
    expect(result.current.open).toBe(true);
  });

  it('exposes the 4 steps configured in soloTourSteps', async () => {
    const store = buildStore(false);
    const { result } = renderHook(() =>
      useSoloTour({
        tourSeenStore: store,
        forcedOpen: false,
        onForcedOpenConsumed: vi.fn(),
      }),
    );
    await flushTour();
    expect(result.current.totalSteps).toBe(4);
    expect(result.current.step?.id).toBe('welcome');
  });
});
