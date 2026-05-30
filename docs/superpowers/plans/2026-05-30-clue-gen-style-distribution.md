# Clue-gen target-style distribution — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `modal_jobs/04_generate.py` produce clues in a configured target style mix (so `metonymie`/`technique` get generated at all), with an adaptive top-up loop that compensates for uneven `pipeline_v2` rejection so the *accepted* style mix matches the target.

**Architecture:** A new pure, GPU-free module `modal_jobs/style_allocation.py` holds all allocation logic (config load+validate, largest-remainder target counts, per-pass shortfall allocation). It is unit-tested directly with no `modal`/`torch` import. `04_generate.py` imports it both locally (entrypoint) and inside the Modal container (added to the image), and its `generate_remote` becomes a generate→filter→top-up loop. This separate-module choice refines the spec (which placed the helpers in `04_generate.py`): the helpers must not transitively `import modal`, otherwise unit tests need the full GPU stack.

**Tech Stack:** Python 3.14, pytest, PyYAML (already in the repo `.venv`), Modal. Run tests with `python -m pytest` from repo root (activate the repo venv first).

**Spec:** `docs/superpowers/specs/2026-05-30-clue-gen-target-style-distribution-design.md`

---

## File Structure

- **Create** `modal_jobs/style_allocation.py` — pure logic: constants, `charger_distribution`, `cibles_acceptation`, `paires_pour_manque`.
- **Create** `modal_jobs/style_distribution.yaml` — committed target distribution.
- **Create** `modal_jobs/test_style_allocation.py` — unit + property tests for the pure module.
- **Modify** `modal_jobs/04_generate.py` — drop `STYLES_ACTIFS`/`n_per_pair`/inline cartesian; add the module to the Modal image; rewrite `generate_remote` into the top-up loop; rewrite the `generate` entrypoint.
- **Modify** `modal_jobs/test_04_generate.py` — remove `test_styles_actifs_count` (constant deleted); keep the rest.

Conventions (match existing repo Python): French identifiers where the existing file uses them; tests via `importlib`-free direct import for the new module (normal filename), and the existing `_load_module()` helper for `04_generate.py`.

**Test runner shorthand used throughout:** `python -m pytest` from repo root (with the repo venv active).

---

## Task 1: Pure module scaffold + constants

**Files:**
- Create: `modal_jobs/style_allocation.py`
- Test: `modal_jobs/test_style_allocation.py`

- [ ] **Step 1: Write the failing test**

```python
# modal_jobs/test_style_allocation.py
from __future__ import annotations

import textwrap
from pathlib import Path

import pytest

from style_allocation import (
    VALID_STYLES,
    HORS_IA,
    charger_distribution,
    cibles_acceptation,
    paires_pour_manque,
)


def test_valid_styles_is_the_nine() -> None:
    assert VALID_STYLES == {
        "definition_directe", "periphrase", "metonymie", "fonction_role",
        "calembour", "culturel", "cryptique", "cryptique_morphologique",
        "technique",
    }
    assert HORS_IA == {"calembour"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd modal_jobs && "$VENV/bin/python" -m pytest test_style_allocation.py::test_valid_styles_is_the_nine -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'style_allocation'`

(Tests `cd` into `modal_jobs/` so the module imports by plain name.)

- [ ] **Step 3: Write minimal implementation**

```python
# modal_jobs/style_allocation.py
from __future__ import annotations

import math
from pathlib import Path

VALID_STYLES: frozenset[str] = frozenset({
    "definition_directe", "periphrase", "metonymie", "fonction_role",
    "calembour", "culturel", "cryptique", "cryptique_morphologique",
    "technique",
})

# Styles excluded from automatic generation (clue-style-guide-v2.md §4.5).
HORS_IA: frozenset[str] = frozenset({"calembour"})
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd modal_jobs && "$VENV/bin/python" -m pytest test_style_allocation.py::test_valid_styles_is_the_nine -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modal_jobs/style_allocation.py modal_jobs/test_style_allocation.py
git commit -s -m "feat(clue-gen): style-allocation module scaffold + style constants"
```

---

## Task 2: `charger_distribution` — load + validate the YAML config

