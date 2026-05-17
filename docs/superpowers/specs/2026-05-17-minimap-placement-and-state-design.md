# Minimap Placement & State — Design

**Date:** 2026-05-17 (follow-up to #467)
**Status:** Proposed
**Bounded context / layer:** `frontend/` · `ui/components/grid/`
**Branch:** `feat/minimap-placement-and-state`

## Goal

Three refinements to the GridMinimap that shipped in #467, all driven by
post-merge user feedback:

1. **Don't overlay the grid.** Currently the minimap is
   `position: absolute; top: 8px; right: 12px` inside the `stage`
   wrapper, so it covers the grid's top-right cells. Move it to sit
   *outside* the grid: in the empty horizontal margin on desktop, and
   stacked below the grid on narrow viewports.
2. **Show the focused cell and current-word highlight on the minimap.**
   The Grid already tracks `nav.localCursor` (position + solving
   direction) and `nav.currentClue` (the word the cursor is on). The
   minimap should reflect both — a focus marker on the focused cell
   and a tint on every cell in the current clue's path — so the
   minimap doubles as a position indicator, not just a topology aid.
3. **Tint filled-but-not-validated letter cells.** Today letter cells
   render as either white (any state) or sage (validated). Add a
   middle state — a subtle wash on cells the player has filled but
   not yet validated — so progress is visible at a glance.

None of this touches contracts, dependencies, or any bounded context
boundary. It's a UX polish PR scoped to `Grid.tsx` and
`GridMinimap.tsx` plus their tests.

## Approach

### 1. Placement

Lift the minimap one level OUT of the `stage` wrapper so it's a
sibling of `gridShell`, with a new relative-positioned `gridArea`
wrapper around `gridShell` to anchor the absolute positioning.

```
<>                                                  // Grid's return
  <CurrentCluePanel />
  <div className={gridArea} style={{position:relative}}>
    <div className={gridShell}>                     // unchanged
      <div className={stage}>                       // unchanged
        <TransformWrapper>…</TransformWrapper>
        <div className={overlayFrame}>              // SCROLLBARS ONLY now
          <GridScrollbar v />
          <GridScrollbar h />
        </div>
      </div>
    </div>
    <GridMinimap … />                               // ← NEW LOCATION
  </div>
  <GridZoomControls />
</>
```

The minimap's own positioning becomes media-query-driven:

- **Desktop (`min-width: 768px`):** `position: absolute; top: 12px;
  right: 12px;` — pinned to the gridArea's top-right corner, which on
  a 1440-px-wide layout sits in the ~360-px-wide empty margin to the
  right of the centered grid. Not over the grid.
- **Narrow (`max-width: 767px`):** `position: static; margin: 8px auto
  0;` — falls into flow below `gridShell`, above `GridZoomControls`.

The minimap stays `display: none` at `scale ≤ 1.01` (existing
behavior, unchanged). The change is purely a re-parenting + a swap of
the absolute coordinates with a media query.

### 2. Focused cell + current-word highlight

Thread two new optional props into `GridMinimap`:

- `localCursor: { position: Position; direction: Direction } | null`
- `currentWordKeys: ReadonlySet<string>` — the set of `"row,col"`
  keys for the cells the current clue covers. Pre-computed in
  `Grid.tsx` from `nav.currentClue.cells` so the minimap stays a
  dumb renderer.

In the SVG topology, for each cell rect:

- If the key is in `currentWordKeys`, swap the fill to a rose tint
  matching the main grid's `letterCellInWord`. Validated cells
  already win (sage); cells in the current word AND validated stay
  sage — same precedence as the live grid.
- If the cell matches `localCursor.position`, render an additional
  small focus marker as a separate `<rect>` overlaid on top — a 1×1
  cell-square stroked with the focus rose. Visible regardless of
  whether the cell is validated, in-word, filled, or empty.

Colors come from existing tokens used by `Cell.tsx`. Match the literal
hexes already chosen for validated / definition / block in the
shipped minimap; add rose/focus equivalents the same way (hardcoded
literals + a comment naming the token they mirror).

### 3. Filled-but-not-validated tint

Compute a `filledPositions: ReadonlySet<string>` in `Grid.tsx`:

```ts
const filledPositions = useMemo(() => {
  const s = new Set<string>();
  for (const cell of puzzle.cells) {
    if (cell.kind !== 'letter') continue;
    const k = `${cell.position.row},${cell.position.col}`;
    if (validated.has(k)) continue;
    if (nav.getEntryAt(cell.position.row, cell.position.col) !== '') s.add(k);
  }
  return s;
}, [puzzle.cells, validated, nav.getEntryAt]);
```

`nav.getEntryAt`'s identity changes on every entry write (via the
hook's internal version counter — see
`useGridNavigation.ts:340-347`), so the memo re-runs each time a
letter is typed or cleared. No new event subscription needed.

Pass `filledPositions` to `GridMinimap`. Render those cells with a
"filled" fill — a tint between empty white and validated sage. The
precedence chain becomes:

