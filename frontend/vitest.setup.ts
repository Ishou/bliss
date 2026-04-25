import '@testing-library/jest-dom/vitest';

// jsdom does not implement window.scrollTo; TanStack Router's scroll
// restoration calls it on every navigation. Stubbing it keeps test
// output clean without changing behavior under test.
if (typeof window !== 'undefined') {
  window.scrollTo = (() => {}) as typeof window.scrollTo;
}
