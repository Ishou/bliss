"""Tests for `inflect_clue.inflect_clue`.

Locks in the syncretic-tag fix: when grammalecte stores syncretic surface
forms on a single row with a UNION of mood/person tags (e.g. `unis` carries
`{ipre, 1sg, 2sg}`; `accompagne` carries `{ipre, spre, 1sg, 3sg, impe, 2sg}`),
strict superset-match against the head verb's paradigm fails for any verb
whose paradigm splits the same syncretism across separate rows. Pre-fix the
inflater bailed with `no-inflection`; post-fix it decomposes the target into
the cartesian product of (mood, person) and tries each in preference order.

Also covers: `inv` and `epi` wildcard compatibility on either side; `non` not
captured as a noun head; the head's `inv` not propagating as wildcard
through downstream agreement.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))

from inflect_clue import _decompose_targets, inflect_clue  # noqa: E402
from morphology_index import MorphologyIndex  # noqa: E402


def _add(idx: MorphologyIndex, lemma: str, surface: str, tags: str) -> None:
    ts = frozenset(tags.split())
    idx.by_lemma.setdefault(lemma, []).append((surface, ts))
    idx.by_form.setdefault(surface, []).append((lemma, ts))


def _build_index() -> MorphologyIndex:
    """Hand-rolled index that mirrors grammalecte's emission style — syncretic
    forms on a SINGLE row with the UNION of features. Without that the test
    wouldn't actually exercise the bug."""
    idx = MorphologyIndex()

    # `unir` (2nd-group) — `unis` is fused 1sg+2sg ipre AND mas-pl ppas.
    _add(idx, "unir", "unir", "v2__t___zz infi")
    _add(idx, "unir", "unis", "v2__t___zz ipre 1sg 2sg")
    _add(idx, "unir", "unissons", "v2__t___zz ipre 1pl")
    _add(idx, "unir", "unirent", "v2__t___zz ipsi 3pl")
    _add(idx, "unir", "unis", "v2__t___zz ppas mas pl")
    _add(idx, "unir", "unie", "v2__t___zz ppas fem sg")
    _add(idx, "unir", "unies", "v2__t___zz ppas fem pl")

    # `associer` (1st-group) — ipre 1sg / 2sg on SEPARATE rows. The asymmetry
    # vs `unir`'s fused row is what broke matching.
    _add(idx, "associer", "associer", "v1__t___zz infi")
    _add(idx, "associer", "associe", "v1__t___zz ipre 1sg")
    _add(idx, "associer", "associes", "v1__t___zz ipre 2sg")
    _add(idx, "associer", "associons", "v1__t___zz ipre 1pl")
    _add(idx, "associer", "associèrent", "v1__t___zz ipsi 3pl")
    _add(idx, "associer", "associé", "v1__t___zz ppas mas sg")
    _add(idx, "associer", "associés", "v1__t___zz ppas mas pl")
    _add(idx, "associer", "associée", "v1__t___zz ppas fem sg")
    _add(idx, "associer", "associées", "v1__t___zz ppas fem pl")

    # `abaisser` (-er): syncretic ipre+spre+impe on `abaisse`.
    _add(idx, "abaisser", "abaisser", "v1__t___zz infi")
    _add(idx, "abaisser", "abaisse", "v1__t___zz ipre spre 1sg 3sg impe 2sg")
    _add(idx, "abaisser", "abaisses", "v1__t___zz ipre spre 2sg")

    # `rendre` (irregular -re): ipre and spre on SEPARATE rows. Head for
    # `abaisse → Rendre plus bas`.
    _add(idx, "rendre", "rendre", "v3__t___zz infi")
    _add(idx, "rendre", "rends", "v3__t___zz ipre 1sg 2sg")
    _add(idx, "rendre", "rend", "v3__t___zz ipre 3sg")
    _add(idx, "rendre", "rende", "v3__t___zz spre 1sg 3sg")

    # `mettre` (irregular -re), suppletive `être`. Heads for the production
    # rows `associes → Mettre en relation` and `accompagne → Être avec
    # quelqu'un` respectively.
    _add(idx, "mettre", "mettre", "v3__t___zz infi")
    _add(idx, "mettre", "mets", "v3__t___zz ipre 1sg 2sg")
    _add(idx, "être", "être", "v3__i___zz infi")
    _add(idx, "être", "es", "v3__i___zz ipre 2sg")
    _add(idx, "être", "est", "v3__i___zz ipre 3sg")

    # `prendre`: invariable masc ppas `pris` (tagged `inv`, not `sg`/`pl`).
    _add(idx, "prendre", "prendre", "v3_itnq__a infi")
    _add(idx, "prendre", "pris", "v3_itnq__a ppas mas inv")
    _add(idx, "prendre", "prise", "v3_itnq__a ppas fem sg")

    # `monstrueux`: mas-inv adj; fem variants on separate rows.
    _add(idx, "monstrueux", "monstrueux", "adj mas inv")
    _add(idx, "monstrueux", "monstrueuse", "adj fem sg")

    # `faire`: separate ppas rows per gender × number — used to exercise
    # `inv` on the TARGET (surface `appartenu` is `{ppas, epi, inv}`).
    _add(idx, "faire", "faire", "v3__t___zz infi")
    _add(idx, "faire", "fait", "v3__t___zz ppas mas sg")
    _add(idx, "faire", "faits", "v3__t___zz ppas mas pl")

    return idx


