// Per-session colour utility. Derives a stable hue (0–360) by hashing
// the SessionId so each player has a distinct colour across reloads and
// across peers (no server round-trip). Used by:
//   - the grid's `PresenceOverlay` (cursor rings, word tint, chip dot)
//   - `PlayerList` (per-row left-border accent)
//   - the future per-author cell-tint feature (out of scope here).
//
// ADR-0018 §"Presence" promises stable distinct colours; this is the
// single derivation.
//
// On the charbon palette the player hues paint at 65 % lightness so
// they read clearly against the dark surfaces. The text-on-chip
// variant blends toward `fg` (off-white) for AA contrast — the
// previous cream-page blend toward deep-navy ink would land near-
// black on a dark background and fail readability badly.

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
  // FNV-1a 32-bit. `Math.imul` keeps the multiplication in int32
  // territory so the hash stays a positive 32-bit value when xored
  // back into `>>> 0` at the end.
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  return (hash >>> 0) % 360;
}

// CSS custom-property bundle for a single session. Apply via inline
// `style={...}` at the call site so the variables are scoped to that
// element (no global leakage across players). Consumers reference the
// vars from regular Panda CSS classes via `var(--player-color)` etc.
//
// `--player-hue`        — raw hue 0–360, used by callers that want to
//                         reconstruct their own hsl/color-mix expression.
// `--player-color`      — opaque accent hsl, suitable for borders /
//                         chip backgrounds (NOT for foreground text on
//                         cream — see `--player-text-color`).
// `--player-color-soft` — translucent accent (~18% alpha), suitable for
//                         word-tint backgrounds inside the overlay.
//                         Stacks well via `mix-blend-mode: multiply`.
// `--player-text-color` — accent blended toward `ink` for ≥ 4.5:1
//                         contrast against the cream page background.
//                         Used by the pseudonym chip label.
//
// Returns a `Record<string, string>` because the CSSStyleDeclaration
// typings reject custom properties that start with `--`; React passes
// the bag through unchanged at the JSX call site.
export function playerColorVars(sessionId: SessionId): Record<string, string> {
  const hue = sessionIdToHue(sessionId);
  return {
    '--player-hue': String(hue),
    // Saturated bright hsl — the "brand" colour for the player.
    // 70 % saturation reads as vibrant without being neon; 65 %
    // lightness pops on the charbon palette without washing out
    // (the prior 45 % was tuned for the cream page and looked
    // muddy on the new dark surfaces).
    '--player-color': `hsl(${hue} 70% 65%)`,
    // Same hue, low alpha for word-tint backgrounds.
    '--player-color-soft': `hsla(${hue}, 70%, 65%, 0.18)`,
    // Foreground text on the chip. Blend the raw hue toward `fg`
    // (off-white #E8E8EB) so the resulting colour reads clearly
    // against `surfaceElevated` / `surface` charcoal backgrounds.
    // 80 % hue / 20 % fg keeps the colour identity while lifting the
    // luminance enough to clear AA at body text size (~5:1+ in
    // measured worst-case hues).
    '--player-text-color': `color-mix(in srgb, hsl(${hue} 70% 65%) 80%, #E8E8EB)`,
  };
}
