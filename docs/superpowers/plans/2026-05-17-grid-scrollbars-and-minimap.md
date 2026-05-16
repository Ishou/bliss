# Grid Scrollbars & Minimap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add synthetic scrollbars and a minimap as overlays inside the existing puzzle grid so a zoomed-in player can see where their viewport sits and pan via thumb/minimap drag.

**Architecture:** Two new React components (`GridScrollbar`, `GridMinimap`) are mounted as siblings of the grid inside the existing `gridFrame` div, but only when `scale > 1.01`. Both read the live transform state from `react-zoom-pan-pinch`'s `onTransform` callback (rAF-coalesced into a single `useState`) and pan the grid via the library's imperative `setTransform`. Pure transform math lives in a separate `transformMath.ts` module to keep it unit-testable.

**Tech Stack:** React 19, TypeScript, Panda CSS, `react-zoom-pan-pinch` (existing), Vitest + React Testing Library (unit/component), Playwright (E2E), axe-core via Playwright (a11y). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-17-grid-scrollbars-and-minimap-design.md`

**Branch & worktree:** `feat/grid-scrollbars-minimap` at `/Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap`. All paths below are relative to that worktree root.

---

## File Structure

**Create:**
- `frontend/src/ui/components/grid/transformMath.ts` — pure math helpers (no React, no DOM).
- `frontend/src/ui/components/grid/GridScrollbar.tsx` — horizontal/vertical scrollbar overlay.
- `frontend/src/ui/components/grid/GridMinimap.tsx` — minimap overlay.
- `frontend/tests/grid-transform-math.test.ts` — unit tests for the math module.
- `frontend/tests/grid-scrollbar.test.tsx` — component tests.
- `frontend/tests/grid-minimap.test.tsx` — component tests.
- `frontend/tests/grid-scrollbars-wireup.test.tsx` — integration tests in `Grid`.
- `frontend/e2e/grid-scrollbars-and-minimap.spec.ts` — real-pointer E2E.

**Modify:**
- `frontend/src/ui/components/grid/Grid.tsx` — track full transform state, mount the new components inside `gridFrame`, add an `id="puzzle-grid"` to the `<div role="grid">` so scrollbars can reference it via `aria-controls`.

No other files are touched. `Grid.tsx` is at ~990 lines today; this plan does not extract `useGridTransformState` (flagged as a follow-up in the spec — implementer may carve it out if `Grid.tsx` becomes hard to read after Task 4, but it's not in the critical path).

**Conventions used:**
- Test files live flat under `frontend/tests/` (existing pattern, see `grid-render.test.tsx`).
- Imports use `@/` alias (resolves to `frontend/src/`).
- Property-based testing: this repo does **not** currently use `fast-check`. Use table-driven test cases instead (adding a runtime dep needs an ADR — outside this PR's scope).
- E2E lives under `frontend/e2e/` as `*.spec.ts`.

---

## Task 1: transformMath module

**Files:**
- Create: `frontend/src/ui/components/grid/transformMath.ts`
- Test: `frontend/tests/grid-transform-math.test.ts`

This module hosts the pure math that translates between (scale, positionX, positionY) and (thumb size, thumb offset, viewport-rect size, viewport-rect offset). No React, no DOM, no library imports — so it's trivially unit-testable.

- [ ] **Step 1.1: Write failing tests for `computeThumbGeometry`**

Create `frontend/tests/grid-transform-math.test.ts`:

```ts
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

// Round-trip table — for a fixed scale, varying position through the
// library's valid range should produce thumb offsets in [0, trackSize -
// thumbSize], and inverting those offsets should reproduce the position
// (modulo clamping at the bounds).
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
    // Original position is within bounds, so the round-trip should match
    // within sub-pixel precision.
    expect(recovered).toBeCloseTo(pos, 5);
    // Sanity: the overflow value is the negation of the most-negative
    // valid position (referenced so a future bound change here forces
    // the test to be updated).
    expect(-pos).toBeLessThanOrEqual(overflow);
  });
});
```

- [ ] **Step 1.2: Run the tests and confirm they fail**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap/frontend
pnpm vitest run tests/grid-transform-math.test.ts
```

Expected: all tests fail with "Cannot find module '@/ui/components/grid/transformMath'" or similar.

- [ ] **Step 1.3: Implement `transformMath.ts`**

Create `frontend/src/ui/components/grid/transformMath.ts`:

```ts
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

/**
 * Where the thumb should be drawn on the track for a given transform state.
 * Returns {thumbSize, thumbOffset} both in track-space pixels.
 */
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
  // progress = 0 at position=0 (origin), 1 at position=-overflow (panned fully away).
  const progress = clamp(-position / overflow, 0, 1);
  const thumbOffset = progress * (trackSize - thumbSize);
  return { thumbSize, thumbOffset };
}

export interface ThumbDeltaInput {
  /** Where the thumb has been dragged to, in track-space px. */
  newThumbOffset: number;
  scale: number;
  trackSize: number;
  contentSize: number;
}

/** Given a new thumb offset, return the library `position` to set. */
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
  /** Natural (unscaled) content width in px. */
  contentWidth: number;
  /** Natural (unscaled) content height in px. */
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

/**
 * Where (in minimap-space px) the translucent rectangle should be drawn
 * to mark the part of the content that is currently visible.
 */
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
  /** Where the user clicked on the minimap, in minimap-space px. */
  centerX: number;
  centerY: number;
  scale: number;
  contentWidth: number;
  contentHeight: number;
  minimapWidth: number;
  minimapHeight: number;
}

/**
 * Given a desired centre point on the minimap, return the library
 * (positionX, positionY) that would place the viewport's centre there.
 * Clamps to the library's bounds.
 */
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
  return {
    positionX: -progressX * overflowX,
    positionY: -progressY * overflowY,
  };
}
```

- [ ] **Step 1.4: Run the tests and confirm they pass**

```sh
pnpm vitest run tests/grid-transform-math.test.ts
```

Expected: all tests pass.

- [ ] **Step 1.5: Run typecheck**

```sh
pnpm typecheck
```

Expected: PASS (no new errors).

- [ ] **Step 1.6: Commit**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap
git add frontend/src/ui/components/grid/transformMath.ts frontend/tests/grid-transform-math.test.ts
git commit -s -m "$(cat <<'EOF'
feat(frontend-grid): transform-math helpers for scrollbar + minimap

Pure functions translating react-zoom-pan-pinch's (scale, positionX/Y)
into thumb geometry and viewport-rect geometry, plus their inverses for
drag interactions. No React, no DOM; unit-testable in isolation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: GridScrollbar component

**Files:**
- Create: `frontend/src/ui/components/grid/GridScrollbar.tsx`
- Test: `frontend/tests/grid-scrollbar.test.tsx`

A single component that renders either the horizontal or vertical scrollbar overlay. Reads transform state from props, panes the grid via the imperative `transformRef.current.setTransform(...)`. Uses native pointer events with capture to track drags reliably (no global mousemove listener — set `setPointerCapture` on the thumb).

- [ ] **Step 2.1: Write failing component tests**

