# Grid Scrollbars & Minimap — Design

**Date:** 2026-05-17
**Status:** Proposed
**Bounded context / layer:** `frontend/` · `ui/components/grid/`
**Branch:** `feat/grid-scrollbars-minimap`

## Goal

When the user has zoomed into the puzzle grid (scale > 1) and the
visible viewport no longer contains the whole puzzle, give them two
complementary navigation aids:

1. **Scrollbars** along the right and bottom edges of the grid frame —
   show where the viewport sits within the puzzle, and let the user
   drag a thumb to pan.
2. **A minimap** floating in a corner of the grid frame — show the full
   puzzle at a small scale with a translucent rectangle marking the
   current viewport; click/drag to re-center.

Both auto-hide at scale 1 (nothing to navigate). Both are wired to the
same source of truth — the existing `react-zoom-pan-pinch`
`TransformWrapper`'s `state.scale / positionX / positionY` — and pan
via the library's imperative `setTransform`. The existing zoom
controls, pinch gestures, custom mouse-pan handler, iOS Safari
focus-revert flow, and keyboard-avoidance shell sizing all stay
untouched.

## Context

The grid currently lives in `frontend/src/ui/components/grid/Grid.tsx`
(~990 lines). It wraps the puzzle in `react-zoom-pan-pinch`'s
`TransformWrapper`, with `minScale=1`, `maxScale=4`. Today the only
zoom UI is the bottom-of-page `GridZoomControls` (`− 1:1 +`); panning
is gesture-only (touch pinch + drag, plus a custom mouse drag once
zoomed in).

This is fine on a phone, but on desktop with a mouse-and-keyboard, two
gaps show up:

- No visual indication that the grid IS scrollable when zoomed.
- No fast way to jump to a far corner without sweeping the mouse all
  the way across the grid.

Scrollbars solve the first; a minimap solves the second. They are
also a familiar pair of affordances — every map tile UI, every
diagram editor, every IDE's overview ruler does some flavor of this.

## Approach

Three options considered:

### A. Synthetic overlays on top of `react-zoom-pan-pinch` (chosen)

Keep the library. Render custom scrollbars and minimap as siblings of
`TransformComponent` inside the existing `gridFrame` div. Drive them
off the library's `onTransform` callback (fires every frame the
transform changes) and pan via its imperative `setTransform`.

**Pros:**
- Reuses the carefully-tuned pinch / pan / focus / iOS-Safari behavior.
- One source of truth for the transform; no two-way sync to debug.
- Smallest diff. The library's API is the seam we already use.

**Cons:**
- "Scrollbars" are synthetic, not native browser scrollbars. We have to
  paint them ourselves and wire up drag-to-pan.

### B. Replace with CSS `overflow: auto` + `transform: scale`

Wrap the grid in `overflow: auto`; scale the grid's actual layout
dimensions (not just a visual transform); get native browser scrollbars
"for free."

**Pros:**
- Native scrollbars (free a11y, free behavior).

**Cons:**
- Throws away the library's pinch handling, momentum disabling,
  bounds clamping, `centerView` snap-back, focus-revert flow, and
  the custom mouse-pan threshold. All would need to be reimplemented
  on top of `overflow: auto`.
- Native scrollbar appearance is unstyleable on iOS Safari and
  inconsistent across desktop browsers.
- Big-bang refactor of a ~990-line file that has been tuned across
  multiple incidents.

### C. Minimap only, no scrollbars

Just add the minimap.

**Pros:** smallest possible change.
**Cons:** doesn't satisfy the request. Without a scrollbar the user
still has no visual indicator that the grid IS scrollable.

**Decision: A.** B's regression surface is too large for the upside;
C is under-scoped.

## Architecture

### Two new components under `frontend/src/ui/components/grid/`

#### `GridMinimap.tsx`

Renders a small SVG representation of the puzzle (~120 px wide on
desktop, ~80 px on viewports ≤ 480 px). Cells colored by kind:

- **Block** → `colors.gridLine` (the existing dark separator color).
- **Definition** → existing beige def-cell color.
- **Letter (unfilled)** → white.
- **Letter (validated)** → existing sage validated color.

A translucent rectangle overlays the area currently visible in the
main viewport, computed from `scale`, `positionX`, `positionY`
against the natural grid box.

**Interaction (unified pointer model):**
- Pointer-down anywhere on the minimap arms a drag and re-centers the
  viewport on that point (one `setTransform` call).
