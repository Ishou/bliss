# Survey Accent Preservation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve French diacritics end-to-end from DBnary parsing through lemma curation, generation, and `survey_items` into `/sondage`, so the maintainer rates `SOLDÉ adjectif` (not `SOLDE adjectif`) when the actual word is `soldé`.

**Architecture:** Two producer-side edits + one backfill script. `build_pos_lemmas.py` recovers the canonical accented lemma from DBnary instead of passing the input verbatim, so even ASCII-only input lists produce accented output. A one-shot Python migration (`backfill_accents.py`) walks `survey_items` and rewrites `mot` from ASCII to accented uppercase via the same DBnary lookup. The StyleGuide CSV parser/writer already round-trip accents via NFC normalization — no Kotlin code changes. The ASCII grid-cell boundary in `bliss-worker export-words` (via `CsvWordRepository.foldToAscii`) is unchanged.

**Tech Stack:** Python 3.12 (`unicodedata`, `csv`, `psycopg`), pytest + testcontainers for migration tests, the existing DBnary parser output at `data/dbnary/dbnary_fr.csv`.

---

## Spec reference

This plan implements `docs/superpowers/specs/2026-06-01-survey-accent-preservation-design.md`. Re-read the spec's "Data model" + "Producer-side changes" + "Migration" sections before starting — they cover the *why* this plan doesn't repeat.

## File Structure

**Modified:**
- `scripts/clue_generation/build_pos_lemmas.py` — `load_dbnary` returns both POS counts and the canonical accented lemma; `main` emits the canonical lemma uppercased.

**Created:**
- `scripts/clue_generation/test_build_pos_lemmas.py` — unit tests for the curation script (none exist today).
- `scripts/survey/backfill_accents.py` — one-shot migration script, mirrors `backfill_campaigns.py` shape.
- `scripts/survey/test_backfill_accents.py` — testcontainer integration tests, mirrors `test_backfill_campaigns.py` shape.

**Touched (test-only):**
- `survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt` — adds an accented-`mot` round-trip case to lock the existing accent-preserving behavior against regression.

**Unchanged (verified during brainstorm):**
- `survey/application/src/main/kotlin/com/bliss/survey/application/csv/StyleGuideCsvParser.kt` — already NFC-normalizes `mot`; passes accents through verbatim.
- `survey/application/src/main/kotlin/com/bliss/survey/application/csv/StyleGuideCsvWriter.kt` — same.
- `modal_jobs/04_generate_command_r.py` — prompt template substitutes `{mot}` from the lemma file; the file's contents change, the script doesn't.
- `survey_items` schema — `mot TEXT NOT NULL` with no CHECK constraint, accented uppercase already passes.
- `grid/infrastructure/.../CsvWordRepository.kt` — `foldToAscii` stays exactly as-is; grid wordlist remains ASCII.

---

## Task 1: Test fixture for `build_pos_lemmas`

**Files:**
- Create: `scripts/clue_generation/test_build_pos_lemmas.py`

- [ ] **Step 1: Create the test file with the fixture scaffolding**

```python
"""Unit tests for build_pos_lemmas — DBnary lookup + accent preservation."""

from __future__ import annotations

import csv
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

import build_pos_lemmas as bpl  # noqa: E402


def _write_dbnary(tmp_path: Path, rows: list[dict[str, str]]) -> Path:
    """Build a minimal data/dbnary/dbnary_fr.csv with the columns load_dbnary reads."""
    path = tmp_path / "dbnary_fr.csv"
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["lemma", "pos", "definition"])
        w.writeheader()
        w.writerows(rows)
    return path


def _write_words(tmp_path: Path, words: list[str]) -> Path:
    """Build a minimal --words input: one mot per line, with a header line."""
    path = tmp_path / "words.csv"
    path.write_text("mot\n" + "\n".join(words) + "\n", encoding="utf-8")
    return path


def _read_out(path: Path) -> list[tuple[str, str]]:
    """Read the output CSV back into a list of (mot, pos) tuples."""
    with path.open(encoding="utf-8") as f:
        reader = csv.reader(f, delimiter=";")
        next(reader)  # header
        return [(row[0], row[1]) for row in reader]
```

