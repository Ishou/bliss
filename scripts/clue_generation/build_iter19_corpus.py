#!/usr/bin/env python3
"""Build iter19 DPO training corpus = iter18 DPO carryover + iter19 English-leak pairs.

Why iter19 exists
-----------------
iter18's v10 verified that the new prompt schema + CROSS_LINGUAL_HOMOGRAPH
bucket fix the FR/EN homograph class (`cane → "Femelle du canard"`
generates cleanly at v10 across T=0.3/0.6/1.1). iter19 widens the
attack surface to non-homograph lemmas where the multilingual base
still leaks toward English — `chien → "Man's best friend"`,
`voiture → "Motor vehicle"`, `ordinateur → "Le computer"` — by
adding 693 hand-authored (chosen, rejected) pairs across 58 lemmas
from `iter19_english_rejection_pairs.py` (two sub-buckets:
WHOLLY_ENGLISH_CLUE and MIXED_LANGUAGE_LEAK).

Corpus shape
------------
iter19 = iter18 DPO data (already in new schema, no rebuild needed)
       + iter19_english_rejection_pairs.all_pairs() (re-emit into the
         same `{system, prompt, chosen, rejected}` shape).

We resume from v10 (iter18 DPO output), so the iter18 corpus carry-
forward keeps the model anchored on iter18's wins (cane, errer, clair,
user, triple, sucre, advenir) while iter19's English-leak signal lands
on top.

This is DPO-only: no fresh SFT lane. The model already imitates French
style from v9; iter19 only teaches "reject English-language outputs".

OUTPUTS:
  data/lora_dpo_iter19/{train,valid,test}.jsonl  (DPO — for v11 on top of v10)

Schema (same as iter18):
  {"system": "<SYSTEM_PROMPT>",
   "prompt": "Génère une définition mots-fléchés courte pour: <lemma-lc> [<pos>]",
   "chosen": "<French clue>",
   "rejected": "<English-leak clue>"}
"""
from __future__ import annotations
import json, random, sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "clue_generation"))

ITER18_DPO_DIR = REPO / "data" / "lora_dpo_iter18"
OUT_DPO_DIR    = REPO / "data" / "lora_dpo_iter19"

SYSTEM_PROMPT = (
    "Tu es un générateur de définitions pour mots fléchés en français. "
    "Tu réponds toujours en français, jamais en anglais. "
    "Ta réponse est une définition courte, idiomatique, sans le mot à deviner."
)
USER_TEMPLATE = "Génère une définition mots-fléchés courte pour: {lemma} [{pos}]"

# Distinct seed from iter18 (20260511) so the train/valid/test split is
# independent and doesn't accidentally re-use iter18's test set.
SEED = 20260512
random.seed(SEED)


def _normalize_pos(label: str) -> str:
    label = label.strip().lower()
    return {"adjectif": "adj", "adverbe": "adv"}.get(label, label)


def _rebuild_user(lemma: str, pos: str) -> str:
    return USER_TEMPLATE.format(lemma=lemma.lower(), pos=pos or "nom")


def _load_iter18_dpo_pairs() -> list[dict]:
    """Carry forward iter18's deduped DPO corpus verbatim (already in the
    new schema — system + lowercase prompt + chosen/rejected)."""
    out: list[dict] = []
    for split in ("train", "valid", "test"):
        path = ITER18_DPO_DIR / f"{split}.jsonl"
        with path.open(encoding="utf-8") as f:
            for line in f:
                if not line.strip():
                    continue
                obj = json.loads(line)
                out.append({
                    "system":   obj.get("system", SYSTEM_PROMPT),
                    "prompt":   obj["prompt"],
                    "chosen":   obj["chosen"],
                    "rejected": obj["rejected"],
                })
    return out


def _load_iter19_authored() -> list[dict]:
    """Pull from iter19_english_rejection_pairs.py — 693 English-leak pairs
    across 58 lemmas (WHOLLY_ENGLISH_CLUE + MIXED_LANGUAGE_LEAK)."""
    from iter19_english_rejection_pairs import all_pairs  # type: ignore
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
    iter18 = _load_iter18_dpo_pairs()
    authored = _load_iter19_authored()
    print(f"DPO sources: iter18={len(iter18)} iter19-authored={len(authored)}",
          file=sys.stderr)

    all_pairs_ = iter18 + authored
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
    splits = build_dpo()
    for split, rows in splits.items():
        out = OUT_DPO_DIR / f"{split}.jsonl"
        write_jsonl(rows, out)
        print(f"  wrote {out}  ({len(rows)} rows)", file=sys.stderr)
    print(
        f"\nDPO totals: train={len(splits['train'])} "
        f"valid={len(splits['valid'])} test={len(splits['test'])}",
        file=sys.stderr,
    )


if __name__ == "__main__":
    main()
