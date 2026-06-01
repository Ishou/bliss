import { createMemoryHistory, createRouter } from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as ContribuerRoute } from '@/ui/routes/contribuer';
import { Route as ContribuerPairsRoute } from '@/ui/routes/contribuer.pairs';
import { Route as SondageRedirectRoute } from '@/ui/routes/sondage';
import { Route as SondagePairsRedirectRoute } from '@/ui/routes/sondage.pairs';

function routerAt(initialPath: string) {
  const routeTree = RootRoute.addChildren([
    ContribuerRoute,
    ContribuerPairsRoute,
    SondageRedirectRoute,
    SondagePairsRedirectRoute,
  ]);
  return createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: [initialPath] }),
    context: {
      surveyClient: undefined,
      surveyAnonStore: undefined,
      analytics: { trackEvent: vi.fn() },
      authClient: undefined,
      getPseudonym: () => 'Lapin 1',
      puzzleRepository: {
        fetchById: vi.fn(),
        fetchDaily: vi.fn(),
        listDailySummaries: vi.fn().mockResolvedValue({ items: [], hasMore: false }),
      },
      puzzleSolver: { validate: vi.fn(), requestHint: vi.fn() },
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
        loadHintsUsed: () => 0,
        recordHintUsed: () => {},
        clearForPuzzle: () => {},
      },
      tourSeenStore: { get: () => true, set: () => {}, clear: () => {} },
    },
  });
}

describe('legacy sondage redirects', () => {
  it('redirects /sondage to /contribuer', async () => {
    const router = routerAt('/sondage');
    await router.load();
    expect(router.state.location.pathname).toBe('/contribuer');
  });

  it('redirects /sondage/pairs to /contribuer/pairs', async () => {
    const router = routerAt('/sondage/pairs');
    await router.load();
    expect(router.state.location.pathname).toBe('/contribuer/pairs');
  });
});
