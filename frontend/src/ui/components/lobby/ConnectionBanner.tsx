import { css } from 'styled-system/css';
import type { ConnectionState } from '@/application/game';

// Wave H PR #17 — top-of-page banner that surfaces transport health for
// the multiplayer lobby. The component is purely prop-driven so the
// route (or any test) can render it deterministically; the lobby route
// will subscribe to `gameClient.subscribeConnectionState` in a follow-
// up PR and feed the result here.
//
// Returns `null` when the socket is healthy — no chrome on the happy
// path. Intentional: a persistent "connected" pill draws the eye away
// from the grid for zero benefit.
//
// Accessibility (WCAG 2.2 AA per CLAUDE.md):
// - `role="status"` + `aria-live="polite"` so assistive tech announces
//   transitions without interrupting the player's current focus. Same
//   pattern as `CurrentCluePanel`.
// - Color is never the only signal: each state has distinct copy and
//   the reconnecting variant adds a textual retry indicator. Contrast
//   ratios are verified against the page background at AA minima.
//
// Color rationale (role-based semantic tokens):
// - `connecting` — neutral, low-urgency. `surface` bg + `fg` text.
// - `disconnected` — error. Uses the `error*` semantic family
//   (`errorBg` bg, `errorText` text). On the dark twilight palette
//   that resolves to a deep secondary fill with light pink text;
//   re-themable in one place if a future palette wants a dedicated
//   signal ramp.
// - `reconnecting` — warning / in-flight retry. Uses
//   `surfaceVariant` (the def-cell surface) + `fg` text — close
//   enough to "an extra surface" that the eye reads it as
//   "different state" without needing a yellow signal token.
//   The retry indicator is a plain Unicode arrow rather than a
//   spinning icon — no animation dependency, no
//   `prefers-reduced-motion` work.

type BannerVariant = Exclude<ConnectionState, 'connected'>;

interface BannerCopy {
  readonly text: string;
  readonly indicator?: string;
  readonly className: string;
}

const baseStyles = css({
  // Float at the top of the viewport so the banner's mount/unmount
  // doesn't reflow the lobby content underneath. Without this the
  // layout stuttered every time the connection state flipped (e.g.
  // every WebSocket reconnect handshake during a flaky network).
  position: 'fixed',
  top: 0,
  left: 0,
  right: 0,
  zIndex: 100,
  paddingBlock: 'sm',
  paddingInline: 'md',
  textAlign: 'center',
  fontFamily: 'body',
  fontSize: 'body',
  fontWeight: 'semibold',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: 'sm',
  boxShadow: 'floating',
});

const connectingStyles = css({
  bg: 'surface',
  color: 'fg',
  borderBottom: '1px solid token(colors.border)',
});

const disconnectedStyles = css({
  bg: 'errorBg',
  color: 'errorText',
  borderBottom: '1px solid token(colors.error)',
});

const reconnectingStyles = css({
  bg: 'surfaceVariant',
  color: 'fg',
  borderBottom: '1px solid token(colors.border)',
});

const indicatorStyles = css({
  fontSize: 'lg',
  lineHeight: 1,
  flexShrink: 0,
});

// Disconnected state offers a one-click recovery — the WS adapter has
// already given up reconnecting, so `window.location.reload()` is the
// only path forward. Sized to read as a primary action without
// crowding the banner copy on narrow viewports.
const recoveryButtonStyles = css({
  marginInlineStart: 'sm',
  paddingInline: 'md',
  paddingBlock: '4px',
  fontFamily: 'body',
  fontSize: 'sm',
  fontWeight: 'semibold',
  color: 'errorBg',
  bg: 'errorText',
  borderRadius: 'sm',
  border: 'none',
  cursor: 'pointer',
  flexShrink: 0,
  _hover: { opacity: 0.9 },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
  },
});

const COPY: Record<BannerVariant, BannerCopy> = {
  connecting: {
    text: 'Connexion en cours…',
    className: connectingStyles,
  },
  disconnected: {
    text: 'Connexion perdue.',
    className: disconnectedStyles,
  },
  reconnecting: {
    text: 'Reconnexion…',
    indicator: '↻',
    className: reconnectingStyles,
  },
};

export interface ConnectionBannerProps {
  readonly state: ConnectionState;
}

export function ConnectionBanner({ state }: ConnectionBannerProps) {
  if (state === 'connected') return null;
  const copy = COPY[state];
  return (
    <div
      className={`${baseStyles} ${copy.className}`}
      role="status"
      aria-live="polite"
      data-testid="connection-banner"
      data-state={state}
    >
      {copy.indicator ? (
        <span className={indicatorStyles} aria-hidden="true">
          {copy.indicator}
        </span>
      ) : null}
      <span>{copy.text}</span>
      {state === 'disconnected' ? (
        <button
          type="button"
          className={recoveryButtonStyles}
          onClick={() => {
            window.location.reload();
          }}
        >
          Recharger
        </button>
      ) : null}
    </div>
  );
}
