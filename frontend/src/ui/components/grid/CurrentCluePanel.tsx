import { useEffect, useRef } from 'react';
import { css } from 'styled-system/css';
import type { ArrowDirection } from '@/domain';
import type { Clue } from './useGridNavigation';

// Sticky-pinned to the top of the page-level scroll. The panel is rendered
// as a direct child of `<main>` (Grid returns a fragment, not a wrapping
// div) so its containing block is the page itself — taller than the
// viewport, so sticky has room to stick. Width matches the grid below
// (100% / 480px-cap / centered) so the two align horizontally on every
// breakpoint, instead of the panel spanning a wider area than the grid.
//
// Mobile (`base`): full available width inside `<main>`'s padding, slightly
// larger font for thumb-distance reading.
// Desktop (`md`): capped at 480px (same as grid), font reverts to body.
//
// `willChange: transform` hints the browser to promote the panel onto its
// own compositor layer. The pinch-zoom handler below mutates `transform`
// every animation frame; without the layer hint the browser would re-paint
// the panel on each update, instead of just shifting an already-painted
// texture on the GPU. The cost is a few KB of GPU memory for a single
// small element — trivial.
const panel = css({
  position: 'sticky',
  top: 0,
  zIndex: 10,
  width: '100%',
  maxWidth: '480px',
  margin: '0 auto',
  paddingBlock: 'sm',
  paddingInline: 'sm',
  border: '1px solid token(colors.border)',
  borderRadius: 'sm',
  // Constant elevation — visible once it sticks against the cream page bg.
  boxShadow: '0 2px 6px -2px rgba(0, 0, 0, 0.08)',
  bg: 'definition',
  color: 'fg',
  textAlign: 'left',
  fontFamily: 'body',
  // `md` (1.125rem) on phones gives better thumb-distance readability than
  // `body` (1rem); desktop reverts to body so the panel doesn't dominate.
  fontSize: { base: 'md', md: 'body' },
  lineHeight: '1.3',
  display: 'flex',
  alignItems: 'center',
  gap: 'sm',
  minHeight: '2.5rem',
  willChange: 'transform',
});
const arrow = css({
  fontSize: 'lg',
  color: 'accent',
  flexShrink: 0,
  lineHeight: 1,
});
const text = css({
  flex: 1,
  fontWeight: 'bold',
  color: 'leaf.700',
  overflowWrap: 'break-word',
  wordBreak: 'normal',
});
const placeholder = css({
  flex: 1,
  fontStyle: 'italic',
  color: 'accent',
});

const arrowGlyph: Record<ArrowDirection, string> = {
  right: '→',
  down: '↓',
  'down-right': '↳',
  'right-down': '↴',
};
const arrowLabel: Record<ArrowDirection, string> = {
  right: 'horizontale',
  down: 'verticale',
  'down-right': 'horizontale',
  'right-down': 'verticale',
};

