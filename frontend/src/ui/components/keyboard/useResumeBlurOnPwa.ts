import { useEffect } from 'react';

// Blur the currently-focused grid input when the document becomes hidden.
// Mitigates an Android Chrome PWA bug where backgrounding then resuming the
// app re-attaches the OS soft keyboard to a still-focused <input>, ignoring
// inputMode="none". On the next visible transition the document has no
// focused input, so the keyboard does not pop. A subsequent tap re-focuses
// programmatically, and programmatic focus honors inputMode="none".
// No-op when touchPrimary is false (desktop never hits this Android path).
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
