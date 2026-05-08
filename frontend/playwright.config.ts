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
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
  webServer: {
    command: 'pnpm dev:preview',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
