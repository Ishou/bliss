import { css } from '../../../../styled-system/css';

/**
 * Visible zoom controls for the grid: zoom-in, zoom-out, reset. Driven
 * by the parent's `react-zoom-pan-pinch` ref (passed in via the three
 * imperative callbacks). Discoverability + accessibility: keyboard-only
 * users get a way to zoom without the mouse wheel; screen-reader users
 * get aria-labelled buttons. Stays unobtrusive (small floating cluster,
 * top-right of the grid) so it doesn't compete with cell content.
 *
 * Visual telegraphy:
 * - At scale 1, "Zoom out" and "Reset" are disabled — there's nothing
 *   to undo. "Zoom in" is the only enabled button until the user has
 *   started zooming.
 * - At max scale, "Zoom in" disables.
 *
 * Disabled state uses `aria-disabled` on the underlying <button> so
 * focus order isn't disrupted (vs `disabled` which removes the button
 * from the tab order — fine for now but would surprise a screen-reader
 * user expecting all controls to be reachable).
 */
const cluster = css({
  position: 'absolute',
  top: '8px',
  right: '8px',
  display: 'flex',
  flexDirection: 'column',
  gap: '4px',
  zIndex: 2,
  // Don't intercept gestures on the grid itself — the controls should
  // only react to clicks on their own buttons.
  pointerEvents: 'none',
});

const button = css({
  pointerEvents: 'auto',
  width: '32px',
  height: '32px',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  bg: 'surface',
  color: 'fg',
  border: '1px solid token(colors.border)',
  borderRadius: '4px',
  fontFamily: 'body',
  fontSize: '18px',
  fontWeight: 'bold',
  cursor: 'pointer',
  // Subtle shadow lifts the cluster above the grid without being heavy.
  boxShadow: '0 1px 2px rgba(0, 0, 0, 0.08)',
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
    <div className={cluster} role="group" aria-label="Zoom controls">
      <button
        type="button"
        className={button}
        onClick={onZoomIn}
        disabled={!canZoomIn}
        aria-label="Zoom in"
      >
        +
      </button>
      <button
        type="button"
        className={button}
        onClick={onZoomOut}
        disabled={!canZoomOut}
        aria-label="Zoom out"
      >
        −
      </button>
      <button
        type="button"
        className={button}
        onClick={onReset}
        disabled={!canZoomOut}
        aria-label="Reset zoom"
      >
        ⌂
      </button>
    </div>
  );
}