**Files:**
- Modify: `modal_jobs/style_allocation.py`
- Modify: `modal_jobs/test_style_allocation.py`

Contract: load a YAML file shaped `{styles: {<style>: <weight float>}}`. Validate: every key ∈ `VALID_STYLES`; no `HORS_IA` style with weight > 0; all weights ≥ 0; weights sum to 1.0 ± 0.001. Return `dict[str, float]` containing only styles with weight > 0, sorted by key for determinism. Raise `ValueError` with a specific message on each failure.

- [ ] **Step 1: Write the failing tests**

```python
def _write(tmp_path: Path, body: str) -> Path:
    p = tmp_path / "dist.yaml"
    p.write_text(textwrap.dedent(body), encoding="utf-8")
    return p


def test_charger_distribution_valid(tmp_path: Path) -> None:
    p = _write(tmp_path, """\
        styles:
          definition_directe: 0.217
          periphrase: 0.217
          cryptique: 0.216
          culturel: 0.10
          fonction_role: 0.10
          metonymie: 0.10
          technique: 0.05
    """)
    w = charger_distribution(p)
    assert set(w) == {
        "definition_directe", "periphrase", "cryptique",
        "culturel", "fonction_role", "metonymie", "technique",
    }
    assert abs(sum(w.values()) - 1.0) < 1e-9
    assert list(w) == sorted(w)  # deterministic key order


def test_charger_distribution_drops_zero_weight(tmp_path: Path) -> None:
    p = _write(tmp_path, """\
        styles:
          definition_directe: 0.5
          periphrase: 0.5
          metonymie: 0.0
    """)
    w = charger_distribution(p)
    assert "metonymie" not in w


def test_charger_distribution_bad_sum(tmp_path: Path) -> None:
    p = _write(tmp_path, """\
        styles:
          definition_directe: 0.5
          periphrase: 0.4
    """)
    with pytest.raises(ValueError, match="sum"):
        charger_distribution(p)


def test_charger_distribution_unknown_style(tmp_path: Path) -> None:
    p = _write(tmp_path, """\
        styles:
          definition_directe: 0.5
          blague: 0.5
    """)
    with pytest.raises(ValueError, match="unknown style"):
        charger_distribution(p)


def test_charger_distribution_calembour_nonzero(tmp_path: Path) -> None:
    p = _write(tmp_path, """\
        styles:
          definition_directe: 0.5
          calembour: 0.5
    """)
    with pytest.raises(ValueError, match="hors-IA"):
        charger_distribution(p)


def test_charger_distribution_missing_file(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        charger_distribution(tmp_path / "nope.yaml")
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd modal_jobs && "$VENV/bin/python" -m pytest test_style_allocation.py -k charger -v`
Expected: FAIL — `AttributeError`/`ImportError` (function not defined) or assertion failures.

- [ ] **Step 3: Write the implementation**

Add to `modal_jobs/style_allocation.py`:

```python
import yaml


def charger_distribution(path: Path) -> dict[str, float]:
    if not Path(path).exists():
        raise FileNotFoundError(f"Distribution introuvable : {path}")
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    styles = raw.get("styles")
    if not isinstance(styles, dict) or not styles:
        raise ValueError(f"Clé `styles` manquante ou vide dans {path}")

    weights: dict[str, float] = {}
    for name, w in styles.items():
        if name not in VALID_STYLES:
            raise ValueError(f"unknown style: {name!r}")
        w = float(w)
        if w < 0:
            raise ValueError(f"negative weight for {name!r}: {w}")
        if name in HORS_IA and w > 0:
            raise ValueError(f"{name!r} is hors-IA and must have weight 0")
        if w > 0:
            weights[name] = w

    total = sum(weights.values())
    if abs(total - 1.0) > 1e-3:
        raise ValueError(f"weights must sum to 1.0 (got {total:.4f})")
    return dict(sorted(weights.items()))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd modal_jobs && "$VENV/bin/python" -m pytest test_style_allocation.py -k charger -v`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add modal_jobs/style_allocation.py modal_jobs/test_style_allocation.py
