import { useEffect, useRef } from 'react';
import { css } from '../../../../styled-system/css';
import { GRID_TRACK_WIDTH } from './layout';

/**
 * Visible zoom controls for the grid: zoom-in, zoom-out, reset. Driven
 * by the parent's `react-zoom-pan-pinch` ref (passed in via the three
 * imperative callbacks). Discoverability + accessibility: keyboard-only
 * users get a way to zoom without the mouse wheel; screen-reader users
 * get aria-labelled buttons. Sits below the grid as a horizontal row
 * so it never covers cell content.
 *
 * Visual telegraphy:
 * - At scale 1, "Zoom out" and "Reset" are disabled — there's nothing
 *   to undo. "Zoom in" is the only enabled button until the user has
 *   started zooming.
 * - At max scale, "Zoom in" disables.
 */
// Hidden below `md` (768 px) — touch devices have native pinch-zoom,
// so the buttons are redundant there AND they steal ~52 px of vertical
// space inside the grid panel (a button row + its top margin). On
// mobile-tiny that squashed cells from ~24 px to ~17 px and pushed
// stacked-clue text below the e2e visibility gate (clue-ratio.spec).
// Desktop / tablet keep the cluster for keyboard + motor a11y.
const cluster = css({
  display: { base: 'none', md: 'flex' },
  flexDirection: 'row',
  justifyContent: 'center',
  gap: '6px',
  width: '100%',
  margin: '8px auto 0',
});

// Inline-style: Panda CSS cannot statically extract min(…) with viewport units.
const clusterStyle = { maxWidth: GRID_TRACK_WIDTH } as const;

// Same visual rhythm as the toolbar `IconButton` primitive (subtle
// border, muted icon, hover brightens, press scales) — the previous
// `bg: surface` + `_hover: primary.50` looked out of palette next to
// the new toolbar buttons. Touch target stays at 44 px (WCAG 2.5.5 AA)
// so we keep the larger size; the IconButton's 28×28 is desktop-only
// for the toolbar.
const button = css({
  minWidth: '44px',
  height: '44px',
  px: 'sm',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  bg: 'transparent',
  color: 'fgMuted',
  border: '1px solid token(colors.border)',
  borderRadius: '6px',
  fontFamily: 'body',
  fontSize: 'body',
  fontWeight: 'medium',
  cursor: 'pointer',
  transition: 'background-color 120ms ease-out, border-color 120ms ease-out, color 120ms ease-out, transform 120ms ease-out',
  _hover: { borderColor: 'fg', color: 'fg' },
  _active: { transform: 'scale(0.96)' },
  _focusVisible: { outline: '2px solid token(colors.focusRing)', outlineOffset: '2px' },
  _disabled: { opacity: 0.5, cursor: 'not-allowed' },
});

export function GridZoomControls({
  canZoomIn,
  canZoomOut,
  onZoomIn,
  onZoomOut,
  onReset,
}: {
  canZoomIn: boolean;
  canZoomOut: boolean;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onReset: () => void;
}) {
  const clusterRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = clusterRef.current;
    if (!el) return;
    const publish = () => {
      const h = Math.ceil(el.getBoundingClientRect().height);
      document.documentElement.style.setProperty('--grid-zoom-controls-height', `${h}px`);
    };
    publish();
    let ro: ResizeObserver | null = null;
    if (typeof ResizeObserver !== 'undefined') {
      ro = new ResizeObserver(publish);
      ro.observe(el);
    }
    return () => {
      ro?.disconnect();
      document.documentElement.style.removeProperty('--grid-zoom-controls-height');
    };
  }, []);
  return (
    <div ref={clusterRef} className={cluster} style={clusterStyle} role="group" aria-label="Zoom controls">
      {/*
        `onMouseDown={e => e.preventDefault()}` on every button: stops
        the browser's default mousedown→focus on the <button>, which
        would otherwise blur the focused cell input. We still get the
        onClick (click is dispatched after mouseup; preventDefault on
        mousedown only suppresses default focus, not the click event
        itself). Standard React toolbar pattern.
      */}
      <button
        type="button"
        className={button}
        onMouseDown={(e) => e.preventDefault()}
        onClick={onZoomOut}
        disabled={!canZoomOut}
        aria-label="Zoom out"
      >
        −
      </button>
      <button
        type="button"
        className={button}
        onMouseDown={(e) => e.preventDefault()}
        onClick={onReset}
        disabled={!canZoomOut}
        aria-label="Reset zoom"
        title="Reset zoom"
      >
        1:1
      </button>
      <button
        type="button"
        className={button}
        onMouseDown={(e) => e.preventDefault()}
        onClick={onZoomIn}
        disabled={!canZoomIn}
        aria-label="Zoom in"
      >
        +
      </button>
    </div>
  );
}
