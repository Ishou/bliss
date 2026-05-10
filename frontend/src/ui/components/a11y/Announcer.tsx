import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { flushSync } from 'react-dom';
import { css } from 'styled-system/css';

// Visually-hidden — live-region content must be present in the DOM
// (axe SR rules) but not painted. Same shape as the AppHeader skip
// link's resting state.
const visuallyHiddenStyles = css({
  position: 'absolute',
  width: '1px',
  height: '1px',
  margin: '-1px',
  padding: 0,
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  borderWidth: 0,
});

export interface SayOptions {
  readonly assertive?: boolean;
}

export interface AnnouncerApi {
  readonly say: (text: string, opts?: SayOptions) => void;
}

const AnnouncerContext = createContext<AnnouncerApi | null>(null);

const DEDUP_WINDOW_MS = 200;

export function AnnouncerProvider({ children }: { readonly children: ReactNode }) {
  const [politeText, setPoliteText] = useState('');
  const [assertiveText, setAssertiveText] = useState('');

  // Per-channel last-emit tracking for the 200ms de-dup window. Ref —
  // not state — so updates don't re-render and don't race with the
  // setText call below.
  const lastPolite = useRef<{ text: string; at: number }>({ text: '', at: 0 });
  const lastAssertive = useRef<{ text: string; at: number }>({ text: '', at: 0 });

  const say = useCallback((text: string, opts: SayOptions = {}) => {
    const now = Date.now();
    const ref = opts.assertive ? lastAssertive : lastPolite;
    const setter = opts.assertive ? setAssertiveText : setPoliteText;
    if (ref.current.text === text && now - ref.current.at < DEDUP_WINDOW_MS) return;
    ref.current = { text, at: now };
    // Two-step empty→text ensures SR clients see a DOM mutation even
    // when the same text is re-emitted after the de-dup window expires.
    // flushSync forces the empty render to commit before the real value,
    // preventing React's bail-out optimization (which would skip the
    // re-render when the final batched value equals current state).
    flushSync(() => { setter(''); });
    setter(text);
  }, []);

  const api = useMemo<AnnouncerApi>(() => ({ say }), [say]);

  return (
    <AnnouncerContext.Provider value={api}>
      {children}
      {/*
        Drop the `role="status"` / `role="alert"` attrs on these regions:
        `aria-live` alone is sufficient for SR clients to announce
        mutations, and the explicit roles collide with
        `screen.getByRole('alert')` / `'status'` queries elsewhere in
        the test suite (the live regions would always match, making
        unrelated alert lookups ambiguous).
      */}
      <div aria-live="polite" aria-atomic="true" className={visuallyHiddenStyles}>
        {politeText}
      </div>
      <div aria-live="assertive" aria-atomic="true" className={visuallyHiddenStyles}>
        {assertiveText}
      </div>
    </AnnouncerContext.Provider>
  );
}

export function useAnnouncer(): AnnouncerApi {
  const ctx = useContext(AnnouncerContext);
  if (!ctx) {
    // Defensive: a hook caller outside the provider gets a no-op
    // rather than a crash. Lets unit tests of grid components run
    // without forcing every harness to mount the provider.
    return { say: () => undefined };
  }
  return ctx;
}