- [ ] **Step 2: Verify the test file imports cleanly**

Run: `python3 -m pytest scripts/clue_generation/test_build_pos_lemmas.py --collect-only -q`
Expected: `0 tests collected` (file is just scaffolding, no tests yet).

- [ ] **Step 3: Commit the scaffolding**

```bash
git add scripts/clue_generation/test_build_pos_lemmas.py
git commit -s -m "test(clue-gen): scaffold build_pos_lemmas test fixture"
```

---

## Task 2: Failing test — accented DBnary lemma is preserved in output

**Files:**
- Modify: `scripts/clue_generation/test_build_pos_lemmas.py`

- [ ] **Step 1: Add the failing test**

Append to `test_build_pos_lemmas.py`:

```python
def test_emits_accented_lemma_from_dbnary(tmp_path, monkeypatch):
    """ASCII input `SOLDE adjectif` recovers DBnary's `soldé` and emits `SOLDÉ;adjectif`."""
    dbnary = _write_dbnary(
        tmp_path,
        [
            {"lemma": "solde", "pos": "noun", "definition": "Excédent comptable."},
            {"lemma": "soldé", "pos": "adjective", "definition": "Vendu au rabais."},
        ],
    )
    words = _write_words(tmp_path, ["SOLDE"])
    out = tmp_path / "out.csv"

    monkeypatch.setattr(
        sys, "argv",
        ["build_pos_lemmas.py",
         "--words", str(words),
         "--dbnary", str(dbnary),
         "--out", str(out),
         "--multi-pos-fraction", "1.0"],
    )
    assert bpl.main() == 0

    rows = _read_out(out)
    assert ("SOLDE", "nom_commun") in rows
    assert ("SOLDÉ", "adjectif") in rows
```

- [ ] **Step 2: Run the test, confirm it fails**

Run: `python3 -m pytest scripts/clue_generation/test_build_pos_lemmas.py::test_emits_accented_lemma_from_dbnary -v`
Expected: FAIL. The current `main()` writes the input `mot` ("SOLDE") verbatim, so the assertion on `("SOLDÉ", "adjectif")` fails — the output contains `("SOLDE", "adjectif")` instead.

---

## Task 3: Implement accent preservation in `build_pos_lemmas`

**Files:**
- Modify: `scripts/clue_generation/build_pos_lemmas.py`

- [ ] **Step 1: Update `load_dbnary` to also return the canonical accented lemma**

Replace lines 30–45 of `build_pos_lemmas.py`:

```python
# {stripped lemma: ({enum_pos: non-obsolete sense count}, canonical_lemma)}.
# canonical_lemma is the first accented variant we see for the stripped key
# (alphabetical tie-break on subsequent variants is fine — collisions are rare).
def load_dbnary(path: Path) -> dict[str, tuple[dict[str, int], str]]:
    db: dict[str, tuple[dict[str, int], str]] = {}
    with path.open() as f:
        for row in csv.DictReader(f):
            pos = row["pos"].strip()
            if pos not in DBNARY_TO_ENUM:
                continue
            senses = [s for s in (row.get("definition") or "").split("|") if s.strip()]
            live = [s for s in senses if not _OBSOLETE.search(s)]
            if senses and not live:
                continue
            stripped = _strip(row["lemma"])
            counts, canonical = db.get(stripped, ({}, row["lemma"]))
            # Deterministic tie-break: keep the alphabetically-first lemma when stripped keys collide.
            if row["lemma"] < canonical:
                canonical = row["lemma"]
            enum_pos = DBNARY_TO_ENUM[pos]
            counts[enum_pos] = counts.get(enum_pos, 0) + (len(live) or 1)
            db[stripped] = (counts, canonical)
    return db
```

- [ ] **Step 2: Update `main` to emit the canonical accented lemma uppercased**

Replace the loop body inside `main` (lines 75–88 of the current file). Locate this block:

