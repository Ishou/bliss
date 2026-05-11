#!/usr/bin/env python3
"""Build iter18 SFT + DPO training corpora with the new prompt schema.

Schema (matches the post-PR-#366 production pipeline exactly):

  System message (every prompt):
    "Tu es un générateur de définitions pour mots fléchés en français.
     Tu réponds toujours en français, jamais en anglais. Ta réponse est
     une définition courte, idiomatique, sans le mot à deviner."

  User prompt:
    "Génère une définition mots-fléchés courte pour: <lemma-lowercase> [<pos>]"

  Assistant: <clue>

This replaces the iter12+ training format which used UPPERCASE lemmas
and no system message. The 2026-05-11 diagnostic on the existing v8
adapter showed both changes are real signal lifters:

  • lowercase   → +2 wins (errer, clair) via removing the English-headline prior
  • system msg  → +2 wins (user, triple) via cross-lingual anchor

Combined with iter17's 227 coord-long preference pairs and the 385
authored diverse pairs (`iter18_authored_pairs.py`), iter18 should
lock in the schema and the wins together.

OUTPUTS:
  data/lora_iter18_sft/{train,valid,test}.jsonl   (SFT — for v9 fresh SFT base)
  data/lora_dpo_iter18/{train,valid,test}.jsonl   (DPO — for v10 on top of v9)

mlx-lm-lora's DPODataset reads `{"system", "prompt", "chosen",
"rejected"}` fields and constructs messages via the chat template
(see .venv/.../mlx_lm_lora/trainer/datasets.py:177 — `DPODataset`).
SFT uses the `{"messages": [...]}` shape with `system` / `user` /
`assistant` roles.
"""
from __future__ import annotations
import csv, json, random, re, sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "clue_generation"))
sys.path.insert(0, str(REPO / "scripts" / "eval"))
from clue_metrics import MAX_CLUE_CHARS  # noqa: E402

# Hard cell-fit budget. Any SFT or DPO chosen clue exceeding this is
# dropped at build time — teaching the model to emit over-cap clues
# wastes training on rows the merge pipeline would later refuse to
# ship. The inherited iter14 SFT data has ~40 such rows (long
# definitional clues from before MAX_CLUE_CHARS was tightened to 25).
MAX_SFT_CHARS = MAX_CLUE_CHARS

# Existing iter14 + iter17 sources to carry forward.
ITER14_SFT_DIR    = REPO / "data" / "lora"                          # data/lora/{train,valid,test}.jsonl
ITER17_DPO_DIR    = REPO / "data" / "lora_dpo_iter17_combined"      # iter14-base + coord-long + 14 hand-pairs
RAW_POS           = REPO / "data" / "eval" / "production" / "lemma_clues_raw_pos.csv"

OUT_SFT_DIR       = REPO / "data" / "lora_iter18_sft"
OUT_DPO_DIR       = REPO / "data" / "lora_dpo_iter18"

SYSTEM_PROMPT = (
    "Tu es un générateur de définitions pour mots fléchés en français. "
    "Tu réponds toujours en français, jamais en anglais. "
    "Ta réponse est une définition courte, idiomatique, sans le mot à deviner."
)
USER_TEMPLATE = "Génère une définition mots-fléchés courte pour: {lemma} [{pos}]"

SEED = 20260511
random.seed(SEED)


def _normalize_pos(label: str) -> str:
    """Match the iter17 normalization: verbe/nom/adj/adv."""
    label = label.strip().lower()
    return {"adjectif": "adj", "adverbe": "adv"}.get(label, label)


def _parse_old_user(content: str) -> tuple[str, str]:
    """Extract (lemma, pos) from the historical user prompt:
       'Génère une définition mots-fléchés courte pour: LEMMA [POS]'."""
    m = re.search(r"pour:\s*(\S+)(?:\s+\[([^\]]+)\])?", content)
    if not m:
        return "", ""
    return m.group(1), _normalize_pos(m.group(2) or "")


def _rebuild_user(lemma: str, pos: str) -> str:
    return USER_TEMPLATE.format(lemma=lemma.lower(), pos=pos or "nom")


