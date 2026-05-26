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
  RatingResult,
  RatingSubmission,
  SurveyAnonStore,
  SurveyClient,
  SurveyItem,
} from '@/application/survey';
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
    getProgress: vi.fn().mockResolvedValue({
      itemsRated: 0,
      calibrationAgreement: null,
      lastRatedAt: null,
    }),
    getContributions: vi.fn().mockResolvedValue([]),
    patchPreferences: vi.fn().mockResolvedValue(undefined),
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
    analytics: analytics as { trackEvent: ReturnType<typeof vi.fn> },
    authClient,
    rendered: render(
      <AuthProvider authClient={authClient} getPseudonym={() => 'Lapin 1'}>
        <RouterProvider router={router} />
      </AuthProvider>,
    ),
  };
}

function clickVerdict(verdict: 'BAD' | 'SKIP' | 'GOOD'): void {
  const btn = document.querySelector<HTMLButtonElement>(`[data-verdict="${verdict}"]`);
  if (!btn) throw new Error(`verdict button ${verdict} not found`);
  btn.click();
}

describe('Sondage route', () => {
  it('renders the rating card after the next-item fetch resolves', async () => {
    renderSondage();
    await waitFor(() => expect(screen.getByTestId('rating-card')).toBeInTheDocument());
    expect(screen.getByRole('heading', { name: 'CHAT' })).toBeInTheDocument();
  });

  it('shows the sign-in banner for anon visitors', async () => {
    renderSondage();
    await waitFor(() => expect(screen.getByTestId('rating-card')).toBeInTheDocument());
    expect(screen.getByRole('note', { name: /Invitation à se connecter/i })).toBeInTheDocument();
  });

  it('passes excludedItemIds from localStorage for anon visitors', async () => {
    localStorage.setItem('survey.anon.rated_ids', JSON.stringify(['prev-a', 'prev-b']));
    const surveyClient = stubSurveyClient();
    renderSondage({ surveyClient });
    await waitFor(() => expect(screen.getByTestId('rating-card')).toBeInTheDocument());
    expect(surveyClient.getNextItem).toHaveBeenCalledWith({
      excludedItemIds: ['prev-a', 'prev-b'],
    });
    localStorage.clear();
  });

  it('shows the pool-empty message when getNextItem returns null', async () => {
    const surveyClient = stubSurveyClient({
      getNextItem: vi.fn().mockResolvedValue(null),
    });
    renderSondage({ surveyClient });
    await waitFor(() =>
      expect(screen.getByText(/Plus d.indices à noter/i)).toBeInTheDocument(),
    );
  });

  it('fires survey_session_start once with the submitted_as dimension', async () => {
    const analytics = stubAnalytics();
    renderSondage({ analytics });
    await waitFor(() => expect(screen.getByTestId('rating-card')).toBeInTheDocument());
    const sessionStartCalls = analytics.trackEvent.mock.calls.filter(
      ([category, action]) => category === 'survey' && action === 'session_start',
    );
    expect(sessionStartCalls).toHaveLength(1);
    expect(sessionStartCalls[0][2]).toBe('anon');
  });

  it('does not flash the sign-in banner during the auth-hydration window', async () => {
    let resolveWhoami: (value: null) => void = () => {};
    const whoamiPromise = new Promise<null>((resolve) => { resolveWhoami = resolve; });
    const authClient: AuthClient = {
      ...stubAuth(),
      whoami: vi.fn().mockReturnValue(whoamiPromise),
    };
    renderSondage({ authClient });
    expect(screen.queryByRole('note', { name: /Invitation à se connecter/i })).toBeNull();
    await act(async () => { resolveWhoami(null); });
    await waitFor(() =>
      expect(screen.getByRole('note', { name: /Invitation à se connecter/i })).toBeInTheDocument(),
    );
  });

  it('keeps the sign-in banner hidden once the visitor resolves as authed', async () => {
    const authClient: AuthClient = {
      ...stubAuth(),
      whoami: vi.fn().mockResolvedValue({
        userId: '0190e3a4-7a2c-7c9e-8f1a-1234567890ab',
        displayName: 'Lapin 472',
      }),
    };
    renderSondage({ authClient });
    await waitFor(() => expect(screen.getByTestId('rating-card')).toBeInTheDocument());
    expect(screen.queryByRole('note', { name: /Invitation à se connecter/i })).toBeNull();
  });

  it('GOOD verdict submits qualite=5 difficulte=3 + adds the item to anon dedup', async () => {
    const analytics = stubAnalytics();
    const surveyClient = stubSurveyClient();
    renderSondage({ analytics, surveyClient });
    await waitFor(() => expect(screen.getByTestId('rating-card')).toBeInTheDocument());

    await act(async () => { clickVerdict('GOOD'); });

    await waitFor(() => expect(surveyClient.submitRating).toHaveBeenCalled());
    const submitArgs = (surveyClient.submitRating as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(submitArgs[0]).toBe(sampleItem.itemId);
    const payload = submitArgs[1] as RatingSubmission;
    expect(payload.qualite).toBe(5);
    expect(payload.difficulte).toBe(3);
    expect(payload.correctif).toBeUndefined();
    expect(payload.flag).toBeUndefined();

    const verdictEventCalls = analytics.trackEvent.mock.calls.filter(
      ([category, action]) => category === 'survey' && action === 'verdict_submitted',
    );
    expect(verdictEventCalls).toHaveLength(1);
    expect(verdictEventCalls[0][2]).toBe('tier=mid;verdict=GOOD');

    const stored = JSON.parse(localStorage.getItem('survey.anon.rated_ids') ?? '[]');
    expect(stored).toContain(sampleItem.itemId);
    localStorage.clear();
  });

  it('BAD verdict submits qualite=1 difficulte=3', async () => {
    const analytics = stubAnalytics();
    const surveyClient = stubSurveyClient();
    renderSondage({ analytics, surveyClient });
    await waitFor(() => expect(screen.getByTestId('rating-card')).toBeInTheDocument());

    await act(async () => { clickVerdict('BAD'); });

    await waitFor(() => expect(surveyClient.submitRating).toHaveBeenCalled());
    const payload = (surveyClient.submitRating as ReturnType<typeof vi.fn>).mock.calls[0][1] as RatingSubmission;
    expect(payload.qualite).toBe(1);
    expect(payload.difficulte).toBe(3);

    const verdictEventCalls = analytics.trackEvent.mock.calls.filter(
      ([category, action]) => category === 'survey' && action === 'verdict_submitted',
    );
    expect(verdictEventCalls[0][2]).toBe('tier=mid;verdict=BAD');
    localStorage.clear();
  });

  it('SKIP verdict does NOT call submitRating but still advances + emits verdict_skipped', async () => {
    const analytics = stubAnalytics();
    const second: SurveyItem = { ...sampleItem, itemId: '0190e3a4-7a2c-7c9e-8f1a-cafecafecafe', mot: 'NEXT' };
    const getNextItem = vi
      .fn()
      .mockResolvedValueOnce(sampleItem)
      .mockResolvedValue(second);
    const surveyClient = stubSurveyClient({ getNextItem });
    renderSondage({ analytics, surveyClient });
    await waitFor(() => expect(screen.getByTestId('rating-card')).toBeInTheDocument());

    await act(async () => { clickVerdict('SKIP'); });

    await waitFor(() => expect(screen.getByRole('heading', { name: 'NEXT' })).toBeInTheDocument());
    expect(surveyClient.submitRating).not.toHaveBeenCalled();

    const skipEventCalls = analytics.trackEvent.mock.calls.filter(
      ([category, action]) => category === 'survey' && action === 'verdict_skipped',
    );
    expect(skipEventCalls).toHaveLength(1);
    expect(skipEventCalls[0][2]).toBe('tier=mid');

    // anon dedup prevents the same item re-appearing in subsequent sessions.
    const stored = JSON.parse(localStorage.getItem('survey.anon.rated_ids') ?? '[]');
    expect(stored).toContain(sampleItem.itemId);
    localStorage.clear();
  });

  it('auth SKIP excludes skipped ids on the next getNextItem call without touching surveyAnonStore', async () => {
    const authClient: AuthClient = {
      ...stubAuth(),
      whoami: vi.fn().mockResolvedValue({
        userId: '0190e3a4-7a2c-7c9e-8f1a-1234567890ab',
        displayName: 'Lapin 472',
      }),
    };
    const second: SurveyItem = { ...sampleItem, itemId: '0190e3a4-7a2c-7c9e-8f1a-cafecafecafe', mot: 'NEXT' };
    const getNextItem = vi
      .fn()
      .mockResolvedValueOnce(sampleItem)
      .mockResolvedValue(second);
    const surveyClient = stubSurveyClient({ getNextItem });
    renderSondage({ authClient, surveyClient });
    await waitFor(() => expect(screen.getByTestId('rating-card')).toBeInTheDocument());

    await act(async () => { clickVerdict('SKIP'); });

    await waitFor(() =>
      expect(getNextItem).toHaveBeenLastCalledWith({ excludedItemIds: [sampleItem.itemId] }),
    );
    expect(surveyClient.submitRating).not.toHaveBeenCalled();
    // Auth path does not pollute the anon localStorage.
    expect(localStorage.getItem('survey.anon.rated_ids')).toBeNull();
  });
});
