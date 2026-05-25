"""Palier 3a — Upload du corpus d'entraînement sur un volume Modal.

OBJECTIF
--------
Pousser le corpus JSONL local sur un volume Modal persistant nommé
``mots-fleches-datasets``, monté sur ``/datasets``. Ce volume sera
utilisé en lecture seule par le palier 3b (fine-tune).

Deux modes :

- ``--mode fused`` (défaut) : ``data/lora/modal_corpus_v1/
  {train,val}.jsonl`` (corpus multi-source de PR 4b).
- ``--mode gold-only`` : ``data/seed/gold_pilot_v1_{train,val}.jsonl``
  (chemin smoke pilote — output de
  ``scripts/clue_generation/modal/prepare_dataset.py``).

Les noms côté volume sont stables (``/datasets/train.jsonl`` +
``/datasets/val.jsonl``) — palier 3b ne distingue pas le mode source.

DURÉE / COÛT
------------
Quelques secondes, fractions de cent (CPU léger, pas de GPU).

COMMANDE POUR LANCER
--------------------
Depuis la racine du projet :

    modal run modal_jobs/03a_upload_dataset.py                    # fused
    modal run modal_jobs/03a_upload_dataset.py --mode gold-only   # smoke
"""

from pathlib import Path

import modal


ROOT = Path(__file__).resolve().parent.parent

# Fused-corpus paths (default). The gold-only smoke path remains
# available via `--mode gold-only` on the local entrypoint.
TRAIN_PATH = ROOT / "data" / "lora" / "modal_corpus_v1" / "train.jsonl"
VAL_PATH = ROOT / "data" / "lora" / "modal_corpus_v1" / "val.jsonl"

# Nom de l'app Modal — visible dans le dashboard
app = modal.App("mots-fleches-upload-dataset")

# Volume persistant pour stocker les datasets. Distinct du volume modèles
# `mots-fleches-models` (utilisé au palier 2), afin de séparer les
# préoccupations et faciliter les évolutions futures (versions de dataset,
# etc.).
volume = modal.Volume.from_name(
    "mots-fleches-datasets",
    create_if_missing=True,
)

# Image minimaliste : pas besoin de torch ni de huggingface_hub ici,
# on ne fait que copier des octets sur le volume.
image = modal.Image.debian_slim(python_version="3.11")


@app.function(
    image=image,
    volumes={"/datasets": volume},
    timeout=300,  # 5 min plafond — bien plus que nécessaire (~quelques s)
)
def upload_dataset(train_content: str, val_content: str) -> dict:
    """Écrit les 2 JSONL reçus en argument sur le volume `/datasets`.

    Les contenus sont passés en arguments de `.remote()` côté local
    (Modal sérialise et transfère). 114 entrées = quelques Ko, bien sous
    la limite de Modal (~256 Mo par argument).
    """
    from pathlib import Path

    cible = Path("/datasets")
    cible.mkdir(parents=True, exist_ok=True)

    # Stable volume-side names so palier 3b doesn't care which corpus
    # (fused vs gold-only) was uploaded.
    train_path = cible / "train.jsonl"
    val_path = cible / "val.jsonl"

    train_path.write_text(train_content, encoding="utf-8")
    val_path.write_text(val_content, encoding="utf-8")

    # CRITIQUE : commit pour persister les fichiers sur le volume
    print("[CONTAINER] volume.commit() — persistance des datasets...")
    volume.commit()
    print("[CONTAINER] Volume committé.")

    # Listing du volume après upload
    fichiers: list[dict] = []
    for f in sorted(cible.rglob("*")):
        if not f.is_file():
            continue
        with f.open(encoding="utf-8") as fh:
            nb_lignes = sum(1 for _ in fh)
        fichiers.append({
            "name": str(f.relative_to(cible)),
            "size_bytes": f.stat().st_size,
            "lines": nb_lignes,
        })

    return {
        "status": "uploaded",
        "files": fichiers,
    }


@app.local_entrypoint()
def main(mode: str = "fused") -> None:
    """Lit les JSONL en local et lance leur upload sur Modal.

    Modes:
      - ``fused`` (default): uploads ``data/lora/modal_corpus_v1/
        {train,val}.jsonl`` (multi-source corpus from PR 4b).
      - ``gold-only``: uploads ``data/seed/gold_pilot_v1_{train,val}.jsonl``
        (gold pilot smoke path, output of
        ``scripts/clue_generation/modal/prepare_dataset.py``).
    """
    if mode not in ("fused", "gold-only"):
        raise SystemExit(
            f"ERREUR : mode invalide '{mode}'. "
            f"Valeurs acceptées : fused, gold-only."
        )

    if mode == "gold-only":
        train_path = ROOT / "data" / "seed" / "gold_pilot_v1_train.jsonl"
        val_path = ROOT / "data" / "seed" / "gold_pilot_v1_val.jsonl"
        prepare_hint = (
            "python3 scripts/clue_generation/modal/prepare_dataset.py"
        )
    else:
        train_path = TRAIN_PATH
        val_path = VAL_PATH
        prepare_hint = (
            "python3 -m scripts.clue_generation.modal.build_modal_corpus"
        )

    print(f"[LOCAL] Mode : {mode}")

    if not train_path.exists() or not val_path.exists():
        raise SystemExit(
            f"ERREUR : fichiers JSONL introuvables.\n"
            f"  - {train_path}\n  - {val_path}\n"
            f"Lance d'abord : {prepare_hint}"
        )

    train_content = train_path.read_text(encoding="utf-8")
    val_content = val_path.read_text(encoding="utf-8")

    print(f"[LOCAL] Lecture {train_path.name} : "
          f"{len(train_content)} octets, "
          f"{train_content.count(chr(10))} lignes")
    print(f"[LOCAL] Lecture {val_path.name}   : "
          f"{len(val_content)} octets, "
          f"{val_content.count(chr(10))} lignes")
    print()
    print("[LOCAL] Envoi vers Modal et écriture sur volume "
          "`mots-fleches-datasets`...")

    resultat = upload_dataset.remote(train_content, val_content)

    print()
    print(f"[LOCAL] Status : {resultat['status']}")
    print(f"[LOCAL] Fichiers sur le volume :")
    for f in resultat["files"]:
        print(f"   {f['name']:35s}  {f['size_bytes']:>8d} octets  "
              f"{f['lines']:>4d} lignes")

    print()
    print(f"Palier 3a validé (mode={mode}) : corpus disponible sur le "
          "volume `mots-fleches-datasets` (lecture seule au palier 3b).")