# --------------------------------------------------------------------- #
# SFT corpus build                                                      #
# --------------------------------------------------------------------- #
def _sft_row(lemma: str, pos: str, clue: str) -> dict:
    return {
        "messages": [
            {"role": "system",    "content": SYSTEM_PROMPT},
            {"role": "user",      "content": _rebuild_user(lemma, pos)},
            {"role": "assistant", "content": clue},
        ]
    }


def _load_iter18_sft_extras() -> list[dict]:
    """Pull the chosen variants from iter18_authored_pairs.py into the
    SFT corpus. Without these the bug lemmas (cane / errer / sucre /
    user / triple / clair / advenir + ~37 analogous lemmas across
    cross-lingual / wrong-POS / near-synonym buckets) have ZERO
    representation in the SFT data — the model only meets them through
    DPO's contrastive signal, which iter17 already showed is too weak
    to override strong base-model priors. SFT-level imitation gives the
    direct (lemma → correct-clue) mapping every cross-lingual leak
    needs.

    One SFT row per (lemma, chosen_variant) — the multiple chosen
    variants per lemma in `iter18_authored_pairs.py` are deliberate
    paraphrase coverage."""
    from iter18_authored_pairs import (
        CROSS_LINGUAL_HOMOGRAPH,
        POS_GLOSS_MISMATCH,
        NEAR_SYNONYM_CONFUSION,
    )
    out: list[dict] = []
    over_cap_authored: list[tuple[str, str]] = []
    for bucket in (CROSS_LINGUAL_HOMOGRAPH, POS_GLOSS_MISMATCH, NEAR_SYNONYM_CONFUSION):
        for lemma, pos, chosens, _rejecteds in bucket:
            for clue in chosens:
                if len(clue) > MAX_SFT_CHARS:
                    over_cap_authored.append((lemma, clue))
                    continue
                out.append(_sft_row(lemma, _normalize_pos(pos), clue))
    if over_cap_authored:
        print(
            f"WARN: {len(over_cap_authored)} authored chosens skipped (over "
            f"{MAX_SFT_CHARS} chars). Shorten in iter18_authored_pairs.py:",
            file=sys.stderr,
        )
        for lemma, clue in over_cap_authored:
            print(f"  {lemma}: {clue!r} ({len(clue)} chars)", file=sys.stderr)
    return out


