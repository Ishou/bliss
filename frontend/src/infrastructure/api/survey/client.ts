// HTTP adapter for the survey-api surface (ADR-0002 §7). Pure fetch
// because the surface is small (5 endpoints) and we need finer error
// classification than openapi-fetch's generic envelope provides — 401
// triggers a sign-in nudge, 422 carries an RFC 7807 correctif rejection.
//
// Auth is optional on /v1/items/*; required on /v1/me/*. `credentials:
// 'include'` is set per call so the __Secure-ws_session cookie flows
// through. The composition root reads VITE_SURVEY_API_BASE; tests pass
// baseUrl explicitly.
//
// The shape returned satisfies the application-layer `SurveyClient`
// port; the wire types come from the generated OpenAPI types but only
// flow through this file — the application layer never sees them.

import type {
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

  return { getNextItem, submitRating, getProgress, getContributions, patchPreferences };
}

export type { paths };
