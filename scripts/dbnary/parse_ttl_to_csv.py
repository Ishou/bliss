#!/usr/bin/env python3
"""Parse a DBnary ontolex TTL dump into the CSV format consumed by
`bliss-worker ingest-dbnary` (lemma, pos, language, definition, synonyms).

Streams the bz2-compressed TTL block-by-block so the whole graph never
sits in RAM. Two-pass: first collects sense definitions keyed by sense
URI, then joins them into LexicalEntry records.

Output schema matches `IngestDbnaryCommand`:
    lemma, pos, language, definition, synonyms

Where:
    pos        = English label ('noun' | 'verb' | 'adjective' | 'adverb' | ...)
                 derived from lexinfo:partOfSpeech URI suffix.
    definition = pipe-delimited senses in DBnary order (sense_index 1..N).
    synonyms   = pipe-delimited canonical-form labels (URL-decoded slugs).

Usage:
    python scripts/dbnary/parse_ttl_to_csv.py \\
        --input  data/dbnary/fr_dbnary_ontolex.ttl.bz2 \\
        --output data/dbnary/dbnary_fr.csv
"""

from __future__ import annotations

import argparse
import bz2
import csv
import re
import sys
import urllib.parse
from collections.abc import Iterator
from pathlib import Path

# --- TTL block streaming -----------------------------------------------------