@pytest.fixture(scope="module")
def index() -> MorphologyIndex:
    return _build_index()


# --- Decomposition unit ---------------------------------------------------


def test_decompose_yields_full_target_first_then_2sg_then_ipre() -> None:
    """Three properties at once: (1) full target tried first (preserves the
    fast path); (2) 2sg outranks 1sg in the cartesian product (more natural
    mots-fléchés rendering); (3) ipre outranks spre."""
    out = _decompose_targets({"ipre", "spre", "1sg", "2sg"})
    assert out[0] == {"ipre", "spre", "1sg", "2sg"}
    assert {"ipre", "2sg"} in out
    assert {"spre", "1sg"} in out
    assert out.index({"ipre", "2sg"}) < out.index({"spre", "1sg"})


# --- The user-reported regression ----------------------------------------


@pytest.mark.parametrize("tags,expected", [
    # The screenshot bug. `unis` syncretic ipre {1sg, 2sg}; `associer` splits.
    ({"v2__t___zz", "ipre", "1sg", "2sg"}, "Associes ensemble"),
    # The other analysis of `unis`: mas-pl ppas — already worked pre-fix,
    # locked in to make sure decomposition didn't regress it.
    ({"v2__t___zz", "ppas", "mas", "pl"}, "Associés ensemble"),
    # `unirent` — already worked pre-fix.
    ({"v2__t___zz", "ipsi", "3pl"}, "Associèrent ensemble"),
    # `unie` — fem sg ppas, agreement triggers gender flip.
    ({"v2__t___zz", "ppas", "fem", "sg"}, "Associée ensemble"),
    # `unies` — fem pl ppas.
    ({"v2__t___zz", "ppas", "fem", "pl"}, "Associées ensemble"),
])
def test_unir_to_associer(index: MorphologyIndex, tags: set[str],
                            expected: str) -> None:
    res = inflect_clue("Associer ensemble", tags, index)
    assert res.flag == ""
    assert res.text == expected


# --- Cross-paradigm bugs (Hypothesis B/D) --------------------------------


@pytest.mark.parametrize("clue,tags,expected_prefix", [
    # Syncretic ipre+spre 1sg/3sg surface (as grammalecte emits for -er 1sg/3sg).
    # `rendre` splits ipre/spre on separate rows → strict superset fails;
    # decomposition picks (ipre, 3sg), matching `rend`.
    ("Rendre plus bas",
     {"v1__t___zz", "ipre", "spre", "1sg", "3sg"},
     "Rend plus bas"),
    # `associes` → `Mettre en relation`. Surface is fused ipre+spre 2sg;
    # `mettre` splits ipre/spre.
    ("Mettre en relation",
     {"v1__t___zz", "ipre", "spre", "2sg"},
     "Mets en relation"),
    # Syncretic ipre+spre 1sg/3sg surface. Suppletive `être` has each
    # person/mood on its own row → strict superset fails; decomposition
    # picks (ipre, 3sg), matching `est`.
    ("Être avec quelqu'un",
     {"v1__t___zz", "ipre", "spre", "1sg", "3sg"},
     "Est avec quelqu'un"),
])
def test_decomposition_unblocks_irregular_head(
    index: MorphologyIndex, clue: str, tags: set[str], expected_prefix: str,
) -> None:
    res = inflect_clue(clue, tags, index)
    assert res.flag == ""
    assert res.text == expected_prefix


# --- inv / epi wildcard compatibility ------------------------------------


