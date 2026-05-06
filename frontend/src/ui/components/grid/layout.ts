// Leaf module: avoids Grid.tsx ↔ {CurrentCluePanel,GridZoomControls} circular import (back-edge = undefined at init).
// Inline-style: Panda CSS cannot statically extract min(…) with viewport units.
//
// Width cap for chrome aligned to the grid (sticky clue panel, zoom
// controls). The grid wrapper itself uses a flex-fit layout (see
// `Grid.tsx` `gridShellStyles`) so it shrinks to the smaller of
// available width and available height — the post-PR-#195 regression
// was that `min(95vw, 80vmin, 720px)` ignored the visible viewport
// height and pushed the page above 100dvh on 1080p laptops, producing
// a vertical scrollbar. Chrome (panel, zoom row) still uses this
// width-only cap because they sit above and below the grid in the flex
// column and don't compete for height.
export const GRID_TRACK_WIDTH = 'min(95vw, 720px)';