git commit -s -m "feat(clue-gen): charger_distribution loads + validates style YAML"
```

---

## Task 3: `cibles_acceptation` — largest-remainder target counts

**Files:**
- Modify: `modal_jobs/style_allocation.py`
- Modify: `modal_jobs/test_style_allocation.py`

Contract: given `weights: dict[str,float]` and `n_target: int`, return `dict[str,int]` of per-style target *accepted* counts that sum **exactly** to `n_target`, using largest-remainder (Hamilton) apportionment. Ties on remainder broken by style name (ascending) for determinism.

- [ ] **Step 1: Write the failing tests**

```python
def test_cibles_sum_exactly_and_match_example() -> None:
    w = {
        "definition_directe": 0.217, "periphrase": 0.217, "cryptique": 0.216,
        "culturel": 0.10, "fonction_role": 0.10, "metonymie": 0.10,
        "technique": 0.05,
    }
    c = cibles_acceptation(w, 100)
    assert sum(c.values()) == 100
    assert c == {
        "definition_directe": 22, "periphrase": 22, "cryptique": 21,
        "culturel": 10, "fonction_role": 10, "metonymie": 10, "technique": 5,
    }


def test_cibles_sum_exactly_arbitrary() -> None:
    w = {"a": 1 / 3, "b": 1 / 3, "c": 1 / 3}
    # rename to real styles to satisfy nothing in particular; counts only
    w = {"culturel": 1 / 3, "periphrase": 1 / 3, "cryptique": 1 / 3}
    for n in (1, 7, 33, 100, 999):
        c = cibles_acceptation(w, n)
        assert sum(c.values()) == n


def test_cibles_only_positive_styles() -> None:
    w = {"culturel": 0.5, "periphrase": 0.5}
    c = cibles_acceptation(w, 10)
    assert set(c) == {"culturel", "periphrase"}
    assert c == {"culturel": 5, "periphrase": 5}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd modal_jobs && "$VENV/bin/python" -m pytest test_style_allocation.py -k cibles -v`
Expected: FAIL — function not defined.

- [ ] **Step 3: Write the implementation**

Add to `modal_jobs/style_allocation.py`:

```python
def cibles_acceptation(weights: dict[str, float], n_target: int) -> dict[str, int]:
    styles = sorted(weights)
    raw = {s: weights[s] * n_target for s in styles}
    floors = {s: math.floor(raw[s]) for s in styles}
    deficit = n_target - sum(floors.values())
    # largest remainder first; tie-break by style name ascending
    order = sorted(styles, key=lambda s: (-(raw[s] - floors[s]), s))
    for s in order[:deficit]:
        floors[s] += 1
    return {s: floors[s] for s in styles}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd modal_jobs && "$VENV/bin/python" -m pytest test_style_allocation.py -k cibles -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add modal_jobs/style_allocation.py modal_jobs/test_style_allocation.py
git commit -s -m "feat(clue-gen): cibles_acceptation largest-remainder target counts"
```

---

## Task 4: `paires_pour_manque` — per-pass shortfall allocation

**Files:**
- Modify: `modal_jobs/style_allocation.py`
- Modify: `modal_jobs/test_style_allocation.py`

Contract: `paires_pour_manque(cibles, acceptes, lemmes, seed, pass_idx, inflation) -> list[tuple[str,str]]`. For each style `s` (sorted) where `acceptes.get(s,0) < cibles[s]`, request `ceil((cibles[s] - acceptes.get(s,0)) * inflation)` pairs `(lemme, s)`. Lemmas are chosen by striding from a per-`(style,seed)` offset, advanced by `pass_idx`, cycling the lemma list when the request exceeds its length. Return `[]` when no style is short. Deterministic given identical inputs.

- [ ] **Step 1: Write the failing tests**

```python
LEMMES = [f"mot{i}" for i in range(20)]
CIBLES = {"culturel": 10, "technique": 5, "metonymie": 10}


def test_paires_empty_when_targets_met() -> None:
    acceptes = {"culturel": 10, "technique": 5, "metonymie": 10}
    assert paires_pour_manque(CIBLES, acceptes, LEMMES, seed=7, pass_idx=0, inflation=2.0) == []