@pytest.mark.parametrize("tags,expected_prefix", [
    # mas-sg target against `pris` tagged `inv` (the head's invariability).
    ({"v1__t_q_zz", "ppas", "mas", "sg"}, "Pris "),
    # mas-pl target — same row, `inv` must accept either number.
    ({"v1__t_q_zz", "ppas", "mas", "pl"}, "Pris "),
])
def test_invariable_ppas_matches_either_number(
    index: MorphologyIndex, tags: set[str], expected_prefix: str,
) -> None:
    """`absorbé,absorber,verbe,Prendre et retenir` — `prendre`'s mas ppas
    is `pris`, tagged `inv`. Strict subset rejected `inv` against `sg`/`pl`
    pre-fix; the inv/epi compatibility branch lets `inv` match either."""
    res = inflect_clue("Prendre et retenir", tags, index)
    assert res.flag == ""
    assert res.text.startswith(expected_prefix)


def test_invariable_target_matches_any_number(index: MorphologyIndex) -> None:
    """`appartenu,appartenir,verbe,Faire partie de` — surface `appartenu` is
    `{ppas, epi, inv}` (epicene invariable). `faire` splits ppas by gender ×
    number. Pre-fix `wants_number={inv}` was a hard match against
    `tag_numbers ∈ {sg, pl}` and we bailed."""
    res = inflect_clue("Faire partie de",
                       {"v3__t___zz", "ppas", "epi", "inv"}, index)
    assert res.flag == ""
    assert res.text.startswith("Fait ")


def test_epicene_target_matches_any_gender(index: MorphologyIndex) -> None:
    """`abominables,abominable,adj,Monstrueux` — `abominables` is
    `{adj, epi, pl}`; `monstrueux` is mas-inv (no epi tag). `epi` on EITHER
    side is now a wildcard."""
    res = inflect_clue("Monstrueux", {"adj", "epi", "pl"}, index)
    assert res.flag == ""
    assert res.text.startswith("Monstrueux")


# --- Head-selection: `non` ----------------------------------------------


def test_non_negation_does_not_capture_head() -> None:
    """`absente,absent,nom,Non présent` — `non` is tagged both `:G:X` (adv)
    AND `:N:m:i` (mas-inv noun). Pre-fix the head-ranker picked `non` as the
    leftmost noun; the agreement walk then inherited its `inv` and (with the
    new inv-as-wildcard rule) mis-agreed every following adj. Adding `non`
    to `_FUNCTION_WORDS` keeps the ranker from picking it."""
    idx = MorphologyIndex()
    _add(idx, "non", "non", "nom mas inv")
    _add(idx, "présent", "présent", "adj nom mas sg")
    _add(idx, "présent", "présente", "adj nom fem sg")
    _add(idx, "présent", "présents", "adj nom mas pl")
    _add(idx, "présent", "présentes", "adj nom fem pl")
    res = inflect_clue("Non présent", {"adj", "fem", "nom", "sg"}, idx)
    assert res.flag in ("", "identity")
    assert "présente" in res.text.lower(), res.text


# --- Negative paths preserved -------------------------------------------


def test_no_inflection_when_lemma_truly_missing(index: MorphologyIndex) -> None:
    """Decomposition relaxes the strict superset rule but does NOT fabricate
    forms. A future tense not in the head's paradigm still bails."""
    res = inflect_clue("Associer ensemble",
                       {"v2__t___zz", "ifut", "1pl"}, index)
    assert res.flag == "no-inflection"


# --- PP+DObj guard ------------------------------------------------------


def _build_pp_index() -> MorphologyIndex:
    """Index for forer / munir / mettre — three frames the PP+DObj guard
    must distinguish."""
    idx = MorphologyIndex()
    # forer: verb+DObj clue ("Percer un trou") doesn't PP-inflate.
    _add(idx, "forer", "forer", "v1__t___zz infi")
    _add(idx, "forer", "forée", "v1__t___zz ppas fem sg")
    _add(idx, "forer", "forés", "v1__t___zz ppas mas pl")
    _add(idx, "percer", "percer", "v1__t___zz infi")
    _add(idx, "percer", "percée", "v1__t___zz ppas fem sg")
    _add(idx, "percer", "percées", "v1__t___zz ppas fem pl")
    _add(idx, "trou", "trou", "nom mas sg")
    _add(idx, "trou", "trous", "nom mas pl")
    # munir: verb+de+N reframing PP-inflates.
    _add(idx, "munir", "munir", "v2__t___zz infi")
    _add(idx, "munir", "munie", "v2__t___zz ppas fem sg")
    _add(idx, "munir", "munies", "v2__t___zz ppas fem pl")
    # mettre: verb+en+N also PP-friendly.
    _add(idx, "mettre", "mettre", "v3__t___zz infi")
    _add(idx, "mettre", "mise", "v3__t___zz ppas fem sg")
    _add(idx, "ordre", "ordre", "nom mas sg")
    return idx


