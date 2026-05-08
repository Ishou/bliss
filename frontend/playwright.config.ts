import { defineConfig, devices } from '@playwright/test';

// Playwright configuration for end-to-end tests under `frontend/e2e/`.
//
// Tests run against `vite --mode preview`, which loads `.env.preview`
// (`VITE_MOCK_GRID_API=true` + `VITE_MOCK_GAME_API=true`). MSW
// intercepts every Grid + Game REST/WS call, so the suite needs no
// live API and never touches production — per ADR-0007 §5.
//
// `frontend/e2e/` is intentionally separate from `frontend/tests/`
// (vitest) to keep the runners' globs disjoint. eslint-plugin-boundaries
// only scans `src/**`, so e2e tests don't trip the layer rules.
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
  },
  // Browser/device matrix:
  //   chromium  — desktop Chrome on the developer machine (default)
  //   pixel-7   — Chromium + Android UA, viewport, and touch defaults.
  //               Catches the mobile-Chrome rendering path; does NOT
  //               reproduce real-Android IME / soft-keyboard composition
  //               (no Playwright engine does — that's a real-device gap).
  //   iphone-14 — WebKit + iOS UA, viewport, and touch defaults.
  //               Catches Safari iOS rendering and pointer-events branches;
  //               same IME caveat as pixel-7.
  // Adding webkit/mobile projects roughly triples e2e wall time. Run a
  // single project in dev with `pnpm e2e --project=chromium`; CI runs all.
  projects: [
    { name: 'chromium',  use: { ...devices['Desktop Chrome'] } },
    { name: 'pixel-7',   use: { ...devices['Pixel 7'] } },
    { name: 'iphone-14', use: { ...devices['iPhone 14'] } },
  ],
  webServer: {
    command: 'pnpm dev:preview',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
