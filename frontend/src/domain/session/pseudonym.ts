// Pure predicate (ADR-0002 §7): matches the auto-generated default
// pseudonym shape `<Word> NNN` issued by `localStorageSession`. Used by
// `AuthProvider` to decide whether to carry an anon name into the
// server-side identity on first sign-in. No dependency on the animal
// list — adding a new animal must not change the heuristic.
const DEFAULT_PSEUDONYM_PATTERN = /^\S+ \d{3}$/;

export function isDefaultPseudonym(name: string): boolean {
  return DEFAULT_PSEUDONYM_PATTERN.test(name);
}