def test_pp_dobj_skip_for_verb_dobj_frame() -> None:
    """`Percer un trou` + ppas-fem-sg surface → pp-only-skipped. The frame
    can't be PP-inflected and the inflater must not invent a `*Percée un
    trou` or fall back to a 3sg-action-clue."""
    idx = _build_pp_index()
    res = inflect_clue("Percer un trou",
                       {"v1__t___zz", "ppas", "fem", "sg"}, idx)
    assert res.flag == "pp-only-skipped", res
    assert res.text == "Percer un trou"  # original clue preserved verbatim


def test_pp_de_complement_inflates_normally() -> None:
    """`Munir d'un trou` + ppas-fem-sg → `Munie d'un trou`. The `de` bridge
    licenses the participial state-clue."""
    idx = _build_pp_index()
    res = inflect_clue("Munir d'un trou",
                       {"v2__t___zz", "ppas", "fem", "sg"}, idx)
    assert res.flag == "", res
    assert res.text.lower().startswith("munie d"), res


def test_pp_en_complement_inflates_normally() -> None:
    """`Mettre en ordre` + ppas-fem-sg → `Mise en ordre`. `en` is a
    PP-friendly bridge."""
    idx = _build_pp_index()
    res = inflect_clue("Mettre en ordre",
                       {"v3__t___zz", "ppas", "fem", "sg"}, idx)
    assert res.flag == "", res
    assert res.text.lower().startswith("mise en"), res


def test_pp_dobj_skip_does_not_fire_on_non_pp_targets() -> None:
    """The guard is gated on `ppas in target`. An ipre-3sg target on the
    same `Percer un trou` clue must inflate normally to `Perce un trou`,
    not get the skip."""
    idx = _build_pp_index()
    res = inflect_clue("Percer un trou",
                       {"v1__t___zz", "ipre", "3sg"}, idx)
    # Either inflated or no-inflection, but NOT pp-only-skipped.
    assert res.flag != "pp-only-skipped", res


# --- PP+Reflexive guard -------------------------------------------------


def _build_reflexive_index() -> MorphologyIndex:
    """Index for relever / soulever — reflexive vs non-reflexive heads."""
    idx = MorphologyIndex()
    _add(idx, "relever", "relever", "v1__t___zz infi")
    _add(idx, "relever", "relevée", "v1__t___zz ppas fem sg")
    _add(idx, "élever", "élever", "v1__t___zz infi")
    _add(idx, "élever", "élevée", "v1__t___zz ppas fem sg")
    _add(idx, "soulever", "soulever", "v1__t___zz infi")
    _add(idx, "soulever", "soulevée", "v1__t___zz ppas fem sg")
    return idx


def test_pp_reflexive_skip_for_se_apostrophe_head() -> None:
    """`S'élever` + ppas-fem-sg → pp-reflexive-skipped. PP-inflating
    a reflexive head produces stranded-pronoun text (`*S'élevée`)."""
    idx = _build_reflexive_index()
    res = inflect_clue("S'élever",
                       {"v1__t___zz", "ppas", "fem", "sg"}, idx)
    assert res.flag == "pp-reflexive-skipped", res


def test_pp_reflexive_skip_for_se_space_head() -> None:
    """`Se déplacer` + ppas-fem-sg → pp-reflexive-skipped. Same shape
    with a space-separated `Se` instead of `S'`."""
    idx = MorphologyIndex()
    _add(idx, "déplacer", "déplacer", "v1__t___zz infi")
    _add(idx, "déplacer", "déplacée", "v1__t___zz ppas fem sg")
    res = inflect_clue("Se déplacer",
                       {"v1__t___zz", "ppas", "fem", "sg"}, idx)
    assert res.flag == "pp-reflexive-skipped", res


