import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { setupServer } from 'msw/node';

import { handlers } from '@/infrastructure/mocks/handlers';
import { __resetLobbyStore } from '@/infrastructure/mocks/handlers/lobbyStore';
import type { components } from '@/infrastructure/api/game/types';

// Contract tests for the MSW lobby REST handlers (game/api/openapi.yaml).
// Same posture as `msw-handlers.test.ts` for the grid surface: spin up
// `setupServer`, exercise the handlers via the global `fetch`, assert
// the wire shape mirrors the spec — `Lobby` body for the happy paths,
// `application/problem+json` (RFC 7807) for the error paths.
//
// These run only at unit-test level — preview-deploy verification is
// out of scope here (CI's deploy job covers that surface).

type Lobby = components['schemas']['Lobby'];
type Problem = components['schemas']['Problem'];

const server = setupServer(...handlers);

const BASE = 'https://game.test';
const sessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';
const pseudonym = 'Joueur 1234';

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers(...handlers));
afterAll(() => server.close());
beforeEach(() => __resetLobbyStore());

describe('MSW lobby REST handlers', () => {
  it('POST /v1/lobbies returns 201 with a freshly minted base58 id and Location header', async () => {
    const response = await fetch(`${BASE}/v1/lobbies`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ownerSessionId: sessionId, ownerPseudonym: pseudonym }),
    });

    expect(response.status).toBe(201);
    const body = (await response.json()) as Lobby;
    // 8-char base58 nanoid per ADR-0020.
    expect(body.id).toMatch(/^[1-9A-HJ-NP-Za-km-z]{8}$/);
    expect(body.ownerSessionId).toBe(sessionId);
    expect(body.players).toHaveLength(1);
    expect(body.players[0]?.sessionId).toBe(sessionId);
    expect(body.players[0]?.pseudonym).toBe(pseudonym);
    expect(body.state).toBe('WAITING');
    expect(body.gridConfig).toEqual({ width: 5, height: 5 });
    // ADR-0003 §6: `game` is in `required`; `null` (still WAITING) is the
    // explicit blank value, not absence.
    expect(body.game).toBeNull();
    expect(response.headers.get('Location')).toBe(`/v1/lobbies/${body.id}`);
  });

  it('POST /v1/lobbies + GET /v1/lobbies/:id round-trips the same lobby', async () => {
    const create = await fetch(`${BASE}/v1/lobbies`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ ownerSessionId: sessionId, ownerPseudonym: pseudonym }),
    });
    const created = (await create.json()) as Lobby;

    const read = await fetch(`${BASE}/v1/lobbies/${created.id}`);
    expect(read.status).toBe(200);
    const got = (await read.json()) as Lobby;
    expect(got.id).toBe(created.id);
    expect(got.players[0]?.sessionId).toBe(sessionId);
  });

  it('POST /v1/lobbies returns 400 problem+json when the body is malformed JSON', async () => {
    const response = await fetch(`${BASE}/v1/lobbies`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: 'not-json',
    });
    expect(response.status).toBe(400);
    expect(response.headers.get('content-type')).toContain('application/problem+json');
    const body = (await response.json()) as Problem;
    expect(body.type).toBe('https://bliss.example/errors/invalid-lobby-create-request');
    expect(body.status).toBe(400);
  });

  it('POST /v1/lobbies returns 400 problem+json when required fields are missing', async () => {
    const response = await fetch(`${BASE}/v1/lobbies`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({}),
    });
    expect(response.status).toBe(400);
    const body = (await response.json()) as Problem;
    expect(body.type).toBe('https://bliss.example/errors/invalid-lobby-create-request');
  });

  it('GET /v1/lobbies/:lobbyId returns 400 problem+json on malformed lobbyId', async () => {
    const response = await fetch(`${BASE}/v1/lobbies/not-a-base58-id`);
    expect(response.status).toBe(400);
    expect(response.headers.get('content-type')).toContain('application/problem+json');
    const body = (await response.json()) as Problem;
    expect(body.type).toBe('https://bliss.example/errors/invalid-lobby-id');
  });

  it('GET /v1/lobbies/:lobbyId returns 404 problem+json when no lobby exists', async () => {
    const response = await fetch(`${BASE}/v1/lobbies/AbCdEfGh`);
    expect(response.status).toBe(404);
    expect(response.headers.get('content-type')).toContain('application/problem+json');
    const body = (await response.json()) as Problem;
    expect(body.type).toBe('https://bliss.example/errors/lobby-not-found');
  });
});
