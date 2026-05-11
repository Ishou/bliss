import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

import { LobbyClientError } from '@/application/game';
import { createHttpLobbyClient } from '@/infrastructure';
import type { components } from '@/infrastructure/api/game/types';
import type { LobbyId, Pseudonym, SessionId } from '@/domain/game';

// MSW-driven contract test for the HTTP `LobbyClient` adapter. Same
// pattern as `msw-handlers.test.ts`: spin up an in-process server, reset
// handlers between tests, close on teardown. External boundary (the
// network) is mocked per ADR-0001; everything from `openapi-fetch` down
// runs the real implementation.

type WireLobby = components['schemas']['Lobby'];
type WireProblem = components['schemas']['Problem'];

const BASE_URL = 'https://game.test';
const sessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b' as SessionId;
const pseudonym = 'Joueur 1234' as Pseudonym;
const lobbyId = '7gQ2xK9p' as LobbyId;

const lobbyFixture: WireLobby = {
  id: '7gQ2xK9p',
  ownerSessionId: sessionId,
  players: [{ sessionId, pseudonym, joinedAt: '2026-05-02T15:30:00Z' }],
  state: 'WAITING',
  gridConfig: { width: 7, height: 7 },
  game: null,
  code: 'A2B3C4',
};

const problemBody = (status: number, detail: string, type: string): WireProblem => ({
  type, title: 'Problem', status, detail,
});

const respondProblem = (status: number, body: WireProblem) =>
  HttpResponse.json(body, { status, headers: { 'content-type': 'application/problem+json' } });

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const makeClient = () => createHttpLobbyClient({ baseUrl: BASE_URL });

const expectError = async (
  promise: Promise<unknown>,
  expected: { kind: LobbyClientError['kind']; status: number | null; messageMatch?: RegExp; type?: string },
) => {
  await expect(promise).rejects.toBeInstanceOf(LobbyClientError);
  const err = (await promise.catch((e) => e)) as LobbyClientError;
  expect(err.kind).toBe(expected.kind);
  expect(err.status).toBe(expected.status);
  if (expected.messageMatch) expect(err.message).toMatch(expected.messageMatch);
  if (expected.type) expect(err.problem?.type).toBe(expected.type);
};

describe('HttpLobbyClient.createLobby', () => {
  it('POSTs the request body and maps the 201 response to a domain Lobby', async () => {
    let receivedBody: unknown;
    server.use(
      http.post(`${BASE_URL}/v1/lobbies`, async ({ request }) => {
        receivedBody = await request.json();
        return HttpResponse.json(lobbyFixture, {
          status: 201, headers: { Location: `/v1/lobbies/${lobbyFixture.id}` },
        });
      }),
    );

    const lobby = await makeClient().createLobby({ ownerSessionId: sessionId, ownerPseudonym: pseudonym });

    expect(receivedBody).toEqual({ ownerSessionId: sessionId, ownerPseudonym: pseudonym });
    // `createLobby` carries the server-issued `id` — the home-route
    // button reads it to navigate. `getLobby` still drops `id` because
    // the route already knows it from the URL.
    expect(lobby).toEqual({
      id: lobbyFixture.id,
      players: lobbyFixture.players,
      ownerSessionId: lobbyFixture.ownerSessionId,
      state: lobbyFixture.state,
      gridConfig: lobbyFixture.gridConfig,
      game: lobbyFixture.game,
      code: lobbyFixture.code,
    });
  });

  it('lifts a 400 problem-details body into LobbyClientError(kind: validation)', async () => {
    const problem = problemBody(400, 'ownerPseudonym must not be blank',
      'https://bliss.example/errors/invalid-lobby-create-request');
    server.use(http.post(`${BASE_URL}/v1/lobbies`, () => respondProblem(400, problem)));

    await expectError(
      makeClient().createLobby({ ownerSessionId: sessionId, ownerPseudonym: pseudonym }),
      { kind: 'validation', status: 400, messageMatch: /ownerPseudonym must not be blank/, type: problem.type },
    );
  });

  it('treats a non-RFC-7807 response (e.g., a wrong service occupying the port) as upstream-unavailable', async () => {
    // Mimics the local-dev failure mode: something is bound to
    // localhost:8081 but it isn't game-api — a gunicorn HTML 404
    // sitting where the Bliss service should be. The bare HTTP body
    // doesn't carry `type` / `title` / `status`, so the adapter must
    // surface it as `upstream-unavailable` rather than `not-found`
    // (which would render "Une erreur est survenue" instead of the
    // correct "Service indisponible").
    server.use(
      http.post(`${BASE_URL}/v1/lobbies`, () =>
        new HttpResponse('<html>404 Not Found</html>', {
          status: 404,
          headers: { 'content-type': 'text/html; charset=utf-8' },
        }),
      ),
    );

    await expectError(
      makeClient().createLobby({ ownerSessionId: sessionId, ownerPseudonym: pseudonym }),
      { kind: 'upstream-unavailable', status: 404 },
    );
  });
});

