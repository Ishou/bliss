// Pure math for translating between react-zoom-pan-pinch transform state
// (scale + positionX/Y) and the synthetic scrollbar / minimap UI. No
// React, no DOM, no library imports — so this module is trivially
// unit-testable.
//
// Coordinate model: matches react-zoom-pan-pinch with `limitToBounds: true`
// and `centerOnInit`. At scale s on a content of natural size W:
//   overflow = W * (s - 1)
//   position ∈ [-overflow, 0]
//     position =       0 → viewport shows the left/top edge of the content.
//     position = -overflow → viewport shows the right/bottom edge.
// The library's `centerOnInit` sets initial position to -overflow/2; the
// math here treats that as just another point in the valid range.
//
// Assumes `contentSize === viewportSize` on each axis (in this codebase
// the grid content is sized to fit its frame). If a caller violates that
// equality the `overflow` derivation no longer matches the library's
// actual position-space overflow, and scroll math will silently drift.

export const MIN_THUMB_SIZE_PX = 24;

const clamp = (v: number, lo: number, hi: number): number =>
  Math.max(lo, Math.min(hi, v));

export interface ThumbGeometryInput {
  /** Current transform scale (≥ 1). */
  scale: number;
  /** Library position along the axis (negative when panned away from origin). */
  position: number;
  /** Visible track length in px. */
  trackSize: number;
  /** Natural (unscaled) content size in px along the same axis. */
  contentSize: number;
}

export interface ThumbGeometry {
  thumbSize: number;
  thumbOffset: number;
}

export function computeThumbGeometry({
  scale,
  position,
  trackSize,
  contentSize,
}: ThumbGeometryInput): ThumbGeometry {
  if (scale <= 1 || contentSize <= 0 || trackSize <= 0) {
    return { thumbSize: trackSize, thumbOffset: 0 };
  }
  const thumbSize = Math.max(MIN_THUMB_SIZE_PX, trackSize / scale);
  const overflow = contentSize * (scale - 1);
  const progress = clamp(-position / overflow, 0, 1);
  const thumbOffset = progress * (trackSize - thumbSize);
  return { thumbSize, thumbOffset };
}

export interface ThumbDeltaInput {
  newThumbOffset: number;
  scale: number;
  trackSize: number;
  contentSize: number;
}

export function thumbDeltaToPosition({
  newThumbOffset,
  scale,
  trackSize,
  contentSize,
}: ThumbDeltaInput): number {
  if (scale <= 1 || contentSize <= 0 || trackSize <= 0) return 0;
  const thumbSize = Math.max(MIN_THUMB_SIZE_PX, trackSize / scale);
  const overflow = contentSize * (scale - 1);
  const denom = trackSize - thumbSize;
  if (denom <= 0) return 0;
  const progress = clamp(newThumbOffset / denom, 0, 1);
  return -progress * overflow;
}

export interface ViewportRectInput {
  scale: number;
  positionX: number;
  positionY: number;
  contentWidth: number;
  contentHeight: number;
  minimapWidth: number;
  minimapHeight: number;
}

export interface ViewportRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export function computeViewportRect({
  scale,
  positionX,
  positionY,
  contentWidth,
  contentHeight,
  minimapWidth,
  minimapHeight,
}: ViewportRectInput): ViewportRect {
  if (scale <= 1) {
    return { x: 0, y: 0, width: minimapWidth, height: minimapHeight };
  }
  const width = minimapWidth / scale;
  const height = minimapHeight / scale;
  const overflowX = contentWidth * (scale - 1);
  const overflowY = contentHeight * (scale - 1);
  const progressX = overflowX > 0 ? clamp(-positionX / overflowX, 0, 1) : 0;
  const progressY = overflowY > 0 ? clamp(-positionY / overflowY, 0, 1) : 0;
  const x = progressX * (minimapWidth - width);
  const y = progressY * (minimapHeight - height);
  return { x, y, width, height };
}

export interface RectCenterInput {
  centerX: number;
  centerY: number;
  scale: number;
  contentWidth: number;
  contentHeight: number;
  minimapWidth: number;
  minimapHeight: number;
}

export function rectCenterToPosition({
  centerX,
  centerY,
  scale,
  contentWidth,
  contentHeight,
  minimapWidth,
  minimapHeight,
}: RectCenterInput): { positionX: number; positionY: number } {
  if (scale <= 1) return { positionX: 0, positionY: 0 };
  const rectWidth = minimapWidth / scale;
  const rectHeight = minimapHeight / scale;
  const rectLeft = clamp(centerX - rectWidth / 2, 0, minimapWidth - rectWidth);
  const rectTop = clamp(centerY - rectHeight / 2, 0, minimapHeight - rectHeight);
  const progressX = (minimapWidth - rectWidth) > 0
    ? rectLeft / (minimapWidth - rectWidth)
    : 0;
  const progressY = (minimapHeight - rectHeight) > 0
    ? rectTop / (minimapHeight - rectHeight)
    : 0;
  const overflowX = contentWidth * (scale - 1);
  const overflowY = contentHeight * (scale - 1);
  const positionX = -progressX * overflowX;
  const positionY = -progressY * overflowY;
  // Normalize -0 → 0 so callers can compare with Object.is / toBe(0).
  return {
    positionX: positionX === 0 ? 0 : positionX,
    positionY: positionY === 0 ? 0 : positionY,
  };
}
