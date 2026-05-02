# ADR-0017: Lemma-level deduplication in grid generation

## Status

Accepted

## Context

ADR-0014 introduced the `lemma` column in the `words` table and the 9-column CSV
export format (`word,language,...,lemma`). ADR-0015 introduced the skeleton-based
three-phase pipeline, where `SkeletonFiller` is the backtracking CSP that assigns
words to slots.

After both ADRs shipped, the generator could produce grids that contained multiple
inflected forms of the same headword — e.g. "COURT", "COURAIT", and "COURIONS" all
mapping to `lemma = COURIR`. This is a recognised quality defect in French
mots-fléchés: solvers expect each clue to point at a unique dictionary entry.
Placing inflections of the same verb alongside each other is confusing, and the
clue propagation strategy from ADR-0014 §3 (all inflections share the lemma's clue)
means such duplicates would produce identical clues for distinct cells.

Two separate problems required decisions:

1. **Generation-time constraint**: should the `SkeletonFiller` CSP prevent lemma
   duplicates from being placed at all?
2. **Validation-time violation**: should `GridValidator` report lemma duplicates in
   grids that were generated or loaded without lemma dedup?
3. **CSV contract**: the 8-column format omitted `lemma`; the 9-column export now
   always writes it.  Both reader and writer need a defined behaviour for the gap.

## Decision

### 1. `GridViolation.DuplicateLemma` — new domain violation type

`GridViolation.DuplicateLemma(lemma: String, words: List<Word>)` is added to the
`GridViolation` sealed interface.  It fires when two or more **distinct surface
forms** in a grid share the same `lemma` value.

Semantic contract:
- `DuplicateLemma` groups inflections; `DuplicateWord` reports a repeated surface
  form.  They are complementary, not redundant.
- When the same surface form appears twice, `DuplicateWord` is emitted;
  `DuplicateLemma` is **not** — the group contains no distinct inflections.  This
  is enforced by the guard `words.distinctBy { it.text }.size > 1` in
  `GridValidator.duplicateWords`.
- Callers can render `DuplicateLemma` as a single grouped message
  ("COURT / COURAIT / COURIONS → COURIR") rather than three separate entries.

### 2. `SkeletonFiller` lemma exclusion via `usedLemmas`

`SkeletonFiller.fill` maintains a `usedLemmas: HashSet<String>` in parallel with
`usedWords`.  Before a candidate word is accepted, its `lemma` is checked against
`usedLemmas`; candidates whose lemma is already placed are filtered out.  On
backtrack, `usedLemmas -= word.lemma` is called symmetrically with the `usedWords`
undo — the undo is correct and tested.

For corpora that do not carry lemma data (any corpus imported before ADR-0014, or
future corpora without a lemma column), `Word.lemma` defaults to `Word.text`.  In
that state `usedLemmas` is functionally identical to `usedWords` and the extra set
is a no-op overhead rather than a behaviour change.  This preserves backward
compatibility without a code-path split.

A fast-path short-circuit skips filtering when both `usedWords` and `usedLemmas`
are empty (pre-first-placement state).

### 3. CSV schema: 9-column write, 8-or-9-column read

`CsvWordCorpusExportSink` always writes a 9-column header (adding `lemma`).
`CsvWordRepository.toWordWithFreq` detects whether `lemma` is present by checking
`OPTIONAL_LEMMA_HEADER in record.parser.headerNames`:

- **Present**: uses the column value; falls back to the surface form if blank or
  non-ASCII.
- **Absent** (8-column legacy file): falls back to `folded` (the surface form),
  making lemma dedup degenerate to surface-form-only dedup without failing load.

The `validateHeader` method accepts both 8-column and 9-column variants; the
`lemma` column is not required.

## Alternatives considered

**Reject lemma duplicates only at validation time, not during generation.**
Rejected.  The generator would continue emitting low-quality grids; the validator
finding would be noise without a corresponding generation fix.  Both layers need
the constraint to be consistent.

**Add a `--allow-lemma-duplicates` flag to disable dedup.**
Rejected as premature.  No use case has been identified for intentionally placing
inflections of the same headword.  A flag can be added later if a real need
emerges; premature flags add configuration surface with no benefit today.

**Treat `DuplicateLemma` as a sub-case of `DuplicateWord` rather than a separate
violation type.**
Rejected.  `DuplicateWord` is an exact-match check (same text twice); `DuplicateLemma`
is a semantic grouping (distinct texts, same headword).  Merging them into a single
type would require callers to distinguish the two cases by inspecting `words.size`,
which is fragile.  A separate type makes the distinction explicit in the type system.

## Consequences

### Easier

- Grids generated post-ADR carry the guarantee that no two inflected forms of the
  same headword appear together.  Clue quality improves because the identical-clue
  problem (ADR-0014 §3) cannot surface in a well-formed grid.
- `DuplicateLemma` violations in legacy grids are now reportable and renderable as
  a single grouped message rather than repeated individual entries.
- The 8-column legacy CSV load path continues to work without modification.

### Harder

- `SkeletonFiller` search-space is reduced by the lemma exclusion: the effective
  corpus shrinks as lemmas are exhausted.  For large grids on small corpora the
  backtracker may time out more often.  Profiling on the production French corpus
  (≥515k forms, ≥50k distinct lemmas) shows no regression at grid sizes up to 11×11.
- Future corpus integrations must populate `Word.lemma` or accept that dedup
  degenerates to surface-form-only.

### Different

- `CsvWordCorpusExportSink` now always emits a 9-column header.  Any downstream
  consumer hard-coding an 8-column parse must be updated; the `CsvWordRepository`
  reader already handles both.
- `GridValidator.duplicateWords` now emits both `DuplicateWord` and `DuplicateLemma`
  violations; callers that previously only checked for `DuplicateWord` should also
  handle `DuplicateLemma` to get complete validation output.
