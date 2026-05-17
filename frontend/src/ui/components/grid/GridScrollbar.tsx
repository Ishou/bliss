import { useCallback, useRef } from 'react';
import { css } from 'styled-system/css';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import { computeThumbGeometry, thumbDeltaToPosition } from './transformMath';

const TRACK_THICKNESS_PX = 8;
const TRACK_THICKNESS_PX_MOBILE = 6;

const trackBase = css({
  position: 'absolute',
  bg: 'rgba(212, 204, 184, 0.15)',
  touchAction: 'none',
  // Re-enable pointer events: the overlayFrame wrapper that contains
  // this track sets `pointer-events: none` so its transparent area
  // doesn't swallow grid clicks. The track itself must respond to
  // pointer gestures for drag-scrolling to work.
  pointerEvents: 'auto',
});

const trackVertical = css({
  top: 0,
  right: 0,
  bottom: '8px',
  width: `${TRACK_THICKNESS_PX}px`,
  '@media (max-width: 480px)': { width: `${TRACK_THICKNESS_PX_MOBILE}px` },
});

const trackHorizontal = css({
  left: 0,
  right: '8px',
  bottom: 0,
  height: `${TRACK_THICKNESS_PX}px`,
  '@media (max-width: 480px)': { height: `${TRACK_THICKNESS_PX_MOBILE}px` },
});

const thumbBase = css({
  position: 'absolute',
  bg: 'rgba(212, 204, 184, 0.7)',
  borderRadius: '4px',
  cursor: 'grab',
  _active: { cursor: 'grabbing', bg: 'rgba(212, 204, 184, 0.9)' },
});

const thumbVertical = css({
  left: 0,
  right: 0,
});

const thumbHorizontal = css({
  top: 0,
  bottom: 0,
});

export interface GridScrollbarProps {
  orientation: 'horizontal' | 'vertical';
  transformRef: React.RefObject<ReactZoomPanPinchContentRef | null>;
  scale: number;
  positionX: number;
  positionY: number;
  /** Natural (unscaled) content width in px. */
  contentWidth: number;
  /** Natural (unscaled) content height in px. */
  contentHeight: number;
}

export function GridScrollbar({
  orientation,
  transformRef,
  scale,
  positionX,
  positionY,
  contentWidth,
  contentHeight,
}: GridScrollbarProps) {
  // Hooks must come before any early return; the React rules-of-hooks
  // ESLint rule (and React itself) forbids conditional hook calls.
  const trackRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<{
    startClientCoord: number;
    startThumbOffset: number;
    pointerId: number;
  } | null>(null);

  const isVertical = orientation === 'vertical';

  const onPointerDown = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      if (event.button !== 0) return;
      event.preventDefault();
      event.stopPropagation();
      const thumb = event.currentTarget as HTMLElement;
      const thumbRect = thumb.getBoundingClientRect();
      const trackRect = trackRef.current?.getBoundingClientRect();
      if (!trackRect) return;
      const currentThumbOffset = isVertical
        ? thumbRect.top - trackRect.top
        : thumbRect.left - trackRect.left;
      const coord = isVertical ? event.clientY : event.clientX;
      dragRef.current = {
        startClientCoord: coord,
        startThumbOffset: currentThumbOffset,
        pointerId: event.pointerId,
      };
      try {
        thumb.setPointerCapture(event.pointerId);
      } catch {
        // JSDOM doesn't implement setPointerCapture; safe to ignore in tests.
      }
    },
    [isVertical],
  );

  const onPointerMove = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      const drag = dragRef.current;
      if (!drag || drag.pointerId !== event.pointerId) return;
      const trackEl = trackRef.current;
      if (!trackEl) return;
      const trackBox = trackEl.getBoundingClientRect();
      const trackSize = isVertical ? trackBox.height : trackBox.width;
      const contentSize = isVertical ? contentHeight : contentWidth;
      const coord = isVertical ? event.clientY : event.clientX;
      const delta = coord - drag.startClientCoord;
      const newThumbOffset = drag.startThumbOffset + delta;
      const newPosition = thumbDeltaToPosition({
        newThumbOffset,
        scale,
        trackSize,
        contentSize,
      });
      const tw = transformRef.current;
      if (!tw) return;
      const nextX = isVertical ? positionX : newPosition;
      const nextY = isVertical ? newPosition : positionY;
      tw.setTransform(nextX, nextY, scale, 0);
    },
    [isVertical, scale, contentHeight, contentWidth, transformRef, positionX, positionY],
  );

  const onPointerUp = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      const drag = dragRef.current;
      if (!drag || drag.pointerId !== event.pointerId) return;
      dragRef.current = null;
      try {
        (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId);
      } catch {
        // Already released.
      }
    },
    [],
  );

  // Render-nothing guard AFTER hooks (rules-of-hooks).
  if (scale <= 1.01) return null;

  const position = isVertical ? positionY : positionX;
  const contentSize = isVertical ? contentHeight : contentWidth;
  const trackEl = trackRef.current;
  const trackSize = trackEl
    ? (isVertical ? trackEl.getBoundingClientRect().height : trackEl.getBoundingClientRect().width)
    : contentSize;

  const { thumbSize, thumbOffset } = computeThumbGeometry({
    scale,
    position,
    trackSize,
    contentSize,
  });

  const overflow = contentSize * (scale - 1);
  const progressPct = overflow > 0
    ? Math.round((-position / overflow) * 100)
    : 0;

  const trackClass = `${trackBase} ${isVertical ? trackVertical : trackHorizontal}`;
  const thumbClass = `${thumbBase} ${isVertical ? thumbVertical : thumbHorizontal}`;
  const thumbStyle: React.CSSProperties = isVertical
    ? { top: `${thumbOffset}px`, height: `${thumbSize}px` }
    : { left: `${thumbOffset}px`, width: `${thumbSize}px` };

  return (
    <div
      ref={trackRef}
      role="scrollbar"
      aria-orientation={orientation}
      aria-controls="puzzle-grid"
      aria-label={isVertical ? 'Défilement vertical de la grille' : 'Défilement horizontal de la grille'}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={progressPct}
      className={trackClass}
    >
      <div
        data-testid={`grid-scrollbar-thumb-${orientation}`}
        className={thumbClass}
        style={thumbStyle}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      />
    </div>
  );
}
