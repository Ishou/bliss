import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import {
  RouterProvider,
  createMemoryHistory,
  createRouter,
} from '@tanstack/react-router';
import { describe, expect, it, vi } from 'vitest';
import type { AnalyticsPort } from '@/application/analytics';
import type { AuthClient } from '@/application/auth';
import type {
  ItemPair,
  PairRatingSubmission,
  SurveyAnonStore,
  SurveyClient,
  SurveyItem,
} from '@/application/survey';
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
    getCurrentCampaign: vi.fn().mockResolvedValue({
      campaignId: '0190e3a4-7a2c-7c9e-8f1a-000000000007',
      batchLabel: 'round-7',
      openedAt: '2026-05-30T10:00:00Z',
      closedAt: null,
    }),
    getLemmaMeta: vi.fn().mockResolvedValue({ priorSenses: [], priorSubTags: [] }),
    putLemmaSubTags: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

type SpyAnalytics = AnalyticsPort & { trackEvent: ReturnType<typeof vi.fn> };

function stubAnalytics(): SpyAnalytics {
  const trackEvent = vi.fn();
  return { trackEvent } as SpyAnalytics;
}

function renderContribuerPairs(opts: {
  authClient?: AuthClient;
  surveyClient?: SurveyClient;
  analytics?: AnalyticsPort;
  surveyAnonStore?: SurveyAnonStore;
} = {}) {
  const authClient = opts.authClient ?? stubAuth();
  const surveyClient = opts.surveyClient ?? stubSurveyClient();
  const analytics = opts.analytics ?? stubAnalytics();
  const anonStore = opts.surveyAnonStore ?? surveyAnonRatedStore;
  const routeTree = RootRoute.addChildren([ContribuerRoute, ContribuerPairsRoute]);
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
    analytics: analytics as { trackEvent: ReturnType<typeof vi.fn> },
    authClient,
    rendered: render(
      <AuthProvider authClient={authClient} getPseudonym={() => 'Lapin 1'}>
        <RouterProvider router={router} />
      </AuthProvider>,
    ),
  };
}

function clickVerdict(verdict: 'LEFT_WINS' | 'RIGHT_WINS' | 'BOTH_GOOD' | 'BOTH_BAD' | 'SKIP'): void {
  const btn = document.querySelector<HTMLButtonElement>(`[data-verdict="${verdict}"]`);
  if (!btn) throw new Error(`verdict button ${verdict} not found`);
  btn.click();
}

