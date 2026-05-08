import { act, render, screen } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRouter,
} from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { Puzzle } from '@/domain';
import type { TourSeenStore } from '@/application/tour/TourSeenStore';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as IndexRoute } from '@/ui/routes/index';

// End-to-end-ish test of the tour wiring at the route level: confirms
// the Bienvenue step renders when the seen flag is false, and that
// `?tour=1` overrides a seen=true flag.

const stubPuzzle: Puzzle = {
  id: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  title: 't',
  language: 'fr',
  width: 1,
  height: 1,
  hintsAllowed: 3,
  cells: [{ kind: 'letter', position: { row: 0, col: 0 }, entry: '' }],
};

const buildContext = (tourSeenStore: TourSeenStore) => ({
  puzzleRepository: { fetchById: vi.fn().mockResolvedValue(stubPuzzle) },
  puzzleSolver: {
    validate: vi.fn().mockResolvedValue({ solved: false, incorrectCells: [] }),
    requestHint: vi.fn().mockRejectedValue(new Error('not used')),
  },
  sessionClient: {
    eraseSession: () => Promise.resolve({ deleted: 0 }),
    getSessionId: () => 'test-session-id',
    clearLocalSession: () => {},
  },
  soloEntriesStore: {
    load: () => [],
    save: () => {},
    loadLockedCells: () => [],
    lockCell: () => {},
    clearForPuzzle: () => {},
  },
  tourSeenStore,
});

const renderIndex = (
  tourSeenStore: TourSeenStore,
  initialEntry = '/',
) => {
  const routeTree = RootRoute.addChildren([IndexRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialEntry] }),
    context: buildContext(tourSeenStore),
  });
  return render(<RouterProvider router={router} />);
};

const flush = async () => {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 60));
  });
};

describe('index route — onboarding tour wiring', () => {
  it('auto-opens the Bienvenue step on first visit (seen=false)', async () => {
    renderIndex({
      get: () => false,
      set: vi.fn(),
      clear: vi.fn(),
    });
    await screen.findByRole('grid');
    await flush();
    expect(screen.getByText('Bienvenue')).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: 'Passer le tour' }),
    ).toBeInTheDocument();
    // The backdrop must be visible (not `hidden`) — Ark UI's
    // Tour.Backdrop applies the HTML `hidden` attribute when the active
    // step's `backdrop` field is unset / falsy. If a future step config
    // forgets the flag, the tour renders without a scrim and the
    // content disappears behind page chrome.
    const backdrop = document.querySelector<HTMLElement>(
      '[data-scope="tour"][data-part="backdrop"]',
    );
    expect(backdrop).not.toBeNull();
    expect(backdrop?.hasAttribute('hidden')).toBe(false);
  });

  it('does not auto-open when seen=true', async () => {
    renderIndex({
      get: () => true,
      set: vi.fn(),
      clear: vi.fn(),
    });
    await screen.findByRole('grid');
    await flush();
    expect(screen.queryByText('Bienvenue')).toBeNull();
  });

  it('re-opens the tour when navigated with ?tour=1, even when seen=true', async () => {
    renderIndex(
      {
        get: () => true,
        set: vi.fn(),
        clear: vi.fn(),
      },
      '/?tour=1',
    );
    await screen.findByRole('grid');
    await flush();
    expect(screen.getByText('Bienvenue')).toBeInTheDocument();
  });

  it('renders spotlight without a CSS transition on top/left/width/height', async () => {
    // Animating the spotlight ring's box on the *first* step transition
    // (no-target dialog → tooltip with target) reads as "the ring grows
    // out of (0,0)" because Ark's inline `top/left/width/height` go
    // from unset (default 0) to the target rect in a single frame. The
    // styles file deliberately omits `transition` so the ring snaps
    // into place. This test fails if a future change re-adds an
    // animation on those box properties.
    renderIndex({
      get: () => false,
      set: vi.fn(),
      clear: vi.fn(),
    });
    await screen.findByRole('grid');
    await flush();
    const spotlight = document.querySelector<HTMLElement>(
      '[data-scope="tour"][data-part="spotlight"]',
    );
    expect(spotlight).not.toBeNull();
    const transition = window.getComputedStyle(spotlight!).transitionProperty;
    expect(transition).not.toMatch(/all|width|height|left|top/);
  });

  it('renders the dismiss + step-action triggers as Bliss Button instances', async () => {
    // Ark's Tour.ActionTrigger is a polymorphic factory `<button>`. We
    // wrap it with `asChild` + the Button primitive so the tour footer
    // matches the brand (sage CTA, ghost outline). The buttons must
    // carry the project's `c_onAccent` / `c_fg` text colors instead of
    // the default user-agent button styling.
    renderIndex({
      get: () => false,
      set: vi.fn(),
      clear: vi.fn(),
    });
    await screen.findByRole('grid');
    await flush();
    const skip = screen.getByRole('button', { name: 'Passer le tour' });
    const next = screen.getByRole('button', { name: 'Suivant' });
    // The Button primitive applies `bdr_6px` (border-radius: 6px) on
    // `baseStyles`. Presence of that atomic class confirms the
    // primitive's styles were merged onto Ark's button via asChild.
    expect(skip.className).toMatch(/bdr_6px/);
    expect(next.className).toMatch(/bdr_6px/);
  });
});
