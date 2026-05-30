# Per-row training_weight in the Modal corpus builder — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Teach `scripts/clue_generation/modal/build_modal_corpus.py` to honour an optional per-row `weight_column` so a row's frozen `training_weight` controls its replication into the SFT corpus ("row wins", never multiplied with the per-source weight).

**Architecture:** The builder is manifest-driven. A source may declare `weight_column = "<col>"`. When present, each row is replicated by `max(1, round(cell_value))` instead of the uniform per-source `weight`, and the source must declare `weight = 1` (enforced — the two weights never compound). When absent, behaviour is byte-identical to today. Mechanism only: no live survey-export source is added, because the §8.1 export is a runtime artifact with no in-repo file.

**Tech Stack:** Python 3 (stdlib `csv` + `tomllib`), pytest. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-30-survey-corpus-row-weight-design.md`

**ADR pre-read (path-bound):** ADR-0058 (commercial-data-license posture) governs `data/` + `scripts/`. This change adds **no new external data source** — it changes the replication mechanism over first-party rater-proposed survey content — so no new license-matrix entry is triggered. ADR-0056 owns the survey context + the gold-weighting rollout.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `scripts/clue_generation/modal/build_modal_corpus.py` | Manifest-driven corpus builder | Add `_row_copies` helper; `_load_source` reads `weight_column` per row; `load_all_sources` enforces the `weight==1` invariant and replicates per-row. |
| `scripts/clue_generation/modal/test_build_modal_corpus.py` | Builder tests | Add a row-weight fixture + 2 tests; existing tests untouched. |
| `data/lora/modal_corpus_v1/manifest.toml` | Production corpus recipe | Document the optional `weight_column` field in the existing grammar comment block. No new source entry. |

**Test command (run from repo root):**
```
python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py -q
```
(`scripts/` has no `__init__.py`; pytest roots the package at `clue_generation.modal`, satisfying the test's `from . import build_modal_corpus` import.)

---

## Task 1: Per-row replication via `weight_column`

**Files:**
- Modify: `scripts/clue_generation/modal/build_modal_corpus.py` (add `_row_copies`; edit `_load_source` lines 84-118; edit `load_all_sources` lines 121-131)
- Test: `scripts/clue_generation/modal/test_build_modal_corpus.py`

- [ ] **Step 1: Write the failing test**

Append to `scripts/clue_generation/modal/test_build_modal_corpus.py`:

```python
@pytest.fixture
def tmp_rowweight_dir(tmp_path: Path) -> Path:
    """Single survey-style source that declares a per-row weight column."""
    root = tmp_path
    (root / "data" / "seed").mkdir(parents=True)
    (root / "data" / "lora" / "modal_corpus_v1").mkdir(parents=True)
    (root / "data" / "seed" / "survey_export.csv").write_text(
        "mot;definition;training_weight\n"
        "CHAT;Animal qui ronronne;3.0\n"
        "CHIEN;Meilleur ami de l'homme;1.0\n"
        "SOURIS;Petit rongeur;\n",
        encoding="utf-8",
    )
    (root / "data" / "lora" / "modal_corpus_v1" / "manifest.toml").write_text(
        """
version = "test"
seed = 42
val_ratio = 0.0
exclude_lemmas_from = ""
user_prompt_template = "Donne une définition de mot fléché pour {mot}."

[[sources]]
name = "survey"
path = "data/seed/survey_export.csv"
tier = "gold"
weight = 1
csv_delimiter = ";"
schema_mapping = { mot = "mot", definition = "definition", force = "" }
row_filter = ""
weight_column = "training_weight"
""",
        encoding="utf-8",
    )
    return root


def _rowweight_manifest(root: Path) -> Path:
    return root / "data" / "lora" / "modal_corpus_v1" / "manifest.toml"


