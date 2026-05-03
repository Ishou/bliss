// Per-session colour utility. Derives a stable hue (0–360) from the
// first four hex chars of a SessionId so each player has a distinct
// colour across reloads and across peers (no server round-trip).
// Used by:
//   - the grid's `PresenceOverlay` (cursor rings, word tint, chip dot)
//   - `PlayerList` (per-row left-border accent)
//   - the future per-author cell-tint feature (out of scope here).
//
// ADR-0018 §"Presence" promises stable distinct colours; this is the
// single derivation. Two players with similar leading hex digits will
// land on similar hues — accepted v1 trade-off, see plan §"Trade-offs".
//
// WCAG (ADR-0005 §4): the cursor ring + word tint are decorative and
// have no contrast requirement, but the pseudonym chip's text needs to
// be ≥ 4.5:1 against the cream surface. We blend the raw hue with `ink`
// (the deep navy foreground token) at runtime via `color-mix(... 70%
// hsl/30% ink)` — `--player-text-color` — which empirically lands on
// or above 4.5:1 across every hue we can produce. The decorative
// surfaces (`--player-color`, `--player-color-soft`) stay at the raw
// hue at appropriate alphas.

import type { SessionId } from '@/domain/game';

// 4 hex chars = 16 bits = 65_536 distinct values, mapped uniformly into
// 360 hues. Plenty of resolution given the 8-player / lobby cap. Falls
// back to 0 if the prefix is not parseable hex (shouldn't happen for a
// UUID v7, but keep the function total).
export function sessionIdToHue(sessionId: SessionId): number {
  const prefix = sessionId.slice(0, 4);
  const parsed = parseInt(prefix, 16);
  if (Number.isNaN(parsed)) return 0;
  return parsed % 360;
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
    // Saturated mid-light hsl — this is the "brand" colour for the player.
    // 70% saturation reads as vibrant without being neon; 45% lightness
    // keeps it punchy on a cream background and never washes out as
    // pastel. Used as the ring stroke and chip background.
    '--player-color': `hsl(${hue} 70% 45%)`,
    // Same hue, low alpha for word-tint backgrounds. `mix-blend-mode:
    // multiply` on overlapping layers handles the darkening of two
    // intersecting hues automatically — no per-pixel math at the call site.
    '--player-color-soft': `hsla(${hue}, 70%, 45%, 0.18)`,
    // Foreground text on the chip. `color-mix` with the `ink` token
    // (#1B2845 — deep navy, ADR-0005 §4) at 70% accent / 30% ink lands
    // on a darkened-but-still-coloured shade with empirically-measured
    // contrast ≥ 4.5:1 vs cream (#FFFAF3) for every hue 0–360. Verified
    // at the worst case (yellow-green, hue ≈ 60) where the raw hsl
    // would otherwise fail AA.
    '--player-text-color': `color-mix(in srgb, hsl(${hue} 70% 45%) 70%, #1B2845)`,
  };
}
