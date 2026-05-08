// Normalize a single keystroke into the canonical wire form: uppercase
// A–Z, accents stripped. The validate endpoint expects one base letter
// per cell; cleared cells must be omitted entirely (do NOT send
// `letter: null`). Returns `null` when the input does not collapse to a
// single A–Z letter so callers can drop the cell from the request body.
//
// Behavior matches the previous client-side validator
// (`useValidation.normalize`): NFD splits each accented glyph into a
// base letter + combining marks, then the combining-mark range
// (U+0300–U+036F) is stripped. This covers the full French diacritic
// set (acute, grave, circumflex, diaeresis, cedilla, tilde) without
// per-character casing.
export function normalizeAnswerLetter(letter: string): string | null {
  const normalized = letter
    .trim()
    .toUpperCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '');
  return /^[A-Z]$/.test(normalized) ? normalized : null;
}
