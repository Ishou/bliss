# ADR-0024: Authorize capitalized `synonym_lemma` as a direct clue candidate

## Status

Accepted

## Context

ADR-0023 established two hard constraints on DBnary data:

> 1. No DBnary `definition_text` or `synonym_lemma` may appear verbatim in any API
>    response, exported CSV row, or product surface. If that constraint is ever relaxed,
>    a new ADR and legal review are required first.
> 2. DBnary data must remain local-dev / offline-pipeline scratch space, never deployed
>    to the production cluster in a form that would imply redistribution. The production
>    read path (the committed CSV) contains only LLM-generated clues, not DBnary content.

ADR-0023 was written when the only planned path from `dbnary_synonyms` to a clue was
LLM-mediated: synonyms would be fed as context into `generate-clues`, which would emit
a new creative work independent of any DBnary string. Under that plan constraint #1 was
never at risk.

PR #185 (`deriveSynonymClues`) introduces a direct path: the SQL derivation stores

```sql
upper(left(syn.synonym_lemma, 1)) || substring(syn.synonym_lemma, 2)
```

as `clue_text` in `clue_candidates` with `source = 'dbnary-synonym'`. These rows are
eligible to be picked by `findTopBySourcePriority` and exported to `words-fr.csv` by
`export-words`. This short-circuits the LLM step and constitutes a relaxation of
ADR-0023 constraint #1. A new ADR and legal review are therefore required.

### Legal analysis — why constraint #1 can be safely relaxed for synonym lemmas

ADR-0023's CC BY-SA concern was:

> The plan was to ship the corpus data itself … as part of the product. Under that plan
> the word list would become embedded in the product, creating a derivative work.

The same concern applies here if the synonym lemma constitutes copyrightable expression.
It does not, for three reasons:

**1. Individual words are not copyrightable.**
Copyright protects original creative expression, not facts or language itself. A French
synonym lemma such as "bagnole" or "automobile" is a word in the French lexicon. It
exists independently of DBnary and Wiktionary; the editors who added the
`dbnary:synonym` edge to the `LexicalEntry` node did not create it. CC BY-SA on DBnary
covers the creative compilation — the structure, the relationships, the glosses — not
the underlying lexical items.

**2. Multi-sentence definitions are categorically different from single-word synonyms.**
The `definition_text` constraint is the load-bearing part of ADR-0023's CC BY-SA
analysis: a Wiktionary gloss like "Voiture automobile, par ellipse" is an editorial
sentence that is plausibly original creative expression. A synonym lemma ("voiture") is
a single common word. This ADR relaxes the constraint only for synonym lemmas; the
`definition_text` prohibition remains unchanged.

**3. Capitalization is an independent editorial decision.**
DBnary stores `synonym_lemma` in lower-case (`"bagnole"`). Bliss stores `"Bagnole"` —
first character uppercased per the French mots-fléchés convention (clues start with a
capital). The transformation is not a reproduction of a DBnary string; it is Bliss
applying its own crossword-formatting rule to a French word.

### Attribution obligation

CC BY-SA requires attribution. `source = 'dbnary-synonym'` in `clue_candidates`
provides full traceability: any audit of exported clues can identify their origin.
Pipeline documentation (ADR-0023 §1, this ADR) discloses the DBnary / Wiktionary
provenance at the project level.

## Decision

### Amendment to ADR-0023 constraint #1

Constraint #1 is amended as follows (strikethrough = removed, bold = added):

> ~~No DBnary `definition_text` or `synonym_lemma` may appear verbatim in any API
> response, exported CSV row, or product surface. If that constraint is ever relaxed, a
> new ADR and legal review are required first.~~
>
> **No DBnary `definition_text` may appear verbatim in any API response, exported CSV
> row, or product surface. A capitalized `synonym_lemma` (first character uppercased
> per French mots-fléchés convention) may be stored as a `clue_candidates` row with
> `source = 'dbnary-synonym'` and is eligible for export. Any further relaxation of
> this constraint requires a new ADR and legal review.**

Constraint #2 (no DBnary data in the production read path beyond the committed CSV) is
unchanged.

### Source identifier

The string `'dbnary-synonym'` is the canonical source identifier for candidates derived
by `deriveSynonymClues`. It appears in `ClueSource.DBNARY_SYNONYM` (domain) and in
`source` column values. Any priority-ordered export pipeline that wants to prefer
LLM-generated clues over synonym-derived ones should rank `'dbnary-synonym'` below LLM
sources.

### Fallback position

If this legal analysis is challenged, the remediation is straightforward: exclude
`source = 'dbnary-synonym'` rows from `findTopBySourcePriority` in the export step,
reverting to LLM-only clue generation. No schema migration is required; the rows remain
as context-enrichment scratch space.

## Consequences

### Easier

- **LLM-free cold start.** A word with no LLM-generated clue can still be exported
  with a synonym-derived candidate. This reduces the gap between a fresh DBnary ingest
  and a fully clued export.
- **Richer candidate pool.** `findTopBySourcePriority` picks the best candidate by
  source rank; synonym candidates act as a fallback tier below LLM-generated ones.

### Harder

- **CC BY-SA guard-rail scope narrows but does not disappear.** `definition_text`
  remains strictly off-limits for the product surface. Every future use of DBnary data
  must be evaluated against this updated constraint, not the original.
- **Attribution maintenance.** `source = 'dbnary-synonym'` must be preserved in the
  schema and never coalesced or renamed to something that loses the DBnary provenance.

### Different

- ADR-0023's pipeline diagram changes: the `synonym_lemma` column now has a direct path
  to `words-fr.csv` as a fallback tier, bypassing the LLM step.
- `export-words` priority configuration becomes a policy decision: teams must explicitly
  choose where `'dbnary-synonym'` ranks relative to LLM sources.
