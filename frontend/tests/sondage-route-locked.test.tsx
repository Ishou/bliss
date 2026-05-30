import { act, render, screen, waitFor } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRouter,
} from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { AnalyticsPort } from '@/application/analytics';
import type { AuthClient } from '@/application/auth';
import type {
  Campaign,
  RatingResult,
  SurveyAnonStore,
  SurveyClient,
  SurveyItem,
} from '@/application/survey';
import { NoCampaignError, SondageLockedError } from '@/infrastructure/api/survey/client';
import { surveyAnonRatedStore } from '@/infrastructure/session/localStorageSurveyAnon';
import { AuthProvider } from '@/ui/components/auth';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as SondageRoute } from '@/ui/routes/sondage';

const sampleItem: SurveyItem = {
  itemId: '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b',
  mot: 'CHAT',
  definition: 'Animal domestique à moustaches',
  pos: 'nom_commun',
  categorie: 'animals',
  style: 'definition_directe',
  forceClaimed: 2,
  longueur: 4,
  tier: 'mid',
  isCalibration: false,
};

const ratingResult: RatingResult = {
  ratingId: '0190e3a4-7a2c-7c9e-8f1a-1234567890ab',
  itemId: sampleItem.itemId,
  submittedAs: 'anon',
  proposedItemId: null,
  undoToken: null,
};

const openCampaign: Campaign = {
  campaignId: '0190e3a4-7a2c-7c9e-8f1a-000000000007',
  batchLabel: 'round-7',
  openedAt: '2026-05-30T10:00:00Z',
  closedAt: null,
};

const closedCampaign: Campaign = {
  ...openCampaign,
  closedAt: '2026-05-30T12:00:00Z',
};

function stubAuth(): AuthClient {
  return {
    whoami: vi.fn().mockResolvedValue(null),
    getMe: vi.fn(),
    updateMe: vi.fn(),
    deleteMe: vi.fn(),
    logout: vi.fn(),
    signInUrl: (provider, returnTo) =>
      `https://auth.test/${provider}?return=${encodeURIComponent(returnTo)}`,
  };
}

function stubSurveyClient(overrides: Partial<SurveyClient> = {}): SurveyClient {
  return {
    getNextItem: vi.fn().mockResolvedValue(sampleItem),
    submitRating: vi.fn().mockResolvedValue(ratingResult),
    getNextPair: vi.fn().mockResolvedValue(null),
    submitPairRating: vi.fn().mockResolvedValue({ undoToken: null }),
    undoAction: vi.fn().mockResolvedValue(undefined),
    getProgress: vi.fn().mockResolvedValue({
      itemsRated: 0,
      calibrationAgreement: null,
      lastRatedAt: null,
    }),
    getContributions: vi.fn().mockResolvedValue([]),
    patchPreferences: vi.fn().mockResolvedValue(undefined),
    getCurrentCampaign: vi.fn().mockResolvedValue(openCampaign),
    ...overrides,
  };
}

type SpyAnalytics = AnalyticsPort & { trackEvent: ReturnType<typeof vi.fn> };
function stubAnalytics(): SpyAnalytics {
  const trackEvent = vi.fn();
  return { trackEvent } as SpyAnalytics;
}

function renderSondage(opts: {
  authClient?: AuthClient;
  surveyClient?: SurveyClient;
  analytics?: AnalyticsPort;
  surveyAnonStore?: SurveyAnonStore;
} = {}) {
  const authClient = opts.authClient ?? stubAuth();
  const surveyClient = opts.surveyClient ?? stubSurveyClient();
  const analytics = opts.analytics ?? stubAnalytics();
  const anonStore = opts.surveyAnonStore ?? surveyAnonRatedStore;
  const routeTree = RootRoute.addChildren([SondageRoute]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/sondage'] }),
    context: {
      authClient,
      getPseudonym: () => 'Lapin 1',
      surveyClient,
      surveyAnonStore: anonStore,
      analytics,
      puzzleRepository: {
        fetchById: vi.fn(),
        fetchDaily: vi.fn(),
        listDailySummaries: vi.fn().mockResolvedValue({ items: [], hasMore: false }),
      },
      puzzleSolver: {
        validate: vi.fn(),
        requestHint: vi.fn(),
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
        loadHintsUsed: () => 0,
        recordHintUsed: () => {},
        clearForPuzzle: () => {},
      },
      tourSeenStore: {
        get: () => true,
        set: () => {},
        clear: () => {},
      },
    },
  });
  return {
    surveyClient,
    rendered: render(
      <AuthProvider authClient={authClient} getPseudonym={() => 'Lapin 1'}>
        <RouterProvider router={router} />
      </AuthProvider>,
    ),
  };
}

describe('/sondage when no campaign has ever been opened (server 503)', () => {
  it('renders the LockBanner and aria-disables verdict buttons', async () => {
    const surveyClient = stubSurveyClient({
      getCurrentCampaign: vi.fn().mockRejectedValue(new NoCampaignError()),
    });
    renderSondage({ surveyClient });
    await waitFor(() =>
      expect(screen.getByTestId('sondage-lock-banner')).toBeInTheDocument(),
    );
    const goodBtn = await screen.findByRole('button', { name: /Bonne définition/i });
    expect(goodBtn).toHaveAttribute('aria-disabled', 'true');
  });
});

describe('/sondage when campaign is closed', () => {
  it('renders the LockBanner and aria-disables verdict buttons', async () => {
    const surveyClient = stubSurveyClient({
      getCurrentCampaign: vi.fn().mockResolvedValue(closedCampaign),
    });
    renderSondage({ surveyClient });
    await waitFor(() =>
      expect(screen.getByTestId('sondage-lock-banner')).toBeInTheDocument(),
    );
    const goodBtn = await screen.findByRole('button', { name: /Bonne définition/i });
    expect(goodBtn).toHaveAttribute('aria-disabled', 'true');
  });

  it('reacts to 423 from submit by refreshing status', async () => {
    const getCurrentCampaign = vi
      .fn()
      .mockResolvedValueOnce(openCampaign)
      .mockResolvedValueOnce(closedCampaign);
    const submitRating = vi.fn().mockRejectedValue(new SondageLockedError());
    const surveyClient = stubSurveyClient({ getCurrentCampaign, submitRating });
    renderSondage({ surveyClient });
    await waitFor(() => expect(screen.getByTestId('rating-card')).toBeInTheDocument());
    expect(screen.queryByTestId('sondage-lock-banner')).toBeNull();

    await act(async () => {
      document.querySelector<HTMLButtonElement>('[data-verdict="GOOD"]')!.click();
    });

    await waitFor(() => expect(submitRating).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.getByTestId('sondage-lock-banner')).toBeInTheDocument(),
    );
    expect(getCurrentCampaign).toHaveBeenCalledTimes(2);
  });
});