- Pointer-move while held continues to re-center on the latest pointer
  position. So pointer-down-in-rect-then-drag and
  pointer-down-outside-then-drag are the same flow; the rect simply
  follows the pointer.
- Pointer-up ends the drag.

**Positioning:** absolute, top-right of `gridFrame`, ~8 px inset on
desktop. On viewports ≤ 480 px, moves to bottom-left to avoid the
one-handed thumb zone.

#### `GridScrollbar.tsx`

One component, two orientations via an `orientation: 'horizontal' |
'vertical'` prop. Renders an 8 px-thick track (6 px on viewports ≤
480 px) with a draggable thumb.

- **Thumb size:** `track_length / scale` (at scale 2 the thumb covers
  half the track, etc.).
- **Thumb position:** derived from `positionX` (or `positionY`)
  against the library's bounds for that axis.
- **Drag the thumb** → `setTransform` with the inverse-scaled
  translation, so a 1 px thumb drag maps to ~1 px of viewport
  translation on the underlying grid.

**Positioning:** absolute on the right edge (vertical) and bottom
edge (horizontal) of `gridFrame`. The corner where they would meet
stays empty (~8 × 8 px gap).

### State plumbing in `Grid.tsx`

Today `handleTransform` already updates `isZoomedIn` and `isMaxZoom`
from `state.scale` on every transform frame. Extend it to also track
the full transform tuple in one `useState`:

```ts
const [transform, setTransform] = useState({
  scale: 1,
  positionX: 0,
  positionY: 0,
});
```

To avoid React render churn during pan (the library fires
`onTransform` at ~60 Hz), rAF-coalesce the setter — same pattern the
existing keyboard-avoidance effect uses (one pending frame, subsequent
events in the frame are no-ops). When the rAF fires, compute next
state once and `setState` once.

Auto-hide threshold: `scale > 1.01`, mirroring the existing
`isZoomedIn` check.

### Mounting

Inside `gridFrame` (the positioned ancestor that already wraps `<div
role="grid">`), add the new components as siblings of the grid div:

```tsx
<div ref={gridFrameRef} className={gridFrame}>
  <div role="grid" id="puzzle-grid" ...>...</div>
  {isZoomedIn && (
    <>
      <GridScrollbar orientation="vertical"
        transformRef={transformWrapperRef}
        scale={transform.scale}
        positionY={transform.positionY}
        gridSizePx={gridSizePx}
      />
      <GridScrollbar orientation="horizontal" .../>
      <GridMinimap
        puzzle={puzzle}
        validatedPositions={validatedPositions}
        transformRef={transformWrapperRef}
        scale={transform.scale}
        positionX={transform.positionX}
        positionY={transform.positionY}
      />
    </>
  )}
</div>
```

`gridFrame` keeps `overflow: visible` (required for cell-edge arrow
glyphs that bleed past the grid border box); the scrollbars and
minimap sit on top via `position: absolute`. They have
`pointer-events: auto` (the rest of `gridFrame` is unchanged).

### Transform math

The library exposes `setTransform(positionX, positionY, scale,
animationTimeMs?)`. Its bounds clamp `positionX` and `positionY` to
keep the content within the wrapper.

For the **vertical scrollbar** at scale `s`:

- `gridSizePx` = the wrapper's natural (unscaled) height in px,
  obtained via `gridFrameRef.current.getBoundingClientRect().height`
  (the wrapper and frame share a height because the grid fills the
  wrapper).
- `contentSize` = `gridSizePx * s` (the scaled content height).
- `overflowPx` = `contentSize - gridSizePx` = `gridSizePx * (s - 1)`.
- The library's `positionY` ranges from `-overflowPx` (panned all the
  way down) to `0` (at the top).
- `progress` = `-positionY / overflowPx`, clamped to `[0, 1]`.
- `trackSize` = `gridSizePx` (the scrollbar runs the full edge).
- `thumbSize` = `trackSize / s`, with a minimum of 24 px so a small
  thumb stays grabbable at scale 4.
- `thumbOffset` = `progress * (trackSize - thumbSize)`.

Dragging the thumb by `Δ px` in track-space maps to a position
change of:

- `newProgress` = `(thumbOffset + Δ) / (trackSize - thumbSize)`,
  clamped to `[0, 1]`.
- `newPositionY` = `-newProgress * overflowPx`.
- Call `setTransform(positionX, newPositionY, scale, 0)` — no
  animation, the user is dragging in real time.

