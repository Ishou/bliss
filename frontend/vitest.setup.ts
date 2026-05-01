import '@testing-library/jest-dom/vitest';

// jsdom does not implement window.scrollTo; TanStack Router's scroll
// restoration calls it on every navigation. Stubbing it keeps test
// output clean without changing behavior under test.
if (typeof window !== 'undefined') {
  window.scrollTo = (() => {}) as typeof window.scrollTo;
}

// jsdom doesn't ship `ResizeObserver`; `react-zoom-pan-pinch` (used by
// `Grid` to host pinch/zoom) instantiates one to track wrapper sizing.
// A no-op stub is enough for our tests — we don't assert on transform
// state, only on the cell-level focus / clue-panel interactions wired
// through the wrapped grid.
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
}
