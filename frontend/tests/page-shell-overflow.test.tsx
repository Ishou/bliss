// jsdom Layer 1 — page-shell overflow smoke. See ADR-0036 §5.
//
// jsdom does not run real flex / grid layout (no Blink layout engine),
// so scrollWidth / clientWidth on most elements resolve to 0. This
// suite is therefore a SMOKE: it asserts that each public route mounts
// without throwing at faked viewport widths and that the documented
// skip-link target is present. The Playwright suite in
// frontend/e2e/page-shell-overflow.spec.ts is the truth gate for
// horizontal overflow.

import { render, waitFor } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRouter,
} from '@tanstack/react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PuzzleRepository, PuzzleSolver } from '@/application';
import type { SoloEntriesStore } from '@/application/solo/SoloEntriesStore';
import type { Puzzle } from '@/domain';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as AccueilRoute } from '@/ui/routes/accueil';
import { Route as AideRoute } from '@/ui/routes/aide';
import { Route as ConfRoute } from '@/ui/routes/confidentialite';
import { Route as GrilleRoute } from '@/ui/routes/grille';
import { Route as MentionsRoute } from '@/ui/routes/mentions-legales';

const stubPuzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 't',
  language: 'fr',
  width: 1,
  height: 1,
  hintsAllowed: 3,
  cells: [{ kind: 'letter', position: { row: 0, col: 0 }, entry: '' }],
};

const stubPuzzleSolver: PuzzleSolver = {
  validate: () => Promise.resolve({ solved: false, incorrectCells: [] }),
  requestHint: () => Promise.reject(new Error('not used')),
};

const emptyStore: SoloEntriesStore = {
  load: () => [],
  save: () => {},
  loadLockedCells: () => [],
  lockCell: () => {},
  loadHintsUsed: () => 0,
  recordHintUsed: () => {},
  clearForPuzzle: () => {},
};

const buildRouter = (initialPath: string) => {
  const puzzleRepository: PuzzleRepository = {
    fetchById: () => Promise.resolve(stubPuzzle),
    fetchDaily: () => Promise.resolve(stubPuzzle),
  };
  const routeTree = RootRoute.addChildren([
    AccueilRoute,
    AideRoute,
    ConfRoute,
    GrilleRoute,
    MentionsRoute,
  ]);
  return createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialPath] }),
    context: {
      puzzleRepository,
      puzzleSolver: stubPuzzleSolver,
      sessionClient: {
        eraseSession: () => Promise.resolve({ deleted: 0 }),
        getSessionId: () => 'test-session-id',
        clearLocalSession: () => {},
      },
      soloEntriesStore: emptyStore,
      tourSeenStore: { get: () => true, set: () => {}, clear: () => {} },
    },
  });
};

const PUBLIC_ROUTES = [
  { name: 'Accueil',         path: '/' },
  { name: 'Aide',             path: '/aide' },
  { name: 'Confidentialité', path: '/confidentialite' },
  { name: 'Grille',           path: '/grille' },
  { name: 'Mentions légales', path: '/mentions-legales' },
];

const VIEWPORTS = [320, 375, 768];

beforeEach(() => {
  // Best-effort viewport stub. jsdom honours these defineProperty
  // overrides for the duration of the test; clientWidth / clientHeight
  // become readable as the stubbed values, which matters for any
  // component reading them (none in scope today, but future-proofing).
  for (const dim of ['clientWidth', 'clientHeight']) {
    const original = Object.getOwnPropertyDescriptor(
      Element.prototype, dim,
    );
    if (original) {
      // Cache for restore in afterEach (Element.prototype defines a
      // shared accessor; we reset to the captured descriptor below).
      (globalThis as Record<string, unknown>)[`__orig_${dim}`] = original;
    }
  }
});

afterEach(() => {
  vi.restoreAllMocks();
});

const stubViewport = (width: number, height = 800) => {
  Object.defineProperty(document.documentElement, 'clientWidth', {
    configurable: true, value: width,
  });
  Object.defineProperty(document.documentElement, 'clientHeight', {
    configurable: true, value: height,
  });
};

describe.each(VIEWPORTS)('page-shell at viewport %ipx', (width) => {
  it.each(PUBLIC_ROUTES)('$name ($path) mounts and has #main-content', async ({ path }) => {
    stubViewport(width);
    const router = buildRouter(path);
    render(<RouterProvider router={router} />);
    await waitFor(() => {
      expect(document.querySelector('main#main-content')).not.toBeNull();
    });
  });
});

// Smoke "no obviously oversized inline width" probe. Catches the cheap
// case where someone drops a fixed-pixel width that exceeds the viewport.
// Does NOT catch min-content overflow — that's Playwright's job.
describe('inline width smoke', () => {
  it.each(PUBLIC_ROUTES)('$name has no inline px width > 768', async ({ path }) => {
    stubViewport(768);
    const router = buildRouter(path);
    render(<RouterProvider router={router} />);
    await waitFor(() => {
      expect(document.querySelector('main#main-content')).not.toBeNull();
    });
    const elements = document.querySelectorAll<HTMLElement>('[style*="width:"]');
    for (const el of Array.from(elements)) {
      const m = /width:\s*(\d+)px/.exec(el.style.cssText);
      if (!m) continue;
      const px = Number(m[1]);
      expect(px).toBeLessThanOrEqual(768);
    }
  });
});
