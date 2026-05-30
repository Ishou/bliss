# Spec D — Per-row training_weight in the Modal corpus builder

> Design doc. Fourth and final part of the gold-weighting rollout
> (ADR-0056). A shipped `UserRoleChanged`; B stamps a frozen
> `training_weight` on maintainer-authored survey items post-cutoff;
> C exports that column in the §8.1 CSV; **D (this spec) teaches the
> training-corpus builder to honour the per-row weight.**

## Goal

Teach `scripts/clue_generation/modal/build_modal_corpus.py` to honour an
optional **per-row** weight column when a manifest source declares one,
so a row's frozen `training_weight` (1.0 default, 3.0 gold) controls how
many times that row is replicated into the SFT corpus.

## Background — how weighting works today

The builder is manifest-driven (`data/lora/modal_corpus_v1/manifest.toml`).
Each source declares an **integer `weight`**; the builder replicates that
source's rows uniformly (`rows * weight`, `build_modal_corpus.py:130`).
`_load_source` extracts only `mot` / `definition` / `force` — it never
reads a per-row weight. The `tier` field (gold/silver/bronze) is a
human-facing label and does not drive replication; only `weight` does.

The survey export is **not** a manifest source today: the §8.1 export is
a runtime artifact emitted by the survey worker (`ExportDatasetUseCase`),
not a checked-in file. The one committed §8.1-schema file,
`data/curated/gold_pilot_v1.csv`, carries the *pre-Spec-C* 9-column header
(`mot;…;source;meta`) with **no** `training_weight` column.

## Decision

### Weighting model: "row wins", never multiplied

A source is weighted **either** by its per-source `weight` (today's
behaviour) **or** by a per-row weight column — never both compounded.
Formally: effective copies are `tier × 1` *or* `1 × row`, never
`tier × row`.

- A source MAY declare `weight_column = "<column-name>"` in the manifest.
- **Absent** (every source today): unchanged. Uniform `rows * weight`.
- **Present:** each row is replicated by its own column value. The
  source's `weight` is not applied as a multiplier.

### Invariant: weight_column requires weight == 1

To make "never multiplied" enforceable rather than conventional, a source
that declares `weight_column` MUST also have `weight = 1`. If it declares
`weight_column` and `weight != 1`, the builder raises `ValueError` at load
time. This guarantees the `1 × row` form structurally.

### Float → integer copies

The exported column is `NUMERIC > 0` (default `1.0`, gold `3.0`).
Replication needs a whole count, so:

```
copies = max(1, round(value))
```

- Today's values are integral, so `round` is a no-op.
- `max(1, …)` guarantees a positive-weight row is never silently dropped
  (a hypothetical `0.4` would otherwise round to 0 copies).
- A missing or blank cell on a row defaults to `1.0` — matching Spec C's
  parser default and `csv.DictReader`'s "absent column reads as empty"
  semantics. Blank → `1.0` → 1 copy.

`round` uses Python's built-in banker's rounding; this is acceptable
because only integral values occur in practice and the `.5` tie case is
unreachable with current data. The behaviour is deterministic regardless.

### Scope: mechanism only

This spec ships the **mechanism**, not a live survey source:

- Modify `_load_source` / `load_all_sources` to read and apply
  `weight_column`.
- Document `weight_column` in the manifest's grammar comment block.
- **Do NOT** add a survey-export source to the production manifest — no
  export file exists in-repo to point at. Wiring an actual survey-export
  source is a follow-up operational step (run the worker, land the CSV
  under `data/`, add a `[[sources]]` entry with
  `weight_column = "training_weight"` and `weight = 1`).

This keeps the PR within the ADR-0001 §4 target and avoids committing a
generated training artifact.

## Components touched

| File | Change |
|---|---|
| `scripts/clue_generation/modal/build_modal_corpus.py` | `_load_source` reads `weight_column`; per-row replication with the `max(1, round())` rule; invariant guard (`weight_column` ⇒ `weight == 1`). |
| `scripts/clue_generation/modal/test_build_modal_corpus.py` | New tests for row-wins replication, the invariant guard, blank-cell default; existing tests unchanged (backward-compat proof). |
| `data/lora/modal_corpus_v1/manifest.toml` | Grammar-doc comment block documents the new optional `weight_column` field. No new source entry. |

## Data flow

```
survey worker  ──ExportDatasetUseCase──▶  §8.1 CSV (runtime artifact)
                                            │  (operational: land under data/, add source)
                                            ▼
manifest [[sources]] weight_column="training_weight", weight=1
                                            ▼
_load_source  ──reads training_weight per row──▶  rows replicated max(1,round(w)) each
                                            ▼
                          train.jsonl / val.jsonl  (gold rows over-represented)
```

## Error handling

- `weight_column` set + `weight != 1` → `ValueError` at load time
  (fail fast; the recipe is misconfigured).
- A `weight_column` naming a column absent from the CSV → every row reads
  blank → defaults to `1.0` (1 copy). This is intentional: a row-weighted
  source missing its column degrades to uniform weight-1, not a crash.
- Non-numeric cell value → defaults to `1.0` (same `toDouble`-or-default
  posture as Spec C's parser), keeping the build robust to dirty exports.

## Determinism

The `test_rebuild_is_byte_identical` guarantee must hold. Row expansion
stays insertion-ordered and replication is a deterministic count, so the
same manifest + inputs produce byte-identical JSONL.

## Testing

TDD throughout. New cases:

1. A source with `weight_column = "training_weight"`: a `3.0` row → 3
   copies, a `1.0` row → 1 copy.
2. Blank/missing `training_weight` cell → 1 copy.
3. `weight_column` declared with `weight = 2` → `ValueError`.
4. Existing `test_weight_replication_exact_counts`, `test_loads_sources…`,
   `test_rebuild_is_byte_identical`, held-out tests: unchanged and green
   (proves backward compatibility for sources without `weight_column`).

## Licensing note (ADR-0058)

ADR-0058 binds `data/` and `scripts/` paths and requires a license-matrix
entry for every **new data source**. Spec D adds **no** new external data
source — it changes the replication mechanism over first-party
rater-proposed survey content, which is not external licensed data. No
new matrix entry is triggered. (Recorded here to preempt the §6a review.)

## Out of scope

- Adding a live survey-export source to the production manifest.
- Changing the survey export format (shipped in Spec C).
- Any DPO / RAFT weighting (this is SFT-corpus replication only).
- Per-row weighting for the existing curated/synthetic/iter sources —
  they have no weight column and stay on uniform per-source weight.
