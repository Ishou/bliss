import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

import { createHttpSurveyClient } from '@/infrastructure';
import { useLemmaMeta } from '@/ui/components/sondage';

const BASE = 'http://survey.test';
const client = createHttpSurveyClient({ baseUrl: BASE });

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('useLemmaMeta', () => {
  it('returns undefined data when the mot is empty', () => {
    const { result } = renderHook(() => useLemmaMeta(client, ''));
    expect(result.current.data).toBeUndefined();
    expect(result.current.isLoading).toBe(false);
    expect(result.current.isError).toBe(false);
  });

  it('fetches the lemma meta and resolves with data', async () => {
    const payload = {
      priorSenses: ['animal félin', 'conversation digitale'],
      priorSubTags: ['félin', 'domestique'],
    };
    server.use(
      http.get(`${BASE}/v1/lemma-meta/CHAT-uniq-a`, () => HttpResponse.json(payload)),
    );
    const { result } = renderHook(() => useLemmaMeta(client, 'CHAT-uniq-a'));
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.data).toEqual(payload);
    expect(result.current.isError).toBe(false);
  });

  it('flags isError when the request fails', async () => {
    server.use(
      http.get(`${BASE}/v1/lemma-meta/BOOM-uniq-b`, () =>
        HttpResponse.json({ title: 'oops', status: 503 }, { status: 503 }),
      ),
    );
    const { result } = renderHook(() => useLemmaMeta(client, 'BOOM-uniq-b'));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
    expect(result.current.isLoading).toBe(false);
  });

  it('returns empty arrays for a lemma with no history', async () => {
    server.use(
      http.get(`${BASE}/v1/lemma-meta/UNKNOWN-uniq-c`, () =>
        HttpResponse.json({ priorSenses: [], priorSubTags: [] }),
      ),
    );
    const { result } = renderHook(() => useLemmaMeta(client, 'UNKNOWN-uniq-c'));
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(result.current.data?.priorSenses).toEqual([]);
    expect(result.current.data?.priorSubTags).toEqual([]);
  });

  it('encodes path-special characters in the mot', async () => {
    let calledUrl = '';
    server.use(
      http.get(`${BASE}/v1/lemma-meta/:mot`, ({ request }) => {
        calledUrl = new URL(request.url).pathname;
        return HttpResponse.json({ priorSenses: [], priorSubTags: [] });
      }),
    );
    const { result } = renderHook(() => useLemmaMeta(client, 'café / thé'));
    await waitFor(() => expect(result.current.data).toBeDefined());
    expect(calledUrl).toBe('/v1/lemma-meta/caf%C3%A9%20%2F%20th%C3%A9');
  });

  it('returns undefined when the client is null', () => {
    const { result } = renderHook(() => useLemmaMeta(null, 'CHAT'));
    expect(result.current.data).toBeUndefined();
    expect(result.current.isLoading).toBe(false);
  });
});