```python
    rows: list[tuple[str, str]] = []
    uncovered: list[str] = []
    for mot in words:
        counts = db.get(_strip(mot), {})
        if not counts:
            uncovered.append(mot)
            continue
        # rank by sense-count desc (dominant POS first), tie-break by name
        ranked = sorted(counts, key=lambda p: (-counts[p], p))
        # most multi-POS words collapse to their dominant POS (breadth); a fraction keep all (variety).
        if len(ranked) > 1 and rng.random() >= args.multi_pos_fraction:
            ranked = ranked[:1]
        for pos in sorted(ranked):
            rows.append((mot, pos))
```

Replace with:

```python
    rows: list[tuple[str, str]] = []
    uncovered: list[str] = []
    for mot in words:
        entry = db.get(_strip(mot))
        if entry is None:
            uncovered.append(mot)
            continue
        counts, canonical = entry
        # The DBnary canonical lemma carries the accents the input mot may have lost.
        emitted_mot = canonical.upper()
        # rank by sense-count desc (dominant POS first), tie-break by name
        ranked = sorted(counts, key=lambda p: (-counts[p], p))
        # most multi-POS words collapse to their dominant POS (breadth); a fraction keep all (variety).
        if len(ranked) > 1 and rng.random() >= args.multi_pos_fraction:
            ranked = ranked[:1]
        for pos in sorted(ranked):
            rows.append((emitted_mot, pos))
```

- [ ] **Step 3: Run the test, confirm it now passes**

Run: `python3 -m pytest scripts/clue_generation/test_build_pos_lemmas.py::test_emits_accented_lemma_from_dbnary -v`
Expected: PASS.

---

## Task 4: Tests for the edge cases

**Files:**
- Modify: `scripts/clue_generation/test_build_pos_lemmas.py`

- [ ] **Step 1: Add the three remaining tests**

Append to `test_build_pos_lemmas.py`:

```python
def test_accent_free_word_unchanged(tmp_path, monkeypatch):
    """`pain` has no accent in DBnary; output stays `PAIN`."""
    dbnary = _write_dbnary(
        tmp_path,
        [{"lemma": "pain", "pos": "noun", "definition": "Aliment de boulangerie."}],
    )
    words = _write_words(tmp_path, ["PAIN"])
    out = tmp_path / "out.csv"
    monkeypatch.setattr(
        sys, "argv",
        ["build_pos_lemmas.py", "--words", str(words), "--dbnary", str(dbnary), "--out", str(out)],
    )
    assert bpl.main() == 0
    assert _read_out(out) == [("PAIN", "nom_commun")]


def test_accented_input_passes_through(tmp_path, monkeypatch):
    """When the input is already `SOLDÉ`, the stripped lookup still matches DBnary."""
    dbnary = _write_dbnary(
        tmp_path,
        [{"lemma": "soldé", "pos": "adjective", "definition": "Vendu au rabais."}],
    )
    words = _write_words(tmp_path, ["SOLDÉ"])
    out = tmp_path / "out.csv"
    monkeypatch.setattr(
        sys, "argv",
        ["build_pos_lemmas.py", "--words", str(words), "--dbnary", str(dbnary), "--out", str(out)],
    )
    assert bpl.main() == 0
    assert _read_out(out) == [("SOLDÉ", "adjectif")]


def test_unknown_word_reported_to_stderr(tmp_path, monkeypatch, capsys):
    """A word with no DBnary match lands in the uncovered list and is logged."""
    dbnary = _write_dbnary(tmp_path, [])
    words = _write_words(tmp_path, ["XYZQZW"])
    out = tmp_path / "out.csv"
    monkeypatch.setattr(
        sys, "argv",
        ["build_pos_lemmas.py", "--words", str(words), "--dbnary", str(dbnary), "--out", str(out)],
    )
    assert bpl.main() == 0
    assert _read_out(out) == []
    err = capsys.readouterr().err
    assert "uncovered" in err
    assert "XYZQZW" in err
```

- [ ] **Step 2: Run the tests, confirm all four pass**

Run: `python3 -m pytest scripts/clue_generation/test_build_pos_lemmas.py -v`
Expected: 4 passed.

- [ ] **Step 3: Commit**

