# ADR-0014: Grammalecte lexique as word source — lemma column and frequency signal

## Status

Accepted.

## Context

ADR-0013 §1 chose **Hunspell-fr** (MPL 2.0) over Lexique3 (CC BY-SA 4.0) after a licensing
analysis. The Hunspell-fr corpus expanded to ~70k surface forms via `unmunch`, but two
structural gaps appeared during the first clue-generation run:

1. **Coverage gap.** `unmunch` is designed for spell-checking, not NLP: it expands affix
   rules but skips irregular conjugations and edge forms. Verb inflections (past participle,
   present/future conjugated forms) were largely absent, leaving the grid generator with a
   sparse verb corpus.

2. **No frequency signal.** ADR-0013 §4 acknowledges that Hunspell-fr has no frequency data
   and falls back to alphabetical rank. The §4 sigmoid difficulty formula degrades to a
   word-length proxy, which is too coarse to produce useful difficulty scores.

A second source was evaluated: **Grammalecte's `lexique-grammalecte-fr-v7.7.txt`**, the
frequency-annotated lexicon shipped with the open-source Grammalecte grammar checker
(`grammalecte.net`). Key properties:

- **~515k inflected forms** (vs ~70k), with full verb conjugation coverage and proper
  lemma ↔ inflected-form mappings.
- **Corpus-frequency column** (`nOccurrences` or equivalent) derived from a real text
  corpus, replacing the alphabetical-rank fallback.
- **License:** MPL 2.0 — identical to Hunspell-fr, confirmed in `LICENSE` in the
  Grammalecte repository. File-scoped copyleft; derivative works (the `words` table, the
  clues, the API response) are unencumbered.

The trade-off accepted by this decision: `import-grammalecte` replaces `import-words`
(the `unmunch`-backed importer). The old importer is retained in the codebase for
reference but is no longer the primary ingest path.

## Decision

### 1. Word source pivot: Grammalecte lexique (MPL 2.0)

`lexique-grammalecte-fr-v7.7.txt` becomes the primary French word source. The importer
(`import-grammalecte` sub-command) reads the tab-separated file, deduplicates by surface
form keeping the highest-frequency POS row, applies the ADR-0013 §2 filter rules
(lower-case, alphabetic only, no hyphens/apostrophes), and populates
`word + lemma + frequency` in a single pass.

**Attribution** follows ADR-0013 §1 conventions: `source = 'grammalecte-fr-v7.7'`,
`source_license = 'MPL-2.0'`. `NOTICE.md` is updated to list Grammalecte alongside the
existing Hunspell-fr entry.

### 2. Schema amendment: `lemma` column (ADR-0013 §3 extension)

A new nullable `lemma TEXT` column is added to the `words` table via a V2 Flyway migration.
A `words_lang_lemma` index is created on `(language, lemma)` to keep the export-time JOIN
cheap.

For self-lemma rows (`import-grammalecte` populates `lemma = word` when the surface form is
its own canonical form). `NULL` means "lemma unknown" — used for rows imported by the legacy
`import-words` path or inserted manually.

This is an **expand step** (ADR-0013 §6): nullable column, no existing rows invalidated,
backward-compatible with any in-flight V1 schema.

### 3. Clue strategy: lemma-only generation with propagation at export time

`generate-clues` defaults to `WHERE word = lemma` — only canonical lemma forms are sent to
the Claude API. Inflected forms inherit their lemma's clue via a `LEFT JOIN` at
`export-words` time:

```sql
COALESCE(w.clue, l.clue) AS clue
FROM words w
LEFT JOIN words l ON l.language = w.language AND l.word = w.lemma
```

This reduces Claude API spend by roughly 10× (lemmas ≈ 50k vs inflected forms ≈ 515k).
Pass `--all-forms` to `generate-clues` for the legacy clue-everything behaviour.

### 4. Frequency signal: `import-frequencies` sub-command

A separate `import-frequencies` sub-command accepts any `<word> <count>` UTF-8 file (e.g.
the Brunet/Eduscol list distributed by the French Ministry of Education) and updates the
`frequency` column, then recomputes `difficulty` from rank using the ADR-0013 §4 sigmoid.
This decouples the word-form corpus (Grammalecte) from the frequency source (any UTF-8
word-count file), making both replaceable independently.

## Consequences

### Easier

- **Full verb coverage.** ~515k inflected forms vs ~70k; the grid generator no longer
  has a verb-shaped gap.
- **Real difficulty signal.** Corpus frequency replaces alphabetical rank; the §4 sigmoid
  produces meaningful difficulty scores from first import.
- **~10× clue cost reduction.** Lemma-only clueing with propagation cuts Claude API
  spend from O(500k) to O(50k) per language.

### Harder

- **Import-grammalecte replaces import-words** as the primary ingest path. Operators
  running the local-dev pipeline must use the new sub-command. The old sub-command
  (`import-words`) remains in the codebase but is not the recommended path.
- **Schema bump to V2.** Existing local-dev `words` tables need the migration applied;
  the worker applies it automatically on startup.

### Different

- `source` values in the `words` table will read `grammalecte-fr-v7.7` for rows
  imported via the new path (vs `hunspell-fr` for legacy rows).
- The `frequency` column is now populated at first import (not deferred to a second
  frequency-layering pass as under ADR-0013).
