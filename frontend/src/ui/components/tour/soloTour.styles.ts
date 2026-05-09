import { css } from 'styled-system/css';

// Visual layer for the solo tour. Mirrors the dark-card / pink-accent
// rhythm used by `Dialog.tsx` so the coachmark reads as the same family
// as the end-of-game modal — the tour is a piece of the same surface.

export const backdropStyles = css({
  position: 'fixed',
  inset: 0,
  bg: 'rgba(10, 10, 12, 0.7)',
  zIndex: 1002,
});

// Spotlight is a decorative pink ring drawn around the target rect.
// Ark UI sets its `top/left/width/height` inline at every step
// transition (tooltip steps); we deliberately do NOT add a CSS
// transition on those properties because the first transition (from
// step 1 — `dialog` type, no target → step 2 — `tooltip` with target)
// would animate from (0,0,0,0) to the target rect, which reads as the
// ring "growing out of the top-left corner". Snapping is correct;
// between-step movement is fast enough that animation isn't needed.
export const spotlightStyles = css({
  position: 'fixed',
  borderRadius: '10px',
  border: '2px solid token(colors.focusRing)',
  boxShadow: '0 0 0 9999px transparent',
  pointerEvents: 'none',
  zIndex: 1002,
});

// Positioner sits above the backdrop. Ark UI only injects positioning
// styles for tooltip-type steps (via Floating UI); dialog-type steps
// arrive with no positioning at all, so we provide a full-viewport
// flex container that centers the content card. The dialog branch
// uses `position: fixed`; the tooltip branch keeps its inline
// `position: absolute` from Floating UI.
//
// `pointer-events: none` on the wrapper lets the backdrop's pointer-
// blocker still catch clicks outside the content; `pointer-events:
// auto` on the content re-enables interaction with the card itself.
export const positionerStyles = css({
  zIndex: 1003,
  pointerEvents: 'none',
  '&[data-type="dialog"]': {
    position: 'fixed',
    inset: 0,
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 'md',
  },
});

// Content gets `position: relative; z-index: 1003` so zag's `syncZIndex`
// effect (dialog steps) reads a real number from content's computed
// style and copies it to the positioner via `--z-index`. Without an
// explicit z-index on content, the positioner stays at `auto` and the
// backdrop (z-index 1002) intercepts pointer events on the card.
export const contentStyles = css({
  position: 'relative',
  zIndex: 1003,
  pointerEvents: 'auto',
  bg: 'surface',
  color: 'fg',
  borderRadius: 'md',
  padding: 'lg',
  maxWidth: '420px',
  width: 'min(420px, calc(100vw - 32px))',
  boxShadow: '0 12px 40px -8px rgba(0, 0, 0, 0.35)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
  fontFamily: 'body',
  outline: 'none',
  _focusVisible: {
    boxShadow: '0 12px 40px -8px rgba(0, 0, 0, 0.35), 0 0 0 3px token(colors.focusRing)',
  },
});

export const progressTextStyles = css({
  fontSize: 'xs',
  color: 'fgMuted',
  letterSpacing: '0.08em',
  textTransform: 'uppercase',
  margin: 0,
});

export const titleStyles = css({
  fontSize: 'lg',
  fontWeight: 'semibold',
  color: 'fg',
  margin: 0,
});

export const descriptionStyles = css({
  fontSize: 'body',
  color: 'fg',
  margin: 0,
  opacity: 0.85,
  lineHeight: 1.5,
});

export const arrowStyles = css({
  '--arrow-size': '12px',
  '--arrow-background': 'token(colors.surface)',
});

export const arrowTipStyles = css({
  bg: 'surface',
});

export const footerStyles = css({
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 'sm',
  marginTop: 'sm',
});

export const footerRightGroupStyles = css({
  display: 'flex',
  alignItems: 'center',
  gap: 'sm',
});

export const dotsStyles = css({
  display: 'flex',
  alignItems: 'center',
  gap: '6px',
});

export const dotBaseStyles = css({
  width: '6px',
  height: '6px',
  borderRadius: '50%',
  bg: 'neutral.500',
  transition: 'background-color 120ms ease-out',
});

export const dotActiveStyles = css({
  bg: 'focusRing',
});