def test_paires_only_short_styles_and_counts() -> None:
    acceptes = {"culturel": 8, "technique": 5, "metonymie": 0}
    pairs = paires_pour_manque(CIBLES, acceptes, LEMMES, seed=7, pass_idx=0, inflation=2.0)
    by_style = {}
    for _mot, s in pairs:
        by_style[s] = by_style.get(s, 0) + 1
    # culturel short by 2 -> ceil(2*2)=4 ; metonymie short by 10 -> ceil(10*2)=20 ; technique met
    assert by_style == {"culturel": 4, "metonymie": 20}
    assert all(s != "technique" for _m, s in pairs)


def test_paires_all_lemmes_are_valid() -> None:
    acceptes: dict[str, int] = {}
    pairs = paires_pour_manque(CIBLES, acceptes, LEMMES, seed=1, pass_idx=0, inflation=1.0)
    assert all(m in LEMMES for m, _s in pairs)


def test_paires_deterministic_given_seed() -> None:
    a = paires_pour_manque(CIBLES, {}, LEMMES, seed=3, pass_idx=0, inflation=1.0)
    b = paires_pour_manque(CIBLES, {}, LEMMES, seed=3, pass_idx=0, inflation=1.0)
    assert a == b


def test_paires_pass_idx_varies_lemmes() -> None:
    p0 = paires_pour_manque(CIBLES, {}, LEMMES, seed=3, pass_idx=0, inflation=1.0)
    p1 = paires_pour_manque(CIBLES, {}, LEMMES, seed=3, pass_idx=1, inflation=1.0)
    # same shape, different lemma choices across passes
    assert [s for _m, s in p0] == [s for _m, s in p1]
    assert [m for m, _s in p0] != [m for m, _s in p1]
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd modal_jobs && "$VENV/bin/python" -m pytest test_style_allocation.py -k paires -v`
Expected: FAIL — function not defined.

- [ ] **Step 3: Write the implementation**

Add to `modal_jobs/style_allocation.py`:

```python
def paires_pour_manque(
    cibles: dict[str, int],
    acceptes: dict[str, int],
    lemmes: list[str],
    seed: int,
    pass_idx: int,
    inflation: float,
) -> list[tuple[str, str]]:
    n = len(lemmes)
    pairs: list[tuple[str, str]] = []
    for s in sorted(cibles):
        manque = cibles[s] - acceptes.get(s, 0)
        if manque <= 0:
            continue
        req = math.ceil(manque * inflation)
        # deterministic per-(style,seed) base offset, advanced each pass
        base = (abs(hash((s, seed))) + pass_idx * (req + 1)) % n
        for i in range(req):
            pairs.append((lemmes[(base + i) % n], s))
    return pairs
```

Note: `hash()` of a `(str, int)` tuple is process-stable within a run; the tests pass a fixed `seed` and compare within one process, so determinism holds. The offset only needs to (a) spread lemmas and (b) shift across passes — exact lemma identity is not contractual.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd modal_jobs && "$VENV/bin/python" -m pytest test_style_allocation.py -k paires -v`
Expected: PASS

- [ ] **Step 5: Full pure-module test run + commit**

Run: `cd modal_jobs && "$VENV/bin/python" -m pytest test_style_allocation.py -v`
Expected: PASS (all tasks 1–4 tests)

```bash
git add modal_jobs/style_allocation.py modal_jobs/test_style_allocation.py
git commit -s -m "feat(clue-gen): paires_pour_manque per-pass shortfall allocation"
```

---

## Task 5: Commit the target-distribution config

**Files:**
- Create: `modal_jobs/style_distribution.yaml`

- [ ] **Step 1: Write the config**

```yaml
# Target style mix for clue generation (modal_jobs/04_generate.py).
# Weights are fractions of *accepted* (post-filter) clues; must sum to 1.0.
# calembour stays hors-IA (clue-style-guide-v2.md §4.5); omitted styles = 0.
styles:
  definition_directe: 0.217
  periphrase:         0.217
  cryptique:          0.216
  culturel:           0.10
  fonction_role:      0.10
  metonymie:          0.10
  technique:          0.05
```

- [ ] **Step 2: Verify it loads + sums to 1.0**