def stream_blocks(path: Path) -> Iterator[str]:
    """Yield TTL blocks from a bz2-compressed file.

    A block ends with ` .` at the start of a line OR at end of a line
    immediately followed by a blank line. DBnary writes blocks on consecutive
    lines, separated by blank lines. We accumulate non-prefix lines until we
    see a terminating dot."""
    opener = bz2.open if str(path).endswith(".bz2") else open
    buf: list[str] = []
    with opener(path, "rt", encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line:
                if buf:
                    yield "\n".join(buf)
                    buf = []
                continue
            if line.startswith("@prefix") or line.startswith("@base"):
                # Skip directives (we hardcode prefix knowledge below).
                continue
            buf.append(line)
            # A block ends with " ." at end of last line (TTL convention).
            if line.rstrip().endswith(" ."):
                yield "\n".join(buf)
                buf = []
        if buf:
            yield "\n".join(buf)


# --- block parsing -----------------------------------------------------------

# Subject is the first token. May be `prefix:local`, `<http://...>`, or
# `<http://...> rdf:type ...`.
_SUBJECT_RE = re.compile(r"^\s*(<[^>]+>|[\w\-]+:[\S]+)")
_RDF_TYPE_RE = re.compile(r"\brdf:type\s+([^;.]+?)\s*[;.]")
# rdfs:label "voiture"@fr
_LABEL_RE = re.compile(r"\brdfs:label\s+\"((?:[^\"\\]|\\.)*)\"@fr")
# lexinfo:partOfSpeech lexinfo:noun
_POS_URI_RE = re.compile(r"\blexinfo:partOfSpeech\s+lexinfo:(\w+)")
# dbnary:synonym fra:bagnole , fra:auto , <http://...>
_SYNONYMS_RE = re.compile(
    r"\bdbnary:synonym\s+([^;.]+?)\s*[;.]",
    re.DOTALL,
)
_SENSES_RE = re.compile(
    r"\bontolex:sense\s+([^;.]+?)\s*[;.]",
    re.DOTALL,
)
# dbnary:senseNumber "3"
_SENSE_NUMBER_RE = re.compile(r"\bdbnary:senseNumber\s+\"(\d+)\"")
# skos:definition [ rdf:value "..."@fr ]
_SENSE_DEF_RE = re.compile(
    r"\bskos:definition\s+\[\s*rdf:value\s+\"((?:[^\"\\]|\\.)*)\"@fr",
    re.DOTALL,
)


def _block_subject(block: str) -> str | None:
    m = _SUBJECT_RE.match(block)
    return m.group(1) if m else None


def _block_types(block: str) -> set[str]:
    m = _RDF_TYPE_RE.search(block)
    if not m:
        return set()
    raw = m.group(1)
    return {tok.strip() for tok in re.split(r"\s*,\s*", raw) if tok.strip()}


def _split_uri_list(raw: str) -> list[str]:
    """Split a comma-separated list of URIs/curies. Tolerates whitespace
    and newlines (TTL allows multi-line lists)."""
    return [tok.strip() for tok in re.split(r"\s*,\s*", raw) if tok.strip()]


def _label_from_uri(uri: str) -> str:
    """Return the lemma label from a URI/curie. Handles three forms:
        fra:bagnole                        -> 'bagnole'
        <http://.../fra/page_d'accueil>     -> "page d'accueil"
        fra:page_d%E2%80%99accueil__nom__1 -> 'page d’accueil'
    Strips DBnary's __pos__num suffix when present so a synonym pointer
    resolves to the bare lemma."""
    if uri.startswith("<") and uri.endswith(">"):
        slug = uri[1:-1].rsplit("/", 1)[-1]
    elif ":" in uri:
        slug = uri.split(":", 1)[1]
    else:
        slug = uri
    slug = urllib.parse.unquote(slug)
    # Drop __pos__num suffix: 'voiture__nom__1' -> 'voiture'
    slug = re.sub(r"__\w+__\d+$", "", slug)
    return slug.replace("_", " ").strip()


def _unescape_ttl_string(s: str) -> str:
    """Undo TTL string escapes inside a quoted literal."""
    return (
        s.replace(r"\n", "\n")
        .replace(r"\t", "\t")
        .replace(r"\r", "")
        .replace(r"\"", '"')
        .replace(r"\\", "\\")
    )


# --- main passes -------------------------------------------------------------

def first_pass_senses(path: Path) -> dict[str, tuple[int, str]]:
    """Map sense URI -> (sense_number, definition_text). Sense URIs in DBnary
    look like `fra:__ws_3_accueil__nom__1`."""
    senses: dict[str, tuple[int, str]] = {}
    n = 0
    for block in stream_blocks(path):
        if "ontolex:LexicalSense" not in block:
            continue
        subject = _block_subject(block)
        if not subject:
            continue
        m_def = _SENSE_DEF_RE.search(block)
        if not m_def:
            continue
        m_num = _SENSE_NUMBER_RE.search(block)
        try:
            num = int(m_num.group(1)) if m_num else 0
        except ValueError:
            num = 0
        senses[subject] = (num, _unescape_ttl_string(m_def.group(1)))
        n += 1
        if n % 50000 == 0:
            print(f"  pass1: {n} senses parsed", file=sys.stderr)
    print(f"  pass1 complete: {n} senses", file=sys.stderr)
    return senses


def second_pass_entries(
    path: Path,
    senses: dict[str, tuple[int, str]],
) -> Iterator[dict[str, str]]:
    """Yield CSV rows for each LexicalEntry, joining sense URIs to defs."""
    n = 0
    for block in stream_blocks(path):
        types = _block_types(block)
        if "ontolex:LexicalEntry" not in types:
            continue
        subject = _block_subject(block)
        if not subject:
            continue

        m_label = _LABEL_RE.search(block)
        m_pos = _POS_URI_RE.search(block)
        if not m_label or not m_pos:
            continue
        lemma = _unescape_ttl_string(m_label.group(1)).strip().lower()
        pos = m_pos.group(1)  # 'noun', 'verb', 'adjective', 'adverb', ...
        if not lemma or not pos:
            continue

        # Sense lookup, ordered by senseNumber (DBnary assigns 1..N).
        sense_uris = []
        m_senses = _SENSES_RE.search(block)
        if m_senses:
            sense_uris = _split_uri_list(m_senses.group(1))
        defs: list[tuple[int, str]] = []
        for uri in sense_uris:
            entry = senses.get(uri)
            if entry:
                defs.append(entry)
        defs.sort(key=lambda x: x[0])
        definition_csv = "|".join(d for _, d in defs if d)

        # Synonyms: list of page URIs -> labels.
        syn_labels: list[str] = []
        seen_syn: set[str] = set()
        m_syn = _SYNONYMS_RE.search(block)
        if m_syn:
            for uri in _split_uri_list(m_syn.group(1)):
                label = _label_from_uri(uri)
                if not label or label.lower() == lemma:
                    continue
                if label.lower() in seen_syn:
                    continue
                seen_syn.add(label.lower())
                syn_labels.append(label)
        synonyms_csv = "|".join(syn_labels)

        yield {
            "lemma": lemma,
            "pos": pos,
            "language": "fr",
            "definition": definition_csv,
            "synonyms": synonyms_csv,
        }
        n += 1
        if n % 50000 == 0:
            print(f"  pass2: {n} entries written", file=sys.stderr)
    print(f"  pass2 complete: {n} entries", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True,
                        help="path to fr_dbnary_ontolex.ttl.bz2")
    parser.add_argument("--output", type=Path, required=True,
                        help="output CSV (lemma,pos,language,definition,synonyms)")
    args = parser.parse_args()

    if not args.input.exists():
        print(f"missing {args.input}", file=sys.stderr)
        sys.exit(1)

    print("pass 1: collecting sense definitions...", file=sys.stderr)
    senses = first_pass_senses(args.input)

    print("pass 2: writing entries...", file=sys.stderr)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    fields = ["lemma", "pos", "language", "definition", "synonyms"]
    with args.output.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, lineterminator="\n")
        w.writeheader()
        for row in second_pass_entries(args.input, senses):
            w.writerow(row)

    print(f"wrote {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