Create `frontend/tests/grid-scrollbar.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import { GridScrollbar } from '@/ui/components/grid/GridScrollbar';

const makeRef = (overrides?: Partial<ReactZoomPanPinchContentRef['state']>) => {
  const setTransform = vi.fn();
  const ref = {
    current: {
      state: { scale: 1, positionX: 0, positionY: 0, ...overrides } as ReactZoomPanPinchContentRef['state'],
      setTransform,
    } as unknown as ReactZoomPanPinchContentRef,
  };
  return { ref, setTransform };
};

describe('GridScrollbar', () => {
  it('renders nothing at scale 1', () => {
    const { ref } = makeRef({ scale: 1 });
    const { container } = render(
      <GridScrollbar
        orientation="vertical"
        transformRef={ref}
        scale={1}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders a vertical scrollbar with role=scrollbar at scale > 1', () => {
    const { ref } = makeRef({ scale: 2 });
    render(
      <GridScrollbar
        orientation="vertical"
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const bar = screen.getByRole('scrollbar', { name: /vertical/i });
    expect(bar).toHaveAttribute('aria-orientation', 'vertical');
    expect(bar).toHaveAttribute('aria-controls', 'puzzle-grid');
    expect(bar).toHaveAttribute('aria-valuemin', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '100');
  });

  it('renders a horizontal scrollbar with aria-orientation=horizontal', () => {
    const { ref } = makeRef({ scale: 2 });
    render(
      <GridScrollbar
        orientation="horizontal"
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const bar = screen.getByRole('scrollbar', { name: /horizontal/i });
    expect(bar).toHaveAttribute('aria-orientation', 'horizontal');
  });

  it('aria-valuenow reflects scroll progress (0 at start, 100 at end)', () => {
    const { ref } = makeRef({ scale: 2 });
    const { rerender } = render(
      <GridScrollbar
        orientation="vertical"
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    let bar = screen.getByRole('scrollbar');
    expect(bar.getAttribute('aria-valuenow')).toBe('0');

    // Fully panned: positionY = -contentHeight * (scale - 1) = -200.
    rerender(
      <GridScrollbar
        orientation="vertical"
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={-200}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    bar = screen.getByRole('scrollbar');
    expect(bar.getAttribute('aria-valuenow')).toBe('100');
  });

  it('drags the thumb and calls setTransform with the new position', () => {
    const { ref, setTransform } = makeRef({ scale: 2 });
    render(
      <GridScrollbar
        orientation="vertical"
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const thumb = screen.getByTestId('grid-scrollbar-thumb-vertical');

    // jsdom doesn't lay out boxes, but the component reads its own
    // getBoundingClientRect() to translate page-space drags into
    // track-space. Stub the rects so the math is deterministic.
    const trackEl = screen.getByRole('scrollbar');
    trackEl.getBoundingClientRect = () => ({
      x: 100, y: 0, top: 0, left: 100, right: 108, bottom: 200,
      width: 8, height: 200, toJSON: () => ({}),
    });
    thumb.getBoundingClientRect = () => ({
      x: 100, y: 0, top: 0, left: 100, right: 108, bottom: 100,
      width: 8, height: 100, toJSON: () => ({}),
    });

    // Pointer-down on the thumb at its centre.
    thumb.dispatchEvent(new PointerEvent('pointerdown', {
      bubbles: true, clientX: 104, clientY: 50, pointerId: 1, button: 0,
    }));
    // Pointer-move 25 px down.
    thumb.dispatchEvent(new PointerEvent('pointermove', {
      bubbles: true, clientX: 104, clientY: 75, pointerId: 1,
    }));
    thumb.dispatchEvent(new PointerEvent('pointerup', {
      bubbles: true, clientX: 104, clientY: 75, pointerId: 1,
    }));

    // setTransform receives (positionX, positionY, scale, animationMs).
    expect(setTransform).toHaveBeenCalled();
    const lastCall = setTransform.mock.calls.at(-1);
    expect(lastCall?.[0]).toBe(0); // positionX unchanged for vertical scrollbar
    // 25 px drag on a 100-px-overflow scrollbar (trackSize - thumbSize = 100) →
    // progress 0.25 → positionY = -0.25 * 200 = -50.
    expect(lastCall?.[1]).toBeCloseTo(-50, 1);
    expect(lastCall?.[2]).toBe(2); // scale unchanged
  });
});
```

- [ ] **Step 2.2: Run the tests and confirm they fail**

```sh
pnpm vitest run tests/grid-scrollbar.test.tsx
```

Expected: all tests fail with "Cannot find module '@/ui/components/grid/GridScrollbar'".

- [ ] **Step 2.3: Implement `GridScrollbar.tsx`**

Create `frontend/src/ui/components/grid/GridScrollbar.tsx`:

```tsx
import { useCallback, useRef } from 'react';
import { css } from 'styled-system/css';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import { computeThumbGeometry, thumbDeltaToPosition } from './transformMath';

const TRACK_THICKNESS_PX = 8;
const TRACK_THICKNESS_PX_MOBILE = 6;

// Tracks live on the right edge (vertical) and bottom edge (horizontal)
// of the gridFrame. Track length = the full edge length; we leave a
// `TRACK_THICKNESS_PX` gap at the corner so the two scrollbars don't
// overlap.
const trackBase = css({
  position: 'absolute',
  bg: 'gridLine/15',
  transition: 'opacity 150ms ease',
  touchAction: 'none',
  // Bar is a pointer affordance; not in the tab order.
  // Sub-pixel jitter on the thumb during pan is masked by transition.
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
  bg: 'gridLine/70',
  borderRadius: '4px',
  cursor: 'grab',
  _active: { cursor: 'grabbing', bg: 'gridLine/90' },
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
  // Render nothing at rest; the parent already gates on isZoomedIn but
  // this guard makes the component safe to use independently.
  if (scale <= 1.01) return null;

  const isVertical = orientation === 'vertical';
  const position = isVertical ? positionY : positionX;
  const contentSize = isVertical ? contentHeight : contentWidth;

  const trackRef = useRef<HTMLDivElement | null>(null);
  // Drag state lives in a ref — we don't need a re-render while
  // dragging, only the imperative setTransform.
  const dragRef = useRef<{
    startClientCoord: number;
    startThumbOffset: number;
    pointerId: number;
  } | null>(null);

  // Measure the live track size from the DOM. Read on every render so
  // a viewport resize that shrinks the track is reflected immediately
  // in the thumb geometry (the parent re-renders on viewport resize
  // because the container-query units change `contentWidth/Height`).
  const trackEl = trackRef.current;
  const trackSize = trackEl
    ? (isVertical ? trackEl.getBoundingClientRect().height : trackEl.getBoundingClientRect().width)
    : contentSize; // pre-mount fallback; never actually rendered because guard above

  const { thumbSize, thumbOffset } = computeThumbGeometry({
    scale,
    position,
    trackSize,
    contentSize,
  });

  // Progress 0–100 for aria-valuenow.
  const overflow = contentSize * (scale - 1);
  const progressPct = overflow > 0
    ? Math.round((-position / overflow) * 100)
    : 0;

  const onPointerDown = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return; // primary button only (touch maps to 0)
    event.preventDefault();
    event.stopPropagation();
    const coord = isVertical ? event.clientY : event.clientX;
    dragRef.current = {
      startClientCoord: coord,
      startThumbOffset: thumbOffset,
      pointerId: event.pointerId,
    };
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
  }, [isVertical, thumbOffset]);

  const onPointerMove = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
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
  }, [isVertical, scale, trackSize, contentSize, transformRef, positionX, positionY]);

  const onPointerUp = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag || drag.pointerId !== event.pointerId) return;
    dragRef.current = null;
    try {
      (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId);
    } catch {
      // Already released (e.g. element removed). Ignore.
    }
  }, []);

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
```