def build_sft() -> dict[str, list[dict]]:
    out: dict[str, list[dict]] = {"train": [], "valid": [], "test": []}
    skipped = 0
    over_cap = 0
    for split in out:
        src = ITER14_SFT_DIR / f"{split}.jsonl"
        with src.open(encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                obj = json.loads(line)
                msgs = obj.get("messages") or []
                # Find user + assistant
                user_msg = next((m for m in msgs if m.get("role") == "user"), None)
                asst_msg = next((m for m in msgs if m.get("role") == "assistant"), None)
                if not user_msg or not asst_msg:
                    skipped += 1
                    continue
                lemma, pos = _parse_old_user(user_msg["content"])
                if not lemma:
                    skipped += 1
                    continue
                clue = asst_msg["content"]
                if len(clue) > MAX_SFT_CHARS:
                    over_cap += 1
                    continue
                out[split].append(_sft_row(lemma, pos, clue))

    # Append iter18 authored chosen-variants as SFT rows. Stratify the
    # 90/5/5 split so a few land in valid/test for measurable evaluation
    # on the lemmas we actually care about.
    extras = _load_iter18_sft_extras()
    random.shuffle(extras)
    n = len(extras)
    n_valid_extra = max(int(round(n * 0.05)), 1)
    n_test_extra  = max(int(round(n * 0.05)), 1)
    out["valid"].extend(extras[:n_valid_extra])
    out["test"].extend(extras[n_valid_extra:n_valid_extra + n_test_extra])
    out["train"].extend(extras[n_valid_extra + n_test_extra:])

    print(
        f"SFT: train={len(out['train'])} valid={len(out['valid'])} test={len(out['test'])} "
        f"(extras from iter18_authored_pairs.py={n}; skipped={skipped}; "
        f"iter14-sft over-cap dropped={over_cap})",
        file=sys.stderr,
    )
    return out


# --------------------------------------------------------------------- #
# DPO corpus build                                                      #
# --------------------------------------------------------------------- #
_INFINITIVE_RE = re.compile(r"^(s(?:e|')\s*)?([A-ZÀ-Ÿ][a-zà-ÿ]+(?:er|ir|re|oir))\b")


def _extract_verb_head(clue: str) -> str | None:
    """Reused from build_iter17_dpo_corpus.py — pulls a leading verb-
    infinitive (with reflexive proclitic, if present) from a coord clue."""
    m = _INFINITIVE_RE.match(clue.strip())
    if not m:
        return None
    proclitic = (m.group(1) or "").strip()
    verb = m.group(2)
    if proclitic:
        return f"{proclitic.capitalize().replace('S ', 'Se ').strip()} {verb.lower()}".strip()
    return verb


def _load_iter17_dpo_pairs() -> list[dict]:
    """Re-extract the iter17 DPO pairs in the new schema. Reading the
    iter17 jsonl directly preserves the iter14 base + iter17 coord-long
    + 14 hand-authored wrong-sense pairs without re-mining."""
    out: list[dict] = []
    for split in ("train", "valid", "test"):
        path = ITER17_DPO_DIR / f"{split}.jsonl"
        with path.open(encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                obj = json.loads(line)
                old_prompt = obj["prompt"]
                lemma, pos = _parse_old_user(old_prompt)
                if not lemma:
                    continue
                out.append({
                    "system":   SYSTEM_PROMPT,
                    "prompt":   _rebuild_user(lemma, pos),
                    "chosen":   obj["chosen"],
                    "rejected": obj["rejected"],
                })
    return out


def _load_iter18_authored() -> list[dict]:
    """Pull from iter18_authored_pairs.py — 385 diverse pairs across the
    three iter17 failure modes."""
    from iter18_authored_pairs import all_pairs  # type: ignore
    out: list[dict] = []
    for lemma, pos, chosen, rejected in all_pairs():
        out.append({
            "system":   SYSTEM_PROMPT,
            "prompt":   _rebuild_user(lemma, _normalize_pos(pos)),
            "chosen":   chosen,
            "rejected": rejected,
        })
    return out


def build_dpo() -> dict[str, list[dict]]:
    iter17 = _load_iter17_dpo_pairs()
    authored = _load_iter18_authored()
    print(f"DPO sources: iter17={len(iter17)} authored={len(authored)}", file=sys.stderr)

    all_pairs_ = iter17 + authored
    # Dedup by (prompt, chosen, rejected) — same logic as iter17 builder.
    seen = set()
    deduped = []
    for p in all_pairs_:
        key = (p["prompt"], p["chosen"], p["rejected"])
        if key in seen:
            continue
        seen.add(key)
        deduped.append(p)
    print(f"  after dedup: {len(deduped)}", file=sys.stderr)

    random.shuffle(deduped)
    n = len(deduped)
    n_valid = max(int(round(n * 0.05)), 1)
    n_test  = max(int(round(n * 0.05)), 1)
    n_train = n - n_valid - n_test
    return {
        "train": deduped[:n_train],
        "valid": deduped[n_train:n_train + n_valid],
        "test":  deduped[n_train + n_valid:],
    }


def write_jsonl(rows: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def main() -> None:
    OUT_SFT_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DPO_DIR.mkdir(parents=True, exist_ok=True)

    sft = build_sft()
    for split, rows in sft.items():
        write_jsonl(rows, OUT_SFT_DIR / f"{split}.jsonl")
        print(f"  wrote {OUT_SFT_DIR}/{split}.jsonl ({len(rows)} rows)", file=sys.stderr)

    dpo = build_dpo()
    for split, rows in dpo.items():
        write_jsonl(rows, OUT_DPO_DIR / f"{split}.jsonl")
        print(f"  wrote {OUT_DPO_DIR}/{split}.jsonl ({len(rows)} rows)", file=sys.stderr)


if __name__ == "__main__":
    main()