def test_pp_reflexive_skip_does_not_fire_on_non_pp_targets() -> None:
    """The guard is gated on `ppas in target`. A reflexive clue cluing
    a verb-form answer (e.g. ipre-3sg) is a perfectly legal pattern
    (`S'esclaffer → Rire`) and must NOT be skipped."""
    idx = _build_reflexive_index()
    res = inflect_clue("S'élever",
                       {"v1__t___zz", "ipre", "3sg"}, idx)
    assert res.flag != "pp-reflexive-skipped", res


def test_non_reflexive_head_inflates_normally() -> None:
    """`Soulever` + ppas-fem-sg → `Soulevée`. Non-reflexive infinitive,
    no skip. Sanity check — make sure the guard doesn't false-positive."""
    idx = _build_reflexive_index()
    res = inflect_clue("Soulever",
                       {"v1__t___zz", "ppas", "fem", "sg"}, idx)
    assert res.flag == "", res
    assert res.text.lower().startswith("soulevée"), res


# ---------------------------------------------------------------------------
# Coordination: post-head co-heads of the same POS as the surface target
# should inflate alongside the head. Head-only inflation produced clues like
# "Nettoya et désinfecter" (head verb conjugated, second verb stranded as
# infinitive) and "Élans et énergie" (head noun pluralized, second noun
# stranded as singular). The unified forward walk now inflates both.
# ---------------------------------------------------------------------------


def _build_coord_index() -> MorphologyIndex:
    """Two -er verbs coordinated by `et`, plus `air` (noun) for the
    'stops at non-matching POS' test."""
    idx = MorphologyIndex()
    # `nettoyer`
    _add(idx, "nettoyer", "nettoyer", "v1__t_q_zz infi")
    _add(idx, "nettoyer", "nettoie", "v1__t_q_zz ipre 3sg")
    _add(idx, "nettoyer", "nettoyée", "v1__t_q_zz ppas fem sg")
    # `désinfecter`
    _add(idx, "désinfecter", "désinfecter", "v1__t_q_zz infi")
    _add(idx, "désinfecter", "désinfecte", "v1__t_q_zz ipre 3sg")
    _add(idx, "désinfecter", "désinfectée", "v1__t_q_zz ppas fem sg")
    # `recruter` + `engager` for the chain test
    _add(idx, "recruter", "recruter", "v1__t___zz infi")
    _add(idx, "recruter", "recrute", "v1__t___zz ipre 3sg")
    _add(idx, "recruter", "recrutée", "v1__t___zz ppas fem sg")
    _add(idx, "engager", "engager", "v1__t___zz infi")
    _add(idx, "engager", "engage", "v1__t___zz ipre 3sg")
    _add(idx, "engager", "engagée", "v1__t___zz ppas fem sg")
    # `air` — noun used to stop the forward walk.
    _add(idx, "air", "air", "nom mas sg")
    _add(idx, "air", "airs", "nom mas pl")
    # `élan` + `énergie` for the noun-coord test.
    _add(idx, "élan", "élan", "nom mas sg")
    _add(idx, "élan", "élans", "nom mas pl")
    _add(idx, "énergie", "énergie", "nom fem sg")
    _add(idx, "énergie", "énergies", "nom fem pl")
    return idx


def test_coord_verb_ipre_3sg_inflates_both_verbs() -> None:
    """`Nettoyer et désinfecter` + ipre-3sg surface → both verbs
    conjugate to ipre-3sg ('Nettoie et désinfecte')."""
    idx = _build_coord_index()
    res = inflect_clue(
        "Nettoyer et désinfecter",
        {"v1__t_q_zz", "ipre", "3sg"},
        idx,
    )
    assert res.flag == "", res
    assert res.text == "Nettoie et désinfecte", res


def test_coord_verb_ppas_fem_sg_inflates_both_verbs() -> None:
    """`Recruter et engager` + ppas-fem-sg surface → both verbs become
    ppas fem-sg ('Recrutée et engagée')."""
    idx = _build_coord_index()
    res = inflect_clue(
        "Recruter et engager",
        {"v1__t___zz", "ppas", "fem", "sg"},
        idx,
    )
    assert res.flag == "", res
    assert res.text == "Recrutée et engagée", res


def test_coord_verb_chain_inflates_all_three() -> None:
    """`V1 et V2 et V3` chain — every verb inflates."""
    idx = _build_coord_index()
    res = inflect_clue(
        "Nettoyer et désinfecter et engager",
        {"v1__t_q_zz", "ipre", "3sg"},
        idx,
    )
    assert res.flag == "", res
    assert res.text == "Nettoie et désinfecte et engage", res


