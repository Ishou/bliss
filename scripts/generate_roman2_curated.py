#!/usr/bin/env python3
"""Generate data/curated/roman2.csv: every canonical 2-letter Roman numeral
with an arithmetic-decomposition clue (e.g. MM -> "M + M", IX -> "X - I")."""

import csv
from pathlib import Path

NUMERALS = [
    (1000, "M"), (900, "CM"), (500, "D"), (400, "CD"),
    (100, "C"), (90, "XC"), (50, "L"), (40, "XL"),
    (10, "X"), (9, "IX"), (5, "V"), (4, "IV"), (1, "I"),
]


def to_roman(n: int) -> str:
    out = []
    for value, symbol in NUMERALS:
        while n >= value:
            out.append(symbol)
            n -= value
    return "".join(out)


def is_canonical_roman(s: str) -> bool:
    if not s:
        return False
    total = 0
    value_of = {"I": 1, "V": 5, "X": 10, "L": 50, "C": 100, "D": 500, "M": 1000}
    prev = 0
    for ch in reversed(s):
        v = value_of.get(ch)
        if v is None:
            return False
        total += -v if v < prev else v
        prev = max(prev, v)
    return to_roman(total) == s


def best_decomposition(value: int, word: str) -> str:
    word_letters = sorted(word)
    candidates = []

    for a in range(1, value // 2 + 1):
        b = value - a
        ra, rb = to_roman(a), to_roman(b)
        obvious = len(ra) == 1 and len(rb) == 1 and ra != rb and sorted(ra + rb) == word_letters
        candidates.append((1 if obvious else 0, len(ra) + len(rb), abs(b - a), -a, f"{ra} + {rb}"))

    for b in range(1, value):
        a = value + b
        if a > 3999:
            break
        ra, rb = to_roman(a), to_roman(b)
        candidates.append((0, len(ra) + len(rb), b, -a, f"{ra} - {rb}"))

    candidates.sort()
    return candidates[0][4]


def two_letter_canonical_romans() -> list[tuple[str, int]]:
    letters = ["I", "V", "X", "L", "C", "D", "M"]
    value_of = dict(zip(letters, [1, 5, 10, 50, 100, 500, 1000]))
    results = []
    for a in letters:
        for b in letters:
            s = a + b
            v = value_of[b] - value_of[a] if value_of[a] < value_of[b] else value_of[a] + value_of[b]
            if v > 0 and to_roman(v) == s:
                results.append((s, v))
    results.sort(key=lambda x: x[0])
    return results


def main() -> None:
    repo_root = Path(__file__).resolve().parent.parent
    out_path = repo_root / "data" / "curated" / "roman2.csv"
    out_path.parent.mkdir(parents=True, exist_ok=True)

    rows = two_letter_canonical_romans()
    with out_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f, quoting=csv.QUOTE_MINIMAL, lineterminator="\n")
        writer.writerow(["word", "language", "length", "frequency", "difficulty", "clue", "source", "source_license", "lemma"])
        for word, value in rows:
            writer.writerow([word, "fr", 2, 100000, "", best_decomposition(value, word), "bliss", "CC0-1.0", word])

    print(f"Wrote {len(rows)} entries to {out_path.relative_to(repo_root)}")


if __name__ == "__main__":
    main()