// Tracks `window.visualViewport` and pins the panel to the visible viewport
// when the user pinch-zooms (`scale > 1`). When un-zoomed the panel's
// inline overrides are cleared and the Panda `panel` class's
// `position: sticky` runs natively.
//
// Why we override `position` to `fixed` when zoomed (not just transform):
// `position: sticky` keeps the panel at its natural flow position UNTIL the
// user has scrolled past it, then sticks it at top:0 of the layout viewport.
// On first load (or when content above the panel — wordmark, badge — is
// still visible), the natural flow position is BELOW the layout viewport
// top by `naturalY` CSS px. A pure transform of `translate(0, offsetTop)`
// only compensates for the visual-viewport pan, so the panel ends up at
// device y = naturalY × scale — mid-screen at scale 2. Switching to
// `position: fixed; top: 0` when zoomed re-anchors the panel to layout
// viewport top regardless of natural flow, so the translate then does
// reach the visible top.
//
// Math: at zoom N, every CSS px renders as N device px. With `position:
// fixed; top: 0; left: 0; right: 0` the panel's layout box is at layout
// viewport top-left, full viewport width. `translate(offsetLeft, offsetTop)`
// shifts to visual viewport top-left (in layout coords). `scale(1/N)` with
// `transform-origin: top left` shrinks rendered CSS size to 1/N of layout —
// device size = (layoutWidth/N) × N = layoutWidth, same device size as
// un-zoomed. Panel always fits the visible width edge-to-edge with text at
// its un-zoomed readable size.
//
// Trade-off: zooming in causes a small layout shift (sticky panel had a
// flow box, fixed panel doesn't). Brief and only during the pinch gesture.
//
// Implementation note: we mutate `el.style` directly instead of going
// through React state, AND we run a continuous `requestAnimationFrame`
// loop while zoomed instead of an event-coalesced rAF. The event-driven
// version (event → schedule rAF → read vv values) lagged by one frame
// during pinch-pan because `vv.scroll` fires AFTER the browser has
// already started compositing the new frame; our update landed in the
// next composite, visibly trailing the gesture. The continuous loop
// reads `vv.offsetLeft`/`offsetTop` *inside* the rAF callback, which
// fires before the next composite — so the transform applies for the
// frame about to be drawn, not the one after.
//
// `translate3d(...)` (instead of `translate(...)`) is redundant with
// `willChange: transform` on the class but cheap insurance — guarantees
// a GPU layer on every browser. `Math.round` on the offsets eliminates
// sub-pixel jitter that otherwise shows up as text ghosting / flicker.
//
// CPU cost while zoomed: 60 fps callback that reads two numbers and
// writes six string properties — trivial. The loop self-stops when
// `scale` drops back to ~1 so we don't burn battery in the steady
// un-zoomed state.
//
// Co-located here (~70 lines) to match `useGridNavigation.ts`'s
// component-adjacent-hook style; not worth a `hooks/` folder for this.
function useVisualViewportZoom(elementRef: React.RefObject<HTMLElement | null>) {
  useEffect(() => {
    if (typeof window === 'undefined' || !window.visualViewport) return;
    const vv = window.visualViewport;
    const el = elementRef.current;
    if (!el) return;
    let raf = 0;
    const apply = () => {
      // Tolerance — pinch gestures sometimes leave scale at 1.0001 or so.
      // Below that we treat the user as not zoomed and let CSS sticky run.
      if (vv.scale > 1.001) {
        // Round to whole CSS pixels — sub-pixel transforms cause text
        // ghosting on some Android compositors during fast panning.
        const tx = Math.round(vv.offsetLeft);
        const ty = Math.round(vv.offsetTop);
        el.style.position = 'fixed';
        el.style.top = '0px';
        el.style.left = '0px';
        el.style.right = '0px';
        el.style.transform = `translate3d(${tx}px, ${ty}px, 0) scale(${1 / vv.scale})`;
        el.style.transformOrigin = 'top left';
      } else {
        // Clear inline overrides so the Panda `panel` class's
        // `position: sticky` (and natural width/transform defaults) win.
        el.style.position = '';
        el.style.top = '';
        el.style.left = '';
        el.style.right = '';
        el.style.transform = '';
        el.style.transformOrigin = '';
      }
    };
    const tick = () => {
      apply();
      // Self-stopping loop: keep ticking only while zoomed. When the
      // user pinches back out, scale drops, apply() clears the inline
      // overrides, and the loop ends. A future pinch-in re-arms via
      // the resize listener below.
      if (vv.scale > 1.001) {
        raf = requestAnimationFrame(tick);
      } else {
        raf = 0;
      }
    };
    const start = () => {
      if (!raf) raf = requestAnimationFrame(tick);
    };
    // `resize` fires when scale changes (entering / exiting zoom).
    // `scroll` fires when offset changes (panning while zoomed). Either
    // one re-arms the loop in case it self-stopped or never started.
    vv.addEventListener('resize', start);
    vv.addEventListener('scroll', start);
    apply();
    if (vv.scale > 1.001) start();
    return () => {
      cancelAnimationFrame(raf);
      vv.removeEventListener('resize', start);
      vv.removeEventListener('scroll', start);
      // Reset on unmount — the next mount of this component starts from a
      // clean Panda-class state, not whatever the last zoom left behind.
      el.style.position = '';
      el.style.top = '';
      el.style.left = '';
      el.style.right = '';
      el.style.transform = '';
      el.style.transformOrigin = '';
    };
  }, [elementRef]);
}

export function CurrentCluePanel({ clue }: { clue: Clue | null }) {
  const panelRef = useRef<HTMLDivElement | null>(null);
  useVisualViewportZoom(panelRef);
  if (!clue) {
    return (
      <div
        ref={panelRef}
        className={panel}
        role="status"
        aria-live="polite"
        data-testid="current-clue-panel"
      >
        <span className={placeholder}>
          Sélectionnez une case pour afficher la définition.
        </span>
      </div>
    );
  }
  return (
    <div
      ref={panelRef}
      className={panel}
      role="status"
      aria-live="polite"
      data-testid="current-clue-panel"
    >
      <span className={arrow} aria-label={`définition ${arrowLabel[clue.clue.arrow]}`}>
        {arrowGlyph[clue.clue.arrow]}
      </span>
      <span className={text}>{clue.clue.text}</span>
    </div>
  );
}
