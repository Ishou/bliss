// Per-puzzle solo-mode letter persistence (v1).
//
// Stores the player's typed letters in `localStorage` keyed by puzzle id
// so a page reload restores them. `LoadOrGeneratePuzzleUseCase` caches
// generated puzzles by id (via `PuzzleRepository.getOrCompute`), so the
// same id resolves to the same grid layout — saved entries always line
// up with the live cells. The "Actualiser" CTA clears the entries for
// the current puzzle, giving the player a fresh start without leaving
// stale rows behind in storage.
//
// Wire shape: a single `bliss.solo.entries` JSON object that maps each
// puzzle id to a flat list of `{r, c, l}` triples. One key keeps the
// GDPR erase flow trivial (drop one localStorage key) and avoids the
// per-puzzle keys accumulating without bound; all entries clear at
// once when the player taps "Effacer mes données".
//
// Storage failures (private mode, disabled, sandboxed iframe) are
// non-fatal — every helper degrades to a no-op rather than throw.

const SOLO_ENTRIES_KEY = 'bliss.solo.entries';

export interface PersistedSoloEntry {
  readonly row: number;
  readonly column: number;
  readonly letter: string;
}

interface StoredEntry {
  r: number;
  c: number;
  l: string;
}

type SoloStore = Record<string, StoredEntry[]>;

function readStore(): SoloStore {
  try {
    const raw = globalThis.localStorage?.getItem(SOLO_ENTRIES_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as SoloStore;
    }
  } catch {
    // localStorage threw or JSON was malformed — treat as empty store.
  }
  return {};
}

function writeStore(store: SoloStore): void {
  try {
    globalThis.localStorage?.setItem(
      SOLO_ENTRIES_KEY,
      JSON.stringify(store),
    );
  } catch {
    // Private mode / quota exhausted / sandboxed: drop silently.
  }
}

/** Load the persisted entries for a puzzle, or `[]` if none. */
export function loadSoloEntries(puzzleId: string): PersistedSoloEntry[] {
  const store = readStore();
  const entries = store[puzzleId];
  if (!entries || !Array.isArray(entries)) return [];
  return entries
    .filter((e): e is StoredEntry =>
      typeof e?.r === 'number' &&
      typeof e?.c === 'number' &&
      typeof e?.l === 'string' &&
      e.l.length > 0,
    )
    .map((e) => ({ row: e.r, column: e.c, letter: e.l }));
}

/**
 * Upsert a single cell. Passing `null` (or empty) for `letter` removes
 * the entry. Mirrors the Grid `onCellChange` callback shape so the
 * route can wire it directly.
 */
export function saveSoloLetter(
  puzzleId: string,
  row: number,
  column: number,
  letter: string | null,
): void {
  const store = readStore();
  const list = store[puzzleId] ?? [];
  const next = list.filter((e) => !(e.r === row && e.c === column));
  if (letter && letter.length > 0) {
    next.push({ r: row, c: column, l: letter });
  }
  if (next.length === 0) {
    delete store[puzzleId];
  } else {
    store[puzzleId] = next;
  }
  writeStore(store);
}

/** Drop every entry for one puzzle (the "Actualiser" reset path). */
export function clearSoloEntriesForPuzzle(puzzleId: string): void {
  const store = readStore();
  if (!(puzzleId in store)) return;
  delete store[puzzleId];
  writeStore(store);
}

/** Drop every entry across every puzzle (GDPR erase flow). */
export function clearAllSoloEntries(): void {
  try {
    globalThis.localStorage?.removeItem(SOLO_ENTRIES_KEY);
  } catch {
    // No-op.
  }
}
