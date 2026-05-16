import { beforeEach, describe, expect, it, vi } from 'vitest';

type SoloModule = typeof import('@/infrastructure/session/localStorageSolo');

async function loadFresh(): Promise<SoloModule> {
  vi.resetModules();
  return await import('@/infrastructure/session/localStorageSolo');
}

describe('localStorageSolo (session-scoped)', () => {
  beforeEach(() => {
    globalThis.localStorage.clear();
  });

  it('writes under bliss.solo.entries.<sessionId> and is invisible to a different session', async () => {
    const { saveSoloLetter, loadSoloEntries } = await loadFresh();
    const sessionA = '01234567-89ab-7000-8000-000000000000';
    const sessionB = '01234567-89ab-7000-8000-000000000001';
    saveSoloLetter(sessionA, 'puzzle-1', 0, 0, 'A');

    expect(loadSoloEntries(sessionA, 'puzzle-1')).toEqual([{ row: 0, column: 0, letter: 'A' }]);
    expect(loadSoloEntries(sessionB, 'puzzle-1')).toEqual([]);
    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${sessionA}`)).not.toBeNull();
    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${sessionB}`)).toBeNull();
  });

  it('migrates a legacy unscoped bliss.solo.entries blob to the current session on first read', async () => {
    const { loadSoloEntries } = await loadFresh();
    const session = '01234567-89ab-7000-8000-000000000000';
    globalThis.localStorage.setItem(
      'bliss.solo.entries',
      JSON.stringify({
        'puzzle-1': { entries: [{ r: 1, c: 2, l: 'X' }], lockedCells: [], hintsUsed: 0 },
      }),
    );

    expect(loadSoloEntries(session, 'puzzle-1')).toEqual([{ row: 1, column: 2, letter: 'X' }]);
    expect(globalThis.localStorage.getItem('bliss.solo.entries')).toBeNull();
    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${session}`)).not.toBeNull();
  });

  it('scoped slot wins over legacy blob when both exist (no clobber)', async () => {
    const { loadSoloEntries } = await loadFresh();
    const session = '01234567-89ab-7000-8000-000000000000';
    globalThis.localStorage.setItem(
      'bliss.solo.entries',
      JSON.stringify({
        'puzzle-1': { entries: [{ r: 9, c: 9, l: 'Z' }], lockedCells: [], hintsUsed: 0 },
      }),
    );
    globalThis.localStorage.setItem(
      `bliss.solo.entries.${session}`,
      JSON.stringify({
        'puzzle-1': { entries: [{ r: 1, c: 2, l: 'X' }], lockedCells: [], hintsUsed: 0 },
      }),
    );

    expect(loadSoloEntries(session, 'puzzle-1')).toEqual([{ row: 1, column: 2, letter: 'X' }]);
    expect(globalThis.localStorage.getItem('bliss.solo.entries')).toBeNull();
  });
});
