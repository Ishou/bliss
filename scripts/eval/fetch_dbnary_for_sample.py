#!/usr/bin/env python3
"""For each word in data/eval/sample_100.csv, query the DBnary SPARQL endpoint
for the first sense definition, the lexinfo POS, and any synonyms. Write the
enriched rows to data/eval/sample_100_with_definitions.csv.

Idempotent and resumable: re-running skips words that already have a non-empty
`definition` column in the output file.

Endpoint: http://kaiko.getalp.org/sparql

The output is the input plus three extra columns: pos, definition, synonyms.
`synonyms` is pipe-delimited. Missing values are blank.

Network failures on a single word are logged and the row is written with empty
fields; re-run to retry only those rows.
"""

import csv
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ENDPOINT = "http://kaiko.getalp.org/sparql"
TIMEOUT = 30
SLEEP_BETWEEN_QUERIES = 0.3  # be polite to the public endpoint

DEFINITION_QUERY = """
PREFIX ontolex: <http://www.w3.org/ns/lemon/ontolex#>
PREFIX skos:    <http://www.w3.org/2004/02/skos/core#>
PREFIX lexinfo: <http://www.lexinfo.net/ontology/2.0/lexinfo#>
PREFIX rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#>

SELECT ?pos ?defText WHERE {
  ?lex a ontolex:LexicalEntry ;
       ontolex:canonicalForm/ontolex:writtenRep "__WORD__"@fr ;
       lexinfo:partOfSpeech ?pos ;
       ontolex:sense ?sense .
  ?sense skos:definition/rdf:value ?defText .
  FILTER (CONTAINS(STR(?lex), "/dbnary/fra/"))
}
LIMIT 50
"""

SYNONYM_QUERY = """
PREFIX ontolex: <http://www.w3.org/ns/lemon/ontolex#>
PREFIX dbnary:  <http://kaiko.getalp.org/dbnary#>

SELECT DISTINCT ?syn WHERE {
  ?lex a ontolex:LexicalEntry ;
       ontolex:canonicalForm/ontolex:writtenRep "__WORD__"@fr ;
       dbnary:synonym ?syn .
  FILTER (CONTAINS(STR(?lex), "/dbnary/fra/"))
}
LIMIT 20
"""

POS_PREFERENCE = ["noun", "verb", "adjective", "adverb"]


