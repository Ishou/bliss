// HTTP adapter for the survey-api surface (ADR-0056).

import type {
  ItemPair,
  PairRatingSubmission,
  RatingResult,
  RatingSubmission,
  SurveyClient,
  SurveyContribution,
  SurveyItem,
  SurveyPreferencesPatch,
  SurveyProgress,
} from '@/application/survey';
import type { components, paths } from './types';

type CorrectifRejection = components['schemas']['CorrectifRejection'];

export class SignInRequiredError extends Error {
  constructor() {
    super('sign in required');
    this.name = 'SignInRequiredError';
  }
}

export class CorrectifRejectedError extends Error {
  readonly detail: CorrectifRejection;
  constructor(detail: CorrectifRejection) {
    super(`correctif rejected by filter ${detail.filterId}: ${detail.reason}`);
    this.name = 'CorrectifRejectedError';
    this.detail = detail;
  }
}

export class AlreadyRatedError extends Error {
  readonly response: RatingResult;
  constructor(response: RatingResult) {
    super('already rated');
    this.name = 'AlreadyRatedError';
    this.response = response;
  }
}

export interface HttpSurveyClientOptions {
  readonly baseUrl: string;
  readonly fetch?: typeof globalThis.fetch;
}

export function createHttpSurveyClient(options: HttpSurveyClientOptions): SurveyClient {
  // Resolve fetch at call time so MSW `.listen()` interception takes effect.
  const fetchImpl: typeof globalThis.fetch = options.fetch
    ? options.fetch
    : (...args) => globalThis.fetch(...args);
  const base = options.baseUrl.replace(/\/$/, '');

  const getNextItem: SurveyClient['getNextItem'] = async (opts = {}) => {
    const params = new URLSearchParams();
    const excluded = opts.excludedItemIds;
    if (excluded && excluded.length > 0) params.set('excluded', excluded.join(','));
    const query = params.toString();
    const url = `${base}/v1/items/next${query ? `?${query}` : ''}`;
    const res = await fetchImpl(url, { credentials: 'include' });
    if (res.status === 204) return null;
    if (!res.ok) throw new Error(`getNextItem failed: ${res.status}`);
    return (await res.json()) as SurveyItem;
  };

  const submitRating: SurveyClient['submitRating'] = async (itemId: string, body: RatingSubmission) => {
    const url = `${base}/v1/items/${encodeURIComponent(itemId)}/rating`;
    const res = await fetchImpl(url, {
      method: 'POST',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.status === 401) throw new SignInRequiredError();
    if (res.status === 422) {
      const detail = (await res.json()) as CorrectifRejection;
      throw new CorrectifRejectedError(detail);
    }
    if (res.status === 409) {
      // Auth caller already rated this item; the response envelope is the existing rating.
      throw new AlreadyRatedError((await res.json()) as RatingResult);
    }
    if (!res.ok) throw new Error(`submitRating failed: ${res.status}`);
    return (await res.json()) as RatingResult;
  };

  const getNextPair: SurveyClient['getNextPair'] = async (opts = {}) => {
    const params = new URLSearchParams();
    const excluded = opts.excludedItemIds;
    if (excluded && excluded.length > 0) params.set('excluded', excluded.join(','));
    const query = params.toString();
    const url = `${base}/v1/items/pairs/next${query ? `?${query}` : ''}`;
    const res = await fetchImpl(url, { credentials: 'include' });
    if (res.status === 204) return null;
    if (!res.ok) throw new Error(`getNextPair failed: ${res.status}`);
    return (await res.json()) as ItemPair;
  };

  const submitPairRating: SurveyClient['submitPairRating'] = async (body: PairRatingSubmission) => {
    const res = await fetchImpl(`${base}/v1/ratings/pair`, {
      method: 'POST',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.status === 401) throw new SignInRequiredError();
    // 409 means the auth caller already rated this pair; surface as AlreadyRatedError without a payload body.
    if (res.status === 409) {
      throw new AlreadyRatedError({
        ratingId: '',
        itemId: body.leftItemId,
        submittedAs: 'auth',
        proposedItemId: null,
      });
    }
    if (!res.ok) throw new Error(`submitPairRating failed: ${res.status}`);
  };

  const getProgress: SurveyClient['getProgress'] = async () => {
    const res = await fetchImpl(`${base}/v1/me/progress`, { credentials: 'include' });
    if (res.status === 401) throw new SignInRequiredError();
    if (!res.ok) throw new Error(`getProgress failed: ${res.status}`);
    return (await res.json()) as SurveyProgress;
  };

  const getContributions: SurveyClient['getContributions'] = async () => {
    const res = await fetchImpl(`${base}/v1/me/contributions`, { credentials: 'include' });
    if (res.status === 401) throw new SignInRequiredError();
    if (!res.ok) throw new Error(`getContributions failed: ${res.status}`);
    return (await res.json()) as SurveyContribution[];
  };

  const patchPreferences: SurveyClient['patchPreferences'] = async (body: SurveyPreferencesPatch) => {
    const res = await fetchImpl(`${base}/v1/me/preferences`, {
      method: 'PATCH',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.status === 401) throw new SignInRequiredError();
    if (!res.ok) throw new Error(`patchPreferences failed: ${res.status}`);
  };

  return { getNextItem, submitRating, getNextPair, submitPairRating, getProgress, getContributions, patchPreferences };
}

export type { paths };
