import { beforeEach, describe, expect, it } from 'vitest';

import {
  SURVEY_ANON_RATED_STORE_KEY,
  surveyAnonRatedStore,
} from '@/infrastructure';

beforeEach(() => {
  localStorage.clear();
});

describe('surveyAnonRatedStore', () => {
  it('returns an empty list when the key is unset', () => {
    expect(surveyAnonRatedStore.list()).toEqual([]);
  });

  it('returns an empty list when the stored value is malformed', () => {
    localStorage.setItem(SURVEY_ANON_RATED_STORE_KEY, 'not-json');
    expect(surveyAnonRatedStore.list()).toEqual([]);
  });

  it('ignores non-string entries in the persisted array', () => {
    localStorage.setItem(SURVEY_ANON_RATED_STORE_KEY, JSON.stringify(['ok', 1, null, 'fine']));
    expect(surveyAnonRatedStore.list()).toEqual(['ok', 'fine']);
  });

  it('add() appends a new id and persists', () => {
    surveyAnonRatedStore.add('a');
    surveyAnonRatedStore.add('b');
    expect(surveyAnonRatedStore.list()).toEqual(['a', 'b']);
    expect(JSON.parse(localStorage.getItem(SURVEY_ANON_RATED_STORE_KEY) ?? '[]')).toEqual([
      'a',
      'b',
    ]);
  });

  it('add() is idempotent for the same id', () => {
    surveyAnonRatedStore.add('a');
    surveyAnonRatedStore.add('a');
    expect(surveyAnonRatedStore.list()).toEqual(['a']);
  });

  it('caps the list at MAX_ITEMS by FIFO drop', () => {
    // Force a small seed past the cap; we don't expose MAX so probe by
    // adding 600 ids and asserting the head was evicted while the tail
    // is preserved.
    for (let i = 0; i < 600; i++) {
      surveyAnonRatedStore.add(`id-${i}`);
    }
    const list = surveyAnonRatedStore.list();
    expect(list).toHaveLength(500);
    expect(list[0]).toBe('id-100');
    expect(list[list.length - 1]).toBe('id-599');
  });

  it('clear() removes the key', () => {
    surveyAnonRatedStore.add('a');
    surveyAnonRatedStore.clear();
    expect(localStorage.getItem(SURVEY_ANON_RATED_STORE_KEY)).toBeNull();
    expect(surveyAnonRatedStore.list()).toEqual([]);
  });
});
