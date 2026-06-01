// ADR-0061: soft-normalize for autocomplete/dedup matching only; storage keeps the verbatim string.

const DETERMINER_PREFIX = /^(l['’]|la |le |les )\s*/i;
const COMBINING_MARKS = /[̀-ͯ]/g;

export function normalizeForMatch(s: string): string {
  return s
    .replace(DETERMINER_PREFIX, '')
    .normalize('NFD')
    .replace(COMBINING_MARKS, '')
    .toLowerCase()
    .replace(/\s+/g, ' ')
    .trim();
}
