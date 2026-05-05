// Shared CSS-string constants that govern the grid's full-bleed layout.
// Lives in its own module (vs co-located in `Grid.tsx`) so the chrome
// rows that need it — `CurrentCluePanel` (sticky-top) and
// `GridZoomControls` (below the grid) — can import the value without
// reaching into `Grid.tsx`. Reaching back into `Grid.tsx` creates a
// circular import: `Grid.tsx` imports those two components, and during
// the evaluation of that import chain the back-edge constant resolves
// to `undefined` (ES-module temporal dead zone). React then sees
// `style={{ maxWidth: undefined }}` and silently drops the prop, so
// the inline cap never reaches the DOM. Keeping the constants in this
// leaf module breaks the cycle.
//
// `GRID_TRACK_WIDTH` is the single source of truth for the track that
// the clue panel + grid wrapper + zoom controls share. The formula is:
//
//   min(95vw, 80vmin, 720px)
//
//   * `95vw`   — fills the screen width on portrait phones, leaving a
//                small breathing margin so the grid doesn't sit flush
//                against the edge. Beats the previous 480 px hard cap
//                by ~70 px on a 360-wide phone.
//   * `80vmin` — roughly square-friendly across portrait and landscape:
//                on a tall phone it reuses the height budget, on a
//                wide desktop it caps the visual area to a comfortable
//                reading distance instead of ballooning across a 4K
//                display. `vmin` (smaller of vw / vh) so a portrait
//                phone with the soft keyboard open doesn't shrink the
//                grid more than the visualViewport-aware `maxHeight`
//                already does.
//   * `720px`  — absolute desktop ceiling. A 720 px grid on a 7×7
//                puzzle gives ~100 px cells; larger cells make the
//                FitText autosizer pick a font size that feels
//                oversized and the eye has to track too far between
//                cells to read across-clues.
export const GRID_TRACK_WIDTH = 'min(95vw, 80vmin, 720px)';