1. validated → sage (existing)
2. cell is the focused cell → focus marker overlay (drawn last on top of any base fill)
3. cell is in the current word → rose-in-word (overrides default)
4. cell is filled-but-not-validated → wash (overrides default for letter cells)
5. default by cell kind (block / definition / letter-empty)

### Implementation notes

- The minimap remains a leaf component — no new event listeners, no
  new hooks. The only changes are: props (3 new), the SVG cell loop
  (consult those props), and the container's CSS (media query).
- `Grid.tsx`'s changes are small: a new `gridArea` wrapper, the
  computed `currentWordKeys` and `filledPositions` memos, the
  minimap re-parented out of the stage and into `gridArea`.
- The minimap container's existing `pointer-events: none` overlay
  parent (`overlayFrame`) no longer wraps the minimap — only the
  scrollbars. The minimap manages its own pointer events directly,
  which it already did via the inner container's pointer handlers,
  so this is a no-op behavioral change.
- No new dependency. No ADR. No contract change.

### Edge cases

- **At scale = 1**, the minimap renders `null` (unchanged). Filled +
  current-word props are still passed but unused.
- **Currently in `currentWordKeys` AND validated**: sage wins (no
  rose). Matches the main grid's precedence — validated cells don't
  pick up the in-word tint there either.
- **Current word straddles a cell that's also the focus cell**: the
  base fill is rose-in-word, then the focus marker overlays on top.
- **A cell that's filled-but-not-validated, in the current word, AND
  the focus**: stacked correctly per the precedence chain above —
  rose-in-word as base (overrides filled), focus marker on top.

### Mobile placement caveat

On narrow viewports the minimap drops below the grid. That eats
vertical space in a tall puzzle's bottom margin — but the user has
already chosen to zoom in (where the minimap matters most), so the
trade is fine. The `GridZoomControls` row below stays put; the
minimap inserts above it.

**Update (implementation):** The "desktop: right-of-grid, mobile:
below-grid" strategy described above was abandoned during
implementation. The route applies a max-width to its page content;
on a 1440-px viewport the gridArea is ~1048 px and the stage centers
at 720 px, leaving only ~164 px of margin on each side — not the
~360 px assumed above (120-px minimap + 12-px gap fits, but the
`gridArea`'s own constraint clips it, causing ~38 px of visual
overlap with the stage). The simpler fix: place the minimap
**in-flow below the grid on ALL viewports** (`position: static;
margin: 8px auto 0`). This works regardless of route layout width
and eliminates the breakpoint split entirely. The 480-px breakpoint
for size adjustment (desktop 120 px vs mobile 80 px) is kept.

## Test plan

- **Component tests** (`grid-minimap.test.tsx`):
  - At scale > 1 with `currentWordKeys` of size 3 → exactly 3 cell rects
    have `data-in-word="true"`.
  - With a `localCursor` → exactly one focus-marker `<rect>` is rendered
    at that position.
  - With `filledPositions` of size 2 (not overlapping validated) → 2
    cell rects have `data-filled="true"`.
  - Precedence: a cell in validated AND in `currentWordKeys` renders
    `data-validated="true"`, NOT `data-in-word="true"`.
- **Wireup test** (`grid-scrollbars-wireup.test.tsx`):
  - Add an assertion: at default scale, the minimap is not in the DOM
    (existing). After zoom, the minimap is rendered as a child of
    `gridArea` (new structural assertion — verify by selector path).
- **E2E** (`grid-scrollbars-and-minimap.spec.ts`):
  - Add a case: after zoom, the minimap's bounding box does NOT
    intersect the `[role="grid"]` bounding box on a desktop viewport.
  - Add a case: type a letter into a visible cell → assert a rect with
    `data-filled="true"` appears at that position on the minimap.
  - Add a case: focus a letter cell → assert a focus-marker rect
    appears at that position on the minimap.

## Out of scope

- No new dependencies.
- No change to the scrollbars (Tasks 2-7 from the prior PR).
- No change to puzzle data, server contracts, or any bounded context
  outside `frontend/ui/components/grid/`.
- No change to ADR-0001 line-cap considerations (~150-200 lines of
  code, well under 400).
- Not flagging the existing fixme E2E case from #467 (tap-to-focus
  through `CurrentCluePanel`) — that's a separate cleanup.

## Risks

- **Layout regression at the breakpoint boundary.** A viewport
  exactly at 768 px transitions from in-margin to in-flow placement;
  the layout should snap cleanly. Manual smoke covers it.
- **Repaint cost during typing.** `filledPositions` re-computes on
  every entry write, which triggers a Grid re-render, which causes
  the minimap to re-render. With memoized cell rects and a small
  Set diff per write, this is sub-millisecond on a 15×12 grid.
- **Focus-marker visibility.** The marker must be visible against
  rose-in-word, sage-validated, and filled-tint backgrounds. Pick a
  contrasting stroke color (probably a darker rose) and verify via
  the existing a11y axe scan in the zoomed state.