Run:
```bash
cd modal_jobs && "$VENV/bin/python" -c "from style_allocation import charger_distribution; w=charger_distribution('style_distribution.yaml'); print(sorted(w.items())); print('sum', round(sum(w.values()),6))"
```
Expected: prints the 7 styles and `sum 1.0`.

- [ ] **Step 3: Commit**

```bash
git add modal_jobs/style_distribution.yaml
git commit -s -m "feat(clue-gen): committed target style distribution config"
```

---

## Task 6: Rewrite `generate_remote` into the top-up loop

**Files:**
- Modify: `modal_jobs/04_generate.py`

This task has no local unit test (it requires a GPU + the Modal runtime). Verify by reading and by a syntax/import check; the real exercise is a Modal run (Task 9, manual).

- [ ] **Step 1: Add the allocation module to the Modal image**

In the `image = (...)` chain (currently ends after `add_local_dir(... pipeline_v2 ...)`), add:

```python
    .add_local_file(
        str(ROOT / "modal_jobs" / "style_allocation.py"),
        remote_path="/root/style_allocation.py",
        copy=True,
    )
```

- [ ] **Step 2: Remove the obsolete module-level constant**

Delete the `STYLES_ACTIFS = [...]` block (lines ~14–20). Keep `USER_PROMPT_TEMPLATE` and `construire_prompt`.

- [ ] **Step 3: Replace the `generate_remote` signature + body**

Change the signature to receive `cibles` + loop params instead of `n_per_pair`:

```python
def generate_remote(
    run_tag: str,
    round_n: int,
    cibles: dict,
    lemmes: list[str],
    inflation: float,
    max_passes: int,
    seed: int,
    source_batch: str,
) -> dict:
```

Inside, after the existing model-load block and `from pipeline_v2.run_pipeline import traiter_ligne`, add:

```python
    from style_allocation import paires_pour_manque
```

Replace the old `pairs = [...]; requested = ...; for mot, style in pairs:` section through the end of the accept loop with the top-up loop:

```python
    generated_at = dt.datetime.now(dt.timezone.utc).isoformat()
    source_tag = "synthetic_v1"

    accepted: list[dict] = []
    accepted_by_style: Counter[str] = Counter()
    requested_by_style: Counter[str] = Counter()
    dropped_by_filter: Counter[str] = Counter()
    n_returned = 0
    passes = 0

    for p in range(max_passes):
        pending = paires_pour_manque(
            cibles, dict(accepted_by_style), lemmes, seed, p, inflation,
        )
        if not pending:
            break
        passes = p + 1
        for mot, style in pending:
            requested_by_style[style] += 1
            prompt = construire_prompt(mot, style)
            messages = [{"role": "user", "content": prompt}]
            inputs = tokenizer.apply_chat_template(
                messages, add_generation_prompt=True, return_tensors="pt",
            ).to(model.device)
            with torch.no_grad():
                outputs = model.generate(
                    inputs,
                    max_new_tokens=30,
                    do_sample=True,
                    temperature=0.8,
                    top_p=0.95,
                    num_return_sequences=1,
                    pad_token_id=tokenizer.eos_token_id,
                )
            seq = outputs[0]
            raw = tokenizer.decode(
                seq[inputs.shape[1]:], skip_special_tokens=True,
            ).strip()
            text = re.split(r"(?<=[.!?])\s+", raw, maxsplit=1)[0].rstrip(".!?").strip()
            n_returned += 1
            candidate = {
                "mot": mot, "definition": text, "pos": "autre",
                "categorie": "autre", "style": style, "force": "3",
                "longueur": str(len(mot)), "source": source_tag, "meta": "",
            }
            verdict = traiter_ligne(candidate)
            if verdict["pipeline_status"] == "reject":
                first_reason = verdict["pipeline_reasons"].split(";")[0]
                filter_id = first_reason.split(":")[0].strip() or "unknown"
                dropped_by_filter[filter_id] += 1
                continue
            if accepted_by_style[style] >= cibles[style]:
                continue  # target met for this style; don't overshoot
            accepted.append({
                "mot": mot, "definition": text, "pos": "autre",
                "categorie": "autre", "style": style, "force_estimated": 3,
                "longueur": len(mot), "source": source_tag,
                "source_batch": source_batch, "generated_at": generated_at,
            })
            accepted_by_style[style] += 1
```

