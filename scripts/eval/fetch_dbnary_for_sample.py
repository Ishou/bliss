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


def query_sparql(word: str, lemma: str = "") -> tuple[str, str, str]:
    """Return (pos, definition, synonyms_pipe_delimited). Falls back to lemma
    when the surface form has no DBnary entry (e.g. 'sut' -> 'savoir')."""
    pos, definition, synonyms = _lookup_one(_escape(word))
    if not definition and lemma and lemma != word:
        return _lookup_one(_escape(lemma))
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
