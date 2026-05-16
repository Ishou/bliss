import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  clearAllSoloEntriesForSession,
  clearSoloEntriesForPuzzle,
  loadSoloEntries,
  loadSoloHintsUsed,
  recordSoloHintUsed,
  saveSoloLetter,
  __resetLegacyMigrationFlagForTests,
} from '@/infrastructure/session/localStorageSolo';

const SESSION = '01234567-89ab-7000-8000-000000000000';
const KEY = `bliss.solo.entries.${SESSION}`;
const PUZZLE_A = '0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b';
const PUZZLE_B = '0190e3a4-7a2c-7c9e-8f1a-aaaaaaaaaaaa';

describe('localStorageSolo', () => {
  beforeEach(() => {
    window.localStorage.clear();
    __resetLegacyMigrationFlagForTests();
  });

  afterEach(() => {
    window.localStorage.clear();
  });

  describe('loadSoloEntries', () => {
    it('returns an empty array when nothing has been stored', () => {
      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
    });

    it('returns previously saved entries for the puzzle', () => {
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_A, 1, 2, 'B');

      const entries = loadSoloEntries(SESSION, PUZZLE_A);

      expect(entries).toEqual([
        { row: 0, column: 0, letter: 'A' },
        { row: 1, column: 2, letter: 'B' },
      ]);
    });

    it('isolates entries between puzzles', () => {
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_B, 1, 1, 'Z');

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([
        { row: 0, column: 0, letter: 'A' },
      ]);
      expect(loadSoloEntries(SESSION, PUZZLE_B)).toEqual([
        { row: 1, column: 1, letter: 'Z' },
      ]);
    });

    it('returns an empty array on a malformed store', () => {
      window.localStorage.setItem(KEY, '{not json');
      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
    });

    it('skips entries that fail the shape check', () => {
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
    it('upserts existing positions instead of duplicating them', () => {
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'B');

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([
        { row: 0, column: 0, letter: 'B' },
      ]);
    });

    it('removes the entry when letter is null', () => {
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, null);

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
    });

    it('removes the entry when letter is the empty string', () => {
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, '');

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
    });

    it('drops the puzzle key entirely once its last entry is removed', () => {
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, null);

      const stored = JSON.parse(
        window.localStorage.getItem(KEY) ?? '{}',
      ) as Record<string, unknown>;
      expect(stored[PUZZLE_A]).toBeUndefined();
    });
  });

  describe('clearSoloEntriesForPuzzle', () => {
    it('drops only the targeted puzzle', () => {
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_B, 1, 1, 'Z');

      clearSoloEntriesForPuzzle(SESSION, PUZZLE_A);

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
      expect(loadSoloEntries(SESSION, PUZZLE_B)).toEqual([
        { row: 1, column: 1, letter: 'Z' },
      ]);
    });

    it('is a no-op when the puzzle has no entries', () => {
      expect(() => clearSoloEntriesForPuzzle(SESSION, PUZZLE_A)).not.toThrow();
      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
    });
  });

  describe('clearAllSoloEntriesForSession', () => {
    it('drops every entry across every puzzle for the session', () => {
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      saveSoloLetter(SESSION, PUZZLE_B, 1, 1, 'Z');

      clearAllSoloEntriesForSession(SESSION);

      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([]);
      expect(loadSoloEntries(SESSION, PUZZLE_B)).toEqual([]);
      expect(window.localStorage.getItem(KEY)).toBeNull();
    });
  });

  describe('hintsUsed persistence', () => {
    it('returns 0 when nothing has been recorded', () => {
      expect(loadSoloHintsUsed(SESSION, PUZZLE_A)).toBe(0);
    });

    it('increments and survives a fresh read (page reload analog)', () => {
      recordSoloHintUsed(SESSION, PUZZLE_A);
      recordSoloHintUsed(SESSION, PUZZLE_A);
      expect(loadSoloHintsUsed(SESSION, PUZZLE_A)).toBe(2);
    });

    it('preserves entries and lockedCells when incremented', () => {
      saveSoloLetter(SESSION, PUZZLE_A, 0, 0, 'A');
      recordSoloHintUsed(SESSION, PUZZLE_A);
      expect(loadSoloEntries(SESSION, PUZZLE_A)).toEqual([{ row: 0, column: 0, letter: 'A' }]);
      expect(loadSoloHintsUsed(SESSION, PUZZLE_A)).toBe(1);
    });

    it('isolates hintsUsed between puzzles', () => {
      recordSoloHintUsed(SESSION, PUZZLE_A);
      expect(loadSoloHintsUsed(SESSION, PUZZLE_B)).toBe(0);
    });

    it('clearForPuzzle resets the hintsUsed counter', () => {
      recordSoloHintUsed(SESSION, PUZZLE_A);
      clearSoloEntriesForPuzzle(SESSION, PUZZLE_A);
      expect(loadSoloHintsUsed(SESSION, PUZZLE_A)).toBe(0);
    });
  });
});