- [ ] **Step 4: Replace the summary block**

Replace the `summary = {...}` dict and the `candidates.jsonl`/`summary.json` writes' summary with the new fields (keep the file-writing code that dumps `accepted`):

```python
    shortfall_by_style = {
        s: cibles[s] - accepted_by_style.get(s, 0)
        for s in cibles
        if accepted_by_style.get(s, 0) < cibles[s]
    }
    summary = {
        "passes": passes,
        "generated": n_returned,
        "pipeline_v2_passed": len(accepted),
        "target_by_style": dict(cibles),
        "accepted_by_style": dict(accepted_by_style),
        "requested_by_style": dict(requested_by_style),
        "shortfall_by_style": shortfall_by_style,
        "dropped_by_filter": dict(dropped_by_filter),
    }
```

- [ ] **Step 5: Import-check the module (no GPU needed)**

The `@app.function` decorator and `generate_remote` body reference torch/transformers only at call time, but the file must still import. Verify:

Run from repo root: `python -c "import importlib.util,sys; s=importlib.util.spec_from_file_location('m','modal_jobs/04_generate.py'); m=importlib.util.module_from_spec(s); sys.modules['m']=m; s.loader.exec_module(m); print('import OK', hasattr(m,'generate_remote'))"`
Expected: `import OK True`

- [ ] **Step 6: Commit**

```bash
git add modal_jobs/04_generate.py
git commit -s -m "feat(clue-gen): adaptive top-up loop targeting accepted style mix"
```

---

## Task 7: Rewrite the `generate` local entrypoint

**Files:**
- Modify: `modal_jobs/04_generate.py`

- [ ] **Step 1: Replace the entrypoint signature + body**

```python
@app.local_entrypoint()
def generate(
    run_tag: str = "mistral-nemo-pilot-v1",
    round: int = 1,
    lemmas: str = "data/curated/round_1_lemmas.csv",
    style_config: str = "modal_jobs/style_distribution.yaml",
    n_target: int = 0,
    inflation: float = 2.0,
    max_passes: int = 5,
    seed: int = 1234,
) -> None:
    import uuid

    sys.path.insert(0, str(ROOT / "modal_jobs"))
    from style_allocation import charger_distribution, cibles_acceptation

    lemmes_path = ROOT / lemmas if not Path(lemmas).is_absolute() else Path(lemmas)
    lemmes = charger_lemmes(lemmes_path)

    cfg_path = ROOT / style_config if not Path(style_config).is_absolute() else Path(style_config)
    weights = charger_distribution(cfg_path)

    target = n_target if n_target > 0 else len(lemmes)
    cibles = cibles_acceptation(weights, target)
    source_batch = f"{run_tag}-r{round}-{uuid.uuid4().hex[:8]}"

    print(f"[LOCAL] run_tag      : {run_tag}")
    print(f"[LOCAL] round        : {round}")
    print(f"[LOCAL] lemmes       : {len(lemmes)} (depuis {lemmes_path})")
    print(f"[LOCAL] n_target     : {target} (accepted clues)")
    print(f"[LOCAL] inflation    : {inflation}   max_passes : {max_passes}")
    print(f"[LOCAL] source_batch : {source_batch}")
    print("[LOCAL] plan (target accepted by style) :")
    for s, c in sorted(cibles.items(), key=lambda kv: -kv[1]):
        print(f"          {s:26s} {c}")

    summary = generate_remote.remote(
        run_tag=run_tag,
        round_n=round,
        cibles=cibles,
        lemmes=lemmes,
        inflation=inflation,
        max_passes=max_passes,
        seed=seed,
        source_batch=source_batch,
    )

    print()
    print("=" * 60)
    print("RÉCAP GÉNÉRATION")
    print("=" * 60)
    print(f"Passes                     : {summary['passes']}")
    print(f"Générés                    : {summary['generated']}")
    print(f"Acceptés (pipeline_v2 OK)  : {summary['pipeline_v2_passed']}")
    print("Acceptés par style :")
    for s, n in sorted(summary["accepted_by_style"].items(), key=lambda kv: -kv[1]):
        tgt = summary["target_by_style"].get(s, 0)
        print(f"  {s:26s} {n}/{tgt}")
    if summary["shortfall_by_style"]:
        print("Manque (cible non atteinte) :")
        for s, n in sorted(summary["shortfall_by_style"].items(), key=lambda kv: -kv[1]):
            print(f"  {s:26s} -{n}")
    if summary["dropped_by_filter"]:
        print("Drops par filtre :")
        for fid, n in sorted(summary["dropped_by_filter"].items(), key=lambda kv: -kv[1]):
            print(f"  {fid:35s} {n}")
```

