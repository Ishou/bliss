// Tour-completion flag for the solo onboarding flow.
//
// Persists a single boolean under `wordsparrow.tour.seen`. Mirrors the
// try/catch + in-memory-fallback pattern used by `localStorageSession`
// so Safari Private Mode, SSR, and sandboxed iframes don't crash the
// route — the user just gets a tour that re-opens on every reload, not
// a broken page.
//
// New keys use the public `wordsparrow.*` namespace; existing
// `bliss.session.*` / `bliss.solo.*` keys stay as they are (renaming
// would invalidate every player's stored state with no upside).

const TOUR_SEEN_KEY = 'wordsparrow.tour.seen';

const memoryFallback = new Map<string, string>();

function readKey(key: string): string | null {
  try {
    const value = globalThis.localStorage?.getItem(key);
    if (value !== null && value !== undefined) return value;
  } catch {
    // Fall through to the in-memory fallback.
  }
  return memoryFallback.get(key) ?? null;
}

function writeKey(key: string, value: string): void {
  memoryFallback.set(key, value);
  try {
    globalThis.localStorage?.setItem(key, value);
  } catch {
    // In-memory only for this page lifetime.
  }
}

function removeKey(key: string): void {
  memoryFallback.delete(key);
  try {
    globalThis.localStorage?.removeItem(key);
  } catch {
    // No-op.
  }
}

export function getTourSeen(): boolean {
  return readKey(TOUR_SEEN_KEY) === 'true';
}

export function setTourSeen(seen: boolean): void {
  if (seen) writeKey(TOUR_SEEN_KEY, 'true');
  else removeKey(TOUR_SEEN_KEY);
}

export function clearTourSeen(): void {
  removeKey(TOUR_SEEN_KEY);
}
