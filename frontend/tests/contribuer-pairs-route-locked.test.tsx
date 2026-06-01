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
  ItemPair,
  SurveyAnonStore,
  SurveyClient,
  SurveyItem,
} from '@/application/survey';
import { SondageLockedError } from '@/infrastructure/api/survey/client';
import { surveyAnonRatedStore } from '@/infrastructure/session/localStorageSurveyAnon';
import { AuthProvider } from '@/ui/components/auth';
import { Route as RootRoute } from '@/ui/routes/__root';
import { Route as ContribuerRoute } from '@/ui/routes/contribuer';
import { Route as ContribuerPairsRoute } from '@/ui/routes/contribuer.pairs';

const leftItem: SurveyItem = {
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

const rightItem: SurveyItem = {
  itemId: '0190e3a4-7a2c-7c9e-8f1a-cafecafecafe',
  mot: 'CHAT',
  definition: 'Félin domestique aux iris fendus',
  pos: 'nom_commun',
  categorie: 'animals',
  style: 'periphrase',
  forceClaimed: 3,
  longueur: 4,
  tier: 'mid',
  isCalibration: false,
};

const samplePair: ItemPair = { mot: 'CHAT', left: leftItem, right: rightItem };

const openCampaign: Campaign = {
  campaignId: '0190e3a4-7a2c-7c9e-8f1a-000000000007',
  batchLabel: 'round-7',
  openedAt: '2026-05-30T10:00:00Z',
  closedAt: null,
};

const closedCampaign: Campaign = { ...openCampaign, closedAt: '2026-05-30T12:00:00Z' };

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
    getNextItem: vi.fn().mockResolvedValue(null),
    submitRating: vi.fn().mockResolvedValue(undefined),
    getNextPair: vi.fn().mockResolvedValue(samplePair),
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
    getLemmaMeta: vi.fn().mockResolvedValue({ priorSenses: [], priorSubTags: [] }),
    putLemmaSubTags: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

function stubAnalytics(): AnalyticsPort {
  return { trackEvent: vi.fn() } as AnalyticsPort;
}

function renderContribuerPairs(opts: {
  surveyClient?: SurveyClient;
  authClient?: AuthClient;
  analytics?: AnalyticsPort;
  surveyAnonStore?: SurveyAnonStore;
} = {}) {
  const authClient = opts.authClient ?? stubAuth();
  const surveyClient = opts.surveyClient ?? stubSurveyClient();
  const analytics = opts.analytics ?? stubAnalytics();
  const anonStore = opts.surveyAnonStore ?? surveyAnonRatedStore;
  const routeTree = RootRoute.addChildren([ContribuerRoute.addChildren([ContribuerPairsRoute])]);
  const router = createRouter({
    routeTree,
    history: createMemoryHistory({ initialEntries: ['/contribuer/pairs'] }),
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

describe('/contribuer/pairs when campaign is closed', () => {
  it('renders the LockBanner and hides the pair card', async () => {
    const surveyClient = stubSurveyClient({
      getCurrentCampaign: vi.fn().mockResolvedValue(closedCampaign),
    });
    renderContribuerPairs({ surveyClient });
    await waitFor(() =>
      expect(screen.getByTestId('sondage-lock-banner')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('pair-card')).toBeNull();
  });

  it('reacts to 423 from submitPairRating by refreshing status', async () => {
    const getCurrentCampaign = vi
      .fn()
      .mockResolvedValueOnce(openCampaign)
      .mockResolvedValueOnce(closedCampaign);
    const submitPairRating = vi.fn().mockRejectedValue(new SondageLockedError());
    const surveyClient = stubSurveyClient({ getCurrentCampaign, submitPairRating });
    renderContribuerPairs({ surveyClient });
    await waitFor(() => expect(screen.getByTestId('pair-card')).toBeInTheDocument());
    expect(screen.queryByTestId('sondage-lock-banner')).toBeNull();

    await act(async () => {
      document.querySelector<HTMLButtonElement>('[data-verdict="LEFT_WINS"]')!.click();
    });

    await waitFor(() => expect(submitPairRating).toHaveBeenCalled());
    await waitFor(() =>
      expect(screen.getByTestId('sondage-lock-banner')).toBeInTheDocument(),
    );
    expect(getCurrentCampaign).toHaveBeenCalledTimes(2);
  });
});