describe('Contribuer pairs route', () => {
  it('renders the pair card after the next-pair fetch resolves', async () => {
    renderContribuerPairs();
    await waitFor(() => expect(screen.getByTestId('pair-card')).toBeInTheDocument());
    expect(screen.getByRole('heading', { name: 'CHAT', level: 2 })).toBeInTheDocument();
  });

  it('renders a back-link to the binary /contribuer mode', async () => {
    renderContribuerPairs();
    await waitFor(() => expect(screen.getByTestId('mode-switch-binary')).toBeInTheDocument());
    const link = screen.getByTestId('mode-switch-binary');
    expect(link.getAttribute('href')).toBe('/contribuer');
  });

  it('shows the pool-empty message when getNextPair returns null', async () => {
    const surveyClient = stubSurveyClient({
      getNextPair: vi.fn().mockResolvedValue(null),
    });
    renderContribuerPairs({ surveyClient });
    await waitFor(() =>
      expect(screen.getByText(/Plus de paires à comparer/i)).toBeInTheDocument(),
    );
  });

  it('passes excludedItemIds from anonStore on initial load', async () => {
    localStorage.setItem('survey.anon.rated_ids', JSON.stringify(['prev-a', 'prev-b']));
    const surveyClient = stubSurveyClient();
    renderContribuerPairs({ surveyClient });
    await waitFor(() => expect(screen.getByTestId('pair-card')).toBeInTheDocument());
    expect(surveyClient.getNextPair).toHaveBeenCalledWith({
      excludedItemIds: ['prev-a', 'prev-b'],
    });
    localStorage.clear();
  });

  it('fires pair_session_start once with the submitted_as dimension', async () => {
    const analytics = stubAnalytics();
    renderContribuerPairs({ analytics });
    await waitFor(() => expect(screen.getByTestId('pair-card')).toBeInTheDocument());
    const sessionStartCalls = analytics.trackEvent.mock.calls.filter(
      ([category, action]) => category === 'survey' && action === 'pair_session_start',
    );
    expect(sessionStartCalls).toHaveLength(1);
    expect(sessionStartCalls[0][2]).toBe('anon');
  });

  it('LEFT_WINS verdict submits with both item ids + emits pair_verdict_submitted', async () => {
    const analytics = stubAnalytics();
    const surveyClient = stubSurveyClient();
    renderContribuerPairs({ analytics, surveyClient });
    await waitFor(() => expect(screen.getByTestId('pair-card')).toBeInTheDocument());

    await act(async () => { clickVerdict('LEFT_WINS'); });

    await waitFor(() => expect(surveyClient.submitPairRating).toHaveBeenCalled());
    const payload = (surveyClient.submitPairRating as ReturnType<typeof vi.fn>).mock.calls[0][0] as PairRatingSubmission;
    expect(payload.leftItemId).toBe(leftItem.itemId);
    expect(payload.rightItemId).toBe(rightItem.itemId);
    expect(payload.verdict).toBe('LEFT_WINS');
    expect(payload.difficulte).toBe(3);
    expect(payload.latencyMs).toBeGreaterThanOrEqual(0);

    const verdictCalls = analytics.trackEvent.mock.calls.filter(
      ([category, action]) => category === 'survey' && action === 'pair_verdict_submitted',
    );
    expect(verdictCalls).toHaveLength(1);
    expect(verdictCalls[0][2]).toBe('tier=mid;verdict=LEFT_WINS');
    localStorage.clear();
  });

  it('SKIP verdict does NOT call submitPairRating but advances + emits pair_verdict_skipped', async () => {
    const analytics = stubAnalytics();
    const secondPair: ItemPair = {
      mot: 'CHIEN',
      left: { ...leftItem, itemId: '0190e3a4-7a2c-7c9e-8f1a-dddddddddddd', mot: 'CHIEN' },
      right: { ...rightItem, itemId: '0190e3a4-7a2c-7c9e-8f1a-eeeeeeeeeeee', mot: 'CHIEN' },
    };
    const getNextPair = vi
      .fn()
      .mockResolvedValueOnce(samplePair)
      .mockResolvedValue(secondPair);
    const surveyClient = stubSurveyClient({ getNextPair });
    renderContribuerPairs({ analytics, surveyClient });
    await waitFor(() => expect(screen.getByTestId('pair-card')).toBeInTheDocument());

    await act(async () => { clickVerdict('SKIP'); });

    await waitFor(() => expect(screen.getByRole('heading', { name: 'CHIEN', level: 2 })).toBeInTheDocument());
    expect(surveyClient.submitPairRating).not.toHaveBeenCalled();

    const skipCalls = analytics.trackEvent.mock.calls.filter(
      ([category, action]) => category === 'survey' && action === 'pair_verdict_skipped',
    );
    expect(skipCalls).toHaveLength(1);

    const stored = JSON.parse(localStorage.getItem('survey.anon.rated_ids') ?? '[]');
    expect(stored).toContain(leftItem.itemId);
    expect(stored).toContain(rightItem.itemId);
    localStorage.clear();
  });

  it('BOTH_GOOD verdict submits BOTH_GOOD and stores both ids in the anon dedup', async () => {
    const surveyClient = stubSurveyClient();
    renderContribuerPairs({ surveyClient });
    await waitFor(() => expect(screen.getByTestId('pair-card')).toBeInTheDocument());

    await act(async () => { clickVerdict('BOTH_GOOD'); });

    await waitFor(() => expect(surveyClient.submitPairRating).toHaveBeenCalled());
    const payload = (surveyClient.submitPairRating as ReturnType<typeof vi.fn>).mock.calls[0][0] as PairRatingSubmission;
    expect(payload.verdict).toBe('BOTH_GOOD');

    const stored = JSON.parse(localStorage.getItem('survey.anon.rated_ids') ?? '[]');
    expect(stored).toContain(leftItem.itemId);
    expect(stored).toContain(rightItem.itemId);
    localStorage.clear();
  });

  it('network error from getNextPair surfaces a French message in the error banner', async () => {
    const surveyClient = stubSurveyClient({
      getNextPair: vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
    });
    renderContribuerPairs({ surveyClient });
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByRole('alert').textContent).toMatch(/réseau|connexion|réessayez/i);
  });

  it('shows the sign-in banner for anon visitors', async () => {
    renderContribuerPairs();
    await waitFor(() => expect(screen.getByTestId('pair-card')).toBeInTheDocument());
    expect(screen.getByRole('note', { name: /Invitation à se connecter/i })).toBeInTheDocument();
  });

  it('auth visit also passes anon-store ids so pre-auth dedup survives the upgrade', async () => {
    const authClient: AuthClient = {
      ...stubAuth(),
      whoami: vi.fn().mockResolvedValue({
        userId: '0190e3a4-7a2c-7c9e-8f1a-1234567890ab',
        displayName: 'Lapin 472',
      }),
    };
    const preAuthRatedId = '0190e3a4-7a2c-7c9e-8f1a-aaaaaaaaaaaa';
    localStorage.setItem('survey.anon.rated_ids', JSON.stringify([preAuthRatedId]));
    const getNextPair = vi.fn().mockResolvedValue(samplePair);
    const surveyClient = stubSurveyClient({ getNextPair });
    renderContribuerPairs({ authClient, surveyClient });
    await waitFor(() => expect(getNextPair).toHaveBeenCalled());
    expect(getNextPair).toHaveBeenLastCalledWith({ excludedItemIds: [preAuthRatedId] });
    localStorage.clear();
  });

  it('shows Annuler after a pair verdict and re-presents the pair on undo', async () => {
    const undoAction = vi.fn().mockResolvedValue(undefined);
    const getNextPair = vi
      .fn()
      .mockResolvedValueOnce(samplePair)
      .mockResolvedValueOnce(null);
    const surveyClient = stubSurveyClient({
      getNextPair,
      submitPairRating: vi.fn().mockResolvedValue({ undoToken: 'tok_pair_1' }),
      undoAction,
    });
    renderContribuerPairs({ surveyClient });

    const bothGood = await screen.findByRole('button', {
      name: /Les deux définitions sont bonnes/i,
    });
    const click = (el: HTMLElement) => { el.focus(); fireEvent.click(el); };
    await act(async () => { click(bothGood); });

    const undo = await screen.findByTestId('undo-button');
    await act(async () => { click(undo); });

    expect(undoAction).toHaveBeenCalledWith('tok_pair_1');
    expect(
      await screen.findByRole('button', { name: /Les deux définitions sont bonnes/i }),
    ).toBeTruthy();
    localStorage.clear();
  });
});
