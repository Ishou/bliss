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
const cluster = css({
  display: 'flex',
  flexDirection: 'row',
  justifyContent: 'center',
  gap: '6px',
  width: '100%',
  margin: '8px auto 0',
});

// Width cap shared with the grid wrapper + clue panel via `GRID_TRACK_WIDTH`.
// Inline-style because Panda CSS does not statically extract the
// `min(95vw, …)` clamp and the cap must stay in lock-step with `Grid.tsx`.
const clusterStyle = { maxWidth: GRID_TRACK_WIDTH } as const;

const button = css({
  // WCAG 2.2 AA — Success Criterion 2.5.8 "Target Size (Minimum)" requires
  // touch targets ≥ 24 px; AAA-level Success Criterion 2.5.5 recommends
  // 44 px. Mobile thumb research lands at 44–48 px for comfortable hits;
  // we picked 44 px as the minimum width and height so each control is a
  // first-class touch target (the cells next to it run smaller, but they
  // are not standalone affordances — the player works through one focused
  // cell at a time, while the zoom buttons are tap-anywhere triggers).
  minWidth: '44px',
  height: '44px',
  px: 'sm',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  bg: 'surface',
  color: 'fg',
  border: '1px solid token(colors.border)',
  borderRadius: '4px',
  fontFamily: 'body',
  fontSize: 'md',
  fontWeight: 'bold',
  cursor: 'pointer',
  _hover: { bg: 'leaf.50' },
  _focusVisible: { outline: '2px solid token(colors.leaf.700)', outlineOffset: '1px' },
  _disabled: { opacity: 0.4, cursor: 'not-allowed' },
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
  return (
    <div className={cluster} style={clusterStyle} role="group" aria-label="Zoom controls">
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
        onClick={onZoomIn}
        disabled={!canZoomIn}
        aria-label="Zoom in"
      >
        +
      </button>
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
    </div>
  );
}