- [ ] **Step 2.4: Run the tests and confirm they pass**

```sh
pnpm vitest run tests/grid-scrollbar.test.tsx
```

Expected: all 5 tests pass.

- [ ] **Step 2.5: Run typecheck**

```sh
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 2.6: Commit**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap
git add frontend/src/ui/components/grid/GridScrollbar.tsx frontend/tests/grid-scrollbar.test.tsx
git commit -s -m "$(cat <<'EOF'
feat(frontend-grid): GridScrollbar overlay with pointer-drag panning

Renders a thin draggable thumb on the right edge (vertical) or bottom
edge (horizontal) of the gridFrame whenever scale > 1. Reads transform
state from props, pans via the imperative setTransform on
transformRef. role=scrollbar with aria-controls + aria-valuenow.
Pointer capture keeps the drag attached to the thumb even when the
pointer leaves the track during a fast drag.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: GridMinimap component

**Files:**
- Create: `frontend/src/ui/components/grid/GridMinimap.tsx`
- Test: `frontend/tests/grid-minimap.test.tsx`

A small SVG of the puzzle topology with a translucent rectangle marking the visible viewport. Drag anywhere on the minimap to re-center the viewport.

- [ ] **Step 3.1: Write failing component tests**

Create `frontend/tests/grid-minimap.test.tsx`:

```tsx
import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import { SAMPLE_PUZZLE } from '@/domain';
import { GridMinimap } from '@/ui/components/grid/GridMinimap';

const makeRef = () => {
  const setTransform = vi.fn();
  const ref = {
    current: {
      state: { scale: 2, positionX: 0, positionY: 0 } as ReactZoomPanPinchContentRef['state'],
      setTransform,
    } as unknown as ReactZoomPanPinchContentRef,
  };
  return { ref, setTransform };
};

