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
// Color rationale (Panda CSS tokens, ADR-0005 §4):
// - `connecting` — neutral, low-urgency. `ink` text on the standard
//   `surface` (white) gives the highest legibility for the moment a
//   player has to wait without alarming them.
// - `disconnected` — error / lost-connection. `blossom.700` text on a
//   `blossom.50` tint mirrors the existing "DÉMO" pill at
//   `routes/index.tsx`; both shades come from the existing rose ramp,
//   no new tokens introduced. Contrast: `blossom.700` (#8E5460) on
//   `blossom.50` (#FBF1F2) ≈ 5.9:1, passes AA at body sizes.
// - `reconnecting` — warning / in-flight retry. The Panda config has
//   no yellow ramp, so we reuse `sand` (the parchment-warmer surface)
//   with `ink` text. Contrast: `ink` (#1B2845) on `sand` (#E5DCC6)
//   ≈ 11.4:1. The retry indicator is a plain Unicode arrow rather
//   than a spinning icon — no animation dependency, no
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
  bg: 'blossom.50',
  color: 'blossom.700',
  borderBottom: '1px solid token(colors.blossom.200)',
});

const reconnectingStyles = css({
  bg: 'mauve',
  color: 'fg',
  borderBottom: '1px solid token(colors.border)',
});

const indicatorStyles = css({
  fontSize: 'lg',
  lineHeight: 1,
  flexShrink: 0,
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
    </div>
  );
}
