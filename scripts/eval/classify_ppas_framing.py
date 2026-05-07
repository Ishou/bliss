#!/usr/bin/env python3
"""Diagnostic: bucket each verb-lemma clue by the syntactic shape that
determines its ppas-surface inflation behaviour. Operates on the
*runtime* words-fr.csv — that's where user-visible failures live (the
shipped corpus is a snapshot; runtime carries leaked pre-v7 clues too).

Buckets:
  - reflexive          : clue head is `Se` / `S'`. Already gated by
                          `pp-reflexive-skipped` in inflect_clue.
  - dobj-bare          : head + bare-determiner DObj (e.g. `Faire un trou`,
                          `Manger des pommes`). Already gated by
                          `pp-only-skipped` in inflect_clue.
  - dobj-partitive     : head + `de la` / `de l'` partitive
                          (e.g. `Ajouter de la difficulté`). NOT currently
                          gated — this is the v7 gap the user flagged.
  - oblique            : head + PP-bridge prep + NP (e.g. `Cuire au four`,
                          `Munir d'un trou`, `Solidifier par le froid`).
                          Inflates to a clean passive/state PP.
  - intransitive       : head alone or head + adverb only
                          (e.g. `Courir`, `Dormir profondément`). Bare PP
                          reads as a stranded active-perfect fragment.
  - other              : shape didn't match any of the above.

Counts and stratified samples are reported by run.

Usage:
    python scripts/eval/classify_ppas_framing.py [--corpus PATH] [--surfaces PATH]

Outputs stdout-only; no files are written.
"""
from __future__ import annotations
import argparse, csv, random, re
from collections import defaultdict, Counter
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent

# Reuse the same vocab as inflect_clue.py to keep the diagnostic consistent
# with what the production inflater sees.
_DOBJ_DETERMINERS = {
    "le", "la", "les", "l",
    "un", "une", "des",
    "du",
    "ce", "cet", "cette", "ces",
    "son", "sa", "ses",
    "mon", "ma", "mes", "ton", "ta", "tes",
    "leur", "leurs", "notre", "nos", "votre", "vos",
}
_PP_BRIDGE_PREPS_NON_DE = {"à", "au", "aux", "en", "dans", "sur",
                           "sous", "par", "pour", "avec", "sans"}
# Determiners that, after `de` / `d'`, mark a partitive direct object
# (not a prepositional bridge). `de la difficulté`, `de l'air`. The
# masculine partitive `du` is already in `_DOBJ_DETERMINERS` and gets
# caught by the dobj-bare bucket directly.
_PARTITIVE_AFTER_DE = {"la", "l", "les"}

_TOKEN_RE = re.compile(r"[\wÀ-ÿŒœŸ]+|[^\s\wÀ-ÿ]+", re.UNICODE)


def _alpha_tokens(clue: str) -> list[tuple[int, str]]:
    """Return [(index, lowered)] for alphabetic tokens, preserving order."""
    out = []
    for i, t in enumerate(_TOKEN_RE.findall(clue)):
        if re.match(r"^[\wÀ-ÿŒœŸ]+$", t):
            out.append((i, t.lower().rstrip("'")))
    return out


def classify(clue: str) -> str:
    toks = _alpha_tokens(clue)
    if not toks:
        return "other"
    # Reflexive head?
    if toks[0][1] in ("se", "s"):
        return "reflexive"
    if len(toks) < 2:
        return "intransitive"
    # toks[0] is the head verb (lemma-form clue convention).
    nxt = toks[1][1]
    # Bare-determiner DObj (covers `un trou`, `du pain`, `des pommes`, `la maison`).
    if nxt in _DOBJ_DETERMINERS:
        return "dobj-bare"
    # PP-bridge prep (non-`de`): à/au/en/dans/sur/par/pour/avec/...
    if nxt in _PP_BRIDGE_PREPS_NON_DE:
        return "oblique"
    # `de` / `d'` — disambiguate partitive vs preposition by what follows.
    if nxt in ("de", "d"):
        if len(toks) >= 3:
            after_de = toks[2][1]
            if after_de in _PARTITIVE_AFTER_DE:
                return "dobj-partitive"
            # `d'un trou`, `de mon père`, `de pain`, `de feu`: prepositional.
            return "oblique"
        # Just `verb de` with nothing after — degenerate.
        return "other"
    # Anything else: bare verb, verb + adverb, etc.
    return "intransitive"


def has_ppas_surfaces(lemma: str, lemma_to_ppas: dict[str, list[str]]) -> int:
    return len(lemma_to_ppas.get(lemma, []))