- [ ] **Step 2: Import-check again**

Run from repo root: `python -c "import importlib.util,sys; s=importlib.util.spec_from_file_location('m','modal_jobs/04_generate.py'); m=importlib.util.module_from_spec(s); sys.modules['m']=m; s.loader.exec_module(m); print('import OK')"`
Expected: `import OK`

- [ ] **Step 3: Commit**

```bash
git add modal_jobs/04_generate.py
git commit -s -m "feat(clue-gen): distribution-driven generate entrypoint with plan print"
```

---

## Task 8: Update the existing `04_generate` tests

**Files:**
- Modify: `modal_jobs/test_04_generate.py`

- [ ] **Step 1: Remove the obsolete constant test**

Delete `test_styles_actifs_count` (lines ~33–39) — `STYLES_ACTIFS` no longer exists. Leave `test_construire_prompt_format` and the four `charger_lemmes` tests unchanged.

- [ ] **Step 2: Run the file + full suite**

Run from repo root:
```bash
python -m pytest modal_jobs/ -v
```
Expected: PASS — `test_04_generate.py` (5 tests) + `test_style_allocation.py` (all). Zero failures, no reference to `STYLES_ACTIFS`.

- [ ] **Step 3: Commit**

```bash
git add modal_jobs/test_04_generate.py
git commit -s -m "test(clue-gen): drop STYLES_ACTIFS test after distribution rewrite"
```

---

## Task 9: Manual smoke (maintainer, not CI) — optional pre-merge

Not an automated step; documents how the maintainer validates end-to-end before relying on a real batch. Safe to skip for the PR; required before trusting generated output.

- [ ] Dry intent check: `"$VENV/bin/python" -m pytest modal_jobs/ -q` is green.
- [ ] Real run (spends GPU): `modal run modal_jobs/04_generate.py::generate --lemmas data/curated/round_1_lemmas.csv --n-target 100`
- [ ] Inspect the printed RÉCAP: `accepted_by_style` should track `target_by_style`; note any `shortfall_by_style` (expected for `technique`/`metonymie` if the model can't produce them yet — that is the signal to rate + retrain).

---

## Self-Review

**Spec coverage:**
- Committed YAML config → Task 5. ✓
- Pure allocation helpers (`cibles_acceptation`, `paires_pour_manque`) → Tasks 3, 4. ✓ (Refinement: in `style_allocation.py`, not `04_generate.py`, to decouple from `import modal` — rationale in Architecture.)
- Largest-remainder exact sum → Task 3. ✓
- Adaptive top-up loop inside `generate_remote`, `do_sample=True`, no-overshoot cap → Task 6. ✓
- Validation (sum, unknown style, calembour>0) before GPU spend → Task 2 (+ entrypoint loads it pre-`.remote()` in Task 7). ✓
- Default `n_target = len(lemmes)`, plan print, no confirm prompt → Task 7. ✓
- Summary fields `target/accepted/requested/shortfall_by_style` + `passes` → Tasks 6, 7. ✓
- Out-of-scope items (survey sampler, gold tagging) → untouched. ✓

**Placeholder scan:** none — every code step shows complete code.

**Type consistency:** `cibles` is `dict[str,int]` throughout (Tasks 3, 6, 7); `weights` is `dict[str,float]` (Tasks 2, 3, 7); `paires_pour_manque` returns `list[tuple[str,str]]` consumed as `(mot, style)` in Task 6. `charger_distribution`/`cibles_acceptation`/`paires_pour_manque` names match across tasks. `n_per_pair` fully removed (Tasks 6, 7) — no lingering references.
