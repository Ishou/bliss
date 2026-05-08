// Per-session colour utility. Derives a stable hue (0–360) by hashing
// the SessionId so each player has a distinct colour across reloads and
// across peers (no server round-trip). Used by:
//   - the grid's `Cell` (active ring, word tint, badge)
//   - `PresenceOverlay` (state computer wires the vars onto cells)
//   - `PlayerList` (per-row pill accent + avatar dot)
//
// ADR-0018 §"Presence" promises stable distinct colours; this is the
// single derivation.
//
// The four returned tones are the recipe extracted from the rose / blue /
// amber / violet reference palette: ring + on-color sit at higher
// chroma+lightness, while active-bg and word-bg drop to the same near-
// black tone-on-tone the design uses to prevent the grid from looking
// like a dashboard.

import type { SessionId } from '@/domain/game';

// FNV-1a 32-bit hash over the entire SessionId, mapped into 360 hues.
//
// Earlier revisions read the first 4 hex chars and ran them through
// `parseInt(prefix, 16) % 360`. That collapsed two UUID-v7 ids created
// within the same ~50-day window to identical hues — UUID v7's leading
// 48 bits are a Unix-millisecond timestamp, and the top 16 bits change
// once per ~2^32 ms. Two players joining the same lobby hit the same
// prefix, the same hue, and the "distinctive per-player colour" promise
// from ADR-0018 §"Presence" silently broke.
//
// FNV-1a folds every char into the hash, so the trailing random bits
// of UUID v7 (versions, variant, 62 random) participate. Cheap (linear
// in id length, no BigInt), pure, and deterministic across reloads.
//
// Falls back to 0 on an empty input — keeps the function total.
export function sessionIdToHue(sessionId: SessionId): number {
  const text = String(sessionId);
  if (text.length === 0) return 0;
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0) % 360;
}

// Recipe constants. Reverse-engineered from the four reference colours
// (rose #e8a3b3, blue #8ab4d8, amber #d4b878, violet #b89cd0) by averaging
// the saturation/lightness of each tone family. Documented here so the
// numbers don't drift on a future refactor.
const RECIPE = {
  color: { s: 50, l: 72 }, // ring stroke, badge fill, roster avatar
  activeBg: { s: 22, l: 14 }, // active-cell background under the ring
  wordBg: { s: 15, l: 14 }, // word-tint background for non-active cells
  on: { s: 48, l: 16 }, // text colour on player backgrounds (badges)
} as const;

// CSS custom-property bundle for a single session. Apply via inline
// `style={...}` at the call site so the variables are scoped to that
// element (no global leakage across players).
//
// `--player-color`     — accent stroke / fill (badge, roster avatar, ring)
// `--player-active-bg` — background of the active cell (under the ring)
// `--player-word-bg`   — background of the other cells in the active word
// `--player-on`        — text on `--player-color` backgrounds (badges)
//
// Returns a `Record<string, string>` because the CSSStyleDeclaration
// typings reject custom properties that start with `--`; React passes
// the bag through unchanged at the JSX call site.
export function playerColorVars(sessionId: SessionId): Record<string, string> {
  const hue = sessionIdToHue(sessionId);
  return {
    '--player-color': `hsl(${hue} ${RECIPE.color.s}% ${RECIPE.color.l}%)`,
    '--player-active-bg': `hsl(${hue} ${RECIPE.activeBg.s}% ${RECIPE.activeBg.l}%)`,
    '--player-word-bg': `hsl(${hue} ${RECIPE.wordBg.s}% ${RECIPE.wordBg.l}%)`,
    '--player-on': `hsl(${hue} ${RECIPE.on.s}% ${RECIPE.on.l}%)`,
  };
}

// Single-uppercase initial for badges and roster avatars. Strips
// diacritics so "Élodie" → "E" and "Łukasz" → "L". Empty input returns
// "?" so the badge always has something to render.
export function playerInitial(pseudonym: string): string {
  const trimmed = pseudonym.trim();
  if (trimmed.length === 0) return '?';
  const stripped = trimmed.normalize('NFD').replace(/\p{M}/gu, '');
  const first = stripped.charAt(0);
  return first.toUpperCase();
}
