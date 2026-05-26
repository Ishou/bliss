#!/usr/bin/env python3
"""Emit the bodies of every ADR whose path-glob in docs/adr/INDEX.md matches
at least one of the input paths. Used by the dispatch skill to inline
mandatory-reading material into implementer prompts so an agent never has
to "remember" to read an ADR — the ADR is in the prompt.

Usage:
    scripts/adr-context.sh <path>...

Example:
    scripts/adr-context.sh survey/api/src/main/kotlin/com/bliss/survey/api/Module.kt

Output goes to stdout. Nothing matched → empty output, exit 0.
"""
from __future__ import annotations

import fnmatch
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
INDEX = REPO_ROOT / "docs" / "adr" / "INDEX.md"
ADR_DIR = REPO_ROOT / "docs" / "adr"


def glob_to_regex(glob: str) -> re.Pattern[str]:
    """Translate a registry glob to a regex.

    Conventions:
      * matches anything except a path separator.
      ** matches anything including path separators.
    fnmatch alone doesn't distinguish the two, so we hand-translate.
    """
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
    """Walk INDEX.md and return (adr_id, compiled_glob_regex) for every
    registry line. Lines that don't start with ADR- are ignored, so headings
    and prose around the fenced code block are silently skipped."""
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