describe('GridMinimap', () => {
  it('renders nothing at scale 1', () => {
    const { ref } = makeRef();
    const { container } = render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        transformRef={ref}
        scale={1}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it('renders an accessible img with one rect per cell at scale > 1', () => {
    const { ref } = makeRef();
    const { container } = render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const minimap = screen.getByRole('img', { name: /aperçu de la grille/i });
    expect(minimap).toBeInTheDocument();
    // One <rect> per cell in the puzzle (block cells included as drawn
    // background; empty positions also draw a rect to keep coordinates
    // aligned).
    const rects = container.querySelectorAll('rect[data-cell-kind]');
    const expected = SAMPLE_PUZZLE.width * SAMPLE_PUZZLE.height;
    expect(rects.length).toBe(expected);
    // Plus exactly one viewport-rect overlay.
    expect(container.querySelectorAll('rect[data-role="viewport-rect"]')).toHaveLength(1);
  });

  it('pointer-down recenters the viewport on the pointer location', () => {
    const { ref, setTransform } = makeRef();
    render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const minimap = screen.getByRole('img', { name: /aperçu de la grille/i });
    // Stub the minimap rect: 80×80 at origin.
    minimap.getBoundingClientRect = () => ({
      x: 0, y: 0, top: 0, left: 0, right: 80, bottom: 80,
      width: 80, height: 80, toJSON: () => ({}),
    });

    minimap.dispatchEvent(new PointerEvent('pointerdown', {
      bubbles: true, clientX: 40, clientY: 40, pointerId: 1, button: 0,
    }));

    // Clicking the centre of an 80×80 minimap at scale 2 on a 200×200
    // content → desired centre is (40, 40) on minimap, which is the
    // middle of the content → library-centered position (-100, -100).
    expect(setTransform).toHaveBeenCalledTimes(1);
    const args = setTransform.mock.calls[0];
    expect(args[0]).toBeCloseTo(-100, 1); // positionX
    expect(args[1]).toBeCloseTo(-100, 1); // positionY
    expect(args[2]).toBe(2); // scale unchanged
  });

  it('pointer-move while held continues to re-center', () => {
    const { ref, setTransform } = makeRef();
    render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set()}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const minimap = screen.getByRole('img', { name: /aperçu de la grille/i });
    minimap.getBoundingClientRect = () => ({
      x: 0, y: 0, top: 0, left: 0, right: 80, bottom: 80,
      width: 80, height: 80, toJSON: () => ({}),
    });

    minimap.dispatchEvent(new PointerEvent('pointerdown', {
      bubbles: true, clientX: 20, clientY: 20, pointerId: 1, button: 0,
    }));
    minimap.dispatchEvent(new PointerEvent('pointermove', {
      bubbles: true, clientX: 60, clientY: 60, pointerId: 1,
    }));
    minimap.dispatchEvent(new PointerEvent('pointerup', {
      bubbles: true, clientX: 60, clientY: 60, pointerId: 1,
    }));

    expect(setTransform).toHaveBeenCalledTimes(2);
    // Second call should reflect the (60, 60) pointer location.
    const second = setTransform.mock.calls[1];
    // At (60, 60) on an 80-px minimap, rectCenter=(60,60); the rect
    // is 40 px wide (200/2 / 2.5 = ... actually 80/2 = 40).
    // Clamps to bottom-right: rectLeft = clamp(60 - 20, 0, 40) = 40;
    // progress = 40 / 40 = 1; positionX = -200.
    expect(second[0]).toBe(-200);
    expect(second[1]).toBe(-200);
  });

  it('validated letter cells render with a distinct fill', () => {
    const { ref } = makeRef();
    // Pick a letter cell from the sample puzzle.
    const letterCell = SAMPLE_PUZZLE.cells.find((c) => c.kind === 'letter');
    if (!letterCell) throw new Error('sample puzzle has no letter cell');
    const key = `${letterCell.position.row},${letterCell.position.col}`;
    const { container } = render(
      <GridMinimap
        puzzle={SAMPLE_PUZZLE}
        validatedPositions={new Set([key])}
        transformRef={ref}
        scale={2}
        positionX={0}
        positionY={0}
        contentWidth={200}
        contentHeight={200}
      />,
    );
    const rect = container.querySelector(
      `rect[data-cell-kind="letter"][data-row="${letterCell.position.row}"][data-col="${letterCell.position.col}"]`,
    );
    expect(rect).toHaveAttribute('data-validated', 'true');
  });
});
```

- [ ] **Step 3.2: Run the tests and confirm they fail**

```sh
pnpm vitest run tests/grid-minimap.test.tsx
```

Expected: all tests fail with module-not-found.

- [ ] **Step 3.3: Implement `GridMinimap.tsx`**

Create `frontend/src/ui/components/grid/GridMinimap.tsx`:

```tsx
import { useCallback, useMemo, useRef } from 'react';
import { css } from 'styled-system/css';
import type { ReactZoomPanPinchContentRef } from 'react-zoom-pan-pinch';
import type { Position, Puzzle } from '@/domain';
import {
  computeViewportRect,
  rectCenterToPosition,
} from './transformMath';

const MINIMAP_SIZE_DESKTOP_PX = 120;
const MINIMAP_SIZE_MOBILE_PX = 80;

const minimapContainer = css({
  position: 'absolute',
  top: '8px',
  right: '12px',
  width: `${MINIMAP_SIZE_DESKTOP_PX}px`,
  height: `${MINIMAP_SIZE_DESKTOP_PX}px`,
  bg: 'bg.canvas',
  border: '1px solid',
  borderColor: 'gridLine',
  borderRadius: '4px',
  boxShadow: 'sm',
  touchAction: 'none',
  cursor: 'crosshair',
  transition: 'opacity 150ms ease',
  '@media (max-width: 480px)': {
    width: `${MINIMAP_SIZE_MOBILE_PX}px`,
    height: `${MINIMAP_SIZE_MOBILE_PX}px`,
    top: 'auto',
    right: 'auto',
    bottom: '12px',
    left: '8px',
  },
});

const positionKey = (p: Position) => `${p.row},${p.col}`;

export interface GridMinimapProps {
  puzzle: Puzzle;
  validatedPositions: ReadonlySet<string>;
  transformRef: React.RefObject<ReactZoomPanPinchContentRef | null>;
  scale: number;
  positionX: number;
  positionY: number;
  contentWidth: number;
  contentHeight: number;
}

export function GridMinimap({
  puzzle,
  validatedPositions,
  transformRef,
  scale,
  positionX,
  positionY,
  contentWidth,
  contentHeight,
}: GridMinimapProps) {
  if (scale <= 1.01) return null;

  const minimapRef = useRef<HTMLDivElement | null>(null);
  const dragRef = useRef<{ pointerId: number } | null>(null);

  const cellByKey = useMemo(() => {
    const m = new Map<string, (typeof puzzle.cells)[number]>();
    for (const c of puzzle.cells) m.set(positionKey(c.position), c);
    return m;
  }, [puzzle.cells]);

  // Use SVG viewBox = puzzle dims so each cell is a 1×1 rect; CSS
  // resizes the SVG to the minimap container, so the math is
  // resolution-independent.
  const viewBoxW = puzzle.width;
  const viewBoxH = puzzle.height;

  // We read live minimap size from the DOM when computing the viewport
  // rect — the minimap responds to viewport breakpoints, and the
  // overlay rect must match exactly. Pre-mount fallback uses the
  // desktop size; never visible because of the scale guard.
  const minimapEl = minimapRef.current;
  const minimapBox = minimapEl?.getBoundingClientRect();
  const minimapWidthPx = minimapBox?.width ?? MINIMAP_SIZE_DESKTOP_PX;
  const minimapHeightPx = minimapBox?.height ?? MINIMAP_SIZE_DESKTOP_PX;

  const rect = computeViewportRect({
    scale,
    positionX,
    positionY,
    contentWidth,
    contentHeight,
    minimapWidth: viewBoxW,
    minimapHeight: viewBoxH,
  });

  const recenterFromPointer = useCallback(
    (clientX: number, clientY: number) => {
      const el = minimapRef.current;
      const tw = transformRef.current;
      if (!el || !tw) return;
      const box = el.getBoundingClientRect();
      // Translate page-px → minimap-px → viewBox units (puzzle cells).
      const localX = clientX - box.left;
      const localY = clientY - box.top;
      const vbX = (localX / box.width) * viewBoxW;
      const vbY = (localY / box.height) * viewBoxH;
      const next = rectCenterToPosition({
        centerX: vbX,
        centerY: vbY,
        scale,
        contentWidth,
        contentHeight,
        minimapWidth: viewBoxW,
        minimapHeight: viewBoxH,
      });
      tw.setTransform(next.positionX, next.positionY, scale, 0);
    },
    [scale, contentWidth, contentHeight, transformRef, viewBoxW, viewBoxH],
  );

  const onPointerDown = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      if (event.button !== 0) return;
      event.preventDefault();
      event.stopPropagation();
      dragRef.current = { pointerId: event.pointerId };
      (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
      recenterFromPointer(event.clientX, event.clientY);
    },
    [recenterFromPointer],
  );

  const onPointerMove = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      const drag = dragRef.current;
      if (!drag || drag.pointerId !== event.pointerId) return;
      recenterFromPointer(event.clientX, event.clientY);
    },
    [recenterFromPointer],
  );

  const onPointerUp = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      const drag = dragRef.current;
      if (!drag || drag.pointerId !== event.pointerId) return;
      dragRef.current = null;
      try {
        (event.currentTarget as HTMLElement).releasePointerCapture(event.pointerId);
      } catch {
        // Already released; ignore.
      }
    },
    [],
  );

  // Build the topology rects. Every (row, col) within puzzle bounds gets
  // a rect — even empty positions render as a block cell, mirroring the
  // main grid's BlockCellView fallback.
  const cellRects: React.ReactNode[] = [];
  for (let row = 0; row < puzzle.height; row++) {
    for (let col = 0; col < puzzle.width; col++) {
      const k = `${row},${col}`;
      const cell = cellByKey.get(k);
      const kind = cell?.kind ?? 'block';
      const validated = kind === 'letter' && validatedPositions.has(k);
      const fill = validated
        ? 'var(--colors-validated)'
        : kind === 'block'
        ? 'var(--colors-grid-line)'
        : kind === 'definition'
        ? 'var(--colors-clue)'
        : 'white';
      cellRects.push(
        <rect
          key={k}
          data-cell-kind={kind}
          data-row={row}
          data-col={col}
          data-validated={validated || undefined}
          x={col}
          y={row}
          width={1}
          height={1}
          fill={fill}
        />,
      );
    }
  }

  return (
    <div
      ref={minimapRef}
      role="img"
      aria-label="Aperçu de la grille — la zone surlignée indique la partie visible"
      className={minimapContainer}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
    >
      <svg
        viewBox={`0 0 ${viewBoxW} ${viewBoxH}`}
        width="100%"
        height="100%"
        preserveAspectRatio="none"
        // The whole minimap is the interactive surface; the SVG never
        // hijacks events.
        style={{ pointerEvents: 'none' }}
        aria-hidden="true"
      >
        {cellRects}
        <rect
          data-role="viewport-rect"
          x={rect.x}
          y={rect.y}
          width={rect.width}
          height={rect.height}
          fill="var(--colors-grid-line)"
          fillOpacity={0.3}
          stroke="var(--colors-grid-line)"
          strokeOpacity={0.7}
          strokeWidth={0.1}
        />
      </svg>
    </div>
  );
}
```

> **Note on colors:** the strings `var(--colors-validated)`, `var(--colors-grid-line)`, `var(--colors-clue)` rely on Panda CSS color tokens that may or may not exist verbatim in this repo. **Before implementing, grep the codebase for the actual token names** (try `grep -r "colors-grid-line\|colors-clue\|gridLine" frontend/styled-system frontend/panda.config.ts`). If the token names differ, substitute them. If the validated-cell color is exposed only via a `css({ bg: 'sage.X' })` rule and not as a CSS variable, either (a) add a token for it in `panda.config.ts`, or (b) hardcode the literal hex from the panda config in this file with a comment.

- [ ] **Step 3.4: Resolve the color token references**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap
grep -r "gridLine\|validated\|clue\b" frontend/panda.config.ts frontend/src/ui/styles 2>/dev/null | head -30
```

