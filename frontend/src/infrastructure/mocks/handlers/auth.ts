// MSW handlers for the identity-api (Phase 5). Mutable closure state so
// tests flip between authed / anon via `setAuthed` / `setAnon`.

import { http, HttpResponse } from 'msw';

import type { components } from '@/infrastructure/api/identity/types';

type WhoAmIResponse = components['schemas']['WhoAmIResponse'];
type User = components['schemas']['User'];
type ProblemDetails = components['schemas']['ProblemDetails'];

// Test-only base URL — production builds never hit this host.
export const TEST_BASE_URL = 'https://auth.wordsparrow.example';

interface AuthedState {
  readonly whoami: WhoAmIResponse;
  readonly full: User;
}

let state: AuthedState | null = null;

// Mark the simulated session authed. `full` defaults to a minimal user.
export function setAuthed(whoami: WhoAmIResponse, full?: User): void {
  state = {
    whoami,
    full:
      full ??
      {
        id: whoami.userId,
        displayName: whoami.displayName,
        createdAt: '2026-05-01T10:00:00Z',
        providers: [],
      },
  };
}

// Mark the simulated session anonymous. whoami / getMe will 401.
export function setAnon(): void {
  state = null;
}

const problem = (status: number, title: string, detail?: string): ProblemDetails => ({
  type: 'https://wordsparrow.example/errors/test',
  title,
  status,
  ...(detail != null ? { detail } : {}),
});

const problemResponse = (status: number, body: ProblemDetails) =>
  HttpResponse.json(body, {
    status,
    headers: { 'content-type': 'application/problem+json' },
  });

// Handlers for the identity-api routes the frontend consumes.
export const authHandlers = [
  http.get(`${TEST_BASE_URL}/v1/auth/whoami`, () => {
    if (!state) return problemResponse(401, problem(401, 'unauthenticated'));
    return HttpResponse.json(state.whoami);
  }),

  http.get(`${TEST_BASE_URL}/v1/users/me`, () => {
    if (!state) return problemResponse(401, problem(401, 'unauthenticated'));
    return HttpResponse.json(state.full);
  }),

  http.patch(`${TEST_BASE_URL}/v1/users/me`, async ({ request }) => {
    if (!state) return problemResponse(401, problem(401, 'unauthenticated'));
    const body = (await request.json()) as { displayName?: string };
    const next = body.displayName ?? state.full.displayName;
    if (next.length < 1 || next.length > 30) {
      return problemResponse(
        400,
        problem(400, 'invalid display name', 'Le pseudo doit faire entre 1 et 30 caractères.'),
      );
    }
    const updated: User = { ...state.full, displayName: next };
    state = { whoami: { ...state.whoami, displayName: next }, full: updated };
    return HttpResponse.json(updated);
  }),

  http.delete(`${TEST_BASE_URL}/v1/users/me`, () => {
    if (!state) return problemResponse(401, problem(401, 'unauthenticated'));
    state = null;
    return new HttpResponse(null, { status: 204 });
  }),

  http.post(`${TEST_BASE_URL}/v1/auth/logout`, () => {
    if (!state) return problemResponse(401, problem(401, 'unauthenticated'));
    state = null;
    return new HttpResponse(null, { status: 204 });
  }),
];

// Test-only reset — production builds tree-shake this whole module.
export function __resetAuthState(): void {
  state = null;
}
