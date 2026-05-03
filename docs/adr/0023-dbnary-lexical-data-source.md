# ADR-0023: DBnary as offline lexical enrichment source

## Status

Accepted

## Context

ADR-0013 established the words/clues pipeline with Hunspell-fr (MPL 2.0) as the word source.
ADR-0014 pivoted the primary source to Grammalecte (also MPL 2.0) for fuller inflection
coverage and a real frequency signal. After those two sources are in place, the clue-generation
eval (`scripts/eval/enrich_with_morphology.py`) exposed a remaining gap: neither Hunspell-fr
nor Grammalecte supplies **definitions** or **synonyms**, which are the two strongest inputs
for generating a concise, accurate ≤18-character clue. The LLM is being asked to clue a word
with no semantic context beyond the word itself.

**DBnary** (`kaiko.getalp.org/dbnary`) is a multilingual lexical database extracted from
Wiktionary, queryable via a public SPARQL endpoint. For French (`fra/` graph) it provides:

- One or more `ontolex:LexicalSense` nodes per entry, each with a `skos:definition` string
  (the Wiktionary gloss) and an optional `dbnary:usage` register field (e.g. `(Familier)`,
  `(Religion)`, `(Vieilli)`).
- `dbnary:synonym` edges at the `LexicalEntry` level (word-level, not per-sense), pointing
  to `dbnary:Page` nodes whose label is the canonical synonym lemma.
- POS tagging via `lexinfo:partOfSpeech`, distinguishing e.g. `pull` the noun from `pull`
  the verb — the Grammalecte corpus conflates these by surface form.

### License analysis

DBnary is derived from Wiktionary and is distributed under **CC BY-SA 4.0** — the same
copyleft license that caused ADR-0013 to reject Lexique3. The critical question is whether
this PR's use constitutes a derivative work subject to SA propagation.

ADR-0013 rejected Lexique3 because the plan was to ship the corpus data itself (the
populated `words` table, the clues derived from it) as part of the product. Under that plan
the word list would become embedded in the product, creating a derivative work. The
`dbnary_words/senses/synonyms` tables are **not** the product: they are scratch space for an
offline pipeline whose only outbound artefact is the LLM-generated clue, a new creative work
not copied from any DBnary string. The definitions and synonyms serve as LLM context during
`generate-clues`; they are not excerpted into the CSV, not returned by the API, and not
shown to end users.

This distinction (internal tool vs. redistributed derivative work) is the accepted rationale
for CC BY-SA compatibility here. **Two hard constraints follow:**

1. No DBnary `definition_text` or `synonym_lemma` may appear verbatim in any API response,
   exported CSV row, or product surface. If that constraint is ever relaxed, a new ADR and
   legal review are required first.
2. DBnary data must remain local-dev / offline-pipeline scratch space, never deployed to the
   production cluster in a form that would imply redistribution. The production read path
   (the committed CSV) contains only LLM-generated clues, not DBnary content.

## Decision

### 1. Domain model: `DbnaryWord` + `DbnarySense`

Two domain types in `grid/domain/lexicon/`:

- `DbnaryWord(lemma, pos, language)` — identified by the composite natural key
  `(language, lemma, pos)`. POS is required because DBnary models the noun and verb senses
  of a lemma as distinct `LexicalEntry` nodes; merging them would lose the POS dimension
  used by the self-reference filter.
- `DbnarySense(dbnaryWordId, senseIndex, definitionText, register)` — one row per Wiktionary
  gloss, ordered by `sense_index` (0-based, DBnary SPARQL result order). The
  `enrich_with_morphology.py` self-reference filter walks senses in this order, picking the
  first non-self-referential definition; preserving the order is therefore load-bearing.
  `register` is separated from `definition_text` to avoid bleeding e.g. `(Familier)` into
  the LLM prompt.

Synonyms are a flat `List<String>` on `DbnaryWord`, not a first-class type, because they
carry no per-entry metadata beyond the lemma string.

### 2. Schema: three tables (V3 – V5 Flyway migrations)

```
dbnary_words   (id UUID PK, lemma, pos, language, created_at)
               UNIQUE (language, lemma, pos)
               INDEX (language, lemma)

dbnary_senses  (id UUID PK, dbnary_word_id FK→dbnary_words, sense_index, definition_text, register)
               UNIQUE (dbnary_word_id, sense_index)
               INDEX (dbnary_word_id)

dbnary_synonyms(dbnary_word_id FK→dbnary_words, synonym_lemma)
               PK (dbnary_word_id, synonym_lemma)  — dedup + idempotent re-ingest
               INDEX (dbnary_word_id)
```

The three-table split reflects the DBnary RDF model directly: words and senses are
`1:N` (one entry, many glosses); synonyms are word-level (DBnary attaches
`dbnary:synonym` to `LexicalEntry`, not to individual senses), so a join table with
the word FK and the synonym lemma string is the right shape. Collapsing synonyms into
a `TEXT[]` array column on `dbnary_words` would have been simpler, but a join table
supports per-synonym queries and is consistent with the rest of the schema's relational
style.

Migrations live in both `grid/api/.../db/migration/` and `grid/worker/.../db/migration/`
per the ADR-0013 §6 convention: both the API and the worker apply Flyway on startup
against the same local-dev database.

### 3. Relationship to ADR-0013 §8 (CSV as source of truth)

The `dbnary_*` tables are **not** a production read path. Like the `words` table they are
offline-pipeline scratch space:

```
SPARQL → ingest-dbnary (future) → dbnary_words/senses/synonyms
                                         ↓ enriched prompt context
                    generate-clues → LLM → words.clue (populated)
                                         ↓
                    export-words → words-fr.csv (committed to repo)
```

The CSV contains only LLM-generated clues; no DBnary text leaks to the product surface.

## Consequences

### Easier

- **Richer clue prompts.** `generate-clues` gains access to per-sense definitions and
  the word's synonyms, reducing the frequency of generic or circular clues.
- **POS disambiguation.** The `(language, lemma, pos)` key separates noun/verb homographs;
  downstream phases can target a specific POS variant.
- **Idempotent ingest.** The composite UNIQUE constraint on `dbnary_words` and the
  composite PK on `dbnary_synonyms` make re-ingest safe without manual deduplication.

### Harder

- **CC BY-SA 4.0 guard-rails are non-optional.** Any path from `definition_text` or
  `synonym_lemma` to a user-visible surface requires a new ADR and legal review. This
  is an ongoing maintenance concern, not a one-time check.
- **SPARQL dependency.** The DBnary endpoint is public but not an SLA-backed API.
  The `ingest-dbnary` worker command (out of scope for this PR) should cache the
  SPARQL results locally so pipeline reruns do not require endpoint availability.

### Different

- The existing two-source lexicon (Hunspell-fr + Grammalecte) becomes a three-source
  lexicon. The `words` table is unchanged; the `dbnary_*` tables are additive scratch
  space. Nothing in the existing pipeline is modified by this PR.

## Notes

Out of scope for this PR (follow-up workstreams):

- Application port `DbnaryRepository` in `grid/application/`.
- JDBC adapter implementing the repository in `grid/infrastructure/`.
- `ingest-dbnary` Clikt sub-command in `grid/worker/` with testcontainers integration test.
- The multi-language story: `language` is a column from day one; only `'fr'` is populated
  initially.
