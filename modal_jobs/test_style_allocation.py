from __future__ import annotations

import textwrap
from pathlib import Path

import pytest

from style_allocation import (
    VALID_STYLES,
    HORS_IA,
    charger_distribution,
    cibles_acceptation,
    paires_pour_manque,
)


def test_valid_styles_is_the_nine() -> None:
    assert VALID_STYLES == {
        "definition_directe", "periphrase", "metonymie", "fonction_role",
        "calembour", "culturel", "cryptique", "cryptique_morphologique",
        "technique",
    }
    assert HORS_IA == {"calembour"}


def _write(tmp_path: Path, body: str) -> Path:
    p = tmp_path / "dist.yaml"
    p.write_text(textwrap.dedent(body), encoding="utf-8")
    return p


def test_charger_distribution_valid(tmp_path: Path) -> None:
    p = _write(tmp_path, """\
        styles:
          definition_directe: 0.217
          periphrase: 0.217
          cryptique: 0.216
          culturel: 0.10
          fonction_role: 0.10
          metonymie: 0.10
          technique: 0.05
    """)
    w = charger_distribution(p)
    assert set(w) == {
        "definition_directe", "periphrase", "cryptique",
        "culturel", "fonction_role", "metonymie", "technique",
    }
    assert abs(sum(w.values()) - 1.0) < 1e-9
    assert list(w) == sorted(w)  # deterministic key order


def test_charger_distribution_drops_zero_weight(tmp_path: Path) -> None:
    p = _write(tmp_path, """\
        styles:
          definition_directe: 0.5
          periphrase: 0.5
          metonymie: 0.0
    """)
    w = charger_distribution(p)
    assert "metonymie" not in w


def test_charger_distribution_bad_sum(tmp_path: Path) -> None:
    p = _write(tmp_path, """\
        styles:
          definition_directe: 0.5
          periphrase: 0.4
    """)
    with pytest.raises(ValueError, match="sum"):
        charger_distribution(p)


def test_charger_distribution_unknown_style(tmp_path: Path) -> None:
    p = _write(tmp_path, """\
        styles:
          definition_directe: 0.5
          blague: 0.5
    """)
    with pytest.raises(ValueError, match="unknown style"):
        charger_distribution(p)


def test_charger_distribution_calembour_nonzero(tmp_path: Path) -> None:
    p = _write(tmp_path, """\
        styles:
          definition_directe: 0.5
          calembour: 0.5
    """)
    with pytest.raises(ValueError, match="hors-IA"):
        charger_distribution(p)


def test_charger_distribution_missing_file(tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError):
        charger_distribution(tmp_path / "nope.yaml")


def test_cibles_sum_exactly_and_match_example() -> None:
    w = {
        "definition_directe": 0.217, "periphrase": 0.217, "cryptique": 0.216,
        "culturel": 0.10, "fonction_role": 0.10, "metonymie": 0.10,
        "technique": 0.05,
    }
    c = cibles_acceptation(w, 100)
    assert sum(c.values()) == 100
    assert c == {
        "definition_directe": 22, "periphrase": 22, "cryptique": 21,
        "culturel": 10, "fonction_role": 10, "metonymie": 10, "technique": 5,
    }


def test_cibles_sum_exactly_arbitrary() -> None:
    w = {"culturel": 1 / 3, "periphrase": 1 / 3, "cryptique": 1 / 3}
    for n in (1, 7, 33, 100, 999):
        c = cibles_acceptation(w, n)
        assert sum(c.values()) == n


def test_cibles_only_positive_styles() -> None:
    w = {"culturel": 0.5, "periphrase": 0.5}
    c = cibles_acceptation(w, 10)
    assert set(c) == {"culturel", "periphrase"}
    assert c == {"culturel": 5, "periphrase": 5}


LEMMES = [f"mot{i}" for i in range(20)]
CIBLES = {"culturel": 10, "technique": 5, "metonymie": 10}


def test_paires_empty_when_targets_met() -> None:
    acceptes = {"culturel": 10, "technique": 5, "metonymie": 10}
    assert paires_pour_manque(CIBLES, acceptes, LEMMES, seed=7, pass_idx=0, inflation=2.0) == []


def test_paires_only_short_styles_and_counts() -> None:
    acceptes = {"culturel": 8, "technique": 5, "metonymie": 0}
    pairs = paires_pour_manque(CIBLES, acceptes, LEMMES, seed=7, pass_idx=0, inflation=2.0)
    by_style: dict[str, int] = {}
    for _mot, s in pairs:
        by_style[s] = by_style.get(s, 0) + 1
    # culturel short by 2 -> ceil(2*2)=4 ; metonymie short by 10 -> ceil(10*2)=20 ; technique met
    assert by_style == {"culturel": 4, "metonymie": 20}
    assert all(s != "technique" for _m, s in pairs)


def test_paires_all_lemmes_are_valid() -> None:
    acceptes: dict[str, int] = {}
    pairs = paires_pour_manque(CIBLES, acceptes, LEMMES, seed=1, pass_idx=0, inflation=1.0)
    assert all(m in LEMMES for m, _s in pairs)


def test_paires_deterministic_given_seed() -> None:
    a = paires_pour_manque(CIBLES, {}, LEMMES, seed=3, pass_idx=0, inflation=1.0)
    b = paires_pour_manque(CIBLES, {}, LEMMES, seed=3, pass_idx=0, inflation=1.0)
    assert a == b


def test_paires_pass_idx_varies_lemmes() -> None:
    p0 = paires_pour_manque(CIBLES, {}, LEMMES, seed=3, pass_idx=0, inflation=1.0)
    p1 = paires_pour_manque(CIBLES, {}, LEMMES, seed=3, pass_idx=1, inflation=1.0)
    # same shape, different lemma choices across passes
    assert [s for _m, s in p0] == [s for _m, s in p1]
    assert [m for m, _s in p0] != [m for m, _s in p1]
