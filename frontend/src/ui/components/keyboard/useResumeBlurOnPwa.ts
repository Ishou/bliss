import { useEffect } from 'react';

// Android Chrome PWA: re-attaching focus on resume ignores inputMode="none"; pre-emptive blur prevents OS keyboard pop.
export function useResumeBlurOnPwa(touchPrimary: boolean): void {
  useEffect(() => {
    if (!touchPrimary) return;
    if (typeof document === 'undefined') return;
    const onVisibilityChange = () => {
      if (document.visibilityState !== 'hidden') return;
      const active = document.activeElement;
      if (active instanceof HTMLElement && typeof active.blur === 'function') {
        active.blur();
      }
    };
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [touchPrimary]);
}