def _is_ppas_surface(surface: str, lemma: str, index) -> bool:
    """True iff `surface` carries a `ppas` tag for `lemma` in grammalecte."""
    if surface == lemma:
        return False
    for lem, tags in index.lookup_form(surface):
        if lem == lemma and "ppas" in tags:
            return True
    return False


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--runtime", type=Path,
                   default=REPO / "grid/api/src/main/resources/words/words-fr.csv")
    p.add_argument("--lexique", type=Path,
                   default=Path.home() / "Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt")
    p.add_argument("--samples-per-bucket", type=int, default=15)
    p.add_argument("--seed", type=int, default=20260507)
    args = p.parse_args()

    rng = random.Random(args.seed)

    # Load morphology index for accurate ppas detection.
    import sys
    sys.path.insert(0, str(REPO / "scripts/eval"))
    from morphology_index import MorphologyIndex
    print(f"loading grammalecte from {args.lexique}...")
    index = MorphologyIndex.load(args.lexique)

    # Pass 1: walk runtime CSV, find ppas surfaces with non-placeholder clues.
    # Group by lemma_clue, since multiple ppas surfaces share one underlying
    # lemma clue (head-only inflater varies the head agreement).
    print(f"scanning runtime CSV: {args.runtime}")
    # (lemma, base_clue) -> list of (surface, surface_clue)
    by_lemma_clue: dict[tuple[str, str], list[tuple[str, str]]] = defaultdict(list)
    placeholder_count = 0
    ppas_total = 0

    with args.runtime.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            surface = (r.get("word") or "").strip()
            clue = (r.get("clue") or "").strip()
            lemma = (r.get("lemma") or "").strip()
            if not surface or not lemma:
                continue
            if not _is_ppas_surface(surface, lemma, index):
                continue
            ppas_total += 1
            if clue == surface:  # placeholder (clue==word)
                placeholder_count += 1
                continue
            # Strip the head-token agreement to recover the base lemma clue.
            # Pragmatic: just use the surface_clue itself as the key, since
            # different ppas agreements (mas/fem × sg/pl) produce different
            # surface clues but inflate from the same lemma clue. Use lemma
            # as the disambiguator.
            by_lemma_clue[(lemma, clue)].append((surface, clue))

    print(f"ppas surfaces with non-placeholder clue: "
          f"{ppas_total - placeholder_count}/{ppas_total} "
          f"(placeholder: {placeholder_count})")
    print(f"distinct (lemma, clue) groups: {len(by_lemma_clue)}")
    print()

    # Classify by clue shape.
    bucket_counts = Counter()  # lemma-clue groups per bucket
    bucket_surf_counts = Counter()  # surfaces per bucket
    bucket_examples: dict[str, list] = defaultdict(list)

    for (lemma, clue), surfs in by_lemma_clue.items():
        bucket = classify(clue)
        bucket_counts[bucket] += 1
        bucket_surf_counts[bucket] += len(surfs)
        bucket_examples[bucket].append((lemma, clue, surfs))

    total = sum(bucket_counts.values())
    total_surf = sum(bucket_surf_counts.values())

    # Report.
    print(f"{'bucket':<22}{'groups':>10}{'surfaces':>12}{'%-surf':>10}")
    print("-" * 54)
    for b in ("reflexive", "dobj-bare", "dobj-partitive", "oblique",
              "intransitive", "other"):
        n_grp = bucket_counts.get(b, 0)
        n_surf = bucket_surf_counts.get(b, 0)
        pct = (n_surf / total_surf * 100) if total_surf else 0
        print(f"{b:<22}{n_grp:>10}{n_surf:>12}{pct:>9.1f}%")
    print("-" * 54)
    print(f"{'TOTAL':<22}{total:>10}{total_surf:>12}")
    print()

    # Stratified sample.
    print("=" * 60)
    print(f"Stratified samples (n={args.samples_per_bucket} per bucket)")
    print("=" * 60)
    for b in ("dobj-partitive", "intransitive", "dobj-bare", "oblique",
              "reflexive", "other"):
        ex = bucket_examples.get(b, [])
        if not ex:
            continue
        sample = rng.sample(ex, min(args.samples_per_bucket, len(ex)))
        print(f"\n## {b} ({len(ex)} groups, {bucket_surf_counts[b]} surfaces)")
        for lemma, clue, surfs in sample:
            ex_surf = surfs[0][0] if surfs else "(none)"
            print(f"  {lemma:<18} {ex_surf:<14} → {clue!r}")


if __name__ == "__main__":
    main()
