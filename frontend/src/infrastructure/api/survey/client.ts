// HTTP adapter for the survey-api surface (ADR-0002 §7). Pure fetch
// because the surface is small (5 endpoints) and we need finer error
// classification than openapi-fetch's generic envelope provides — 401
// triggers a sign-in nudge, 422 carries an RFC 7807 correctif rejection.
//
// Auth is optional on /v1/items/*; required on /v1/me/*. `credentials:
// 'include'` is set per call so the __Secure-ws_session cookie flows
// through. The composition root reads VITE_SURVEY_API_BASE; tests pass
// baseUrl explicitly.

import type { components, paths } from './types';

type Item = components['schemas']['Item'];
type RatingRequest = components['schemas']['RatingRequest'];
type RatingResponse = components['schemas']['RatingResponse'];
type ProgressResponse = components['schemas']['ProgressResponse'];
type ContributionItem = components['schemas']['ContributionItem'];
type CorrectifRejection = components['schemas']['CorrectifRejection'];
type PreferencesPatch = components['schemas']['PreferencesPatch'];

// Convenience re-exports for application/ui layers that need the shapes.
export type {
  Item as SurveyApiItem,
  RatingRequest as SurveyApiRatingRequest,
  RatingResponse as SurveyApiRatingResponse,
  ProgressResponse as SurveyApiProgressResponse,
  ContributionItem as SurveyApiContributionItem,
  PreferencesPatch as SurveyApiPreferencesPatch,
};

export class SignInRequiredError extends Error {
  constructor() {
    super('sign in required');
    this.name = 'SignInRequiredError';
  }
}

export class CorrectifRejectedError extends Error {
  constructor(public readonly detail: CorrectifRejection) {
    super(`correctif rejected by filter ${detail.filterId}: ${detail.reason}`);
    this.name = 'CorrectifRejectedError';
  }
}

export class AlreadyRatedError extends Error {
  constructor(public readonly response: RatingResponse) {
    super('already rated');
    this.name = 'AlreadyRatedError';
  }
}

export interface SurveyClient {
  getNextItem(opts?: { readonly excludedItemIds?: readonly string[] }): Promise<Item | null>;
  submitRating(itemId: string, body: RatingRequest): Promise<RatingResponse>;
  getProgress(): Promise<ProgressResponse>;
  getContributions(): Promise<ReadonlyArray<ContributionItem>>;
  patchPreferences(body: PreferencesPatch): Promise<void>;
}

export interface HttpSurveyClientOptions {
  readonly baseUrl: string;
  readonly fetch?: typeof globalThis.fetch;
}

export function createHttpSurveyClient(options: HttpSurveyClientOptions): SurveyClient {
  // Resolve fetch at call time so MSW's `setupServer` interception
  // (which monkey-patches `globalThis.fetch` on `.listen()`) takes
  // effect even when the client is constructed at module load.
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
    return (await res.json()) as Item;
  };

  const submitRating: SurveyClient['submitRating'] = async (itemId, body) => {
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
      // Auth caller already rated this item; return the existing rating envelope.
      throw new AlreadyRatedError((await res.json()) as RatingResponse);
    }
    if (!res.ok) throw new Error(`submitRating failed: ${res.status}`);
    return (await res.json()) as RatingResponse;
  };

  const getProgress: SurveyClient['getProgress'] = async () => {
    const res = await fetchImpl(`${base}/v1/me/progress`, { credentials: 'include' });
    if (res.status === 401) throw new SignInRequiredError();
    if (!res.ok) throw new Error(`getProgress failed: ${res.status}`);
    return (await res.json()) as ProgressResponse;
  };

  const getContributions: SurveyClient['getContributions'] = async () => {
    const res = await fetchImpl(`${base}/v1/me/contributions`, { credentials: 'include' });
    if (res.status === 401) throw new SignInRequiredError();
    if (!res.ok) throw new Error(`getContributions failed: ${res.status}`);
    return (await res.json()) as ContributionItem[];
  };

  const patchPreferences: SurveyClient['patchPreferences'] = async (body) => {
    const res = await fetchImpl(`${base}/v1/me/preferences`, {
      method: 'PATCH',
      credentials: 'include',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.status === 401) throw new SignInRequiredError();
    if (!res.ok) throw new Error(`patchPreferences failed: ${res.status}`);
  };

  return { getNextItem, submitRating, getProgress, getContributions, patchPreferences };
}

export type { paths };
