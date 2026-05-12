import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';

import { createHttpSessionClient } from '@/infrastructure/api/grid/HttpSessionClient';

// MSW-driven contract test for the RGPD erasure adapter. Asserts the
// dual-call fan-out across grid-api + game-api (ADR-0039 PR #11):
//   - both endpoints are hit on every eraseSession() call,
//   - either side failing surfaces a hard error so the UI never claims
//     a partial success.

const GRID_BASE = 'https://grid.test';
const GAME_BASE = 'https://game.test';
const sessionId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';

const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('createHttpSessionClient.eraseSession', () => {
  it('fans the DELETE out to both grid-api and game-api', async () => {
    let gridHit = false;
    let gameHit = false;
    server.use(
      http.delete(`${GRID_BASE}/v1/sessions/${sessionId}`, () => {
        gridHit = true;
        return HttpResponse.json({ deleted: 3 });
      }),
      http.delete(`${GAME_BASE}/v1/sessions/${sessionId}`, () => {
        gameHit = true;
        return HttpResponse.json({
          deletedLobbies: 1,
          transferredLobbies: 0,
          removedPlayerships: 0,
          anonymisedEntries: 2,
        });
      }),
    );

    const client = createHttpSessionClient({
      gridBaseUrl: GRID_BASE,
      gameBaseUrl: GAME_BASE,
    });
    const result = await client.eraseSession(sessionId);

    expect(gridHit).toBe(true);
    expect(gameHit).toBe(true);
    expect(result).toEqual({ deleted: 3 });
  });

  it('throws when grid-api returns an error (game-api success not enough)', async () => {
    server.use(
      http.delete(`${GRID_BASE}/v1/sessions/${sessionId}`, () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'boom', status: 500 },
          { status: 500, headers: { 'content-type': 'application/problem+json' } },
        ),
      ),
      http.delete(`${GAME_BASE}/v1/sessions/${sessionId}`, () =>
        HttpResponse.json({
          deletedLobbies: 0,
          transferredLobbies: 0,
          removedPlayerships: 0,
          anonymisedEntries: 0,
        }),
      ),
    );

    const client = createHttpSessionClient({
      gridBaseUrl: GRID_BASE,
      gameBaseUrl: GAME_BASE,
    });

    await expect(client.eraseSession(sessionId)).rejects.toThrow(/grid/);
  });

  it('throws when game-api returns an error (grid-api success not enough)', async () => {
    server.use(
      http.delete(`${GRID_BASE}/v1/sessions/${sessionId}`, () =>
        HttpResponse.json({ deleted: 0 }),
      ),
      http.delete(`${GAME_BASE}/v1/sessions/${sessionId}`, () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'boom', status: 500 },
          { status: 500, headers: { 'content-type': 'application/problem+json' } },
        ),
      ),
    );

    const client = createHttpSessionClient({
      gridBaseUrl: GRID_BASE,
      gameBaseUrl: GAME_BASE,
    });

    await expect(client.eraseSession(sessionId)).rejects.toThrow(/game/);
  });

  it('skips the game-api call when no gameBaseUrl is provided', async () => {
    let gameHit = false;
    server.use(
      http.delete(`${GRID_BASE}/v1/sessions/${sessionId}`, () =>
        HttpResponse.json({ deleted: 0 }),
      ),
      http.delete(`${GAME_BASE}/v1/sessions/${sessionId}`, () => {
        gameHit = true;
        return HttpResponse.json({
          deletedLobbies: 0,
          transferredLobbies: 0,
          removedPlayerships: 0,
          anonymisedEntries: 0,
        });
      }),
    );

    const client = createHttpSessionClient({ gridBaseUrl: GRID_BASE });
    const result = await client.eraseSession(sessionId);

    expect(gameHit).toBe(false);
    expect(result).toEqual({ deleted: 0 });
  });
});