def test_coord_walk_stops_at_non_target_pos() -> None:
    """`Nettoyer et désinfecter l'air` for an ipre-3sg verb surface:
    both verbs inflate, then the walker hits the article + noun and
    stops. `l'air` stays untouched."""
    idx = _build_coord_index()
    res = inflect_clue(
        "Nettoyer et désinfecter l'air",
        {"v1__t_q_zz", "ipre", "3sg"},
        idx,
    )
    assert res.flag == "", res
    assert res.text == "Nettoie et désinfecte l'air", res


def test_coord_noun_plural_inflates_both_nouns() -> None:
    """`Élan et énergie` for a mas-pl noun surface → both nouns
    pluralize ('Élans et énergies'). The second noun has fem gender
    intrinsically; the walker passes a number-only target via the
    `_RELAX_GENDER_FOR` relaxation, so it picks up the fem-pl form."""
    idx = _build_coord_index()
    # The surface here is conceptually some mas-pl noun whose lemma
    # has clue "Élan et énergie" — we just hand the inflater the
    # right tag set directly (the head matches élan / mas-pl).
    res = inflect_clue("Élan et énergie", {"nom", "mas", "pl"}, idx)
    assert res.flag == "", res
    assert res.text == "Élans et énergies", res


# ---------------------------------------------------------------------------
# Finding 1: function-word guard — "plus" is tagged as a verb form of
# "plaire" by grammalecte; the forward walk must break on it rather than
# inflating it as a co-head.
# ---------------------------------------------------------------------------


def test_function_word_not_inflated_as_verb() -> None:
    """`Rendre plus bas` for an ipre surface: `plus` is registered in the
    index as a verb form of `plaire` (ipsi 1sg/2sg). The `_FUNCTION_WORDS`
    guard must stop the walk before the co-head branch fires on it."""
    idx = MorphologyIndex()
    _add(idx, "rendre", "rendre", "v3__t___zz infi")
    _add(idx, "rendre", "rend", "v3__t___zz ipre 3sg")
    _add(idx, "plaire", "plus", "v3__i___zz ipsi 1sg 2sg")
    _add(idx, "bas", "bas", "adj mas inv")
    res = inflect_clue("Rendre plus bas",
                       {"v1__t___zz", "ipre", "spre", "1sg", "3sg"}, idx)
    assert res.flag == ""
    assert res.text == "Rend plus bas"


# ---------------------------------------------------------------------------
# Finding 2A: conjunction guard — a same-POS token directly adjacent to
# the head (no conjunction) must not be inflated as a co-head.
# ---------------------------------------------------------------------------


def test_no_conjunction_verb_not_inflated_as_cohead() -> None:
    """`Faire savoir` for an ipre-3sg verb target: `savoir` follows the
    head directly without a coordination conjunction and must not be
    inflated."""
    idx = MorphologyIndex()
    _add(idx, "faire", "faire", "v3__t___zz infi")
    _add(idx, "faire", "fait", "v3__t___zz ipre 3sg")
    _add(idx, "savoir", "savoir", "v3__i___zz infi")
    _add(idx, "savoir", "sait", "v3__i___zz ipre 3sg")
    res = inflect_clue("Faire savoir", {"v3__t___zz", "ipre", "3sg"}, idx)
    assert res.flag == ""
    assert res.text == "Fait savoir"


# ---------------------------------------------------------------------------
# Finding 2B: adj/nom-ambiguous token after head — without a conjunction the
# co-head branch must not fire; adj agreement handles it instead.
# ---------------------------------------------------------------------------


def test_adj_nom_ambiguous_agrees_not_cohead_inflated() -> None:
    """`Dévouement total` for a nom fem-pl target: `total` carries `nom` in
    its POS classes, so the co-head branch would inflate it to `totales`
    (fem pl). With the conjunction guard the co-head branch does not fire;
    adj agreement inflates it to `totaux` (mas pl, matching the inflected
    head `dévouements`)."""
    idx = MorphologyIndex()
    _add(idx, "dévouement", "dévouement", "nom mas sg")
    _add(idx, "dévouement", "dévouements", "nom mas pl")
    _add(idx, "total", "total", "adj nom mas sg")
    _add(idx, "total", "totale", "adj nom fem sg")
    _add(idx, "total", "totaux", "adj nom mas pl")
    _add(idx, "total", "totales", "adj nom fem pl")
    res = inflect_clue("Dévouement total", {"nom", "fem", "pl"}, idx)
    assert res.flag == ""
    assert res.text == "Dévouements totaux"