Identify the actual token names. Update the `fill=` props in `GridMinimap.tsx` to match. If a needed token doesn't exist, prefer adding it to `panda.config.ts` over hardcoding hex (single source of truth for the palette).

- [ ] **Step 3.5: Run the tests and confirm they pass**

```sh
pnpm vitest run tests/grid-minimap.test.tsx
```

Expected: all 5 tests pass.

- [ ] **Step 3.6: Run typecheck**

```sh
pnpm typecheck
```

Expected: PASS.

- [ ] **Step 3.7: Commit**

```sh
git add frontend/src/ui/components/grid/GridMinimap.tsx frontend/tests/grid-minimap.test.tsx
# (and panda.config.ts if a new token was added in Step 3.4)
git commit -s -m "$(cat <<'EOF'
feat(frontend-grid): GridMinimap overlay with drag-to-recenter

Renders an SVG of the puzzle topology (one rect per cell) with a
translucent rectangle marking the visible viewport. Drag anywhere on
the minimap to re-center the viewport via setTransform. Validated
cells get a distinct fill so progress shows on the minimap.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Wire components into Grid.tsx

**Files:**
- Modify: `frontend/src/ui/components/grid/Grid.tsx`
- Test: `frontend/tests/grid-scrollbars-wireup.test.tsx`

Add transform-state tracking, measure the gridFrame's natural size, mount the new components inside `gridFrame`, and give the inner `<div role="grid">` an `id="puzzle-grid"` so the scrollbars' `aria-controls` resolves.

- [ ] **Step 4.1: Write failing wire-up tests**

Create `frontend/tests/grid-scrollbars-wireup.test.tsx`:

```tsx
import { render, screen, act } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import userEvent from '@testing-library/user-event';
import { SAMPLE_PUZZLE } from '@/domain';
import { Grid } from '@/ui/components/grid';

describe('Grid scrollbars + minimap wireup', () => {
  it('does not render scrollbars or minimap at scale 1', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    expect(screen.queryByRole('scrollbar')).toBeNull();
    expect(screen.queryByRole('img', { name: /aperçu/i })).toBeNull();
  });

  it('renders both scrollbars and a minimap after zooming in', async () => {
    const user = userEvent.setup();
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    // GridZoomControls exposes a "+" button labelled "Zoom avant".
    const zoomIn = screen.getByRole('button', { name: /zoom avant/i });
    await user.click(zoomIn);
    await user.click(zoomIn);
    // The library's animation is 150 ms; rAF a couple of times.
    await act(async () => {
      await new Promise((r) => setTimeout(r, 200));
    });
    const bars = screen.getAllByRole('scrollbar');
    expect(bars).toHaveLength(2);
    expect(screen.getByRole('img', { name: /aperçu/i })).toBeInTheDocument();
  });

  it('the inner <div role="grid"> has id="puzzle-grid" so aria-controls resolves', () => {
    render(<Grid puzzle={SAMPLE_PUZZLE} />);
    expect(screen.getByRole('grid')).toHaveAttribute('id', 'puzzle-grid');
  });
});
```

- [ ] **Step 4.2: Run the test and confirm it fails**

```sh
pnpm vitest run tests/grid-scrollbars-wireup.test.tsx
```

Expected: the third assertion fails immediately (`id` missing); the second fails because the components aren't mounted yet.

> **Note on test 2:** if the `+` zoom button's accessible name in the current codebase differs from `/zoom avant/i`, update the regex. Check `frontend/src/ui/components/grid/GridZoomControls.tsx` for the actual French labels — they're owned by that file.

- [ ] **Step 4.3: Modify `Grid.tsx` — add transform state tracking**

Open `frontend/src/ui/components/grid/Grid.tsx`.

First, add the new imports at the top of the file (next to the existing `Cell` / `CurrentCluePanel` / `GridZoomControls` imports):

```tsx
import { GridMinimap } from './GridMinimap';
import { GridScrollbar } from './GridScrollbar';
```

Second, locate the existing `useState`/`useRef` block around line 453 that declares `isZoomedIn`, `isMaxZoom`, `isPanning`, `previousScaleRef`. Add a new state and a rAF ref **immediately after** them:

```tsx
// Full transform tuple, rAF-coalesced from onTransform so the
// scrollbars and minimap re-render in step with the library's
// transform without a setState-per-frame.
const [transform, setTransformState] = useState({
  scale: 1,
  positionX: 0,
  positionY: 0,
});
const transformRafRef = useRef<number | null>(null);
```

Third, locate `handleTransform` (around line 477). Extend its body to also schedule the rAF state update. The full new body:

```tsx
const handleTransform = useCallback(
  (_ref: { state: { scale: number } }, state: { scale: number; positionX?: number; positionY?: number }) => {
    setIsZoomedIn(state.scale > 1.01);
    setIsMaxZoom(state.scale >= 4 - 0.01);
    if (state.scale <= 1.01 && previousScaleRef.current > 1.01) {
      const tw = transformWrapperRef.current;
      if (tw) tw.centerView(1, 150);
    }
    previousScaleRef.current = state.scale;

    // rAF-coalesce the full-tuple state update. Read from the live
    // ref inside the rAF callback so we always commit the most recent
    // value the library has applied (the library mutates its own
    // state object on every frame).
    if (transformRafRef.current === null) {
      transformRafRef.current = requestAnimationFrame(() => {
        transformRafRef.current = null;
        const live = transformWrapperRef.current?.state;
        if (!live) return;
        setTransformState({
          scale: live.scale,
          positionX: live.positionX,
          positionY: live.positionY,
        });
      });
    }
  },
  [],
);
```

Fourth, immediately above the `handleTransform` declaration, add a cleanup effect for the rAF (otherwise a pending rAF on unmount calls setState after teardown):

```tsx
useEffect(() => {
  return () => {
    if (transformRafRef.current !== null) {
      cancelAnimationFrame(transformRafRef.current);
      transformRafRef.current = null;
    }
  };
}, []);
```

Fifth, add the gridFrame natural-size measurement. Right after the existing `gridFrameRef` declaration (~line 439) add:

```tsx
const [gridFramePx, setGridFramePx] = useState({ width: 0, height: 0 });
useEffect(() => {
  const el = gridFrameRef.current;
  if (!el || typeof ResizeObserver === 'undefined') return;
  // Read offsetWidth/offsetHeight (NOT getBoundingClientRect): the
  // TransformComponent applies a CSS transform to a parent of
  // gridFrame, which getBoundingClientRect would multiply through.
  // offsetWidth/Height reflect the layout box only.
  const update = () => {
    setGridFramePx({ width: el.offsetWidth, height: el.offsetHeight });
  };
  update();
  const ro = new ResizeObserver(update);
  ro.observe(el);
  return () => ro.disconnect();
}, []);
```

Sixth, modify the `<div role="grid">` opening tag to add an `id`:

```tsx
<div
  role="grid"
  id="puzzle-grid"
  aria-label={puzzle.title}
  lang={puzzle.language}
  className={gridContainer}
  style={templateStyle}