```bash
git add scripts/clue_generation/build_pos_lemmas.py scripts/clue_generation/test_build_pos_lemmas.py
git commit -s -m "feat(clue-gen): emit DBnary-canonical accented lemma from build_pos_lemmas"
```

---

## Task 5: Backfill script scaffolding

**Files:**
- Create: `scripts/survey/backfill_accents.py`

- [ ] **Step 1: Create the script with its argument surface**

```python
#!/usr/bin/env python3
"""Backfill survey_items.mot from ASCII to accented uppercase via DBnary lookup."""

from __future__ import annotations

import argparse
import csv
import sys
import unicodedata
from pathlib import Path
from typing import Iterable

import psycopg


# Mirrors build_pos_lemmas._strip — keep in sync.
def _strip(s: str) -> str:
    return "".join(
        c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn"
    ).lower()


# DBnary POS -> survey enum, same mapping as build_pos_lemmas.
DBNARY_TO_ENUM = {
    "noun": "NOM_COMMUN",
    "properNoun": "NOM_PROPRE",
    "adjective": "ADJECTIF",
    "verb": "VERBE_INFINITIF",
    "adverb": "ADVERBE",
}


def load_lookup(dbnary_path: Path) -> dict[tuple[str, str], str]:
    """Build {(stripped_lemma, survey_pos_enum): accented_lemma} from DBnary."""
    lookup: dict[tuple[str, str], str] = {}
    with dbnary_path.open(encoding="utf-8") as f:
        for row in csv.DictReader(f):
            pos_dbnary = row["pos"].strip()
            if pos_dbnary not in DBNARY_TO_ENUM:
                continue
            survey_pos = DBNARY_TO_ENUM[pos_dbnary]
            key = (_strip(row["lemma"]), survey_pos)
            # Alphabetical tie-break — matches build_pos_lemmas.
            existing = lookup.get(key)
            if existing is None or row["lemma"] < existing:
                lookup[key] = row["lemma"]
    return lookup


def backfill(conn, lookup: dict[tuple[str, str], str], dry_run: bool = False) -> dict:
    """Walk survey_items, UPDATE mot for rows whose (stripped, pos) is in lookup.
    Returns a summary dict {'updated': N, 'skipped': N, 'already': N}.
    """
    updated = 0
    skipped = 0
    already = 0
    with conn.cursor() as cur:
        cur.execute("SELECT item_id, mot, pos FROM survey_items WHERE retired_at IS NULL")
        rows = cur.fetchall()
    for item_id, mot, pos in rows:
        key = (_strip(mot), pos)
        canonical = lookup.get(key)
        if canonical is None:
            print(f"skip (no DBnary match): {mot} ({pos}) item_id={item_id}", file=sys.stderr)
            skipped += 1
            continue
        target = canonical.upper()
        if target == mot:
            already += 1
            continue
        if not dry_run:
            with conn.cursor() as cur:
                cur.execute(
                    "UPDATE survey_items SET mot = %s WHERE item_id = %s",
                    (target, item_id),
                )
        print(f"{'(dry) ' if dry_run else ''}{mot} -> {target} ({pos}) item_id={item_id}", file=sys.stderr)
        updated += 1
    if not dry_run:
        conn.commit()
    return {"updated": updated, "skipped": skipped, "already": already}


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dsn", required=True, help="libpq DSN for survey-api Postgres")
    p.add_argument("--dbnary", type=Path, default=Path("data/dbnary/dbnary_fr.csv"))
    p.add_argument("--dry-run", action="store_true", help="Print updates without applying.")
    args = p.parse_args()

    lookup = load_lookup(args.dbnary)
    with psycopg.connect(args.dsn) as conn:
        summary = backfill(conn, lookup, dry_run=args.dry_run)
    print(f"updated={summary['updated']} skipped={summary['skipped']} already={summary['already']}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Confirm the script parses + imports cleanly**

Run: `python3 -c "import sys; sys.path.insert(0, 'scripts/survey'); import backfill_accents; print(backfill_accents.DBNARY_TO_ENUM)"`
Expected: prints `{'noun': 'NOM_COMMUN', 'properNoun': 'NOM_PROPRE', ...}`.

---

## Task 6: Test fixture for backfill (Testcontainers Postgres)

**Files:**
- Create: `scripts/survey/test_backfill_accents.py`

- [ ] **Step 1: Create the test fixture scaffolding**

```python
"""Integration tests for backfill_accents; requires Docker (Testcontainers)."""

