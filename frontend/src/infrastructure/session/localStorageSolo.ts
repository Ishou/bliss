// storage failures are non-fatal — every helper degrades to a no-op rather than throw.

import type { SoloEntry, SoloLockedCell } from '@/application/solo/SoloEntriesStore';

const SOLO_ENTRIES_KEY = 'bliss.solo.entries';

interface StoredEntry {
  r: number;
  c: number;
  l: string;
}

interface StoredLock {
  r: number;
  c: number;
}

interface StoredPuzzle {
  entries: StoredEntry[];
  lockedCells?: StoredLock[];
  hintsUsed?: number;
}

// Per-puzzle bucket. Legacy shape (PR #242) was `StoredEntry[]` — kept on
// read for transparent migration; on write we always emit the object form.
type StoredPuzzleBucket = StoredEntry[] | StoredPuzzle;
type SoloStore = Record<string, StoredPuzzleBucket>;

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

function readBucket(store: SoloStore, puzzleId: string): StoredPuzzle {
  const raw = store[puzzleId];
  if (!raw) return { entries: [], lockedCells: [], hintsUsed: 0 };
  if (Array.isArray(raw)) return { entries: raw, lockedCells: [], hintsUsed: 0 };
  return {
    entries: raw.entries ?? [],
    lockedCells: raw.lockedCells ?? [],
    hintsUsed: raw.hintsUsed ?? 0,
  };
}

function persistBucket(
  store: SoloStore,
  puzzleId: string,
  bucket: StoredPuzzle,
): void {
  if (
    bucket.entries.length === 0 &&
    (bucket.lockedCells ?? []).length === 0 &&
    (bucket.hintsUsed ?? 0) === 0
  ) {
    delete store[puzzleId];
  } else {
    store[puzzleId] = bucket;
  }
}

/** Load the persisted entries for a puzzle, or `[]` if none. */
export function loadSoloEntries(puzzleId: string): SoloEntry[] {
  const store = readStore();
  const bucket = readBucket(store, puzzleId);
  return bucket.entries
    .filter((e): e is StoredEntry =>
      typeof e?.r === 'number' &&
      typeof e?.c === 'number' &&
      typeof e?.l === 'string' &&
      e.l.length > 0,
    )
    .map((e) => ({ row: e.r, column: e.c, letter: e.l }));
}

// null or '' for letter removes the entry.
export function saveSoloLetter(
  puzzleId: string,
  row: number,
  column: number,
  letter: string | null,
): void {
  const store = readStore();
  const bucket = readBucket(store, puzzleId);
  const next = bucket.entries.filter((e) => !(e.r === row && e.c === column));
  if (letter && letter.length > 0) {
    next.push({ r: row, c: column, l: letter });
  }
  persistBucket(store, puzzleId, {
    entries: next,
    lockedCells: bucket.lockedCells,
    hintsUsed: bucket.hintsUsed,
  });
  writeStore(store);
}

/** Load the locked-cell coordinates for a puzzle, or `[]` if none. */
export function loadSoloLockedCells(puzzleId: string): SoloLockedCell[] {
  const store = readStore();
  const bucket = readBucket(store, puzzleId);
  return (bucket.lockedCells ?? [])
    .filter(
      (e): e is StoredLock => typeof e?.r === 'number' && typeof e?.c === 'number',
    )
    .map((e) => ({ row: e.r, column: e.c }));
}

export function saveSoloLockedCell(
  puzzleId: string,
  row: number,
  column: number,
): void {
  const store = readStore();
  const bucket = readBucket(store, puzzleId);
  const existing = bucket.lockedCells ?? [];
  if (existing.some((e) => e.r === row && e.c === column)) return;
  persistBucket(store, puzzleId, {
    entries: bucket.entries,
    lockedCells: [...existing, { r: row, c: column }],
    hintsUsed: bucket.hintsUsed,
  });
  writeStore(store);
}

export function loadSoloHintsUsed(puzzleId: string): number {
  const store = readStore();
  const bucket = readBucket(store, puzzleId);
  return typeof bucket.hintsUsed === 'number' && bucket.hintsUsed >= 0
    ? bucket.hintsUsed
    : 0;
}

export function recordSoloHintUsed(puzzleId: string): void {
  const store = readStore();
  const bucket = readBucket(store, puzzleId);
  persistBucket(store, puzzleId, {
    entries: bucket.entries,
    lockedCells: bucket.lockedCells,
    hintsUsed: (bucket.hintsUsed ?? 0) + 1,
  });
  writeStore(store);
}

export function clearSoloEntriesForPuzzle(puzzleId: string): void {
  const store = readStore();
  if (!(puzzleId in store)) return;
  delete store[puzzleId];
  writeStore(store);
}

export function clearAllSoloEntries(): void {
  try {
    globalThis.localStorage?.removeItem(SOLO_ENTRIES_KEY);
  } catch {
    // No-op.
  }
}
