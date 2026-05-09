import { css } from 'styled-system/css';

// Visual layer for the solo tour. Mirrors the dark-card / pink-accent
// rhythm used by `Dialog.tsx` so the coachmark reads as the same family
// as the end-of-game modal — the tour is a piece of the same surface.

// Defensive `display: none` on `[data-state="closed"]` and on
// `[hidden]`. Ark UI hides the backdrop via the HTML `hidden` attribute
// (which has the user-agent rule `[hidden] { display: none }` at
// specificity 0,1,0). If a future Panda atom or app-wide CSS reset
// declares `display: block` on a tour part with higher specificity,
// the backdrop would stay visible after dismiss — the literal symptom
// the user reported. Pinning `display: none` at higher specificity on
// the closed-state attribute closes that escape hatch unconditionally.
export const backdropStyles = css({
  position: 'fixed',
  inset: 0,
  bg: 'rgba(10, 10, 12, 0.7)',
  zIndex: 1002,
  '&[data-state="closed"], &[hidden]': {
    display: 'none !important',
  },
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
//
// Width is 500 px (vs. the 420 px Dialog primitive) so the four-chip
// footer row [Passer · dots · Précédent · Suivant/Terminer] fits on
// one line at every step using the Bliss `Button` primitive's brand
// padding. Shrinking the buttons via a custom className was tried and
// fights Panda's atomic-class cascade (`pi_md` from Button vs `pi_sm`
// from a tour override resolves by stylesheet order, which isn't
// deterministic across rebuilds). Widening the popover is the simpler
// fix and keeps the buttons at the brand's accessible touch-target
// size. Mobile (`< 480 px`) still uses `calc(100vw - 32px)` so the
// popover never exceeds the viewport.
export const contentStyles = css({
  position: 'relative',
  zIndex: 1003,
  pointerEvents: 'auto',
  bg: 'surface',
  color: 'fg',
  borderRadius: 'md',
  padding: 'lg',
  maxWidth: '500px',
  width: 'min(500px, calc(100vw - 32px))',
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

// Footer layout.
//
// Desktop (≥ 480 px): one row, single line, no wrap. Order:
//   [Passer] [dots]                             [Précédent] [Suivant]
// `flexWrap: 'nowrap'` forces all four chips onto the same line; the
// compact action-trigger sizing below keeps the total width inside
// the 420 px content box even with the longest combination ("Passer
// le tour" + 6 dots + "Précédent" + "Suivant").
//
// Mobile (< 480 px): stack into two rows. The buttons get full width
// at the top, with the dots below acting as a step indicator. This
// keeps "Passer le tour" on a single line on a 360 px viewport
// without overlapping the prev/next pair.
export const footerStyles = css({
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'space-between',
  gap: 'sm',
  marginTop: 'sm',
  flexWrap: 'nowrap',
  '@media (max-width: 479px)': {
    flexDirection: 'column-reverse',
    alignItems: 'stretch',
    gap: 'md',
    flexWrap: 'wrap',
  },
});

export const footerRightGroupStyles = css({
  display: 'flex',
  alignItems: 'center',
  gap: 'sm',
  flexWrap: 'nowrap',
  '@media (max-width: 479px)': {
    flex: 1,
    justifyContent: 'flex-end',
  },
});

// `whiteSpace: nowrap` keeps long French labels ("Passer le tour")
// from breaking mid-pill on viewports where the button is the
// narrowest element in the row. We deliberately do NOT override the
// Button primitive's padding/font here — the popover width was bumped
// instead (see contentStyles) so the brand's accessible touch-target
// size is preserved.
export const actionTriggerStyles = css({
  whiteSpace: 'nowrap',
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
