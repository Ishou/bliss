"""Multi-source, manifest-driven Modal-lane SFT corpus builder."""

from __future__ import annotations

import csv
import json
import random
import re
import sys
import tomllib
from collections import Counter, defaultdict
from glob import glob
from pathlib import Path
from typing import Any


# Restricted filter grammar: clause := IDENT (==|!=) (IDENT | '<literal>'); joined by " and ".
_CLAUSE_RE = re.compile(
    r"""^\s*
        (?P<lhs>[A-Za-z_][A-Za-z0-9_]*)
        \s*(?P<op>==|!=)\s*
        (?:
            '(?P<str>[^']*)'
          | (?P<rhs_col>[A-Za-z_][A-Za-z0-9_]*)
        )
        \s*$""",
    re.VERBOSE,
)


def _apply_row_filter(row: dict, expr: str) -> bool:
    """Evaluate the manifest's restricted filter grammar (no eval)."""
    expr = expr.strip()
    if not expr:
        return True
    for clause in (c.strip() for c in expr.split(" and ")):
        m = _CLAUSE_RE.match(clause)
        if m is None:
            raise ValueError(f"unsupported filter clause: {clause!r}")
        lhs_val = (row.get(m["lhs"]) or "")
        if m["str"] is not None:
            rhs_val = m["str"]
        else:
            rhs_val = (row.get(m["rhs_col"]) or "")
        op = m["op"]
        if op == "==" and lhs_val != rhs_val:
            return False
        if op == "!=" and lhs_val == rhs_val:
            return False
    return True


def _load_manifest(manifest_path: Path) -> dict[str, Any]:
    """Read TOML manifest from disk."""
    with manifest_path.open("rb") as f:
        return tomllib.load(f)


def _resolve_path(root: Path, relpath: str) -> Path:
    """Join a manifest-relative path under the repo root."""
    return root / relpath


def _load_held_out_lemmas(root: Path, path: str) -> set[str]:
    """Return lemmas declared in the held-out JSONL as a lower-case set."""
    if not path:
        return set()
    p = _resolve_path(root, path)
    if not p.exists():
        return set()
    out: set[str] = set()
    with p.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            lemma = obj.get("lemma") or obj.get("mot") or obj.get("word")
            if lemma:
                out.add(lemma.lower())
    return out


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


def _parse_meta_column(raw: str | None) -> dict[str, list[str]]:
    """Parse `key:value|key:value` meta cell; returns only `senses` and `sub_tags` as lists."""
    text = (raw or "").strip()
    if not text:
        return {}
    out: dict[str, list[str]] = {}
    for pair in text.split("|"):
        if ":" not in pair:
            continue
        key, _, value = pair.partition(":")
        key = key.strip()
        if key not in ("senses", "sub_tags"):
            continue
        out[key] = [v.strip() for v in value.split(",") if v.strip()]
    return out


def _load_source(root: Path, src: dict[str, Any]) -> list[dict[str, str]]:
    """Load one source per its manifest entry and apply schema mapping."""
    if "path_glob" in src:
        paths = [Path(p) for p in glob(str(root / src["path_glob"]))]
    else:
        paths = [_resolve_path(root, src["path"])]

    delim = src.get("csv_delimiter", ",")
    mapping = src["schema_mapping"]
    row_filter = src.get("row_filter", "")
    name = src["name"]
    weight_col = src.get("weight_column", "")

    out: list[dict[str, str]] = []
    for p in paths:
        if not p.exists():
            raise FileNotFoundError(f"source '{name}': {p} not found")
        with p.open(encoding="utf-8", newline="") as f:
            reader = csv.DictReader(f, delimiter=delim)
            for row in reader:
                if not _apply_row_filter(row, row_filter):
                    continue
                mot_col = mapping["mot"]
                def_col = mapping["definition"]
                force_col = mapping.get("force", "")
                mot = (row.get(mot_col) or "").strip()
                definition = (row.get(def_col) or "").strip()
                if not mot or not definition:
                    continue
                entry = {
                    "mot": mot,
                    "definition": definition,
                    "force": (row.get(force_col) or "").strip() if force_col else "",
                    "_source": name,
                }
                if weight_col:
                    entry["_copies"] = _row_copies(row.get(weight_col))
                # `meta` is a column on the survey export only; absent on other sources.
                parsed_meta = _parse_meta_column(row.get("meta"))
                if "senses" in parsed_meta:
                    entry["_senses"] = parsed_meta["senses"]
                if "sub_tags" in parsed_meta:
                    entry["_sub_tags"] = parsed_meta["sub_tags"]
                out.append(entry)
    return out


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


