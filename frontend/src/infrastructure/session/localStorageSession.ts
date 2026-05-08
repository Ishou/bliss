// Anonymous-player identity primitive (per ADR-0018 §6 "Anonymous players").
//
// Each browser persists two values in `localStorage`:
//   - `bliss.session.id`        — a UUID v7 generated on first visit; sent
//                                  on every WebSocket frame so the server
//                                  can reattach a reconnecting client to
//                                  its slot inside the 30 s window.
//   - `bliss.session.pseudonym` — a freely editable display name; default
//                                  is a random French animal name +
//                                  3-digit suffix (e.g. `Renard 423`).
//
// This module is the *infrastructure* adapter for that storage. The
// hexagonal-layering rule (CLAUDE.md "Architecture") forbids domain or
// application code from touching `localStorage` directly; UI code will
// read these values via this module from a TanStack Router loader (or a
// component) when the lobby flow lands.
//
// `localStorage` can be unavailable: Safari Private Mode throws on
// `setItem`, server-side rendering has no `window`, and some browsers
// block storage in third-party iframes. To stay non-fatal, every read /
// write is wrapped in try/catch and falls back to a module-level Map
// that lives for the page lifetime — the player still gets a stable
// identity within the tab, just not across reloads.

import { uuidv7 } from 'uuidv7';

const SESSION_ID_KEY = 'bliss.session.id';
const PSEUDONYM_KEY = 'bliss.session.pseudonym';

const PSEUDONYM_MIN_LENGTH = 1;
const PSEUDONYM_MAX_LENGTH = 32;

// RFC 9562 UUID (any version). The wire schema specifies `format: uuid`,
// so a stored value that happens to be UUID v4 (older client, manual
// edit) is still acceptable — we only regenerate on absent/malformed.
const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const memoryFallback = new Map<string, string>();

function readKey(key: string): string | null {
  try {
    const value = globalThis.localStorage?.getItem(key);
    if (value !== null && value !== undefined) return value;
  } catch {
    // localStorage threw (private mode, disabled, sandboxed). Fall
    // through to the in-memory fallback so the page lifetime is still
    // coherent.
  }
  return memoryFallback.get(key) ?? null;
}

function writeKey(key: string, value: string): void {
  memoryFallback.set(key, value);
  try {
    globalThis.localStorage?.setItem(key, value);
  } catch {
    // Persisted only in memory for this page lifetime.
  }
}

function removeKey(key: string): void {
  memoryFallback.delete(key);
  try {
    globalThis.localStorage?.removeItem(key);
  } catch {
    // No-op: in-memory state is already cleared.
  }
}

// French animal names — all selected so that adjective-free agreement
// is a non-issue and pronunciation reads naturally to a French speaker.
// Order is irrelevant; uniqueness matters because the per-render pick
// is uniform random.
const ANIMAL_NAMES = [
  'Renard', 'Hibou', 'Loup', 'Lynx', 'Faucon', 'Cerf', 'Lapin',
  'Écureuil', 'Castor', 'Blaireau', 'Hérisson', 'Sanglier',
  'Aigle', 'Corbeau', 'Marmotte', 'Élan', 'Chevreuil', 'Goéland',
  'Guépard', 'Panda', 'Koala', 'Tigre', 'Phoque', 'Otarie',
] as const;

function generateDefaultPseudonym(): string {
  const animal = ANIMAL_NAMES[Math.floor(Math.random() * ANIMAL_NAMES.length)];
  // 100–999 keeps total length comfortably under the 32-char Pseudonym
  // ceiling for every entry in ANIMAL_NAMES while giving enough spread
  // (≈900 × 24 = ~21k combinations) that two anonymous players in the
  // same lobby almost never collide on the default.
  const suffix = 100 + Math.floor(Math.random() * 900);
  return `${animal} ${suffix}`;
}

/**
 * Returns the persisted session ID, or generates and persists a fresh
 * UUID v7 on first call (or when the stored value is malformed).
 * Idempotent: subsequent calls return the same value.
 */
export function getOrCreateSessionId(): string {
  const existing = readKey(SESSION_ID_KEY);
  if (existing && UUID_REGEX.test(existing)) return existing;
  const fresh = uuidv7();
  writeKey(SESSION_ID_KEY, fresh);
  return fresh;
}

/**
 * Returns the persisted pseudonym, or generates and persists a default
 * `<AnimalName> ${random3digits}` (e.g. `Renard 423`) on first call.
 */
export function getPseudonym(): string {
  const existing = readKey(PSEUDONYM_KEY);
  if (existing !== null && existing.length > 0) return existing;
  const fresh = generateDefaultPseudonym();
  writeKey(PSEUDONYM_KEY, fresh);
  return fresh;
}

/**
 * Trims, validates (1..32 chars after trim), persists, and returns the
 * trimmed pseudonym. Throws on invalid input — callers (a future React
 * form) should validate the same constraints before submitting and
 * treat a thrown error as a programmer mistake rather than a UX flow.
 */
export function setPseudonym(name: string): string {
  const trimmed = name.trim();
  if (trimmed.length < PSEUDONYM_MIN_LENGTH) {
    throw new Error('Pseudonym cannot be empty.');
  }
  if (trimmed.length > PSEUDONYM_MAX_LENGTH) {
    throw new Error(
      `Pseudonym cannot exceed ${PSEUDONYM_MAX_LENGTH} characters.`,
    );
  }
  writeKey(PSEUDONYM_KEY, trimmed);
  return trimmed;
}

/**
 * Removes both keys from `localStorage` (and the in-memory fallback).
 * Used by the future "leave & forget" flow.
 */
export function clearSession(): void {
  removeKey(SESSION_ID_KEY);
  removeKey(PSEUDONYM_KEY);
}
