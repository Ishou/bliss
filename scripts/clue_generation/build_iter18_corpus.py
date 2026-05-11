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
def build_sft() -> dict[str, list[dict]]:
    out: dict[str, list[dict]] = {"train": [], "valid": [], "test": []}
    skipped = 0
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
                new_user = _rebuild_user(lemma, pos)
                out[split].append({
                    "messages": [
                        {"role": "system",    "content": SYSTEM_PROMPT},
                        {"role": "user",      "content": new_user},
                        {"role": "assistant", "content": asst_msg["content"]},
                    ]
                })
    print(f"SFT: train={len(out['train'])} valid={len(out['valid'])} test={len(out['test'])} (skipped={skipped})", file=sys.stderr)
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
