import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// Each test imports the module fresh via `vi.resetModules()` so the
// module-level `memoryFallback` Map and any cached `localStorage`
// references don't leak between tests.
type SessionModule = typeof import('@/infrastructure/session/localStorageSession');

const SESSION_ID_KEY = 'bliss.session.id';
const PSEUDONYM_KEY = 'bliss.session.pseudonym';

const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function loadFresh(): Promise<SessionModule> {
  vi.resetModules();
  return await import('@/infrastructure/session/localStorageSession');
}

describe('localStorageSession', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('getOrCreateSessionId', () => {
    it('generates a UUID v7 and persists it on first call', async () => {
      const { getOrCreateSessionId } = await loadFresh();

      const id = getOrCreateSessionId();

      expect(id).toMatch(UUID_REGEX);
      // UUID v7 has the version nibble in the 13th hex char (after two
      // dashes + 12 hex chars). We don't pin to v7 in the regex above
      // because pre-existing values may be v4; on a fresh generation we
      // do verify the version nibble.
      expect(id[14]).toBe('7');
      expect(window.localStorage.getItem(SESSION_ID_KEY)).toBe(id);
    });

    it('is idempotent: two calls return the same id', async () => {
      const { getOrCreateSessionId } = await loadFresh();

      const first = getOrCreateSessionId();
      const second = getOrCreateSessionId();

      expect(second).toBe(first);
    });

    it('reads an existing valid id from storage instead of regenerating', async () => {
      const seeded = '01900000-0000-7abc-8def-0123456789ab';
      window.localStorage.setItem(SESSION_ID_KEY, seeded);
      const { getOrCreateSessionId } = await loadFresh();

      expect(getOrCreateSessionId()).toBe(seeded);
    });

    it('accepts a pre-existing UUID v4 without regenerating', async () => {
      const v4 = '550e8400-e29b-41d4-a716-446655440000';
      window.localStorage.setItem(SESSION_ID_KEY, v4);
      const { getOrCreateSessionId } = await loadFresh();

      expect(getOrCreateSessionId()).toBe(v4);
    });

    it('regenerates when the stored value is malformed', async () => {
      window.localStorage.setItem(SESSION_ID_KEY, 'not-a-uuid');
      const { getOrCreateSessionId } = await loadFresh();

      const id = getOrCreateSessionId();

      expect(id).toMatch(UUID_REGEX);
      expect(id).not.toBe('not-a-uuid');
      expect(window.localStorage.getItem(SESSION_ID_KEY)).toBe(id);
    });
  });

  describe('getPseudonym', () => {
    it('generates an `<Animal> ###` default and persists it on first call', async () => {
      const { getPseudonym } = await loadFresh();

      const name = getPseudonym();

      expect(name).toMatch(/^[A-ZÀ-Ü][a-zà-ü]+ \d{3}$/);
      expect(window.localStorage.getItem(PSEUDONYM_KEY)).toBe(name);
    });

    it('returns the persisted pseudonym on subsequent calls', async () => {
      const { getPseudonym } = await loadFresh();

      const first = getPseudonym();
      const second = getPseudonym();

      expect(second).toBe(first);
    });

    it('reads an existing pseudonym from storage', async () => {
      window.localStorage.setItem(PSEUDONYM_KEY, 'Alice');
      const { getPseudonym } = await loadFresh();

      expect(getPseudonym()).toBe('Alice');
    });
  });

  describe('setPseudonym', () => {
    it('trims, persists, and returns the trimmed value', async () => {
      const { setPseudonym, getPseudonym } = await loadFresh();

      const result = setPseudonym('  Bob  ');

      expect(result).toBe('Bob');
      expect(window.localStorage.getItem(PSEUDONYM_KEY)).toBe('Bob');
      expect(getPseudonym()).toBe('Bob');
    });

    it('rejects an empty string', async () => {
      const { setPseudonym } = await loadFresh();

      expect(() => setPseudonym('')).toThrow(/empty/i);
    });

    it('rejects whitespace-only input (empty after trim)', async () => {
      const { setPseudonym } = await loadFresh();

      expect(() => setPseudonym('   ')).toThrow(/empty/i);
    });

    it('rejects pseudonyms longer than 32 chars after trim', async () => {
      const { setPseudonym } = await loadFresh();
      const tooLong = 'x'.repeat(33);

      expect(() => setPseudonym(tooLong)).toThrow(/32/);
    });

    it('accepts the boundary length of exactly 32 chars', async () => {
      const { setPseudonym } = await loadFresh();
      const exact = 'x'.repeat(32);

      expect(setPseudonym(exact)).toBe(exact);
    });

    it('does not persist on validation failure', async () => {
      const { setPseudonym } = await loadFresh();
      window.localStorage.setItem(PSEUDONYM_KEY, 'Initial');

      expect(() => setPseudonym('')).toThrow();
      expect(window.localStorage.getItem(PSEUDONYM_KEY)).toBe('Initial');
    });
  });

  describe('clearSession', () => {
    it('removes both session id and pseudonym from storage', async () => {
      const { getOrCreateSessionId, getPseudonym, clearSession } =
        await loadFresh();
      getOrCreateSessionId();
      getPseudonym();
      expect(window.localStorage.getItem(SESSION_ID_KEY)).not.toBeNull();
      expect(window.localStorage.getItem(PSEUDONYM_KEY)).not.toBeNull();

      clearSession();

      expect(window.localStorage.getItem(SESSION_ID_KEY)).toBeNull();
      expect(window.localStorage.getItem(PSEUDONYM_KEY)).toBeNull();
    });
  });

  describe('localStorage unavailable (Safari Private Mode / SSR)', () => {
    // Replace `window.localStorage` with an object that throws on every
    // access — the tightest reproduction of Safari Private Mode +
    // some-iframe-sandboxed-storage failure modes.
    function installThrowingStorage(): void {
      const throwing: Storage = {
        get length(): number {
          throw new Error('localStorage disabled');
        },
        clear(): void {
          throw new Error('localStorage disabled');
        },
        getItem(): string | null {
          throw new Error('localStorage disabled');
        },
        key(): string | null {
          throw new Error('localStorage disabled');
        },
        removeItem(): void {
          throw new Error('localStorage disabled');
        },
        setItem(): void {
          throw new Error('localStorage disabled');
        },
      };
      Object.defineProperty(window, 'localStorage', {
        configurable: true,
        get: () => throwing,
      });
    }

    it('falls back to in-memory state without crashing', async () => {
      installThrowingStorage();
      const { getOrCreateSessionId, getPseudonym, setPseudonym, clearSession } =
        await loadFresh();

      // Reads + writes do not throw, even though every storage call does.
      const id = getOrCreateSessionId();
      expect(id).toMatch(UUID_REGEX);

      // In-memory persistence within a page lifetime: same id on repeat call.
      expect(getOrCreateSessionId()).toBe(id);

      const defaultName = getPseudonym();
      expect(defaultName).toMatch(/^[A-ZÀ-Ü][a-zà-ü]+ \d{3}$/);
      expect(getPseudonym()).toBe(defaultName);

      const set = setPseudonym('Charlie');
      expect(set).toBe('Charlie');
      expect(getPseudonym()).toBe('Charlie');

      // clearSession also stays non-fatal.
      expect(() => clearSession()).not.toThrow();
    });
  });
});
