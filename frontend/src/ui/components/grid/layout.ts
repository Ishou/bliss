// Leaf module: avoids Grid.tsx ↔ {CurrentCluePanel,GridZoomControls} circular import (back-edge = undefined at init).
// Inline-style: Panda CSS cannot statically extract min(…) with viewport units.
export const GRID_TRACK_WIDTH = 'min(95vw, 720px)';