def test_per_row_weight_replicates_by_column_value(tmp_rowweight_dir):
    """A weight_column source replicates each row by its own value."""
    from collections import Counter
    rows = bc.load_all_sources(tmp_rowweight_dir, _rowweight_manifest(tmp_rowweight_dir))
    counts = Counter(r["mot"] for r in rows)
    assert counts["CHAT"] == 3    # 3.0 -> 3 copies
    assert counts["CHIEN"] == 1   # 1.0 -> 1 copy
    assert counts["SOURIS"] == 1  # blank cell -> default 1.0 -> 1 copy
    assert len(rows) == 5
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py::test_per_row_weight_replicates_by_column_value -q`
Expected: FAIL — today every row is replicated by the source `weight` (1), so `CHAT` count is 1, not 3 (`assert counts["CHAT"] == 3` fails). `weight_column` is currently ignored.

- [ ] **Step 3: Add the `_row_copies` helper**

In `scripts/clue_generation/modal/build_modal_corpus.py`, insert this function immediately above `_load_source` (before line 84):

```python
def _row_copies(raw: str | None) -> int:
    """Copies for a per-row weight cell: max(1, round(value)); blank/non-numeric -> 1."""
    text = (raw or "").strip()
    if not text:
        return 1
    try:
        value = float(text)
    except ValueError:
        return 1
    return max(1, round(value))
```

- [ ] **Step 4: Read `weight_column` in `_load_source`**

In `_load_source`, after the line `name = src["name"]` (line 94), add:

```python
    weight_col = src.get("weight_column", "")
```

Then replace the `out.append({...})` block (lines 112-117) with:

```python
                entry = {
                    "mot": mot,
                    "definition": definition,
                    "force": (row.get(force_col) or "").strip() if force_col else "",
                    "_source": name,
                }
                if weight_col:
                    entry["_copies"] = _row_copies(row.get(weight_col))
                out.append(entry)
```

- [ ] **Step 5: Replicate per-row in `load_all_sources`**

Replace the body of `load_all_sources` (lines 121-131) with:

```python
def load_all_sources(root: Path, manifest_path: Path) -> list[dict[str, str]]:
    """Load every source, exclude held-out lemmas, replicate by weight (per-row when the source sets weight_column)."""
    manifest = _load_manifest(manifest_path)
    held_out = _load_held_out_lemmas(root, manifest.get("exclude_lemmas_from", ""))
    all_rows: list[dict[str, str]] = []
    for src in manifest["sources"]:
        if src.get("weight_column") and int(src["weight"]) != 1:
            raise ValueError(
                f"source '{src['name']}': weight_column requires weight = 1 "
                f"(got {src['weight']})"
            )
        rows = _load_source(root, src)
        rows = [r for r in rows if r["mot"].lower() not in held_out]
        if src.get("weight_column"):
            for r in rows:
                all_rows.extend([r] * r["_copies"])
        else:
            all_rows.extend(rows * int(src["weight"]))
    return all_rows
```

- [ ] **Step 6: Run test to verify it passes**

Run: `python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py::test_per_row_weight_replicates_by_column_value -q`
Expected: PASS.

- [ ] **Step 7: Run the full file to confirm no regression**

Run: `python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py -q`
Expected: PASS — all prior tests (16 now) green. `test_weight_replication_exact_counts` still passes because the existing fixture's sources declare no `weight_column` and take the uniform-`weight` branch unchanged.

- [ ] **Step 8: Commit**

```bash
git add scripts/clue_generation/modal/build_modal_corpus.py scripts/clue_generation/modal/test_build_modal_corpus.py
git commit -s -m "feat(clue-corpus): replicate corpus rows by per-row weight_column"
```

---

## Task 2: Enforce the `weight_column ⇒ weight == 1` invariant

The guard was added in Task 1 Step 5; this task locks it with a test.

**Files:**
- Test: `scripts/clue_generation/modal/test_build_modal_corpus.py`

- [ ] **Step 1: Write the failing test**

Append to `scripts/clue_generation/modal/test_build_modal_corpus.py`:

```python
def test_weight_column_with_nonunit_weight_raises(tmp_rowweight_dir):
    """weight_column + weight != 1 is a misconfiguration -> ValueError (never multiplied)."""
    manifest = _rowweight_manifest(tmp_rowweight_dir)
    manifest.write_text(
        manifest.read_text(encoding="utf-8").replace("weight = 1", "weight = 2"),
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="weight_column requires weight = 1"):
        bc.load_all_sources(tmp_rowweight_dir, manifest)