>
```

Seventh, mount the new components inside `gridFrame`, immediately after the closing `</div>` of `gridContainer` and before the closing `</div>` of `gridFrame`. The full revised gridFrame block:

```tsx
<div ref={gridFrameRef} className={gridFrame}>
  <div
    role="grid"
    id="puzzle-grid"
    aria-label={puzzle.title}
    lang={puzzle.language}
    className={gridContainer}
    style={templateStyle}
  >
    {/* …existing rows.map(…) unchanged… */}
  </div>
  {isZoomedIn && gridFramePx.width > 0 && gridFramePx.height > 0 && (
    <>
      <GridScrollbar
        orientation="vertical"
        transformRef={transformWrapperRef}
        scale={transform.scale}
        positionX={transform.positionX}
        positionY={transform.positionY}
        contentWidth={gridFramePx.width}
        contentHeight={gridFramePx.height}
      />
      <GridScrollbar
        orientation="horizontal"
        transformRef={transformWrapperRef}
        scale={transform.scale}
        positionX={transform.positionX}
        positionY={transform.positionY}
        contentWidth={gridFramePx.width}
        contentHeight={gridFramePx.height}
      />
      <GridMinimap
        puzzle={puzzle}
        validatedPositions={validatedPositions ?? new Set()}
        transformRef={transformWrapperRef}
        scale={transform.scale}
        positionX={transform.positionX}
        positionY={transform.positionY}
        contentWidth={gridFramePx.width}
        contentHeight={gridFramePx.height}
      />
    </>
  )}
</div>
```

- [ ] **Step 4.4: Run the wire-up tests and confirm they pass**

```sh
pnpm vitest run tests/grid-scrollbars-wireup.test.tsx
```

Expected: all 3 tests pass.

- [ ] **Step 4.5: Run the full grid test suite to catch regressions**

```sh
pnpm vitest run tests/grid-
```

Expected: all existing `grid-*.test.tsx` tests still pass.

- [ ] **Step 4.6: Run typecheck and lint**

```sh
pnpm typecheck
pnpm lint
```

Expected: PASS for both.

- [ ] **Step 4.7: Run Konsist / boundary rules**

```sh
# eslint-plugin-boundaries runs as part of `pnpm lint`; confirm by
# re-running explicitly:
pnpm lint -- frontend/src/ui/components/grid
```

Expected: PASS. (If a layer violation surfaces — unlikely; we only added imports inside `ui/components/grid` — re-check the boundaries config.)

- [ ] **Step 4.8: Commit**

```sh
git add frontend/src/ui/components/grid/Grid.tsx frontend/tests/grid-scrollbars-wireup.test.tsx
git commit -s -m "$(cat <<'EOF'
feat(frontend-grid): mount scrollbars + minimap inside gridFrame

Tracks the full transform tuple via rAF-coalesced setState on
onTransform; measures gridFrame's natural box via ResizeObserver on
offsetWidth/offsetHeight; mounts GridScrollbar (×2) and GridMinimap
as siblings of the role=grid div whenever scale > 1.01. Adds
id="puzzle-grid" so the scrollbars' aria-controls resolves.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: E2E with real-pointer interactions

**Files:**
- Create: `frontend/e2e/grid-scrollbars-and-minimap.spec.ts`

Exercise the new components with real Playwright pointer gestures (`mouse.move`/`down`/`up` with `steps`, `page.touchscreen` for the mobile case). No synthetic event dispatch, no calls into the library.

- [ ] **Step 5.1: Write the E2E spec**

Create `frontend/e2e/grid-scrollbars-and-minimap.spec.ts`:

```ts
/**
 * Grid scrollbars + minimap — real-pointer behavior.
 *
 * The spec requires that every interaction is a real Playwright
 * pointer gesture (no synthetic events, no library calls), so rAF
 * coalescing, drag thresholds, and the focus-revert flow are
 * actually exercised.
 */
import { expect, test, type Locator, type Page } from '@playwright/test';

async function gridReady(page: Page): Promise<void> {
  await page.goto('/');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  await page.evaluate(() => new Promise((r) => requestAnimationFrame(() => r(null))));
}

async function zoomIn(page: Page, clicks = 2): Promise<void> {
  const zoomInBtn = page.getByRole('button', { name: /zoom avant/i });
  for (let i = 0; i < clicks; i++) {
    await zoomInBtn.click();
    // Wait for the library's 150 ms animation per click.
    await page.waitForTimeout(180);
  }
}

async function getCenter(locator: Locator): Promise<{ x: number; y: number }> {
  const box = await locator.boundingBox();
  if (!box) throw new Error('locator has no bounding box');
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

test.describe('Grid scrollbars + minimap', () => {
  test('scrollbars and minimap appear only after zoom', async ({ page }) => {
    await gridReady(page);

    // At rest: nothing.
    await expect(page.getByRole('scrollbar')).toHaveCount(0);
    await expect(page.getByRole('img', { name: /aperçu de la grille/i })).toHaveCount(0);

    await zoomIn(page, 2);

    await expect(page.getByRole('scrollbar', { name: /vertical/i })).toBeVisible();
    await expect(page.getByRole('scrollbar', { name: /horizontal/i })).toBeVisible();
    await expect(page.getByRole('img', { name: /aperçu de la grille/i })).toBeVisible();
  });

  test('vertical scrollbar thumb drag pans the grid (real mouse gesture, 20 steps)', async ({ page }) => {
    await gridReady(page);
    await zoomIn(page, 2);

    const thumb = page.getByTestId('grid-scrollbar-thumb-vertical');
    const start = await getCenter(thumb);

    // Measure the focused cell's position before the drag.
    const focusedBefore = await page.evaluate(() => {
      const el = document.activeElement;
      if (!(el instanceof HTMLElement)) return null;
      const r = el.getBoundingClientRect();
      return { top: r.top };
    });

    // Real human-like drag: mouse.move to start, mouse.down,
    // mouse.move to end with 20 intermediate steps over ~300 ms, mouse.up.
    await page.mouse.move(start.x, start.y);
    await page.mouse.down();
    await page.mouse.move(start.x, start.y + 80, { steps: 20 });
    await page.mouse.up();

    // The grid should have scrolled vertically. If a cell is focused,
    // its rect.top moves up (or the grid clipping moves equivalently).
    // We assert on the thumb's aria-valuenow instead, which is the
    // most direct signal of scroll progress.
    const valuenow = await thumb.evaluate((el) => {
      const bar = el.closest('[role="scrollbar"]');
      return bar?.getAttribute('aria-valuenow');
    });
    expect(Number(valuenow)).toBeGreaterThan(20);

    // If a cell was focused, sanity-check that its on-screen position
    // changed.
    if (focusedBefore !== null) {
      const focusedAfter = await page.evaluate(() => {
        const el = document.activeElement;
        if (!(el instanceof HTMLElement)) return null;
        return el.getBoundingClientRect().top;
      });
      if (focusedAfter !== null) {
        expect(focusedAfter).not.toBe(focusedBefore.top);
      }
    }
  });

  test('minimap drag continuously re-centers as the pointer moves (10 steps)', async ({ page }) => {
    await gridReady(page);
    await zoomIn(page, 2);

    const minimap = page.getByRole('img', { name: /aperçu de la grille/i });
    const box = await minimap.boundingBox();
    if (!box) throw new Error('minimap has no bounding box');

    const startX = box.x + box.width * 0.25;
    const startY = box.y + box.height * 0.25;
    const endX = box.x + box.width * 0.75;
    const endY = box.y + box.height * 0.75;

    // Capture the thumb aria-valuenow at start, mid-drag, and end.
    const thumbV = page.getByTestId('grid-scrollbar-thumb-vertical');
    const readProgress = async (): Promise<number> => {
      const v = await thumbV.evaluate((el) => {
        const bar = el.closest('[role="scrollbar"]');
        return bar?.getAttribute('aria-valuenow') ?? '0';
      });
      return Number(v);
    };

    await page.mouse.move(startX, startY);
    await page.mouse.down();
    const before = await readProgress();
    // Move 10 steps, sampling halfway through.
    await page.mouse.move((startX + endX) / 2, (startY + endY) / 2, { steps: 5 });
    const mid = await readProgress();
    await page.mouse.move(endX, endY, { steps: 5 });
    await page.mouse.up();
    const after = await readProgress();

    // The drag must move continuously: mid should be strictly between
    // before and after.
    expect(before).toBeLessThan(mid);
    expect(mid).toBeLessThan(after);
  });

  test('drag past the right edge clamps cleanly (no pageerror)', async ({ page }) => {
    const errors: Error[] = [];
    page.on('pageerror', (e) => errors.push(e));

    await gridReady(page);
    await zoomIn(page, 2);

    const thumbH = page.getByTestId('grid-scrollbar-thumb-horizontal');
    const center = await getCenter(thumbH);

    await page.mouse.move(center.x, center.y);
    await page.mouse.down();
    // Drag far past the right edge of the viewport.
    await page.mouse.move(center.x + 2000, center.y, { steps: 10 });
    await page.mouse.up();

    expect(errors).toHaveLength(0);

    // Progress saturates at 100, not beyond.
    const valuenow = await thumbH.evaluate((el) => {
      const bar = el.closest('[role="scrollbar"]');
      return bar?.getAttribute('aria-valuenow');
    });
    expect(Number(valuenow)).toBe(100);
  });

  test('1:1 reset removes scrollbars and minimap from the DOM', async ({ page }) => {
    await gridReady(page);
    await zoomIn(page, 2);

    await expect(page.getByRole('scrollbar', { name: /vertical/i })).toBeVisible();

    await page.getByRole('button', { name: /1:1|réinitialiser/i }).click();
    // Wait for the 150 ms fade + library reset.
    await page.waitForTimeout(250);

    await expect(page.getByRole('scrollbar')).toHaveCount(0);
    await expect(page.getByRole('img', { name: /aperçu de la grille/i })).toHaveCount(0);
  });

  test('tap-to-focus on a letter cell still works at scale 2 (overlay does not swallow taps)', async ({ page }) => {
    await gridReady(page);
    await zoomIn(page, 2);

    // Pick the first letter cell that is currently visible in the
    // viewport (not under the minimap or scrollbars).
    const visibleLetter = page.locator('input[data-cell-kind="letter"]').first();
    await visibleLetter.click();
    await expect(visibleLetter).toBeFocused();
  });

  test('touch drag on mobile viewport pans via the minimap', async ({ page, browser }) => {
    // Use a fresh context with touch + mobile viewport.
    await page.setViewportSize({ width: 390, height: 844 });
    await gridReady(page);
    await zoomIn(page, 2);

    const minimap = page.getByRole('img', { name: /aperçu de la grille/i });
    const box = await minimap.boundingBox();
    if (!box) throw new Error('minimap has no bounding box');

    // Playwright's touchscreen.tap fires a single down/up. For a drag
    // we dispatch the touch events ourselves via the CDP-backed
    // touchscreen helpers exposed on the page. Fall back to dispatching
    // synthetic Touch events on the locator if direct touch APIs are
    // not exposed for this browser channel.
    const targetX = box.x + box.width * 0.75;
    const targetY = box.y + box.height * 0.75;
    await page.touchscreen.tap(targetX, targetY);

    // After a tap the viewport re-centers; the vertical scrollbar's
    // progress should be > 0 because we tapped below the centre.
    const thumbV = page.getByTestId('grid-scrollbar-thumb-vertical');
    const valuenow = await thumbV.evaluate((el) => {
      const bar = el.closest('[role="scrollbar"]');
      return bar?.getAttribute('aria-valuenow');
    });
    expect(Number(valuenow)).toBeGreaterThan(0);
  });
});
```

> **Note on test labels:** the `1:1`/`réinitialiser` button name and the `zoom avant` zoom-in button name come from `frontend/src/ui/components/grid/GridZoomControls.tsx`. Before running, verify them with `grep -n "aria-label\|>.*<" frontend/src/ui/components/grid/GridZoomControls.tsx`. If they differ, update the regexes in this spec.

- [ ] **Step 5.2: Resolve label regexes**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap
grep -nE 'aria-label|alt=' frontend/src/ui/components/grid/GridZoomControls.tsx | head -20
```

Update `frontend/e2e/grid-scrollbars-and-minimap.spec.ts` if the actual labels differ from `/zoom avant/i`, `/1:1|réinitialiser/i`.

- [ ] **Step 5.3: Run the E2E suite**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap/frontend
pnpm e2e --grep "Grid scrollbars"
```

Expected: all 7 tests pass.

