import { css } from '../../../../styled-system/css';

// Hidden below md — touch pinch-zoom makes the cluster redundant; showing it costs ~52 px of grid height on mobile-tiny.
const cluster = css({
  display: { base: 'none', md: 'flex' },
  flexDirection: 'row',
  justifyContent: 'center',
  gap: '6px',
});

// 44 px touch target (WCAG 2.5.5 AA); transparent bg to stay in-palette next to toolbar buttons.
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
  return (
    <div className={cluster} role="group" aria-label="Zoom controls">
      {/* onMouseDown preventDefault: stops browser focus-on-mousedown from blurring the focused cell input. */}
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
