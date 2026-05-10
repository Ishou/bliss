import '@testing-library/jest-dom/vitest';

import * as axeMatchers from 'vitest-axe/matchers';
import { expect } from 'vitest';
expect.extend(axeMatchers);

// jsdom does not implement window.scrollTo / window.scrollBy. TanStack
// Router calls scrollTo on every navigation; `useGridNavigation`'s
// keyboard-avoidance scroll calls scrollBy after a focus event. Both
// stubs keep test output clean without changing behavior under test
// (we don't assert on scroll position).
if (typeof window !== 'undefined') {
  window.scrollTo = (() => {}) as typeof window.scrollTo;
  window.scrollBy = (() => {}) as typeof window.scrollBy;
}

// jsdom's `document.hasFocus()` returns `false` by default — there is
// no real OS-level window focus inside the test runner. The grid's
// blur-refocus heuristic uses it to skip refocusing when the user has
// switched tabs/windows, so under tests we pretend the tab is focused
// (the common case the heuristic is tuned for).
if (typeof document !== 'undefined') {
  document.hasFocus = () => true;
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

// jsdom doesn't ship `visualViewport`; `@zag-js/tour` reads it on
// machine start to track boundary size for the spotlight cutout. Stub
// the minimum surface (size + listener no-ops) so tour-driven specs
// can mount the machine without ReferenceError.
if (typeof window !== 'undefined' && typeof window.visualViewport === 'undefined') {
  // Use Object.defineProperty since jsdom marks the prop non-writable.
  Object.defineProperty(window, 'visualViewport', {
    configurable: true,
    value: {
      width: 1024,
      height: 768,
      offsetLeft: 0,
      offsetTop: 0,
      pageLeft: 0,
      pageTop: 0,
      scale: 1,
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    },
  });
}
