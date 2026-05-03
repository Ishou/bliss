# Clue-Generation Phase 0 Eval

Status: in progress.

## Setup

- **Sample**: 100 French words, 10 per length bucket (3–12 chars), drawn from
  `grid/api/src/main/resources/words/words-fr.csv` and filtered to entries with
  a non-empty `lemma` and an alphabetic `word`. The plan called for POS
  stratification, but `words-fr.csv` does not retain POS — length stratification
  + lemmatized-alphabetic filter is the proxy used here. POS + morphology come
  back from grammalecte at enrichment time.
  - Generated via `python scripts/eval/sample_eval_words.py`.
  - Saved at `data/eval/sample_100.csv`.
- **Definitions + synonyms**: pulled per-word from the DBnary public SPARQL
  endpoint (`http://kaiko.getalp.org/sparql`) — no full TTL download.
  - Run `python scripts/eval/fetch_dbnary_for_sample.py`.
  - Saved at `data/eval/sample_100_with_definitions.csv`.
- **Morphology**: parsed from `lexique-grammalecte-fr-v7.7.txt` (MPL-2.0 — same
  licence the `import-grammalecte` worker pipeline declares). Each word gets
  POS + gender + number + (for verbs) mood + tense + person, e.g. `SEXUALISENT
  → verbe, indicatif présent, 3e pers. plur.`. Lexique 4 / Lexique3 was
  evaluated and rejected (CC BY-SA share-alike would virally taint the corpus).
  - Run `python scripts/eval/enrich_with_morphology.py --lexique <path>`.
  - Saved at `data/eval/sample_100_enriched.csv`.
- **Model**: `mlx-community/Mistral-7B-Instruct-v0.3-4bit` via `mlx-lm`.
  - One-time install (in a venv): `pip install mlx-lm`. Weights download on
    first load.
- **Prompt**: `scripts/eval/prompts/clue_v0.txt` (13 hand-crafted exemplars
  showing inflection-matching, plus per-word slots `{word_upper}`, `{lemma}`,
  `{morphology}`, `{dbnary_definition}`, `{synonyms_csv}`).
- **Inference + post-filter**: `python scripts/eval/generate_clues_v0.py`
  writes `data/eval/run_v0_results.csv`. A `flag` column is auto-set when the
  clue echoes the surface, the lemma, or exceeds 8 words — those rows should
  be reviewed first. The `rating` column is left blank for hand-rating.

## How to hand-rate

Open `data/eval/run_v0_results.csv` in any editor / spreadsheet. Fill the
`rating` column with one of:

| Rating | Meaning | Score |
| --- | --- | ---: |
| `y` (or `yes` / `✓` / `1`) | acceptable mots-fléchés clue | 1.0 |
| `b` (or `borderline` / `◯`) | usable with a small edit | 0.5 |
| `n` (or `no` / `✗` / `0`) | unusable | 0.0 |

Then run `python scripts/eval/eval_clue_quality.py` to recompute the block
below.

## Decision rule

| Acceptance | Action |
| --- | --- |
| ≥ 85% | **SHIP**. Skip Phase 4, use base model + prompts as the offline batch tool. |
| 70–85% | **FINE-TUNE**. Run Phase 4 (LoRA on Mistral-7B). |
| < 70% | **INVESTIGATE**. Enrich prompt, try other base models, grow curated set, re-run Phase 0. |

## Failure-mode notes

(Fill in when rating: e.g. "leaks the word", "too long", "uses uncommon
register", "circular definition".)

## Results

<!-- AUTO:BEGIN -->
No rows rated yet.
<!-- AUTO:END -->