from __future__ import annotations

import csv
import sys
import uuid as _uuid
from pathlib import Path

import psycopg
import pytest
from testcontainers.postgres import PostgresContainer

sys.path.insert(0, str(Path(__file__).parent))

import backfill_accents as ba  # noqa: E402

_REPO_ROOT = Path(__file__).resolve().parents[2]
_MIGRATIONS = _REPO_ROOT / "survey/infrastructure/src/main/resources/db/migration"


def _docker_available() -> bool:
    try:
        import docker  # type: ignore[import-untyped]

        docker.from_env().ping()
    except Exception:
        return False
    return True


pytestmark = pytest.mark.skipif(not _docker_available(), reason="Docker daemon not available")


@pytest.fixture(scope="module")
def pg():
    with PostgresContainer("postgres:16-alpine") as p:
        with psycopg.connect(p.get_connection_url(driver=None), autocommit=True) as c:
            with c.cursor() as cur:
                cur.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")
                for migration in sorted(_MIGRATIONS.glob("V*.sql")):
                    cur.execute(migration.read_text())
        yield p


@pytest.fixture
def conn(pg):
    with psycopg.connect(pg.get_connection_url(driver=None), autocommit=False) as c:
        yield c
        with c.cursor() as cur:
            cur.execute("TRUNCATE campaigns, ratings, pair_ratings, survey_items CASCADE")
        c.commit()


def _dbnary(tmp_path: Path, rows: list[dict[str, str]]) -> Path:
    path = tmp_path / "dbnary_fr.csv"
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["lemma", "pos", "definition"])
        w.writeheader()
        w.writerows(rows)
    return path


