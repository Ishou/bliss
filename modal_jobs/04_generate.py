"""Palier 4 — round-N candidate generator + pipeline_v2 filtering (ADR-0057)."""

from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

import modal


ROOT = Path(__file__).resolve().parent.parent

STYLES_ACTIFS = [
    "definition_directe",
    "periphrase",
    "culturel",
    "cryptique",
    "fonction_role",
]

# Préfixe identique au SFT (prepare_dataset.py) ; suffixe style = conditionnement d'inférence.
USER_PROMPT_TEMPLATE = (
    "Donne une définition de mot fléché pour {mot}. Style : {style}."
)


app = modal.App("bliss-clue-round-generate")

volume_models = modal.Volume.from_name(
    "mots-fleches-models", create_if_missing=False,
)
volume_adapters = modal.Volume.from_name(
    "mots-fleches-adapters", create_if_missing=False,
)
volume_generations = modal.Volume.from_name(
    "mots-fleches-generations", create_if_missing=True,
)

# pipeline_v2 monté au build via add_local_dir(copy=True) — indépendant du runtime sync.
image = (
    modal.Image.from_registry("python:3.11-slim")
    .pip_install(
        "torch==2.5.0",
        "transformers==4.45.2",
        "peft==0.13.2",
        "bitsandbytes==0.44.1",
        "accelerate==1.0.1",
        "sentencepiece",
        "lingua-language-detector>=2.0",
    )
    .add_local_dir(
        str(ROOT / "scripts" / "clue_generation" / "pipeline_v2"),
        remote_path="/root/pipeline_v2",
        copy=True,
    )
)


def construire_prompt(mot: str, style: str) -> str:
    return USER_PROMPT_TEMPLATE.format(mot=mot, style=style)


def charger_lemmes(path: Path) -> list[str]:
    """Lit une colonne `mot` depuis un CSV séparé par `;` ou `,`."""
    if not path.exists():
        raise FileNotFoundError(f"Liste de lemmes introuvable : {path}")
    with path.open(encoding="utf-8", newline="") as f:
        sample = f.read(2048)
        f.seek(0)
        delim = ";" if sample.count(";") >= sample.count(",") else ","
        reader = csv.DictReader(f, delimiter=delim)
        if reader.fieldnames is None or "mot" not in reader.fieldnames:
            raise ValueError(
                f"Colonne `mot` manquante dans {path} "
                f"(colonnes vues : {reader.fieldnames})"
            )
        lemmes = [row["mot"].strip() for row in reader if row.get("mot")]
    if not lemmes:
        raise ValueError(f"Aucun lemme lu depuis {path}")
    return lemmes


