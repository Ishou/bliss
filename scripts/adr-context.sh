#!/usr/bin/env python3
"""Emit ADR bodies for INDEX.md globs matching the given source paths."""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
INDEX = REPO_ROOT / "docs" / "adr" / "INDEX.md"
ADR_DIR = REPO_ROOT / "docs" / "adr"


def glob_to_regex(glob: str) -> re.Pattern[str]:
    """Translate a registry glob (* = no separator, ** = any) to a regex."""
    out: list[str] = []
    i = 0
    while i < len(glob):
        c = glob[i]
        if c == "*":
            if i + 1 < len(glob) and glob[i + 1] == "*":
                out.append(".*")
                i += 2
            else:
                out.append("[^/]*")
                i += 1
        elif c == "?":
            out.append(".")
            i += 1
        elif c in ".+()[]{}|^$\\":
            out.append("\\" + c)
            i += 1
        else:
            out.append(c)
            i += 1
    return re.compile("^" + "".join(out) + "$")


def parse_registry(index_text: str) -> list[tuple[str, re.Pattern[str]]]:
    """Return (adr_id, compiled_glob_regex) for every registry line in INDEX.md."""
    entries: list[tuple[str, re.Pattern[str]]] = []
    for raw in index_text.splitlines():
        line = raw.strip()
        if not line.startswith("ADR-"):
            continue
        parts = line.split(maxsplit=2)
        if len(parts) < 2:
            continue
        adr_id, glob = parts[0], parts[1]
        entries.append((adr_id, glob_to_regex(glob)))
    return entries


def find_adr_file(adr_id: str) -> Path | None:
    """ADR-0048 → docs/adr/0048-*.md (first match)."""
    num = adr_id.removeprefix("ADR-")
    for candidate in sorted(ADR_DIR.glob(f"{num}-*.md")):
        return candidate
    return None


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: scripts/adr-context.sh <path>...", file=sys.stderr)
        return 2
    if not INDEX.is_file():
        print("scripts/adr-context.sh: docs/adr/INDEX.md not found", file=sys.stderr)
        return 2

    paths = [p.lstrip("./") for p in argv[1:]]
    entries = parse_registry(INDEX.read_text(encoding="utf-8"))

    matched: dict[str, None] = {}  # use dict to preserve order, dedupe
    for adr_id, pattern in entries:
        for p in paths:
            if pattern.match(p):
                matched[adr_id] = None
                break

    if not matched:
        return 0

    for adr_id in sorted(matched):
        adr_file = find_adr_file(adr_id)
        if adr_file is None:
            print(f"## {adr_id} (file not found in docs/adr/)", file=sys.stderr)
            continue
        print("=" * 66)
        print(f"MANDATORY READING — {adr_id} ({adr_file.name})")
        print("=" * 66)
        print(adr_file.read_text(encoding="utf-8"), end="")
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