def _insert_item(conn, mot: str, pos: str = "ADJECTIF") -> _uuid.UUID:
    item_id = _uuid.uuid4()
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO survey_items
              (item_id, mot, definition, pos, categorie, style,
               force_claimed, longueur, source, source_batch, tier)
            VALUES (%s, %s, 'def', %s, 'AUTRE', 'DEFINITION_DIRECTE',
                    1, length(%s), 'SYNTHETIC_V1', 'test', 'MID')
            """,
            (item_id, mot, pos, mot),
        )
    conn.commit()
    return item_id


def _fetch_mot(conn, item_id: _uuid.UUID) -> str:
    with conn.cursor() as cur:
        cur.execute("SELECT mot FROM survey_items WHERE item_id = %s", (item_id,))
        return cur.fetchone()[0]
```

- [ ] **Step 2: Confirm the test file collects (0 tests yet)**

Run: `python3 -m pytest scripts/survey/test_backfill_accents.py --collect-only -q`
Expected: `0 tests collected` (just fixtures, no tests yet).

- [ ] **Step 3: Commit script + test scaffolding**

```bash
git add scripts/survey/backfill_accents.py scripts/survey/test_backfill_accents.py
git commit -s -m "feat(survey): backfill_accents skeleton + testcontainer fixtures"
```

---

## Task 7: Backfill — recover accents on an ASCII row

**Files:**
- Modify: `scripts/survey/test_backfill_accents.py`

- [ ] **Step 1: Add the happy-path test**

Append:

```python
def test_backfill_recovers_accents_from_dbnary(conn, tmp_path):
    """An ASCII `SOLDE adjectif` row gets rewritten to `SOLDÉ`."""
    item_id = _insert_item(conn, "SOLDE", pos="ADJECTIF")
    dbnary = _dbnary(
        tmp_path,
        [{"lemma": "soldé", "pos": "adjective", "definition": "Vendu au rabais."}],
    )
    lookup = ba.load_lookup(dbnary)

    summary = ba.backfill(conn, lookup)
    assert summary == {"updated": 1, "skipped": 0, "already": 0}
    assert _fetch_mot(conn, item_id) == "SOLDÉ"
```

- [ ] **Step 2: Run, expect pass (Docker required)**

Run: `python3 -m pytest scripts/survey/test_backfill_accents.py::test_backfill_recovers_accents_from_dbnary -v`
Expected: PASS (or SKIPPED if Docker isn't available; that's fine — CI runs it).

---

## Task 8: Backfill — non-recoverable row stays as-is, logs to stderr

**Files:**
- Modify: `scripts/survey/test_backfill_accents.py`

- [ ] **Step 1: Add the skip test**

Append:

```python
def test_backfill_skips_when_no_dbnary_match(conn, tmp_path, capsys):
    """A row whose mot+pos has no DBnary entry is left unchanged and logged."""
    item_id = _insert_item(conn, "XYZQZW", pos="NOM_COMMUN")
    dbnary = _dbnary(tmp_path, [])
    lookup = ba.load_lookup(dbnary)

    summary = ba.backfill(conn, lookup)
    assert summary == {"updated": 0, "skipped": 1, "already": 0}
    assert _fetch_mot(conn, item_id) == "XYZQZW"
    err = capsys.readouterr().err
    assert "no DBnary match" in err
    assert "XYZQZW" in err
```

- [ ] **Step 2: Run, expect pass**

Run: `python3 -m pytest scripts/survey/test_backfill_accents.py::test_backfill_skips_when_no_dbnary_match -v`
Expected: PASS.

---

## Task 9: Backfill — already-accented row is a no-op (idempotency)

**Files:**
- Modify: `scripts/survey/test_backfill_accents.py`

- [ ] **Step 1: Add the idempotency test**

Append:

```python
def test_backfill_is_idempotent(conn, tmp_path):
    """Running twice on a recovered row counts as 'already' the second time."""
    item_id = _insert_item(conn, "SOLDE", pos="ADJECTIF")
    dbnary = _dbnary(
        tmp_path,
        [{"lemma": "soldé", "pos": "adjective", "definition": "Vendu au rabais."}],
    )
    lookup = ba.load_lookup(dbnary)

    first = ba.backfill(conn, lookup)
    assert first == {"updated": 1, "skipped": 0, "already": 0}
    assert _fetch_mot(conn, item_id) == "SOLDÉ"

    second = ba.backfill(conn, lookup)
    assert second == {"updated": 0, "skipped": 0, "already": 1}
    assert _fetch_mot(conn, item_id) == "SOLDÉ"
```

- [ ] **Step 2: Run, expect pass**

Run: `python3 -m pytest scripts/survey/test_backfill_accents.py::test_backfill_is_idempotent -v`
Expected: PASS.

---

## Task 10: Backfill — `--dry-run` doesn't mutate

**Files:**
- Modify: `scripts/survey/test_backfill_accents.py`

- [ ] **Step 1: Add the dry-run test**

Append:

```python
def test_backfill_dry_run_does_not_mutate(conn, tmp_path):
    """dry_run=True reports the update but leaves the row alone."""
    item_id = _insert_item(conn, "SOLDE", pos="ADJECTIF")
    dbnary = _dbnary(
        tmp_path,
        [{"lemma": "soldé", "pos": "adjective", "definition": "Vendu au rabais."}],
    )
    lookup = ba.load_lookup(dbnary)

    summary = ba.backfill(conn, lookup, dry_run=True)
    assert summary == {"updated": 1, "skipped": 0, "already": 0}
    assert _fetch_mot(conn, item_id) == "SOLDE"  # unchanged
```

- [ ] **Step 2: Run, expect pass**

Run: `python3 -m pytest scripts/survey/test_backfill_accents.py::test_backfill_dry_run_does_not_mutate -v`
Expected: PASS.

- [ ] **Step 3: Run full backfill test suite to confirm all four pass together**

Run: `python3 -m pytest scripts/survey/test_backfill_accents.py -v`
Expected: 4 passed (or 4 skipped if Docker isn't available locally).

- [ ] **Step 4: Commit backfill tests**

```bash
git add scripts/survey/test_backfill_accents.py
git commit -s -m "test(survey): cover backfill_accents happy/skip/idempotent/dry-run"
```

---

## Task 11: Round-trip regression — confirm StyleGuide CSV preserves accents on `mot`

**Files:**
- Modify: `survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt`

The parser + writer are already accent-preserving (NFC normalization). This task adds a fixture row to lock that against future regression — no production code change.

- [ ] **Step 1: Read the existing round-trip test**

Run: `head -120 survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt`
Expected: a `@Test` annotated method with a fixture row + parse-then-write round-trip assertion.

- [ ] **Step 2: Add an accented-mot test case**

Inside the test class (after the existing tests), add:

```kotlin
@Test
fun `mot column round-trips an accented uppercase French word`() {
    val row = "SOLDÉ;Vendu au rabais.;adjectif;autre;définition_directe;1;5;synthetic_v1;1.0;"
    val parsed = parser.parseRow(row)
    assertThat(parsed.mot).isEqualTo("SOLDÉ")

    val written = writer.toRow(parsed, emptyMap())
    val reparsed = parser.parseRow(written)
    assertThat(reparsed.mot).isEqualTo("SOLDÉ")
}
```

(If `parser` and `writer` fields don't already exist on the class, mirror whatever naming the existing tests use.)

- [ ] **Step 3: Run the test, confirm it passes (no production change needed)**

Run: `./gradlew :survey:application:test --tests '*StyleGuideCsvRoundTrip*' -i 2>&1 | tail -20`
Expected: all tests pass, including the new one.

- [ ] **Step 4: Commit**

```bash
git add survey/application/src/test/kotlin/com/bliss/survey/application/csv/StyleGuideCsvRoundTripTest.kt
git commit -s -m "test(survey-application): lock StyleGuide CSV accent preservation for mot column"
```

---

## Task 12: Open the PR

**Files:** none (workflow step).

- [ ] **Step 1: Push the branch**

Run:

```bash
git push -u origin HEAD:feat/survey-accent-preservation
```

Expected: prints the GitHub URL for opening a PR.

- [ ] **Step 2: Open the PR via `gh`**

```bash
gh pr create --title "feat(survey): preserve accents through curation, prompt, and survey_items" --body "$(cat <<'EOF'
## Summary

- `build_pos_lemmas.py` now emits the **DBnary-canonical accented lemma** (uppercased) instead of passing the input verbatim. ASCII-only input lists recover their accents at curation time.
- New one-shot migration `scripts/survey/backfill_accents.py` walks `survey_items` and rewrites `mot` from ASCII to accented uppercase via the same DBnary lookup. Idempotent, supports `--dry-run`.
- StyleGuide CSV parser + writer are unchanged — they already round-trip accents via NFC. Added a regression test to lock that behavior.

## Why

Producer-side dead leg: `_strip()` keyed DBnary entries by `(stripped, pos)` but emitted the stripped surface as canonical, so `solde` (noun, "Excédent") and `soldé` (adjective, "Vendu au rabais") collapsed into one `SOLDE;adjectif` lemma-file row. Generated items entered `survey_items` with `mot = SOLDE`, and `/sondage` showed the maintainer `SOLDE adjectif` for a word that's actually `SOLDÉ`.

Spec: `docs/superpowers/specs/2026-06-01-survey-accent-preservation-design.md`.

## Scope

Survey side only. Grid wordlist + `Word.text` invariant + `ValidatePuzzleUseCase` letter compare stay ASCII; the fold to ASCII still happens in `CsvWordRepository.foldToAscii` at `bliss-worker export-words` time. Separate spec if/when we revisit the grid side.

## Test plan

- [x] `python3 -m pytest scripts/clue_generation/test_build_pos_lemmas.py -v` — 4 tests pass
- [x] `python3 -m pytest scripts/survey/test_backfill_accents.py -v` — 4 tests pass (testcontainer Postgres)
- [x] `./gradlew :survey:application:test --tests '*StyleGuideCsvRoundTrip*'` — green
- [ ] Post-merge: run `python3 scripts/survey/backfill_accents.py --dry-run --dsn $SURVEY_DB_URL` against prod, eyeball the diff
- [ ] Post-merge: drop `--dry-run` and rerun
- [ ] Post-merge: spot-check `/sondage` shows an `É`-bearing item

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Expected: prints the PR URL.

- [ ] **Step 3: Note the PR number for the auto-merge cron handoff**

The cron set up after PR #720 has been cancelled — establish a new cron for this PR (per `[Auto-merge cron is the default]` memory).

---

## Task 13: Prod migration (manual, post-merge)

**Files:** none — this is an ops step, run after CI is green and the PR has merged.

- [ ] **Step 1: Confirm `SURVEY_DB_URL` is set**

Either export it locally (read-only is NOT enough; this script writes — use the maintenance role), or run the script via `kubectl exec` against the survey-api Postgres pod the same way the round-10 winners query ran.

- [ ] **Step 2: Dry-run first**

```bash
python3 scripts/survey/backfill_accents.py --dry-run --dsn "$SURVEY_DB_URL" 2>&1 | head -50
```

Expected stderr: list of `(dry) MOT -> MOT_ACCENTED (POS) item_id=…` lines, ending with a summary `updated=N skipped=M already=0`. Eyeball the first 50 to confirm the mapping looks sane (e.g., `SOLDE -> SOLDÉ (ADJECTIF)`, `ECOLE -> ÉCOLE (NOM_COMMUN)`, `PAIN -> PAIN (NOM_COMMUN)` → already-equal counts as `updated` once because the target differs from source).

Actually wait — `PAIN -> PAIN` would mean `mot == target.upper()`, so the `already += 1` branch fires. Re-check the script: yes, idempotent on accent-free words.

- [ ] **Step 3: Apply the migration**

```bash
python3 scripts/survey/backfill_accents.py --dsn "$SURVEY_DB_URL" 2>&1 | tee /tmp/backfill-accents.log
```

Expected: stderr summary `updated=N skipped=M already=K` where N + K ≈ 5,816 (total non-retired survey items at the time of writing).

- [ ] **Step 4: Eyeball the skip list**

```bash
grep "^skip" /tmp/backfill-accents.log | head -30
```

Most skips will be `rater_proposed` items where the maintainer typed a non-headword form (e.g., a multi-word phrase, an inflected form) or a typo. Each row stays ASCII — the maintainer can re-correct it manually via `/sondage` later once the accented display is live.

- [ ] **Step 5: Spot-check `/sondage` in prod**

Open `/sondage` in the browser. Rate a few items; confirm any that should show accents (`SOLDÉ`, `ÉCOLE`, `ÉTÉ`, …) now do. Drill into the survey DB with a sanity query:

```sql
SELECT mot, pos, source FROM survey_items WHERE mot ~ '[À-ÿ]' LIMIT 20;
```

Expected: ≥1 row returned. Before the migration this query returned 0 rows; after it returns most of the corpus.

---

## Self-review

**Spec coverage check:**
- ✅ Data model (no schema change, accented uppercase) → covered by Tasks 7–10 + Task 11 regression
- ✅ `build_pos_lemmas.py` change → Tasks 2–4
- ✅ Generator prompt template (no code change) → noted in plan header; nothing to implement
- ✅ Worker `StyleGuideCsvParser` tolerance → no code change; Task 11 locks the existing behavior
- ✅ Backfill script → Tasks 5–10
- ✅ Testing (curation + backfill + round-trip + dry-run) → Tasks 2–11
- ✅ Rollout (PR + dry-run + apply + eyeball) → Tasks 12–13

**Placeholder scan:** No TBDs, no "implement later", every code block is the literal content to type.

**Type / name consistency:**
- `load_dbnary` returns `dict[str, tuple[dict[str, int], str]]` consistently in Task 3 and the test fixture in Task 1 doesn't assume the old shape.
- `backfill(conn, lookup, dry_run=False)` signature stays identical across Tasks 5, 7, 8, 9, 10.
- `_strip` exists in both `build_pos_lemmas.py` and `backfill_accents.py` with the exact same implementation — Task 5 calls this out in a comment ("keep in sync") so a future divergence is at least visible.

**Scope check:** Single workstream, ~280 lines of net diff (40 in `build_pos_lemmas.py`, 120 in `backfill_accents.py`, 110 in tests, 10 in the Kotlin regression test). Under the 400-line cap.
