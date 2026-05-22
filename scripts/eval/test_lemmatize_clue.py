"""Tests for `lemmatize_clue.lemmatize_clue`.

Mirror image of `inflect_clue.py`: take a surface-inflected mots-fléchés clue
and rewrite the head token to its lemma (citation) form. Used by the editorial
cross-surface propagation step — once a clue is back in lemma form, the
existing forward inflater can re-emit it for any other surface of the same
lemma.

Algorithm under test:
- Strip an optional leading reflexive pronoun (`Se` / `S'`).
- Strip an optional auxiliary verb (finite être / avoir) that immediately
  follows the (possibly stripped) reflexive — this is the passé-composé
  shape `aux + ppas`.
- Find the first remaining content verb token, lemmatize it via
  `MorphologyIndex.lemma_of_form(prefer_pos='verbe')`, splice it back in
  place of the surface form.
- If a reflexive prefix was stripped, restore it with elision: `Se` before
  a consonant-initial lemma, `S'` before a vowel- or h-initial lemma.
- Non-head tokens stay verbatim.

Test verbs are minimal hand-rolled grammalecte entries — enough to exercise
the discovery + elision rules without loading the full lexique.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from lemmatize_clue import LemmatizationResult, lemmatize_clue  # noqa: E402
from morphology_index import MorphologyIndex  # noqa: E402


def _add(idx: MorphologyIndex, lemma: str, surface: str, tags: str) -> None:
    ts = frozenset(tags.split())
    idx.by_lemma.setdefault(lemma, []).append((surface, ts))
    idx.by_form.setdefault(surface, []).append((lemma, ts))


def _build_index() -> MorphologyIndex:
    idx = MorphologyIndex()

    # avoir — finite forms must be detected as aux. `eu` is its ppas.
    _add(idx, "avoir", "avoir", "v3__t___zz infi")
    _add(idx, "avoir", "ai", "v3__t___zz ipre 1sg")
    _add(idx, "avoir", "as", "v3__t___zz ipre 2sg")
    _add(idx, "avoir", "a", "v3__t___zz ipre 3sg")
    _add(idx, "avoir", "avons", "v3__t___zz ipre 1pl")
    _add(idx, "avoir", "avait", "v3__t___zz iimp 3sg")
    _add(idx, "avoir", "eu", "v3__t___zz ppas mas sg")

    # être — finite forms aux; `été` is its ppas. Crucial: `été capable` has
    # NO preceding aux, so `été` itself is the content verb to lemmatize.
    _add(idx, "être", "être", "v3__i___zz infi")
    _add(idx, "être", "suis", "v3__i___zz ipre 1sg")
    _add(idx, "être", "es", "v3__i___zz ipre 2sg")
    _add(idx, "être", "est", "v3__i___zz ipre 3sg")
    _add(idx, "être", "était", "v3__i___zz iimp 3sg")
    _add(idx, "être", "été", "v3__i___zz ppas mas sg")

    # Content verbs used in the editorial corpus's BU/RI/LU/NÉ/PU/EU/VA/AI clues.
    _add(idx, "posséder", "posséder", "v1__t___zz infi")
    _add(idx, "posséder", "possède", "v1__t___zz ipre 1sg 3sg")
    _add(idx, "posséder", "possédé", "v1__t___zz ppas mas sg")

    _add(idx, "obtenir", "obtenir", "v3__t___zz infi")
    _add(idx, "obtenir", "obtenu", "v3__t___zz ppas mas sg")

    _add(idx, "désaltérer", "désaltérer", "v1__t___zz infi")
    _add(idx, "désaltérer", "désaltéré", "v1__t___zz ppas mas sg")

    _add(idx, "vider", "vider", "v1__t___zz infi")
    _add(idx, "vider", "vidé", "v1__t___zz ppas mas sg")

    _add(idx, "esclaffer", "esclaffer", "v1__t___zz infi")
    _add(idx, "esclaffer", "esclaffé", "v1__t___zz ppas mas sg")

    _add(idx, "marrer", "marrer", "v1__t___zz infi")
    _add(idx, "marrer", "marré", "v1__t___zz ppas mas sg")

    _add(idx, "parcourir", "parcourir", "v3__t___zz infi")
    _add(idx, "parcourir", "parcouru", "v3__t___zz ppas mas sg")

    _add(idx, "déchiffrer", "déchiffrer", "v1__t___zz infi")
    _add(idx, "déchiffrer", "déchiffré", "v1__t___zz ppas mas sg")

    _add(idx, "venir", "venir", "v3__i___zz infi")
    _add(idx, "venir", "venu", "v3__i___zz ppas mas sg")

    _add(idx, "mettre", "mettre", "v3__t___zz infi")
    _add(idx, "mettre", "mis", "v3__t___zz ppas mas sg")

    _add(idx, "réussir", "réussir", "v2__i___zz infi")
    _add(idx, "réussir", "réussi", "v2__i___zz ppas mas sg")

    _add(idx, "rendre", "rendre", "v3__t___zz infi")
    _add(idx, "rendre", "rend", "v3__t___zz ipre 3sg")

    _add(idx, "apprendre", "apprendre", "v3__t___zz infi")
    _add(idx, "apprendre", "appris", "v3__t___zz ppas mas sg")

    _add(idx, "apercevoir", "apercevoir", "v3__t___zz infi")
    _add(idx, "apercevoir", "aperçu", "v3__t___zz ppas mas sg")

    # `capable` — adjective, ensures we don't mistake it for the head verb.
    _add(idx, "capable", "capable", "adj epi sg")
    _add(idx, "capable", "capables", "adj epi pl")

    # `forme` — noun. Used to verify meta-style clues like "Forme d'avoir"
    # don't trigger any rewrite (no content verb head).
    _add(idx, "forme", "forme", "nom fem sg")
    _add(idx, "forme", "formes", "nom fem pl")

    return idx


def _check(case_name: str, got: LemmatizationResult, want_text: str,
           want_flag: str = "") -> bool:
    ok = got.text == want_text and got.flag == want_flag
    status = "PASS" if ok else "FAIL"
    print(f"  [{status}] {case_name}")
    if not ok:
        print(f"          got  text={got.text!r} flag={got.flag!r}")
        print(f"          want text={want_text!r} flag={want_flag!r}")
    return ok


def main() -> int:
    idx = _build_index()
    cases = [
        # Simple single-token ppas → infinitive.
        ("simple_ppas", lemmatize_clue("Possédé", idx), "Posséder", ""),
        ("ppas_obtenu", lemmatize_clue("Obtenu", idx), "Obtenir", ""),
        ("ppas_apercevoir", lemmatize_clue("Aperçu", idx), "Apercevoir", ""),
        ("ppas_appris", lemmatize_clue("Appris", idx), "Apprendre", ""),

        # ppas + trailing complement (head-only rewrite).
        ("ppas_with_complement",
         lemmatize_clue("Parcouru des yeux", idx), "Parcourir des yeux", ""),
        ("ppas_venir_au_monde",
         lemmatize_clue("Venu au monde", idx), "Venir au monde", ""),
        ("ppas_mis_bas",
         lemmatize_clue("Mis bas", idx), "Mettre bas", ""),
        ("ppas_reussi_a_faire",
         lemmatize_clue("Réussi à faire", idx), "Réussir à faire", ""),

        # Simple ipre (no aux to strip).
        ("ipre_possede", lemmatize_clue("Possède", idx), "Posséder", ""),

        # Aux + ppas (non-reflexive passé composé) — strip the aux.
        ("aux_plus_ppas_non_reflexive",
         lemmatize_clue("A vidé son verre", idx), "Vider son verre", ""),

        # Reflexive aux + ppas (passé composé pronominal). Lemma starts
        # with a consonant → `Se ...`.
        ("reflexive_aux_ppas_no_elision",
         lemmatize_clue("S'est désaltéré", idx), "Se désaltérer", ""),
        ("reflexive_aux_ppas_marrer",
         lemmatize_clue("S'est marré", idx), "Se marrer", ""),

        # Reflexive aux + ppas where the lemma starts with a vowel → `S'...`.
        # Output uses curly apostrophe (U+2019) per the corpus normalization
        # convention; the test's input uses straight `'` because that's what
        # the raw editorial corpus happens to carry in batch 2 (which the
        # merge-time normalization step will canonicalize too).
        ("reflexive_aux_ppas_with_elision",
         lemmatize_clue("S'est esclaffé", idx), "S’esclaffer", ""),

        # Reflexive present (no aux) — strip just the reflexive prefix
        # and lemmatize the finite verb.
        ("reflexive_ipre",
         lemmatize_clue("Se rend", idx), "Se rendre", ""),

        # Aux-as-content: "Été capable" — Été IS the ppas head (no
        # preceding aux), so it lemmatizes to its own infinitive `Être`.
        ("aux_as_content",
         lemmatize_clue("Été capable", idx), "Être capable", ""),

        # Meta-style clues describing the form rather than naming a
        # synonym verb. No content verb head → unchanged with `meta-style`
        # flag. The infinitive `avoir` IS in grammalecte as a verb form,
        # but it sits behind an apostrophe in `d'avoir`; the algorithm
        # should treat `forme` (noun head) as the dominant signal and
        # short-circuit before reaching the genitive.
        ("meta_forme_d_avoir",
         lemmatize_clue("Forme d'avoir", idx), "Forme d'avoir", "meta-style"),
    ]

    print(f"\nRunning {len(cases)} lemmatize_clue cases:\n")
    failed = 0
    for case_name, got, want_text, want_flag in cases:
        if not _check(case_name, got, want_text, want_flag):
            failed += 1
    print()
    if failed:
        print(f"  {failed}/{len(cases)} FAILED")
        return 1
    print(f"  {len(cases)}/{len(cases)} passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