describe('HttpLobbyClient.getLobby', () => {
  it('GETs the lobby and maps the 200 response to a domain Lobby', async () => {
    let calledUrl: string | null = null;
    server.use(
      http.get(`${BASE_URL}/v1/lobbies/:lobbyId`, ({ request }) => {
        calledUrl = request.url;
        return HttpResponse.json(lobbyFixture);
      }),
    );

    const lobby = await makeClient().getLobby(lobbyId);

    expect(calledUrl).toBe(`${BASE_URL}/v1/lobbies/${lobbyId}`);
    expect(lobby.ownerSessionId).toBe(sessionId);
    expect(lobby.state).toBe('WAITING');
    expect(lobby.players).toHaveLength(1);
  });

  it('lifts a 404 problem-details body into LobbyClientError(kind: not-found)', async () => {
    const problem = problemBody(404, `No lobby with id ${lobbyId}`,
      'https://bliss.example/errors/lobby-not-found');
    server.use(http.get(`${BASE_URL}/v1/lobbies/:lobbyId`, () => respondProblem(404, problem)));

    await expectError(makeClient().getLobby(lobbyId),
      { kind: 'not-found', status: 404, type: problem.type });
  });

  it('lifts a 5xx problem-details body into LobbyClientError(kind: transient)', async () => {
    const problem = problemBody(503, 'Lobby store unavailable',
      'https://bliss.example/errors/internal');
    server.use(http.get(`${BASE_URL}/v1/lobbies/:lobbyId`, () => respondProblem(503, problem)));

    await expectError(makeClient().getLobby(lobbyId),
      { kind: 'transient', status: 503, messageMatch: /Lobby store unavailable/ });
  });

  it('lifts a network failure (resolver throws) into LobbyClientError(kind: upstream-unavailable)', async () => {
    server.use(http.get(`${BASE_URL}/v1/lobbies/:lobbyId`, () => HttpResponse.error()));

    await expectError(makeClient().getLobby(lobbyId),
      { kind: 'upstream-unavailable', status: null });
  });
});

describe('HttpLobbyClient.listMyLobbies', () => {
  const summaryFixture: components['schemas']['LobbySummary'] = {
    id: '7Hk2pQrS',
    code: 'A2B3C4',
    state: 'IN_PROGRESS',
    gridConfig: { width: 15, height: 12 },
    playerCount: 2,
    lastActivityAt: '2026-05-10T18:00:00Z',
    title: 'Mardi soir',
  };

  it('GETs the session lobbies and maps the 200 array body to LobbySummary[]', async () => {
    let calledUrl: string | null = null;
    server.use(
      http.get(`${BASE_URL}/v1/sessions/:sessionId/lobbies`, ({ request }) => {
        calledUrl = request.url;
        return HttpResponse.json([summaryFixture]);
      }),
    );

    const summaries = await makeClient().listMyLobbies(sessionId);

    expect(calledUrl).toBe(`${BASE_URL}/v1/sessions/${sessionId}/lobbies`);
    expect(summaries).toHaveLength(1);
    expect(summaries[0]).toEqual({
      id: summaryFixture.id,
      code: summaryFixture.code,
      state: summaryFixture.state,
      gridConfig: summaryFixture.gridConfig,
      playerCount: summaryFixture.playerCount,
      lastActivityAt: summaryFixture.lastActivityAt,
      title: summaryFixture.title,
    });
  });

  it('returns [] when the server responds with an empty array', async () => {
    server.use(
      http.get(`${BASE_URL}/v1/sessions/:sessionId/lobbies`, () => HttpResponse.json([])),
    );

    const summaries = await makeClient().listMyLobbies(sessionId);

    expect(summaries).toEqual([]);
  });

  it('lifts a 400 problem-details body into LobbyClientError(kind: validation)', async () => {
    const problem = problemBody(
      400,
      'sessionId must be a valid UUID',
      'https://bliss.example/errors/invalid-session-id',
    );
    server.use(
      http.get(`${BASE_URL}/v1/sessions/:sessionId/lobbies`, () => respondProblem(400, problem)),
    );

    await expectError(makeClient().listMyLobbies(sessionId), {
      kind: 'validation',
      status: 400,
      messageMatch: /sessionId must be a valid UUID/,
      type: problem.type,
    });
  });

  it('lifts a network failure (resolver throws) into LobbyClientError(kind: upstream-unavailable)', async () => {
    server.use(
      http.get(`${BASE_URL}/v1/sessions/:sessionId/lobbies`, () => HttpResponse.error()),
    );

    await expectError(makeClient().listMyLobbies(sessionId), {
      kind: 'upstream-unavailable',
      status: null,
    });
  });
});
