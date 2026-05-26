// Anon dedup store for the /sondage route. Anonymous raters track the
// item_ids they have already rated locally and pass them to the next-item
// endpoint as `excluded` so the server can hand back something fresh.
// Capped at 500 entries (FIFO) to keep localStorage small; clearing it
// permits re-rating from the same browser — accepted trade-off per
// spec §10.1 (anon ratings are anonymous from inception so there is no
// stable identifier to defeat).

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
    // localStorage may be unavailable (Safari private mode, quota
    // exhausted). Swallow — anon dedup is best-effort.
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
