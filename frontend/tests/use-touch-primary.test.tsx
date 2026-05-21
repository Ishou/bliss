import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { useTouchPrimary } from '@/ui/components/keyboard/useTouchPrimary';

type Listener = (ev: MediaQueryListEvent) => void;

function mockMatchMedia(initial: boolean) {
  const listeners = new Set<Listener>();
  const mql = {
    matches: initial,
    media: '(any-pointer: coarse) and (any-hover: none)',
    onchange: null,
    addEventListener: (_type: string, l: EventListenerOrEventListenerObject) =>
      listeners.add(l as Listener),
    removeEventListener: (_type: string, l: EventListenerOrEventListenerObject) =>
      listeners.delete(l as Listener),
    addListener: () => undefined,
    removeListener: () => undefined,
    dispatchEvent: () => true,
  } as unknown as MediaQueryList;
  window.matchMedia = vi.fn().mockReturnValue(mql);
  return {
    mql,
    emit: (matches: boolean) => {
      (mql as { matches: boolean }).matches = matches;
      listeners.forEach((l) => l({ matches } as MediaQueryListEvent));
    },
    listenerCount: () => listeners.size,
  };
}

describe('useTouchPrimary', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('returns the initial match value', () => {
    mockMatchMedia(true);
    const { result } = renderHook(() => useTouchPrimary());
    expect(result.current).toBe(true);
  });

  it('updates when the media query changes', () => {
    const m = mockMatchMedia(false);
    const { result } = renderHook(() => useTouchPrimary());
    expect(result.current).toBe(false);
    act(() => m.emit(true));
    expect(result.current).toBe(true);
  });

  it('removes the listener on unmount', () => {
    const m = mockMatchMedia(false);
    const { unmount } = renderHook(() => useTouchPrimary());
    expect(m.listenerCount()).toBe(1);
    unmount();
    expect(m.listenerCount()).toBe(0);
  });
});
