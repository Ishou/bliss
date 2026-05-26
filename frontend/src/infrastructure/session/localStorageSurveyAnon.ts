// localStorage adapter for the SurveyAnonStore port — FIFO, capped at 500 entries.

const KEY = 'survey.anon.rated_ids';
const MAX_ITEMS = 500;

export interface SurveyAnonRatedStore {
  list(): ReadonlyArray<string>;
  add(itemId: string): void;
  clear(): void;
}

function readSafe(): string[] {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((x): x is string => typeof x === 'string');
  } catch {
    return [];
  }
}

function writeSafe(list: readonly string[]): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    // localStorage unavailable (Safari private mode, quota exhausted) — best-effort.
  }
}

export const surveyAnonRatedStore: SurveyAnonRatedStore = {
  list(): ReadonlyArray<string> {
    return readSafe();
  },
  add(itemId: string): void {
    const current = readSafe();
    if (current.includes(itemId)) return;
    const next = [...current, itemId];
    while (next.length > MAX_ITEMS) next.shift();
    writeSafe(next);
  },
  clear(): void {
    try {
      localStorage.removeItem(KEY);
    } catch {
      // ignore
    }
  },
};

export const SURVEY_ANON_RATED_STORE_KEY = KEY;
export const SURVEY_ANON_RATED_STORE_MAX = MAX_ITEMS;
