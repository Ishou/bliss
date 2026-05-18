import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { setupServer } from 'msw/node';

import { InvalidDisplayNameError } from '@/application/auth';
import { createHttpAuthClient } from '@/infrastructure';
import {
  authHandlers,
  setAnon,
  setAuthed,
  TEST_BASE_URL,
  __resetAuthState,
} from '@/infrastructure/mocks/handlers/auth';

// MSW contract test for the HTTP AuthClient adapter.

const server = setupServer(...authHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers(...authHandlers));
afterAll(() => server.close());
beforeEach(() => __resetAuthState());

const makeClient = () => createHttpAuthClient({ baseUrl: TEST_BASE_URL });

const userId = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';

describe('HttpAuthClient.whoami', () => {
  it('returns the authed identity on 200', async () => {
    setAuthed({ userId, displayName: 'Lapin 472' });

    const result = await makeClient().whoami();

    expect(result).toEqual({ userId, displayName: 'Lapin 472' });
  });

  it('returns null on 401 (anon session)', async () => {
    setAnon();

    const result = await makeClient().whoami();

    expect(result).toBeNull();
  });
});

describe('HttpAuthClient.getMe', () => {
  it('returns the full user profile and remaps `providers` → `linkedProviders`', async () => {
    setAuthed(
      { userId, displayName: 'Lapin 472' },
      {
        id: userId,
        displayName: 'Lapin 472',
        createdAt: '2026-05-01T10:00:00Z',
        providers: [
          { provider: 'google', linkedAt: '2026-05-01T10:00:00Z', emailOptIn: true },
        ],
      },
    );

    const me = await makeClient().getMe();

    expect(me).toEqual({
      id: userId,
      displayName: 'Lapin 472',
      createdAt: '2026-05-01T10:00:00Z',
      linkedProviders: [
        { provider: 'google', linkedAt: '2026-05-01T10:00:00Z', emailOptIn: true },
      ],
    });
  });

  it('throws on 401', async () => {
    setAnon();

    await expect(makeClient().getMe()).rejects.toThrow(/getMe failed/);
  });
});

describe('HttpAuthClient.updateMe', () => {
  it('PATCHes the display name and resolves on 200', async () => {
    setAuthed({ userId, displayName: 'Joueur' });

    await makeClient().updateMe('Lapin 472');

    const me = await makeClient().getMe();
    expect(me.displayName).toBe('Lapin 472');
  });

  it('throws InvalidDisplayNameError with the RFC 7807 detail on 400', async () => {
    setAuthed({ userId, displayName: 'Joueur' });

    try {
      await makeClient().updateMe(''); // length < 1 → 400
      expect.fail('expected InvalidDisplayNameError');
    } catch (err) {
      expect(err).toBeInstanceOf(InvalidDisplayNameError);
      expect((err as Error).message).toMatch(/entre 1 et 30 caractères/);
    }
  });
});

describe('HttpAuthClient.deleteMe', () => {
  it('resolves on 204 and clears the session', async () => {
    setAuthed({ userId, displayName: 'Lapin 472' });
    const client = makeClient();

    await client.deleteMe();

    expect(await client.whoami()).toBeNull();
  });
});

describe('HttpAuthClient.logout', () => {
  it('resolves on 204 and clears the session', async () => {
    setAuthed({ userId, displayName: 'Lapin 472' });
    const client = makeClient();

    await client.logout();

    expect(await client.whoami()).toBeNull();
  });
});

describe('HttpAuthClient.signInUrl', () => {
  it('builds the Google login URL with the encoded return_to', () => {
    const url = makeClient().signInUrl('https://wordsparrow.io/grille/42?foo=bar');

    const parsed = new URL(url);
    expect(parsed.origin).toBe(TEST_BASE_URL);
    expect(parsed.pathname).toBe('/v1/auth/google/login');
    expect(parsed.searchParams.get('return_to')).toBe(
      'https://wordsparrow.io/grille/42?foo=bar',
    );
  });
});
