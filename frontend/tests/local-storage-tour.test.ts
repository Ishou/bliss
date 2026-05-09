import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import {
  clearTourSeen,
  getTourSeen,
  setTourSeen,
} from '@/infrastructure/session/localStorageTour';

const KEY = 'wordsparrow.tour.seen';

describe('localStorageTour', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    window.localStorage.clear();
  });

  it('returns false when no flag has been written', () => {
    expect(getTourSeen()).toBe(false);
  });

  it('persists true under the wordsparrow.tour.seen key', () => {
    setTourSeen(true);
    expect(window.localStorage.getItem(KEY)).toBe('true');
    expect(getTourSeen()).toBe(true);
  });

  it('clears the flag with setTourSeen(false)', () => {
    setTourSeen(true);
    setTourSeen(false);
    expect(window.localStorage.getItem(KEY)).toBeNull();
    expect(getTourSeen()).toBe(false);
  });

  it('clearTourSeen drops the flag entirely', () => {
    setTourSeen(true);
    clearTourSeen();
    expect(window.localStorage.getItem(KEY)).toBeNull();
    expect(getTourSeen()).toBe(false);
  });

  it('reads only the literal string "true" as truthy', () => {
    window.localStorage.setItem(KEY, 'TRUE');
    expect(getTourSeen()).toBe(false);
    window.localStorage.setItem(KEY, '1');
    expect(getTourSeen()).toBe(false);
  });
});
