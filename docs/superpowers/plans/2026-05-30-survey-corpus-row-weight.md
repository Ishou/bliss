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
| `scripts/clue_generation/modal/build_modal_corpus.py` | Manifest-driven corpus builder | Add `_row_copies` helper; `_load_source` reads `weight_column` per row; `load_all_sources` enforces the `weight==1` invariant, accumulates `raw_counts`, pops `_copies`, returns tuple; `build_corpus` unpacks the tuple; `_build_summary` uses `raw_counts` for weight_column sources. |
| `scripts/clue_generation/modal/test_build_modal_corpus.py` | Builder tests | Update 3 existing `load_all_sources` callers to unpack tuple; add row-weight fixture + 3 new tests. |
| `data/lora/modal_corpus_v1/manifest.toml` | Production corpus recipe | Document the optional `weight_column` field in the existing grammar comment block. No new source entry. |

**Test command (run from repo root):**
```
python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py -q
```
(`scripts/` has no `__init__.py`; pytest roots the package at `clue_generation.modal`, satisfying the test's `from . import build_modal_corpus` import.)

---

## Task 1: Per-row replication via `weight_column`

**Files:**
- Modify: `scripts/clue_generation/modal/build_modal_corpus.py` (add `_row_copies`; edit `_load_source` lines 84-118; edit `load_all_sources` lines 121-131; update `build_corpus` line 190 and 214; update `_build_summary` lines 224-249)
- Modify: `scripts/clue_generation/modal/test_build_modal_corpus.py` (update 3 existing `load_all_sources` callers; add 2 new tests)

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
    rows, raw_counts = bc.load_all_sources(tmp_rowweight_dir, _rowweight_manifest(tmp_rowweight_dir))
    counts = Counter(r["mot"] for r in rows)
    assert counts["CHAT"] == 3    # 3.0 -> 3 copies
    assert counts["CHIEN"] == 1   # 1.0 -> 1 copy
    assert counts["SOURIS"] == 1  # blank cell -> default 1.0 -> 1 copy
    assert len(rows) == 5
    assert raw_counts["survey"] == 3  # 3 unique rows before replication, not 5
    assert all("_copies" not in r for r in rows)  # _copies must not leak into returned rows


def test_summary_reports_raw_rows_in_for_weight_column_source(tmp_rowweight_dir):
    """build_summary.md shows the pre-replication unique row count for weight_column sources."""
    out_dir = tmp_rowweight_dir / "data" / "lora" / "modal_corpus_v1"
    bc.build_corpus(tmp_rowweight_dir, _rowweight_manifest(tmp_rowweight_dir), out_dir)
    summary = (out_dir / "build_summary.md").read_text(encoding="utf-8")
    # survey has 3 unique input rows (CHAT, CHIEN, SOURIS); 5 after replication
    assert "| `survey` | gold | 1 | 3 | 5 |" in summary
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py::test_per_row_weight_replicates_by_column_value -q`
Expected: FAIL — today `load_all_sources` returns a plain list, so `rows, raw_counts = bc.load_all_sources(...)` raises `ValueError: too many values to unpack`. Any implementation that does not return a tuple fails here.

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

In `_load_source`, change the return-type annotation and the `out` declaration (lines 84, 96) — `_copies` is `int`, not `str`, so the list is `dict[str, Any]` temporarily:

```python
def _load_source(root: Path, src: dict[str, Any]) -> list[dict[str, Any]]:  # Any: _copies is int
```

```python
    out: list[dict[str, Any]] = []
```

After the line `name = src["name"]` (line 94), add:

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
def load_all_sources(
    root: Path, manifest_path: Path
) -> tuple[list[dict[str, str]], dict[str, int]]:
    """Load every source, exclude held-out lemmas, replicate by weight (per-row when the source sets weight_column).

    Returns (all_rows, raw_counts) where raw_counts[name] is the pre-replication unique-row
    count for each source (needed by _build_summary to report correct 'Rows in' for
    weight_column sources whose uniform weight is always 1).
    """
    manifest = _load_manifest(manifest_path)
    held_out = _load_held_out_lemmas(root, manifest.get("exclude_lemmas_from", ""))
    all_rows: list[dict[str, str]] = []
    raw_counts: dict[str, int] = {}
    for src in manifest["sources"]:
        if src.get("weight_column") and int(src["weight"]) != 1:
            raise ValueError(
                f"source '{src['name']}': weight_column requires weight = 1 "
                f"(got {src['weight']})"
            )
        rows = _load_source(root, src)
        rows = [r for r in rows if r["mot"].lower() not in held_out]
        raw_counts[src["name"]] = len(rows)  # pre-replication count; weight_column sources need this
        if src.get("weight_column"):
            for r in rows:
                n = r.pop("_copies")  # strip int before replication; eliminates type violation + key leak
                all_rows.extend([r] * n)
        else:
            all_rows.extend(rows * int(src["weight"]))
    return all_rows, raw_counts
```

- [ ] **Step 6: Update `build_corpus` to unpack the tuple**

`load_all_sources` now returns `(rows, raw_counts)`. Update `build_corpus` (line 190) — change:

```python
    rows = load_all_sources(root, manifest_path)
```

to:

```python
    rows, raw_counts = load_all_sources(root, manifest_path)
```

And update the `_build_summary` call (line 214) — change:

```python
    summary = _build_summary(manifest, rows, train_rows, val_rows)
```

to:

```python
    summary = _build_summary(manifest, rows, train_rows, val_rows, raw_counts)
```

- [ ] **Step 7: Update `_build_summary` to report correct "Rows in" for `weight_column` sources**

Change the `_build_summary` signature (line 224) — add `raw_counts`:

```python
def _build_summary(manifest, rows, train, val, raw_counts: dict[str, int]) -> str:
```

Inside the per-source loop (lines 241-249), replace the `rows_in` calculation:

```python
        if src.get("weight_column"):
            rows_in = raw_counts.get(name, 0)  # unique pre-replication count; weight=1 makes out//weight wrong
        else:
            rows_in = out // weight if weight > 0 else 0
```

(The existing `weight = int(src["weight"])` and `out = src_counts.get(name, 0)` lines are unchanged; only the `rows_in` assignment gains a branch.)

- [ ] **Step 8: Update the three existing tests that call `load_all_sources` directly**

`load_all_sources` now returns a tuple. The three existing tests that assign its result to a plain variable must be updated to unpack:

In `test_loads_sources_with_their_delimiter_and_schema` (line 128):

```python
    rows, _ = bc.load_all_sources(
```

In `test_excludes_held_out_lemmas` (line 139):

```python
    rows, _ = bc.load_all_sources(
```

In `test_weight_replication_exact_counts` (line 149):

```python
    rows, _ = bc.load_all_sources(
```

(`test_rebuild_is_byte_identical` calls `bc.build_corpus`, not `load_all_sources` directly — no change needed there.)

- [ ] **Step 9: Run new tests to verify they pass**

Run:
```
python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py::test_per_row_weight_replicates_by_column_value scripts/clue_generation/modal/test_build_modal_corpus.py::test_summary_reports_raw_rows_in_for_weight_column_source -q
```
Expected: both PASS.

- [ ] **Step 10: Run the full file to confirm no regression**

Run: `python3 -m pytest scripts/clue_generation/modal/test_build_modal_corpus.py -q`
Expected: PASS — all prior tests (17 now) green. `test_weight_replication_exact_counts` still passes because the existing fixture's sources declare no `weight_column` and take the uniform-`weight` branch unchanged.

- [ ] **Step 11: Commit**

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
Expected: PASS — 18 tests (15 original + 3 new), 0 failures.

- [ ] **Step 2: Confirm the diff is in scope and within the line target**

Run: `git diff origin/main --stat`
Expected: only the three files above change; total well under the ADR-0001 §4 400-line target (≈25 production lines, ≈55 test lines, ≈7 manifest comment lines).

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
