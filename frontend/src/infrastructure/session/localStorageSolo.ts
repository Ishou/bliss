// storage failures are non-fatal — every helper degrades to a no-op rather than throw.

import type { SoloEntry } from '@/application/solo/SoloEntriesStore';

const SOLO_ENTRIES_KEY = 'bliss.solo.entries';

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
export function loadSoloEntries(puzzleId: string): SoloEntry[] {
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

// null or '' for letter removes the entry.
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