def _to_chat_entry(row: dict[str, str], template: str) -> dict:
    """Wrap a row as a chat-Mistral {messages: [...]} entry."""
    return {
        "messages": [
            {"role": "user", "content": template.format(mot=row["mot"])},
            {"role": "assistant", "content": row["definition"]},
        ]
    }


def _split_train_val(
    rows: list[dict[str, str]],
    val_ratio: float,
    seed: int,
) -> tuple[list[dict], list[dict]]:
    """Stratify by force where present; merge unstratified rows."""
    rng = random.Random(seed)
    by_force: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        by_force[r.get("force", "")].append(r)
    train: list[dict] = []
    val: list[dict] = []
    for force in sorted(by_force.keys()):
        group = list(by_force[force])
        rng.shuffle(group)
        n_val = round(len(group) * val_ratio)
        val.extend(group[:n_val])
        train.extend(group[n_val:])
    rng.shuffle(train)
    rng.shuffle(val)
    return train, val


def _read_held_out_independent(root: Path, path: str) -> set[str]:
    """Independent re-read of the held-out JSONL for the defense-in-depth assertion."""
    if not path:
        return set()
    p = _resolve_path(root, path)
    if not p.exists():
        return set()
    out: set[str] = set()
    with p.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            lemma = obj.get("lemma") or obj.get("mot") or obj.get("word")
            if lemma:
                out.add(lemma.lower())
    return out


def build_corpus(root: Path, manifest_path: Path, out_dir: Path) -> dict:
    """Build train + val JSONL + summary markdown from the manifest."""
    manifest = _load_manifest(manifest_path)
    rows = load_all_sources(root, manifest_path)

    # Defense-in-depth: re-read the held-out set via an independent code path.
    held_out = _read_held_out_independent(root, manifest.get("exclude_lemmas_from", ""))
    for r in rows:
        if r["mot"].lower() in held_out:
            raise ValueError(f"held-out lemma leaked into corpus: {r['mot']}")

    train_rows, val_rows = _split_train_val(
        rows,
        val_ratio=float(manifest.get("val_ratio", 0.12)),
        seed=int(manifest.get("seed", 42)),
    )

    template = manifest["user_prompt_template"]
    out_dir.mkdir(parents=True, exist_ok=True)

    with (out_dir / "train.jsonl").open("w", encoding="utf-8") as f:
        for r in train_rows:
            f.write(json.dumps(_to_chat_entry(r, template), ensure_ascii=False) + "\n")
    with (out_dir / "val.jsonl").open("w", encoding="utf-8") as f:
        for r in val_rows:
            f.write(json.dumps(_to_chat_entry(r, template), ensure_ascii=False) + "\n")

    summary = _build_summary(manifest, rows, train_rows, val_rows)
    (out_dir / "build_summary.md").write_text(summary, encoding="utf-8")

    return {
        "train_size": len(train_rows),
        "val_size": len(val_rows),
        "total_with_weights": len(rows),
    }


def _build_summary(manifest, rows, train, val) -> str:
    """Render the per-source row-count summary markdown."""
    from datetime import date
    lines = [
        f"# Modal corpus build summary — {manifest['version']}",
        "",
        f"- Build date (local): {date.today().isoformat()}",
        f"- Seed: {manifest.get('seed', 42)}",
        f"- Val ratio: {manifest.get('val_ratio', 0.12)}",
        f"- Total rows after weighting + exclusion: {len(rows)}",
        f"- Train rows: {len(train)} | Val rows: {len(val)}",
        "",
        "## Rows per source (after held-out exclusion, before weight replication)",
        "",
        "| Source | Tier | Weight | Rows in | Rows out (× weight) |",
        "|---|---|---:|---:|---:|",
    ]
    src_counts = Counter(r["_source"] for r in rows)
    for src in manifest["sources"]:
        name = src["name"]
        weight = int(src["weight"])
        out = src_counts.get(name, 0)
        rows_in = out // weight if weight > 0 else 0
        lines.append(
            f"| `{name}` | {src['tier']} | {weight} | {rows_in} | {out} |"
        )
    return "\n".join(lines) + "\n"


def main(argv: list[str]) -> int:
    """CLI entrypoint: build the corpus from the manifest under the repo root."""
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest",
                        default="data/lora/modal_corpus_v1/manifest.toml")
    parser.add_argument("--out-dir",
                        default="data/lora/modal_corpus_v1")
    args = parser.parse_args(argv)

    root = Path(__file__).resolve().parents[3]
    manifest_path = root / args.manifest
    out_dir = root / args.out_dir

    summary = build_corpus(root, manifest_path, out_dir)
    print(f"Built corpus: {summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