@app.function(
    image=image,
    gpu="A100-40GB",
    timeout=1800,
    volumes={
        "/models": volume_models,
        "/adapters": volume_adapters,
        "/generations": volume_generations,
    },
    secrets=[modal.Secret.from_name("huggingface")],
)
def generate_remote(
    run_tag: str,
    round_n: int,
    lemmes: list[str],
    n_per_pair: int,
    source_batch: str,
) -> dict:
    """Charge l'adaptateur, génère N candidats par (lemme, style), filtre."""
    import datetime as dt
    from collections import Counter

    import torch
    from peft import PeftModel
    from transformers import (
        AutoModelForCausalLM,
        AutoTokenizer,
        BitsAndBytesConfig,
    )

    sys.path.insert(0, "/root")
    from pipeline_v2.run_pipeline import traiter_ligne

    # cuDNN SDPA est buggé sur Mistral (GQA + sliding window) — fallback Flash/MemEfficient/Math.
    torch.backends.cuda.enable_cudnn_sdp(False)

    base_path = "/models/Mistral-Nemo-Base-2407"
    adapter_path = f"/adapters/{run_tag}"
    if not Path(adapter_path).exists():
        raise FileNotFoundError(
            f"Adaptateur introuvable sur le volume : {adapter_path}"
        )

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )
    tokenizer = AutoTokenizer.from_pretrained(adapter_path)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
    # FA2 + Mistral exige padding_side="left" (transformers 4.45).
    tokenizer.padding_side = "left"

    base_model = AutoModelForCausalLM.from_pretrained(
        base_path,
        quantization_config=bnb_config,
        device_map="auto",
        torch_dtype=torch.bfloat16,
        attn_implementation="sdpa",
    )
    model = PeftModel.from_pretrained(base_model, adapter_path)
    model.train(False)

    generated_at = dt.datetime.now(dt.timezone.utc).isoformat()
    source_tag = f"modal_round_{round_n}"
    pairs = [(m, s) for m in lemmes for s in STYLES_ACTIFS]
    requested = len(pairs) * n_per_pair

    accepted: list[dict] = []
    dropped_by_filter: Counter[str] = Counter()
    n_returned = 0

    for mot, style in pairs:
        prompt = construire_prompt(mot, style)
        messages = [{"role": "user", "content": prompt}]
        inputs = tokenizer.apply_chat_template(
            messages, add_generation_prompt=True, return_tensors="pt",
        ).to(model.device)

        with torch.no_grad():
            outputs = model.generate(
                inputs,
                max_new_tokens=30,
                do_sample=(n_per_pair > 1),
                temperature=0.8 if n_per_pair > 1 else 1.0,
                top_p=0.95,
                num_return_sequences=n_per_pair,
                pad_token_id=tokenizer.eos_token_id,
            )

        for seq in outputs:
            text = tokenizer.decode(
                seq[inputs.shape[1]:], skip_special_tokens=True,
            ).strip()
            n_returned += 1
            candidate = {
                "mot": mot,
                "definition": text,
                "pos": "autre",
                "categorie": "autre",
                "style": style,
                "force": "3",
                "longueur": str(len(mot)),
                "source": source_tag,
                "meta": "",
            }
            verdict = traiter_ligne(candidate)
            if verdict["pipeline_status"] == "reject":
                first_reason = verdict["pipeline_reasons"].split(";")[0]
                filter_id = first_reason.split(":")[0].strip() or "unknown"
                dropped_by_filter[filter_id] += 1
                continue

            accepted.append({
                "mot": mot,
                "definition": text,
                "pos": "autre",
                "categorie": "autre",
                "style": style,
                "force_estimated": 3,
                "longueur": len(mot),
                "source": source_tag,
                "source_batch": source_batch,
                "generated_at": generated_at,
            })

    out_dir = Path(f"/generations/round_{round_n}")
    out_dir.mkdir(parents=True, exist_ok=True)
    candidates_path = out_dir / "candidates.jsonl"
    with candidates_path.open("w", encoding="utf-8") as f:
        for row in accepted:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")

    summary = {
        "requested": requested,
        "generated": n_returned,
        "pipeline_v2_passed": len(accepted),
        "dropped_by_filter": dict(dropped_by_filter),
    }
    (out_dir / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    volume_generations.commit()
    return summary


@app.local_entrypoint()
def generate(
    run_tag: str = "mistral-nemo-pilot-v1",
    round: int = 1,
    lemmas: str = "data/curated/round_1_lemmas.csv",
    n_per_pair: int = 1,
) -> None:
    """Lance la génération round-N et affiche le récap."""
    import uuid

    lemmes_path = ROOT / lemmas if not Path(lemmas).is_absolute() else Path(lemmas)
    lemmes = charger_lemmes(lemmes_path)
    source_batch = f"{run_tag}-r{round}-{uuid.uuid4().hex[:8]}"

    print(f"[LOCAL] run_tag      : {run_tag}")
    print(f"[LOCAL] round        : {round}")
    print(f"[LOCAL] lemmes       : {len(lemmes)} (depuis {lemmes_path})")
    print(f"[LOCAL] styles       : {len(STYLES_ACTIFS)}")
    print(f"[LOCAL] n_per_pair   : {n_per_pair}")
    print(f"[LOCAL] source_batch : {source_batch}")
    print(
        f"[LOCAL] requested    : "
        f"{len(lemmes) * len(STYLES_ACTIFS) * n_per_pair}"
    )

    summary = generate_remote.remote(
        run_tag=run_tag,
        round_n=round,
        lemmes=lemmes,
        n_per_pair=n_per_pair,
        source_batch=source_batch,
    )

    print()
    print("=" * 60)
    print("RÉCAP GÉNÉRATION")
    print("=" * 60)
    print(f"Demandés                   : {summary['requested']}")
    print(f"Générés                    : {summary['generated']}")
    print(f"Acceptés (pipeline_v2 OK)  : {summary['pipeline_v2_passed']}")
    if summary["dropped_by_filter"]:
        print("Drops par filtre :")
        for fid, n in sorted(
            summary["dropped_by_filter"].items(), key=lambda kv: -kv[1],
        ):
            print(f"  {fid:35s} {n}")
    else:
        print("Aucun drop pipeline_v2.")


if __name__ == "__main__":
    sys.exit("Lance ce fichier via `modal run modal_jobs/04_generate.py::generate`.")