```

- [ ] **Step 2: Run test to verify it passes**

Run: `python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py::test_weight_column_with_nonunit_weight_raises -q`
Expected: PASS — the guard from Task 1 Step 5 raises `ValueError("source 'survey': weight_column requires weight = 1 (got 2)")`.

(If it does NOT pass, the Task 1 Step 5 guard is missing or misplaced — fix `load_all_sources`, do not weaken the test.)

- [ ] **Step 3: Commit**

```bash
git add scripts/clue_generation/modal/test_build_modal_corpus.py
git commit -s -m "test(clue-corpus): lock the weight_column-requires-weight-1 invariant"
```

---

## Task 3: Document `weight_column` in the production manifest

**Files:**
- Modify: `data/lora/modal_corpus_v1/manifest.toml` (comment block above the first `[[sources]]`)

- [ ] **Step 1: Add the documentation comment**

In `data/lora/modal_corpus_v1/manifest.toml`, find the comment block that ends with:

```
#   operand  := IDENT | "'" STRING "'"
# Anything outside this grammar raises ValueError at build time.
```

Immediately after that line (still before the first `[[sources]]`), add:

```
#
# Optional per-row weighting: a source may set `weight_column = "<col>"`
# to replicate each row by its own column value ("row wins") instead of
# the uniform per-source `weight`. Such a source MUST set `weight = 1`
# (the two are never multiplied) or the build raises. Blank, missing, or
# non-numeric cells default to 1.0 (one copy). Used by the survey export's
# frozen `training_weight` column (ADR-0056, Spec C/D).
```

This mirrors the file's existing multi-line grammar-documentation block; it documents a config knob, not code logic.

- [ ] **Step 2: Verify the manifest still parses**

Run: `python3 -c "import tomllib; tomllib.load(open('data/lora/modal_corpus_v1/manifest.toml','rb')); print('ok')"`
Expected: prints `ok` (comments don't affect parsing; this confirms no accidental syntax break).

- [ ] **Step 3: Confirm the production build still succeeds unchanged**

Run: `python3 scripts/clue_generation/modal/build_modal_corpus.py --help`
Expected: prints argparse usage (no source declares `weight_column`, so production behaviour is unchanged; this just confirms the module imports cleanly).

- [ ] **Step 4: Commit**

```bash
git add data/lora/modal_corpus_v1/manifest.toml
git commit -s -m "docs(clue-corpus): document the optional weight_column manifest field"
```

---

## Task 4: Full verification, push, and PR

**Files:** none (verification + ship)

- [ ] **Step 1: Run the full modal test file**

Run: `python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py -q`
Expected: PASS — 17 tests (15 original + 2 new), 0 failures.

- [ ] **Step 2: Confirm the diff is in scope and within the line target**

Run: `git diff origin/main --stat`
Expected: only the three files above change; total well under the ADR-0001 §4 400-line target (≈15 production lines, ≈45 test lines, ≈7 manifest comment lines).

- [ ] **Step 3: Push the feature branch**

```bash
git push -u origin HEAD:feat/survey-corpus-row-weight
```

- [ ] **Step 4: Open the PR**

```bash
gh pr create --base main --title "feat(clue-corpus): honour per-row training_weight in modal corpus builder" --body "$(cat <<'EOF'
## Summary
- Spec D (final part of the ADR-0056 gold-weighting rollout): the Modal SFT corpus builder now honours an optional per-row `weight_column`.
- "Row wins": a source declaring `weight_column` replicates each row by `max(1, round(value))`; it must declare `weight = 1` (the per-source and per-row weights never compound) or the build raises.
- Mechanism only — no live survey-export source is added (the §8.1 export is a runtime artifact with no in-repo file). Wiring a real source is a documented follow-up.

## Test plan
- [ ] `python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py -q` — 17 passed
- [ ] New: per-row replication (3.0→3, 1.0→1, blank→1 copies)
- [ ] New: `weight_column` + `weight != 1` raises `ValueError`
- [ ] Existing uniform-weight + held-out + byte-identical tests unchanged and green

Spec: `docs/superpowers/specs/2026-05-30-survey-corpus-row-weight-design.md`
Plan: `docs/superpowers/plans/2026-05-30-survey-corpus-row-weight.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 5: Report the PR URL**

---

## Comment style (binding for this plan)

Default to no comment. Any code comment is a single line on a non-obvious *why*. The function docstrings stay one line each (matches the file's existing convention). Do NOT add multi-line `#` blocks in the `.py` files. The manifest comment in Task 3 is the one exception — it is config documentation mirroring the file's existing grammar block, not code logic.
