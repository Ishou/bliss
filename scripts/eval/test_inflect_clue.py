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
    # `abaisse` → `Rendre plus bas`. Surface fuses ipre+spre+impe; `rendre`
    # splits ipre/spre on separate rows. Decomposition picks (ipre, 2sg).
    ("Rendre plus bas",
     {"v1__t___zz", "ipre", "spre", "1sg", "3sg", "impe", "2sg"},
     "Rends plus bas"),
    # `associes` → `Mettre en relation`. Surface is fused ipre+spre 2sg;
    # `mettre` splits ipre/spre.
    ("Mettre en relation",
     {"v1__t___zz", "ipre", "spre", "2sg"},
     "Mets en relation"),
    # `accompagne` → `Être avec quelqu'un`. Suppletive `être` — every
    # person/mood on its own row.
    ("Être avec quelqu'un",
     {"v1__t___zz", "ipre", "spre", "1sg", "3sg", "impe", "2sg"},
     "Es avec quelqu'un"),
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