Horizontal scrollbar is the same math with X.

For the **minimap viewport rect**:

- `minimapSize` = the minimap's drawn size (px).
- `viewportFraction` = `1 / s`.
- `rectSize` = `minimapSize * viewportFraction` per axis.
- `rectOffset` (per axis) = `progress * (minimapSize - rectSize)`,
  with `progress` computed as above.

Dragging the rect maps minimap-space delta → grid-space delta by
multiplying by `s` (since the minimap shows the full grid in a
smaller box).

### Edge cases

- **Non-square puzzles:** the grid's aspect ratio is
  `puzzle.width / puzzle.height` (driven by the wrapper's
  `aspect-ratio` CSS). The minimap follows the same ratio. The math
  above already treats X and Y independently, so this works without
  special-casing.
- **First render at scale 1:** `isZoomedIn === false`, both
  components return `null`. No measurements happen.
- **The snap-back animation when crossing scale = 1:** the existing
  `handleTransform` calls `centerView(1, 150)` once on the way down
  through 1.01. `isZoomedIn` flips to false on that same frame, so
  the components unmount; we wrap them in a 150 ms fade-out
  transition (CSS opacity) so the unmount aligns visually with the
  snap-back.
- **Bounds clamping:** the library has `limitToBounds: true` by
  default; we don't bypass it. Our `setTransform` calls pass values
  the library then clamps. The thumb position is computed from the
  resulting (clamped) state on the next `onTransform` frame, so the
  thumb visually stops at the track edge even if the user keeps
  dragging beyond it.

### Touch behavior

The minimap and scrollbars set `touch-action: none` on themselves so
touch drags work without the page scrolling. `stopPropagation` on
`pointerdown` keeps the grid's existing touch-pan handler from also
firing.

The grid itself keeps its current `touch-action` rules (`pan-y` at
rest, `none` when zoomed). Outside the new components' bounding
boxes, nothing changes.

### Performance

- `onTransform` already fires at ~60 Hz during pan/zoom. The current
  code does small synchronous work in it (two `setState`s and a ref
  read). The new state update is rAF-coalesced, so React renders at
  most once per frame regardless of input event rate.
- Minimap renders as one SVG with `puzzle.width * puzzle.height`
  `<rect>` elements. For a 15×12 puzzle that's 180 rects — trivial.
  The viewport overlay is one additional `<rect>` updated on every
  rAF tick via inline `x` / `y` / `width` / `height` attribute
  updates (no React re-render needed for the overlay rect — we'll
  hold a ref and mutate the DOM directly inside the same rAF
  callback that updates state, mirroring the library's own
  imperative-transform pattern).

Concretely: split minimap rendering into two parts — the static
puzzle topology (re-renders only when `puzzle` or
`validatedPositions` changes) and the viewport rect (mutated via ref
on every transform frame). This keeps React's reconciliation cost off
the hot path entirely.

## Accessibility

- Each `GridScrollbar` gets `role="scrollbar"`, `aria-orientation`,
  `aria-valuemin="0"`, `aria-valuemax="100"`, `aria-valuenow={progress
  * 100}`, `aria-controls="puzzle-grid"`. Per WAI-ARIA, scrollbars
  are pointer-primary affordances; keyboard users still have arrow-key
  navigation through the cells, which is the semantically-equivalent
  action.
- The minimap gets `role="img"` with `aria-label="Aperçu de la grille
  — la zone surlignée indique la partie visible"`. The viewport
  rectangle is `aria-hidden` (decorative).
- No new tab stops. The minimap and scrollbars are not keyboard-focusable
  (the underlying grid is). This is a deliberate choice: adding them
  to the tab order would mean a keyboard user has to tab through them
  on every page interaction, which is friction for no benefit because
  arrow keys already navigate.
- The CI `pnpm a11y` (axe-core via Playwright) must stay green. Thumb
  and viewport-rect colors will be chosen to hit WCAG AA against the
  cell backgrounds they overlap (white letter cells, beige def cells,
  light sage validated cells). Initial palette: thumb
  `colors.gridLine` at 70% opacity, viewport rect `colors.gridLine` at
  30% fill + 70% stroke.

## Testing

### Unit (vitest)

Tests live flat under `frontend/tests/` (existing convention — see
`grid-render.test.tsx`, `grid-input.test.tsx`, etc.).

`frontend/tests/grid-transform-math.test.ts` — pure math:

- Property-based tests (`fast-check`) for the
  `scale / positionX / positionY → thumb size / offset` mapping. Invariants:
  - `0 ≤ thumbOffset ≤ trackSize - thumbSize`.
  - At `scale = 1`, `thumbSize === trackSize` (the auto-hide threshold
    is enforced at the component layer, but the math should still
    degrade cleanly at the boundary).
  - At max scale (4), `thumbSize = trackSize / 4` (or 24 px floor,
    whichever is larger).
- Round-trip: `thumb drag of Δ → resulting positionY → resulting
  thumb offset` returns to the same point (modulo clamping at bounds).

### Component (vitest + RTL)

`frontend/tests/grid-minimap.test.tsx` and `frontend/tests/grid-scrollbar.test.tsx`:

- Render at scale 1 → component returns `null`, no DOM nodes.
- Render at scale 2, positionX/Y = 0 → thumb at top-left, half-size.
- Simulate a pointer drag on the thumb → assert `setTransform` is
  called with the expected arguments.
- Minimap: pointer-down on the minimap → assert `setTransform` is
  called with values that center the viewport on the click point.
  Pointer-move while held → second `setTransform` with the new
  pointer position. Pointer-up → no further calls.

`frontend/tests/grid-scrollbars-wireup.test.tsx` — Grid wire-up:

- Grid at scale 1 → no scrollbars, no minimap.
- Zoom in programmatically (call `transformWrapperRef.current.zoomIn`
  via a test-only hook, or simulate the `+` button) → scrollbars and
  minimap appear; `aria-valuenow` on each scrollbar reflects the
  initial position.
- Zoom out → both disappear after the fade.

### E2E (Playwright)

`frontend/e2e/grid-scrollbars-and-minimap.spec.ts`:

- Open a puzzle, click `+` zoom button twice → assert scrollbars and
  minimap visible.
- Drag the vertical scrollbar thumb halfway down → assert the focused
  cell's `getBoundingClientRect().top` has moved up (the grid has
  scrolled).
- Click in the minimap → assert the viewport rect re-centers there
  and the grid pans correspondingly.
- Click `1:1` → assert scrollbars and minimap fade out (not in the
  DOM after the transition).

### A11y

Existing `frontend/e2e/a11y.spec.ts` covers the puzzle route at rest.
Add a new test (`frontend/e2e/a11y-grid-zoomed.spec.ts` or a new step
inside the existing file) that drives a zoom step first, then runs
axe — so the new components are in the DOM when scanned. Must stay
green at the existing rule set.

## Performance budget

Bundle-wise, the new components total well under 5 KB minified+gzipped
(SVG topology + math + scrollbar markup, no new dependencies). Runtime,
the transform-frame work is bounded by one rAF tick regardless of
event rate. The frontend's existing performance budget doesn't have a
per-component cap; the new code stays well within general bounds.

## Out of scope

- No change to keyboard navigation through cells.
- No change to `GridZoomControls` (the `− 1:1 +` toolbar). Those keep
  their semantics.
- No new puzzle data; the minimap renders from existing
  `puzzle.cells` and `validatedPositions`.
- No persistence — scroll position resets when the puzzle changes
  (matches today's reset-on-load).
- No contract change. Nothing touches `grid/api/openapi.yaml` or
  `frontend/src/infrastructure/api/`.
- Not an ADR-worthy change (no new dependency, no new bounded
  context, no contract spanning contexts). Per CLAUDE.md the ADR
  trigger doesn't fire.

## Risks

- **Sync drift between library state and our derived UI.** Mitigated
  by deriving everything from the library's `onTransform` callback
  (called on every frame the library transforms) and keeping
  `setTransform` as the only write path.
- **Touch interaction conflict with the grid's pan handler.**
  Mitigated by `touch-action: none` on the new surfaces and
  `pointerdown` propagation stopping inside them.
- **iOS Safari focus / blur quirks under pinch.** The new components
  are not focusable and don't take focus; they don't interact with
  the existing focus-revert flow.
- **Mobile screen real estate.** The minimap takes ~80 × 80 px on
  phones. If user testing finds it cramped, a follow-up could make
  it toggleable. Not solving that now (YAGNI).

## Notes on file size

`Grid.tsx` is already ~990 lines and growing. If the new transform-state
plumbing pushes it past readable, the implementation plan can carve out
a `useGridTransformState` hook into a sibling file. That's a judgment
call to make during implementation, not now — flagged here so the plan
writer knows the option exists.
