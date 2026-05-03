# Clue-Generation Phase 0 Eval

Status: in progress.

## Setup

- **Sample**: 100 French words, 10 per length bucket (3–12 chars), drawn from
  `grid/api/src/main/resources/words/words-fr.csv` and filtered to entries with
  a non-empty `lemma` and an alphabetic `word`. The plan called for POS
  stratification, but `words-fr.csv` does not retain POS — length stratification
  + lemmatized-alphabetic filter is the proxy used here. POS comes back from
  DBnary at enrichment time and is reported in the breakdown below.
  - Generated via `python scripts/eval/sample_eval_words.py`.
  - Saved at `data/eval/sample_100.csv`.
- **Definitions + synonyms**: pulled per-word from the DBnary public SPARQL
  endpoint (`http://kaiko.getalp.org/sparql`) — no full TTL download.
  - Run `python scripts/eval/fetch_dbnary_for_sample.py`.
  - Saved at `data/eval/sample_100_with_definitions.csv`.
- **Model**: `mlx-community/Mistral-7B-Instruct-v0.3-4bit` via `mlx-lm`.
  - One-time install: `pip install mlx-lm`. Weights download on first load.
- **Prompt**: `scripts/eval/prompts/clue_v0.txt` (15 hand-crafted exemplars +
  the per-word slots `{word}`, `{pos}`, `{dbnary_definition}`,
  `{synonyms_csv}`).
- **Inference**: `python scripts/eval/generate_clues_v0.py` (writes
  `data/eval/run_v0_results.csv`, leaving the `rating` column blank).

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
