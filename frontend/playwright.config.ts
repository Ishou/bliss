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
    // 5174, not 5173: `make dev` runs `pnpm dev` on 5173 with
    // `.env.development` (real Grid API on localhost:7777). If e2e
    // shared that port via Playwright's `reuseExistingServer`, tests
    // would silently hit the real backend (random puzzles, no MSW
    // interception) and fail in confusing ways. A dedicated preview
    // port lets `make dev` and `pnpm e2e` coexist on the same machine.
    baseURL: 'http://localhost:5174',
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
    command: 'pnpm dev:preview --port 5174 --strictPort',
    url: 'http://localhost:5174',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
