import { renderHook } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useResumeBlurOnPwa } from '@/ui/components/keyboard/useResumeBlurOnPwa';

function setVisibilityState(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => state,
  });
}

describe('useResumeBlurOnPwa', () => {
  const addSpy = vi.spyOn(document, 'addEventListener');
  const removeSpy = vi.spyOn(document, 'removeEventListener');

  beforeEach(() => {
    addSpy.mockClear();
    removeSpy.mockClear();
    setVisibilityState('visible');
  });

  afterEach(() => {
    setVisibilityState('visible');
  });

  it('does not attach a listener when touchPrimary is false', () => {
    renderHook(() => useResumeBlurOnPwa(false));
    const visibilityAdds = addSpy.mock.calls.filter(
      ([type]) => type === 'visibilitychange',
    );
    expect(visibilityAdds).toHaveLength(0);
  });

  it('blurs the active element on visibilitychange to hidden', () => {
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    const blurSpy = vi.spyOn(input, 'blur');
    expect(document.activeElement).toBe(input);

    renderHook(() => useResumeBlurOnPwa(true));

    setVisibilityState('hidden');
    document.dispatchEvent(new Event('visibilitychange'));

    expect(blurSpy).toHaveBeenCalledTimes(1);
    document.body.removeChild(input);
  });

  it('does nothing when visibility transitions back to visible', () => {
    const input = document.createElement('input');
    document.body.appendChild(input);
    input.focus();
    const blurSpy = vi.spyOn(input, 'blur');

    renderHook(() => useResumeBlurOnPwa(true));

    setVisibilityState('visible');
    document.dispatchEvent(new Event('visibilitychange'));

    expect(blurSpy).not.toHaveBeenCalled();
    document.body.removeChild(input);
  });

  it('removes the listener on unmount', () => {
    const { unmount } = renderHook(() => useResumeBlurOnPwa(true));
    const added = addSpy.mock.calls.filter(([type]) => type === 'visibilitychange');
    expect(added.length).toBeGreaterThanOrEqual(1);

    unmount();

    const removed = removeSpy.mock.calls.filter(
      ([type]) => type === 'visibilitychange',
    );
    expect(removed.length).toBe(added.length);
  });
});