def _run(query: str) -> list[dict]:
    url = ENDPOINT + "?" + urllib.parse.urlencode(
        {"query": query, "format": "application/sparql-results+json"}
    )
    req = urllib.request.Request(url, headers={"Accept": "application/sparql-results+json"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        import json
        return json.load(resp).get("results", {}).get("bindings", [])


def _label_from_page_uri(uri: str) -> str:
    slug = uri.rsplit("/", 1)[-1]
    return urllib.parse.unquote(slug).replace("_", " ").strip()


def _pos_rank(pos: str) -> int:
    try:
        return POS_PREFERENCE.index(pos)
    except ValueError:
        return len(POS_PREFERENCE)


_MAX_SENSES = 5


def _lookup_one(safe_word: str) -> tuple[str, str, str]:
    """Return (pos, senses_pipe_delimited, synonyms_pipe_delimited).

    `senses_pipe_delimited` carries up to _MAX_SENSES DBnary definitions for
    the chosen POS, in the order DBnary returned them. Downstream (enrich
    step) picks the first non-self-referential sense — multi-sense fetching
    means we only fall back to blanking when ALL senses self-reference."""
    def_bindings = _run(DEFINITION_QUERY.replace("__WORD__", safe_word))
    if not def_bindings:
        return ("", "", "")

    # Choose a POS class by priority, then keep every sense from that class.
    chosen_pos = min(
        (b.get("pos", {}).get("value", "").rsplit("#", 1)[-1] for b in def_bindings),
        key=_pos_rank,
    )
    senses: list[str] = []
    for b in def_bindings:
        if b.get("pos", {}).get("value", "").rsplit("#", 1)[-1] != chosen_pos:
            continue
        text = b.get("defText", {}).get("value", "").strip()
        if text and text not in senses:
            senses.append(text)
        if len(senses) >= _MAX_SENSES:
            break

    syn_bindings = _run(SYNONYM_QUERY.replace("__WORD__", safe_word))
    synonyms_seen: list[str] = []
    for b in syn_bindings:
        label = _label_from_page_uri(b.get("syn", {}).get("value", ""))
        if not label or len(label) > 20 or label in synonyms_seen:
            continue
        synonyms_seen.append(label)
        if len(synonyms_seen) >= 8:
            break
    return (chosen_pos, "|".join(senses), "|".join(synonyms_seen))


def _escape(word: str) -> str:
    return word.replace("\\", "\\\\").replace('"', '\\"')


# DBnary frequently records short forms only as ellipsis pointers:
#   pull   -> "(Par ellipse) Pull-over."
#   agar   -> "Synonyme de agar-agar."
#   tv     -> "Abréviation de télévision."
# When EVERY sense fits this shape, the actual definition lives at the target;
# we follow the pointer once and use the unshortened word's senses instead.
_POINTER_RE = re.compile(
    r"(?:\(Par\s+ellipse\)|Synonyme\s+de|Diminutif\s+de"
    r"|Variante\s+(?:de|orthographique\s+de)"
    r"|Abr[ée]viation\s+de|Forme\s+courte\s+de"
    r"|Apocope\s+de|Aph[ée]r[èe]se\s+de|Sigle\s+de)"
    r"\s+(.+?)\s*$",
    re.IGNORECASE,
)
_TAG_PREFIX_RE = re.compile(r"^(?:\([^)]*\)\s*)*$")


def _ellipsis_target(senses_csv: str) -> str | None:
    """If every sense is an ellipsis/synonym pointer, return the target word
    or phrase. The pointer can have leading domain tags like '(Pharmacologie)'
    that are tolerated — only the actual content has to be the pointer."""
    if not senses_csv:
        return None
    targets: list[str] = []
    for raw in senses_csv.split("|"):
        sense = raw.strip().rstrip(".")
        if not sense:
            continue
        m = _POINTER_RE.search(sense)
        if not m:
            return None
        # Everything before the pointer phrase must be empty or only
        # parenthetical domain tags (e.g. '(Pharmacologie) ').
        prefix = sense[: m.start()].strip()
        if prefix and not _TAG_PREFIX_RE.fullmatch(prefix + " "):
            return None
        target = m.group(1).strip().rstrip(".").strip()
        # Strip trailing parentheticals from the target ('Pull-over (vêtement)').
        target = re.sub(r"\s*\([^)]*\)\s*$", "", target).strip()
        if not target or len(target) > 40:
            return None
        targets.append(target)
    return targets[0] if targets else None


def query_sparql(word: str, lemma: str = "") -> tuple[str, str, str]:
    """Return (pos, definition, synonyms_pipe_delimited).

    The downstream pipeline clues lemmas, so look up the LEMMA's DBnary entry
    directly (the citation-form definition) rather than the surface form.
    For 'étés' this fetches 'été' (the noun); for 'fumes' it fetches 'fumer'
    (the verb), avoiding the noisier surface-form senses.

    When the lemma's only senses are ellipsis pointers ("(Par ellipse) Pull-
    over.", "Synonyme de agar-agar."), follow the pointer once and use the
    target's senses. Synonyms from the original lookup are preserved if the
    redirect didn't surface any.

    Falls back to the surface form only when no lemma is provided."""
    target = (lemma or word).strip()
    if not target:
        return ("", "", "")
    pos, definition, synonyms = _lookup_one(_escape(target))

    redirect = _ellipsis_target(definition)
    if redirect and redirect.lower() != target.lower():
        time.sleep(SLEEP_BETWEEN_QUERIES)
        # DBnary's writtenRep is stored lowercase for common nouns; chase by
        # the lowercased target so 'Pull-over' resolves to the entry stored
        # as 'pull-over'.
        pos2, def2, syn2 = _lookup_one(_escape(redirect.lower()))
        if def2:
            return (pos2 or pos, def2, syn2 or synonyms)
    return (pos, definition, synonyms)


def main() -> None:
    repo_root = Path(__file__).resolve().parent.parent.parent
    src = repo_root / "data" / "eval" / "sample_100.csv"
    out = repo_root / "data" / "eval" / "sample_100_with_definitions.csv"

    if not src.exists():
        print(f"missing {src} - run scripts/eval/sample_eval_words.py first", file=sys.stderr)
        sys.exit(1)

    existing: dict[str, dict[str, str]] = {}
    if out.exists():
        with out.open(encoding="utf-8", newline="") as f:
            for row in csv.DictReader(f):
                if row.get("definition"):
                    existing[row["word"]] = row

    with src.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))

    fieldnames = ["word", "language", "length", "frequency", "lemma", "pos", "definition", "synonyms"]
    enriched: list[dict[str, str]] = []
    misses = 0
    for i, row in enumerate(rows, 1):
        word = row["word"]
        if word in existing:
            enriched.append(existing[word])
            continue
        try:
            pos, definition, synonyms = query_sparql(word, row.get("lemma", ""))
        except Exception as e:
            print(f"  [{i:3d}/{len(rows)}] {word}: ERROR {e}", file=sys.stderr)
            pos, definition, synonyms = ("", "", "")
        if not definition:
            misses += 1
        print(f"  [{i:3d}/{len(rows)}] {word:15s} pos={pos:20s} def={definition[:60]!r}")
        enriched.append({**row, "pos": pos, "definition": definition, "synonyms": synonyms})
        time.sleep(SLEEP_BETWEEN_QUERIES)

    with out.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for r in enriched:
            writer.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"\nWrote {len(enriched)} rows to {out.relative_to(repo_root)} ({misses} misses)")


if __name__ == "__main__":
    main()
