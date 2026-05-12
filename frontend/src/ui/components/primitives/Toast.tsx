import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { css, cx } from 'styled-system/css';

// Toast primitive — single-slot, imperative API. `show()` replaces the
// active toast (no stacking) so the user never has to dismiss a queue.
// Mounted via two pieces: `ToastProvider` carries the state at the root
// of the app, `<Toast />` is the surface that renders the active slot.
// Splitting them lets the provider live high in the tree (so any route
// can `useToast()`) while the rendered toast sits at the bottom of the
// shell where the fixed-position element is least likely to mask other
// chrome.
//
// Tone choices:
//   * info — neutral status (e.g. "Reconnexion…"). `role="status"` +
//     `aria-live="polite"` so screen readers announce after the user's
//     current task. Visual: `surface` bg + `fg` text, same posture as
//     the connecting state on `ConnectionBanner`.
//   * error — failure (e.g. failed startGame). `role="alert"` +
//     `aria-live="assertive"` so SR clients interrupt the current
//     reading. Visual: `errorBg` + `errorText`, matching the
//     disconnected variant on `ConnectionBanner` for consistency.
//
// Duration `null` = sticky (no auto-dismiss). Default 6000 ms —
// long enough to read a short French message, short enough that
// stale state never accumulates between user interactions. The
// timer resets on every `show()` and clears on unmount.

export type ToastTone = 'info' | 'error';

export interface ToastOptions {
  readonly text: string;
  readonly tone?: ToastTone;
  // ms; `null` disables auto-dismiss. Default: 6000.
  readonly duration?: number | null;
}

interface ActiveToast {
  readonly text: string;
  readonly tone: ToastTone;
  readonly duration: number | null;
  // Monotonic id — bumped on every `show` so the auto-dismiss timer
  // tied to a previous toast cannot kill the freshly-shown one.
  readonly id: number;
}

interface ToastApi {
  readonly show: (options: ToastOptions) => void;
  readonly dismiss: () => void;
  readonly active: ActiveToast | null;
}

const ToastContext = createContext<ToastApi | null>(null);

const DEFAULT_DURATION_MS = 6000;

export function ToastProvider({ children }: { readonly children: ReactNode }) {
  const [active, setActive] = useState<ActiveToast | null>(null);
  const idRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearTimer = useCallback(() => {
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  const show = useCallback((options: ToastOptions) => {
    clearTimer();
    idRef.current += 1;
    const id = idRef.current;
    const tone: ToastTone = options.tone ?? 'info';
    const duration =
      options.duration === undefined ? DEFAULT_DURATION_MS : options.duration;
    setActive({ text: options.text, tone, duration, id });
    if (duration !== null) {
      timerRef.current = setTimeout(() => {
        // Only clear if the id still matches — a later `show()` will
        // have superseded this timer's target toast.
        setActive((current) => (current && current.id === id ? null : current));
        timerRef.current = null;
      }, duration);
    }
  }, [clearTimer]);

  const dismiss = useCallback(() => {
    clearTimer();
    setActive(null);
  }, [clearTimer]);

  useEffect(() => () => { clearTimer(); }, [clearTimer]);

  const api = useMemo<ToastApi>(() => ({ show, dismiss, active }), [show, dismiss, active]);

  return <ToastContext.Provider value={api}>{children}</ToastContext.Provider>;
}

export function useToast(): { readonly show: (o: ToastOptions) => void; readonly dismiss: () => void } {
  const ctx = useContext(ToastContext);
  if (ctx == null) {
    // Defensive no-op so unit tests of leaf components don't have to
    // mount a provider. Mirrors `useAnnouncer`'s posture.
    return { show: () => undefined, dismiss: () => undefined };
  }
  return { show: ctx.show, dismiss: ctx.dismiss };
}

const containerStyles = css({
  position: 'fixed',
  // Bottom-right is the conventional toast slot — out of the way of
  // the page's primary CTAs (which live in the upper half of the card
  // grid on Accueil and at the centre of the WaitingRoom).
  bottom: 'md',
  right: 'md',
  zIndex: 110,
  maxWidth: 'min(420px, calc(100vw - 2 * token(spacing.md)))',
  display: 'flex',
  alignItems: 'flex-start',
  gap: 'sm',
  paddingBlock: 'sm',
  paddingInline: 'md',
  fontFamily: 'body',
  fontSize: 'body',
  borderRadius: 'md',
  boxShadow: 'floating',
  border: '1px solid token(colors.border)',
});

const infoStyles = css({ bg: 'surface', color: 'fg' });

const errorStyles = css({
  bg: 'errorBg',
  color: 'errorText',
  borderColor: 'error',
});

const dismissButtonStyles = css({
  marginInlineStart: 'auto',
  paddingInline: 'xs',
  paddingBlock: '2px',
  fontFamily: 'body',
  fontSize: 'sm',
  fontWeight: 'semibold',
  color: 'inherit',
  bg: 'transparent',
  border: 'none',
  cursor: 'pointer',
  flexShrink: 0,
  borderRadius: 'sm',
  _hover: { opacity: 0.8 },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

const textStyles = css({ margin: 0, lineHeight: 1.4 });

// Renders the active toast (or nothing). Consumers mount this inside
// the provider tree, typically once at the top of the app shell.
export function Toast() {
  const ctx = useContext(ToastContext);
  if (ctx == null || ctx.active == null) return null;
  const { text, tone } = ctx.active;
  const isError = tone === 'error';
  return (
    <div
      className={cx(containerStyles, isError ? errorStyles : infoStyles)}
      role={isError ? 'alert' : 'status'}
      aria-live={isError ? 'assertive' : 'polite'}
      data-testid="toast"
      data-tone={tone}
    >
      <p className={textStyles}>{text}</p>
      <button
        type="button"
        className={dismissButtonStyles}
        onClick={ctx.dismiss}
        aria-label="Fermer"
      >
        Fermer
      </button>
    </div>
  );
}
