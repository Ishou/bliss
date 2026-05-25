"""Per-normalizer unit tests. Each normalizer returns ``(str, bool)``
where the bool flags whether the normalisation actually changed the
input (cf. ``normalizers.NormResult``)."""

from __future__ import annotations

import unicodedata

from . import normalizers as N


def test_norm_1_apostrophe_curly_quotes_left_alone():
    out, applied = N.norm_1_apostrophe("Forme d’avoir")
    assert out == "Forme d’avoir"
    assert applied is False


def test_norm_1_apostrophe_straight_to_curly():
    out, applied = N.norm_1_apostrophe("Forme d'avoir")
    assert out == "Forme d’avoir"
    assert applied is True


def test_norm_7_nfc_idempotent_on_nfc():
    nfc = unicodedata.normalize("NFC", "Épaule")
    out, applied = N.norm_7_nfc(nfc)
    assert out == nfc
    assert applied is False


def test_norm_7_nfc_converts_nfd_to_nfc():
    nfd = unicodedata.normalize("NFD", "Épaule")
    out, applied = N.norm_7_nfc(nfd)
    assert unicodedata.is_normalized("NFC", out)
    assert applied is True
