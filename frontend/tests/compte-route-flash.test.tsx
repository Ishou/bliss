import { render, screen, waitFor } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRouter,
} from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { AuthClient, GetMeResult } from '@/application/auth';
import { AuthProvider } from '@/ui/components/auth';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as CompteRoute } from '@/ui/routes/compte';

const USER_ID = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';

function stubAuth(overrides: Partial<AuthClient> = {}): AuthClient {
  return {
    whoami: vi.fn().mockResolvedValue(null),
    getMe: vi.fn(),
    updateMe: vi.fn(),
    deleteMe: vi.fn(),
    logout: vi.fn(),
    signInUrl: (provider, returnTo) =>
      `https://auth.test/${provider}?return=${encodeURIComponent(returnTo)}`,
    ...overrides,
  };
}

function renderCompte(authClient: AuthClient) {
  const routeTree = RootRoute.addChildren([CompteRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/compte'] }),
    context: {
      authClient,
      getPseudonym: () => 'Lapin 1',
      surveyClient: undefined,
      analytics: undefined,
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
  return render(
    <AuthProvider authClient={authClient} getPseudonym={() => 'Lapin 1'}>
      <RouterProvider router={router} />
    </AuthProvider>,
  );
}

describe('/compte auth-hydration', () => {
  it('renders the account form once whoami resolves authed', async () => {
    const me: GetMeResult = {
      id: USER_ID,
      displayName: 'Lapin 472',
      createdAt: '2026-01-01T00:00:00Z',
      providers: [{ provider: 'google', linkedAt: '2026-01-01T00:00:00Z', emailOptIn: false }],
    };
    const authClient = stubAuth({
      whoami: vi.fn().mockResolvedValue({ userId: USER_ID, displayName: 'Lapin 472' }),
      getMe: vi.fn().mockResolvedValue(me),
    });
    renderCompte(authClient);
    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: /Pseudonyme/i })).toBeInTheDocument(),
    );
    expect(screen.getByRole('heading', { level: 1, name: /Mon compte/i })).toBeInTheDocument();
  });
});
