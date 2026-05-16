import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

type SoloModule = typeof import('@/infrastructure/session/localStorageSolo');

async function loadFresh(): Promise<SoloModule> {
  vi.resetModules();
  return await import('@/infrastructure/session/localStorageSolo');
}

const SESSION = '01234567-89ab-7000-8000-000000000000';
const KEY = `bliss.solo.entries.${SESSION}`;
const PUZZLE_A = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';
const PUZZLE_B = '0190e3a4-7a2c-7c9e-8f1a-aaaaaaaaaaaa';

describe('localStorageSolo', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    window.localStorage.clear();
  });

  describe('loadSoloEntries', () => {
    it('returns an empty array when nothing has been stored', async () => {
      const { loadSoloEntries } = await loadFresh();
      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
    });

    it('returns previously saved entries for the puzzle', async () => {
      const { saveSoloLetter, loadSoloEntries } = await loadFresh();
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_A, 1, 2, 'B');

      const entries = loadSoloEntries(SESSION, PUZZLE_A);

      expect(entries).toEqual([
        { row: 0, column: 0, letter: 'A' },
        { row: 1, column: 2, letter: 'B' },
      ]);
    });

    it('isolates entries between puzzles', async () => {
      const { saveSoloLetter, loadSoloEntries } = await loadFresh();
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_B, 1, 1, 'Z');

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([
        { row: 0, column: 0, letter: 'A' },
      ]);
      expect(loadSoloEntries(SESSION, PUZZLE_B)).toEqual([
        { row: 1, column: 1, letter: 'Z' },
      ]);
    });

    it('returns an empty array on a malformed store', async () => {
      const { loadSoloEntries } = await loadFresh();
      window.localStorage.setItem(KEY, '{not json');
      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
    });

    it('skips entries that fail the shape check', async () => {
      const { loadSoloEntries } = await loadFresh();
      window.localStorage.setItem(
        KEY,
        JSON.stringify({
          [PUZZLE_A]: [
            { r: 0, c: 0, l: 'A' },
            { r: 1, c: 'oops', l: 'B' },
            { r: 2, c: 2, l: '' },
            { r: 3, c: 3 },
          ],
        }),
      );

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([
        { row: 0, column: 0, letter: 'A' },
      ]);
    });
  });

  describe('saveSoloLetter', () => {
    it('upserts existing positions instead of duplicating them', async () => {
      const { saveSoloLetter, loadSoloEntries } = await loadFresh();
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'B');

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([
        { row: 0, column: 0, letter: 'B' },
      ]);
    });

    it('removes the entry when letter is null', async () => {
      const { saveSoloLetter, loadSoloEntries } = await loadFresh();
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, null);

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
    });

    it('removes the entry when letter is the empty string', async () => {
      const { saveSoloLetter, loadSoloEntries } = await loadFresh();
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, '');

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
    });

    it('drops the puzzle key entirely once its last entry is removed', async () => {
      const { saveSoloLetter } = await loadFresh();
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, null);

      const stored = JSON.parse(
        window.localStorage.getItem(KEY) ?? '{}',
      ) as Record<string, unknown>;
      expect(stored[PUZZLE_A]).toBeUndefined();
    });
  });

  describe('clearSoloEntriesForPuzzle', () => {
    it('drops only the targeted puzzle', async () => {
      const { saveSoloLetter, loadSoloEntries, clearSoloEntriesForPuzzle } = await loadFresh();
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_B, 1, 1, 'Z');

      clearSoloEntriesForPuzzle(SESSION, PUZZLE_A);

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
      expect(loadSoloEntries(SESSION, PUZZLE_B)).toEqual([
        { row: 1, column: 1, letter: 'Z' },
      ]);
    });

    it('is a no-op when the puzzle has no entries', async () => {
      const { loadSoloEntries, clearSoloEntriesForPuzzle } = await loadFresh();
      expect(() => clearSoloEntriesForPuzzle(SESSION, PUZZLE_A)).not.toThrow();
      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
    });
  });

  describe('hintsUsed persistence', () => {
    it('returns 0 when nothing has been recorded', async () => {
      const { loadSoloHintsUsed } = await loadFresh();
      expect(loadSoloHintsUsed(SESSION, PUZZLE_A)).toBe(0);
    });

    it('increments and survives a fresh read (page reload analog)', async () => {
      const { recordSoloHintUsed, loadSoloHintsUsed } = await loadFresh();
      recordSoloHintUsed(SESSION, PUZZLE_A);
      recordSoloHintUsed(SESSION, PUZZLE_A);
      expect(loadSoloHintsUsed(SESSION, PUZZLE_A)).toBe(2);
    });

    it('preserves entries and lockedCells when incremented', async () => {
      const { saveSoloLetter, recordSoloHintUsed, loadSoloEntries, loadSoloHintsUsed } = await loadFresh();
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      recordSoloHintUsed(SESSION, PUZZLE_A);
      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([{ row: 0, column: 0, letter: 'A' }]);
      expect(loadSoloHintsUsed(SESSION, PUZZLE_A)).toBe(1);
    });

    it('isolates hintsUsed between puzzles', async () => {
      const { recordSoloHintUsed, loadSoloHintsUsed } = await loadFresh();
      recordSoloHintUsed(SESSION, PUZZLE_A);
      expect(loadSoloHintsUsed(SESSION, PUZZLE_B)).toBe(0);
    });

    it('clearForPuzzle resets the hintsUsed counter', async () => {
      const { recordSoloHintUsed, clearSoloEntriesForPuzzle, loadSoloHintsUsed } = await loadFresh();
      recordSoloHintUsed(SESSION, PUZZLE_A);
      clearSoloEntriesForPuzzle(SESSION, PUZZLE_A);
      expect(loadSoloHintsUsed(SESSION, PUZZLE_A)).toBe(0);
    });
  });
});
