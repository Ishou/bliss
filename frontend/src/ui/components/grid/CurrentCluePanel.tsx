import { useEffect, useState } from 'react';
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
// Why this is plain sticky and NOT visual-viewport tracked: the grid's
// `TransformWrapper` sets `touch-action: none` on that element only (see
// Grid.tsx `transformWrapperStyle`). Pinch gestures originating on the grid
// are captured by react-zoom-pan-pinch before the browser can treat them as
// native zoom, so the visual viewport does not diverge from the layout
// viewport during grid interactions. Native browser zoom is intentionally
// preserved for non-grid content (WCAG 1.4.4 — see ADR-0016 §2–3). Earlier
// revisions hand-rolled a rAF loop reading `window.visualViewport`; scoping
// zoom to the grid element removed the need.
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

// Tracks `window.visualViewport`. When the user pinch-zooms (`scale > 1`),
// returns the inline-style overrides that pin the panel to the top of the
// VISIBLE visual viewport at a constant on-screen size; otherwise returns
// `undefined` and the Panda `panel` class's `position: sticky` runs natively.
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
// Co-located here (~30 lines) to match `useGridNavigation.ts`'s
// component-adjacent-hook style; not worth a `hooks/` folder for this.
type ZoomedStyle = {
  position: 'fixed';
  top: 0;
  left: 0;
  right: 0;
  transform: string;
  transformOrigin: 'top left';
};

function useVisualViewportZoom(): ZoomedStyle | undefined {
  const [style, setStyle] = useState<ZoomedStyle | undefined>(undefined);
  useEffect(() => {
    if (typeof window === 'undefined' || !window.visualViewport) return;
    const vv = window.visualViewport;
    let raf = 0;
    const update = () => {
      raf = 0;
      // Tolerance — pinch gestures sometimes leave scale at 1.0001 or so.
      // Below that we treat the user as not zoomed and let CSS sticky run.
      if (vv.scale > 1.001) {
        setStyle({
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          transform: `translate(${vv.offsetLeft}px, ${vv.offsetTop}px) scale(${1 / vv.scale})`,
          transformOrigin: 'top left',
        });
      } else {
        setStyle(undefined);
      }
    };
    const schedule = () => {
      // visualViewport.scroll fires every frame during a pinch-pan; rAF
      // coalesces. Listeners are passive by spec, no { passive: true } needed.
      if (!raf) raf = requestAnimationFrame(update);
    };
    vv.addEventListener('scroll', schedule);
    vv.addEventListener('resize', schedule);
    update();
    return () => {
      cancelAnimationFrame(raf);
      vv.removeEventListener('scroll', schedule);
      vv.removeEventListener('resize', schedule);
    };
  }, []);
  return style;
}

export function CurrentCluePanel({ clue }: { clue: Clue | null }) {
  const inlineStyle = useVisualViewportZoom();
  if (!clue) {
    return (
      <div
        className={panel}
        style={inlineStyle}
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
      className={panel}
      style={inlineStyle}
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
