// Single source of truth for the lobby join-code shape on the FE
// (ADR-0027). Mirrors the OpenAPI schema's `code` pattern + the
// Kotlin `LobbyCode` regex; keep all three in sync if the alphabet
// ever changes.
//
// Crockford-style — uppercase A-Z + digits with the ambiguous chars
// `0`/`O`, `1`/`I`/`L` removed so a player reading a code aloud
// cannot land on a different lobby. The frontend filters typed /
// pasted input through `normaliseLobbyCode` before it reaches the
// `findByCode` adapter, so a server validation is purely defensive.

/** Six-char Crockford-style join code. */
export const LOBBY_CODE_PATTERN = /^[A-HJKM-NP-Z2-9]{6}$/;

/** Single character permitted inside the code. Used by the input filter. */
export const LOBBY_CODE_ALLOWED_CHAR = /[A-HJKM-NP-Z2-9]/;

export const LOBBY_CODE_LENGTH = 6;

/**
 * Uppercases, drops chars outside the Crockford alphabet, and caps at
 * six chars. Idempotent on already-canonical input. Use this in any
 * surface that accepts user-supplied codes (Accueil PIN input,
 * `/join/$code` route param, etc.) so the canonical shape lives in one
 * place.
 */
export function normaliseLobbyCode(raw: string): string {
  return Array.from(raw.toUpperCase())
    .filter((ch) => LOBBY_CODE_ALLOWED_CHAR.test(ch))
    .slice(0, LOBBY_CODE_LENGTH)
    .join('');
}

/**
 * Extract a lobby code from any of the share-link shapes a user might
 * paste into a "code" input — convenient because owners tend to copy
 * the full `https://wordsparrow.io/join/A2B3C4` URL, not the bare
 * code, and the input should "just work" for both.
 *
 * Falls through to {@link normaliseLobbyCode} when no `/join/` segment
 * is present, so plain-code input keeps its existing behaviour.
 *
 * Recognised shapes:
 *  - `https://wordsparrow.io/join/A2B3C4` (full URL)
 *  - `/join/A2B3C4` (path)
 *  - `wordsparrow.io/join/A2B3C4` (no scheme)
 *  - `A2B3C4` (bare code — falls through)
 *  - garbage text — filtered through {@link normaliseLobbyCode}
 */
export function extractLobbyCode(raw: string): string {
  // The path segment after the literal `/join/` — case-insensitive on
  // the marker so a pasted `/JOIN/abc123` still works. The captured
  // group passes through `normaliseLobbyCode` so casing and the
  // 6-char cap stay canonical.
  const match = /\/join\/([A-Za-z0-9]+)/i.exec(raw);
  if (match != null) return normaliseLobbyCode(match[1]);
  return normaliseLobbyCode(raw);
}
