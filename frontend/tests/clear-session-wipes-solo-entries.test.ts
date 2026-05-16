// RGPD Art. 17 erase-chain guard: pins clearAllSoloEntriesForEverySession so a refactor can't silently regress to single-session removal.

import { beforeEach, describe, expect, it, vi } from 'vitest';

type SoloModule = typeof import('@/infrastructure/session/localStorageSolo');

async function loadFresh(): Promise<SoloModule> {
  vi.resetModules();
  return await import('@/infrastructure/session/localStorageSolo');
}

describe('clearAllSoloEntriesForEverySession (RGPD Art. 17 erase chain)', () => {
  beforeEach(() => {
    globalThis.localStorage.clear();
  });

  it('removes every bliss.solo.entries.* key and the legacy key', async () => {
    const { saveSoloLetter, clearAllSoloEntriesForEverySession } = await loadFresh();
    const a = '01234567-89ab-7000-8000-000000000000';
    const b = '01234567-89ab-7000-8000-000000000001';
    saveSoloLetter(a, 'puzzle-1', 0, 0, 'A');
    saveSoloLetter(b, 'puzzle-2', 0, 0, 'B');
    globalThis.localStorage.setItem('bliss.solo.entries', JSON.stringify({}));

    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${a}`)).not.toBeNull();
    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${b}`)).not.toBeNull();

    clearAllSoloEntriesForEverySession();

    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${a}`)).toBeNull();
    expect(globalThis.localStorage.getItem(`bliss.solo.entries.${b}`)).toBeNull();
    expect(globalThis.localStorage.getItem('bliss.solo.entries')).toBeNull();
  });

  it('leaves unrelated keys (session id, pseudonym, tour flag) intact', async () => {
    const { saveSoloLetter, clearAllSoloEntriesForEverySession } = await loadFresh();
    saveSoloLetter('01234567-89ab-7000-8000-000000000000', 'puzzle-1', 0, 0, 'A');
    globalThis.localStorage.setItem('bliss.session.id', 'keep-me');
    globalThis.localStorage.setItem('bliss.session.pseudonym', 'Renard 123');
    globalThis.localStorage.setItem('bliss.tour.seen', 'true');

    clearAllSoloEntriesForEverySession();

    // Identity-layer keys are erased separately by clearSession() / clearTourSeen().
    expect(globalThis.localStorage.getItem('bliss.session.id')).toBe('keep-me');
    expect(globalThis.localStorage.getItem('bliss.session.pseudonym')).toBe('Renard 123');
    expect(globalThis.localStorage.getItem('bliss.tour.seen')).toBe('true');
  });
});
