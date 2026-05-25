# Notices and attributions

This file enumerates third-party data sources whose licenses require
attribution when redistributed as part of Bliss. See ADR-0013 §1 for the
rationale.

## Word corpus — fr

- **Source:** `LibreOffice/dictionaries` `fr_FR` (Hunspell French
  dictionary).
- **License:** Mozilla Public License 2.0. The upstream `README_fr.txt`
  declares verbatim:

  > MPL : Mozilla Public License version 2.0 -- http://www.mozilla.org/MPL/2.0/

- **Canonical URL:**
  https://github.com/LibreOffice/dictionaries/tree/master/fr_FR

The exact upstream commit pinned at ingest time is recorded alongside
the importer's checksummed input in the `import-words` PR (PR2 of
ADR-0013). Until that PR lands, the `words` table is empty and no
Hunspell-fr data is redistributed by this repository.

## Word corpus — fr (Grammalecte)

- **Source:** `grammalecte.net` — `lexique-grammalecte-fr-v7.7.txt`
  (Grammalecte French grammar checker lexicon, v7.7).
- **License:** Mozilla Public License 2.0. Confirmed in the `LICENSE`
  file of the Grammalecte repository (`grammalecte.net`).
- **Canonical URL:** https://grammalecte.net

The version pinned at ingest time is `v7.7`, recorded in the `source`
column of the `words` table (`source = 'grammalecte-fr-v7.7'`) for
every row imported via `import-grammalecte`. See ADR-0014 for the
ingest rationale.

## Lexical enrichment — DBnary

- **Source:** `kaiko.getalp.org/dbnary` — French Wiktionary extract
  produced by the DBnary project (Sérasset et al., LIG/GETALP).
- **License:** Creative Commons Attribution-ShareAlike 4.0
  International (CC BY-SA 4.0) — inherited from Wiktionary.
- **Canonical URL:** https://kaiko.getalp.org/about-dbnary/

Per [ADR-0023](./docs/adr/0023-dbnary-lexical-data-source.md), DBnary
is used **only** as offline pipeline scratch space — feeding sense
disambiguation context to the local LoRA generator and providing
positive pairs for the filter model's contrastive training.

**No DBnary `definition_text` or `synonym_lemma` is distributed by
this repository.** The runtime corpus
(`grid/api/src/main/resources/words/words-fr.csv`) contains only
LLM-generated clues authored by us. Per-iteration eval CSVs that
historically embedded verbatim DBnary glosses are gitignored
(`data/eval/lemma_clues_iter[2-7].csv`,
`data/eval/sample_iter[2-7]_*.csv`, etc.) and stay local for
offline analysis only. We list DBnary here as a courtesy: the
filter model's training data and the LoRA's prompting pipeline are
derivative of DBnary as input, even though no source text is
shipped.

## Lexical enrichment — DBnary

- **Source:** `kaiko.getalp.org/dbnary` — French Wiktionary extract
  produced by the DBnary project (Sérasset & al., LIG/GETALP).
- **License:** Creative Commons Attribution-ShareAlike 4.0
  International (CC BY-SA 4.0) — inherited from Wiktionary.
- **Canonical URL:** https://kaiko.getalp.org/about-dbnary/

Per [ADR-0023](./docs/adr/0023-dbnary-lexical-data-source.md), DBnary
is used **only** as offline pipeline scratch space — feeding sense
disambiguation context to the local LoRA generator and providing
positive pairs for the filter model's contrastive training. **No
DBnary `definition_text` or `synonym_lemma` is distributed by this
repository**: the runtime word corpus
(`grid/api/src/main/resources/words/words-fr.csv`) contains only
LLM-generated clues authored by us, and any per-iteration eval CSVs
that previously embedded verbatim DBnary glosses have been removed
from version control. We list DBnary here regardless because the
filter model's weights and the LoRA's prompting pipeline are
derivative of DBnary as training input, and attribution is good
practice even when no source text is shipped.

## Modal clue-AI lane — language detection and base model

- lingua-language-detector v2.2.0 — Apache 2.0
  https://github.com/pemistahl/lingua-py
  Used by scripts/clue_generation/pipeline_v2/filters.py for FR/EN
  classification (§8.3 filter 6).

- Mistral-Nemo-Base-2407 — Apache 2.0 (model downloaded at training
  time; not bundled in any deployed artefact). Used by the Modal
  fine-tuning lane (ADR-0057).
