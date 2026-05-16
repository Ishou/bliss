import { describe, it, expect } from 'vitest';
import {
  computeThumbGeometry,
  computeViewportRect,
  thumbDeltaToPosition,
  rectCenterToPosition,
  MIN_THUMB_SIZE_PX,
} from '@/ui/components/grid/transformMath';

describe('computeThumbGeometry', () => {
  it('at scale 1, thumb covers the whole track', () => {
    const g = computeThumbGeometry({ scale: 1, position: 0, trackSize: 200, contentSize: 200 });
    expect(g.thumbSize).toBe(200);
    expect(g.thumbOffset).toBe(0);
  });

  it('at scale 2 with content centered (position 0), thumb is half size at start of track', () => {
    // Note: position is 0 at the "left" bound; the library reports negative
    // position when content has been panned to expose the right side.
    const g = computeThumbGeometry({ scale: 2, position: 0, trackSize: 200, contentSize: 200 });
    expect(g.thumbSize).toBe(100);
    expect(g.thumbOffset).toBe(0);
  });

  it('at scale 2 fully panned right (position = -200), thumb is at end of track', () => {
    const g = computeThumbGeometry({ scale: 2, position: -200, trackSize: 200, contentSize: 200 });
    expect(g.thumbSize).toBe(100);
    expect(g.thumbOffset).toBe(100);
  });

  it('clamps thumb size to MIN_THUMB_SIZE_PX at very high scale', () => {
    // scale 50 on a 200 px track would otherwise produce a 4 px thumb.
    const g = computeThumbGeometry({ scale: 50, position: 0, trackSize: 200, contentSize: 200 });
    expect(g.thumbSize).toBe(MIN_THUMB_SIZE_PX);
  });

  it('clamps thumb offset within [0, trackSize - thumbSize]', () => {
    // position outside library bounds (shouldn't happen with limitToBounds,
    // but the math must degrade gracefully).
    const beyondMin = computeThumbGeometry({ scale: 2, position: -10_000, trackSize: 200, contentSize: 200 });
    expect(beyondMin.thumbOffset).toBe(100);
    const beyondMax = computeThumbGeometry({ scale: 2, position: 50, trackSize: 200, contentSize: 200 });
    expect(beyondMax.thumbOffset).toBe(0);
  });
});

describe('thumbDeltaToPosition', () => {
  it('inverts computeThumbGeometry: dragging the thumb to offset X yields the position that puts thumb back at X', () => {
    const scale = 2;
    const trackSize = 200;
    const contentSize = 200;
    // Move thumb from 0 to 25 px.
    const newPos = thumbDeltaToPosition({
      newThumbOffset: 25,
      scale,
      trackSize,
      contentSize,
    });
    const g = computeThumbGeometry({ scale, position: newPos, trackSize, contentSize });
    expect(g.thumbOffset).toBeCloseTo(25, 5);
  });

  it('clamps to library bounds: drag beyond track end caps position at min', () => {
    // At scale 2 with trackSize 200, minPosition = -200.
    const newPos = thumbDeltaToPosition({
      newThumbOffset: 500, // way past the end
      scale: 2,
      trackSize: 200,
      contentSize: 200,
    });
    expect(newPos).toBe(-200);
  });

  it('returns 0 at scale 1 (no overflow, no panning possible)', () => {
    const newPos = thumbDeltaToPosition({
      newThumbOffset: 50,
      scale: 1,
      trackSize: 200,
      contentSize: 200,
    });
    expect(newPos).toBe(0);
  });
});

describe('computeViewportRect', () => {
  it('at scale 1, rect covers the whole minimap', () => {
    const r = computeViewportRect({
      scale: 1,
      positionX: 0,
      positionY: 0,
      contentWidth: 200,
      contentHeight: 200,
      minimapWidth: 80,
      minimapHeight: 80,
    });
    expect(r.width).toBe(80);
    expect(r.height).toBe(80);
    expect(r.x).toBe(0);
    expect(r.y).toBe(0);
  });

  it('at scale 2 with position (0, 0), rect is top-left quarter', () => {
    const r = computeViewportRect({
      scale: 2,
      positionX: 0,
      positionY: 0,
      contentWidth: 200,
      contentHeight: 200,
      minimapWidth: 80,
      minimapHeight: 80,
    });
    expect(r.width).toBe(40);
    expect(r.height).toBe(40);
    expect(r.x).toBe(0);
    expect(r.y).toBe(0);
  });

  it('at scale 2 fully panned, rect is bottom-right quarter', () => {
    const r = computeViewportRect({
      scale: 2,
      positionX: -200,
      positionY: -200,
      contentWidth: 200,
      contentHeight: 200,
      minimapWidth: 80,
      minimapHeight: 80,
    });
    expect(r.x).toBe(40);
    expect(r.y).toBe(40);
  });
});

describe('rectCenterToPosition', () => {
  it('centering the viewport on the minimap centre at scale 2 yields the library-centered position', () => {
    // contentSize 200, scale 2, overflow = 200. Library positionX centered = -100.
    const p = rectCenterToPosition({
      centerX: 40, // minimap centre on an 80 px minimap
      centerY: 40,
      scale: 2,
      contentWidth: 200,
      contentHeight: 200,
      minimapWidth: 80,
      minimapHeight: 80,
    });
    expect(p.positionX).toBeCloseTo(-100, 5);
    expect(p.positionY).toBeCloseTo(-100, 5);
  });

  it('centering on minimap top-left clamps to position (0, 0)', () => {
    const p = rectCenterToPosition({
      centerX: 0,
      centerY: 0,
      scale: 2,
      contentWidth: 200,
      contentHeight: 200,
      minimapWidth: 80,
      minimapHeight: 80,
    });
    expect(p.positionX).toBe(0);
    expect(p.positionY).toBe(0);
  });

  it('centering on minimap bottom-right clamps to position (-overflow, -overflow)', () => {
    const p = rectCenterToPosition({
      centerX: 80,
      centerY: 80,
      scale: 2,
      contentWidth: 200,
      contentHeight: 200,
      minimapWidth: 80,
      minimapHeight: 80,
    });
    expect(p.positionX).toBe(-200);
    expect(p.positionY).toBe(-200);
  });
});

describe('thumb position round-trip', () => {
  const scale = 3;
  const trackSize = 300;
  const contentSize = 300;
  const overflow = contentSize * (scale - 1); // 600
  const positions = [0, -100, -200, -300, -400, -500, -600];

  it.each(positions)('position %f → thumb offset → position is stable', (pos) => {
    const g = computeThumbGeometry({ scale, position: pos, trackSize, contentSize });
    const recovered = thumbDeltaToPosition({
      newThumbOffset: g.thumbOffset,
      scale,
      trackSize,
      contentSize,
    });
    expect(recovered).toBeCloseTo(pos, 5);
    // Sanity: the overflow value is the negation of the most-negative
    // valid position (referenced so a future bound change here forces
    // the test to be updated).
    expect(-pos).toBeLessThanOrEqual(overflow);
  });
});