If the mobile touch test (test 7) cannot be made reliable on the available Playwright channel because of the touch-events limitation noted in the spec, mark it `test.fixme(...)` with an inline comment citing the limitation, **not** `test.skip` without a reason. (The spec is explicit: don't silently drop tests.)

- [ ] **Step 5.4: Commit**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap
git add frontend/e2e/grid-scrollbars-and-minimap.spec.ts
git commit -s -m "$(cat <<'EOF'
test(frontend-grid): real-pointer E2E for scrollbars + minimap

Exercises every interaction with actual Playwright mouse.move + down +
multi-step move + up gestures (and touch on the mobile viewport),
covering scroll, continuous-recenter, bounds clamping, 1:1 reset, and
the regression guard for cell-tap focus during zoomed state.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: A11y check

**Files:**
- Modify or create: `frontend/e2e/a11y.spec.ts` (add a zoomed-state scan) **or** `frontend/e2e/a11y-grid-zoomed.spec.ts` (new file).

The existing a11y suite covers the route at rest. Add a new check that drives a zoom-in step first so the scrollbars and minimap are in the DOM when axe runs.

- [ ] **Step 6.1: Inspect the existing a11y spec**

```sh
head -80 frontend/e2e/a11y.spec.ts
```

Decide:
- If the existing file is structured around one route-per-test, **add** a new test inside `a11y.spec.ts` that performs the zoom-in then re-runs the same axe scan helper.
- If the file is rigidly structured and adding a test would require refactoring, **create** `frontend/e2e/a11y-grid-zoomed.spec.ts` mirroring its setup.

- [ ] **Step 6.2: Write the new a11y test**

Either as a new `test(...)` inside the existing file or in a new `frontend/e2e/a11y-grid-zoomed.spec.ts`:

```ts
import { expect, test } from '@playwright/test';
import { runAxe } from './lib/axeRun'; // existing helper, per the e2e/lib/ tree

test('grille route is a11y-clean when zoomed in (scrollbars + minimap visible)', async ({ page }) => {
  await page.goto('/');
  await page.waitForSelector('[role="grid"]', { state: 'visible' });
  // Drive zoom so the new overlays mount.
  const zoomIn = page.getByRole('button', { name: /zoom avant/i });
  await zoomIn.click();
  await zoomIn.click();
  await page.waitForTimeout(200);
  const results = await runAxe(page);
  expect(results.violations, JSON.stringify(results.violations, null, 2)).toHaveLength(0);
});
```

> If `runAxe` exposes a different signature in `e2e/lib/axeRun.ts`, adjust to match. The point is: drive the zoom, then run the same axe helper the existing tests use.

- [ ] **Step 6.3: Run a11y**

```sh
pnpm a11y
```

Expected: PASS. If a contrast violation surfaces on the thumb or viewport-rect colors, adjust the tokens in `GridScrollbar.tsx` / `GridMinimap.tsx` until the test is green. Re-run after each adjustment.

- [ ] **Step 6.4: Commit**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap
git add frontend/e2e/   # whichever file(s) changed
git commit -s -m "$(cat <<'EOF'
test(frontend-grid): a11y scan covers the zoomed-in state

Runs axe on the grille route after a zoom-in so the new scrollbars and
minimap are in the DOM when scanned. Establishes the AA baseline for
the overlays' colors so future palette tweaks can't silently regress.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Final verification and PR

- [ ] **Step 7.1: Run the entire frontend pipeline**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap/frontend
pnpm typecheck
pnpm lint
pnpm test
pnpm e2e
pnpm a11y
pnpm api:check
```

Expected: every step PASS.

- [ ] **Step 7.2: Verify the diff size**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap
git diff --stat origin/main..HEAD
```

Expected: well under the 400-line cap (ADR-0001 §4) for non-generated, non-blank lines. If over, look for the largest file and consider whether tests need trimming (unlikely; component code is small) — but do not skip tests just to fit the cap.

- [ ] **Step 7.3: Confirm working in the browser (manual smoke)**

```sh
cd /Users/isho/.config/superpowers/worktrees/bliss/feat-grid-scrollbars-minimap
make dev    # FORCE=1 if 7777/7778/5173 are occupied
```

In a desktop browser:
- Open the grille route, zoom in via the `+` button.
- Confirm: vertical and horizontal scrollbars appear on the right and bottom edges of the grid; minimap appears top-right.
- Drag the vertical thumb — grid pans up/down. Drag the horizontal thumb — grid pans left/right. Drag the minimap — viewport rect follows the pointer; grid pans accordingly.
- Click `1:1` — scrollbars and minimap fade out.
- Tap on a letter cell while zoomed — cell focuses, keyboard input still works.

Then a mobile-emulator view (Chrome DevTools, iPhone 14):
- Minimap appears in the bottom-left, thinner scrollbars.
- Touch-drag the minimap — viewport follows.

- [ ] **Step 7.4: Push and open PR**

```sh
git push -u origin feat/grid-scrollbars-minimap
gh pr create --title "feat(frontend-grid): scrollbars + minimap overlay on zoomed grid" --body "$(cat <<'EOF'
## Summary
- Adds two synthetic scrollbars (right edge: vertical, bottom edge: horizontal) and a draggable minimap (top-right on desktop, bottom-left on mobile) inside the existing puzzle `gridFrame`. Both overlays auto-hide at scale 1.
- Both read live transform state from `react-zoom-pan-pinch` via a rAF-coalesced `onTransform` handler, and pan by calling the library's imperative `setTransform`. No change to pinch / pan / focus behavior at rest.
- Math lives in a pure `transformMath.ts` module (no React, no DOM) so unit tests cover it with table-driven cases — no new dependency.

Spec: `docs/superpowers/specs/2026-05-17-grid-scrollbars-and-minimap-design.md`
Plan: `docs/superpowers/plans/2026-05-17-grid-scrollbars-and-minimap.md`

## Test plan
- [ ] `pnpm test` — unit + component tests (transformMath, scrollbar, minimap, wireup) pass
- [ ] `pnpm e2e --grep "Grid scrollbars"` — 7 real-pointer E2E cases pass
- [ ] `pnpm a11y` — zoomed-state axe scan green
- [ ] Manual smoke: desktop + mobile viewport, scrollbar drag + minimap drag both pan; `1:1` resets cleanly

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: a PR URL is returned. Verify CI gates (especially `frontend-build`, `openapi-typescript-drift`, `commitlint`) come up green.

---

## Self-review (already applied)

Coverage of the spec:
- **Goal + auto-hide at scale 1** → guarded in both `GridScrollbar` and `GridMinimap`, and in the parent `isZoomedIn` mount gate (Task 4).
- **Library-driven transform state** → rAF-coalesced `setTransformState` in `handleTransform` (Task 4, Step 4.3).
- **Synthetic overlays, not native scrollbars** → `GridScrollbar` is a custom DIV/DIV with `role="scrollbar"` (Task 2).
- **Minimap interaction (unified pointer)** → pointer-down recenters, pointer-move while held continues to recenter (Task 3, Step 3.3 + tests in 3.1).
- **Touch behavior** → `touch-action: none` on both overlays; `stopPropagation` on pointer-down (Tasks 2 & 3).
- **Mobile layout** → media query `(max-width: 480px)` switches sizes + minimap corner (Tasks 2 & 3).
- **A11y** → `role="scrollbar"`, `aria-orientation`, `aria-controls="puzzle-grid"`, `aria-valuenow/min/max`; minimap `role="img"` with descriptive label; not in the tab order; new axe test in Task 6.
- **Tests** → unit (math), component (scrollbar, minimap), wireup (Grid), E2E (real pointer), a11y. Tasks 1, 2, 3, 4, 5, 6.
- **Bounded performance** → rAF coalescing in Task 4; minimap topology rendered once per puzzle/validated change in Task 3.

Placeholder scan: no `TBD`, no `TODO`, no "implement later." Two `> Note:` callouts ask the implementer to verify repo-specific labels (color tokens in Task 3.4, button-name regexes in Task 5.2) before relying on the assumed names — these are unavoidable repo-coupling checks, not deferrals.

Type consistency: `transformRef` is `React.RefObject<ReactZoomPanPinchContentRef | null>` everywhere; `validatedPositions` is `ReadonlySet<string>` everywhere; `contentWidth`/`contentHeight` are always the **natural (unscaled)** content size measured via `offsetWidth`/`offsetHeight`.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-17-grid-scrollbars-and-minimap.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task (Task 1 → 2 → 3 → 4 → 5 → 6 → 7), review between tasks, fast iteration. Best when each task is well-bounded (which these are).

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints for review.

Which approach?
