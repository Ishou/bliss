"""Palier 2 — Téléchargement de Mistral Nemo Base 2407 sur volume Modal.

OBJECTIF
--------
Télécharger le modèle de base mistralai/Mistral-Nemo-Base-2407 (~24 Go,
poids .safetensors) sur un volume Modal persistant nommé
`mots-fleches-models`, monté sur `/models` dans le container. Ce
volume sera réutilisé par les paliers suivants (palier 3 fine-tune,
palier 4 inférence, etc.) — pas besoin de re-télécharger à chaque fois.

CE QUE CE SCRIPT VALIDE
-----------------------
1. Le secret Modal `huggingface` (avec HF_TOKEN) est correctement
   injecté dans le container.
2. Le volume persistant `mots-fleches-models` se crée (si absent) et
   se monte sur `/models`.
3. `huggingface_hub.snapshot_download` peut tirer le modèle (preuve
   que l'accès gated Mistral est OK pour ton compte HF).
4. `volume.commit()` persiste les fichiers (sinon ils disparaissent à
   la fin de la session — bug classique sur Modal).
5. Idempotence : un 2e lancement détecte le modèle déjà présent et
   skip en quelques secondes.

COÛT ATTENDU
------------
- Bande passante : Modal ne facture pas le download depuis HF
  (sortie réseau gratuite côté Modal entrant).
- Stockage volume : ≈ 0,13 $/mois pour ~24 Go.
- Container CPU pendant le download : ≈ 0,03 $/heure × 0,2 h = 0,01 $
  par run.

DURÉE ATTENDUE
--------------
- Premier run : 5-15 min selon bande passante réseau (HF → Modal).
- Runs suivants : 5-10 s (idempotence, skip immédiat).

IDÉMPOTENCE
-----------
Le script vérifie l'existence de `/models/Mistral-Nemo-Base-2407/
config.json` avant de lancer le download. Si présent → skip.

COMMANDE POUR LANCER
--------------------
Depuis la racine du projet :

    modal run modal_jobs/02_download_mistral.py
"""

import os
import time
from pathlib import Path

import modal


# Nom de l'app Modal — visible dans le dashboard
app = modal.App("mots-fleches-download")

# Volume persistant Modal. `create_if_missing=True` crée le volume au
# premier appel et le réutilise ensuite. Le même volume peut être
# monté par d'autres apps en utilisant exactement le même nom.
volume = modal.Volume.from_name(
    "mots-fleches-models",
    create_if_missing=True,
)

# Image Docker : juste ce qu'il faut pour télécharger depuis HF.
# Pas besoin de torch/transformers/accelerate pour ce palier — on
# stockera ces lourdes dépendances dans l'image du palier 3.
image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install("huggingface_hub>=0.24")
)

# Constantes du modèle
HF_REPO_ID = "mistralai/Mistral-Nemo-Base-2407"
LOCAL_DIR = "/models/Mistral-Nemo-Base-2407"

# Patterns d'inclusion/exclusion pour `snapshot_download`.
# INCLUDE : poids safetensors + tous les fichiers tokenizer/config nécessaires.
# EXCLUDE : formats redondants (.bin pour anciennes versions PyTorch,
# .pt checkpoints, .h5 Keras, .msgpack Flax). Pratique défensive même
# si Mistral Nemo n'a normalement que des .safetensors.
ALLOW_PATTERNS = [
    "*.safetensors",
    "model.safetensors.index.json",  # manifest des shards (sinon
                                     # transformers ne peut pas charger)
    "config.json",
    "generation_config.json",
    "tokenizer*",
    "special_tokens_map.json",
    "merges.txt",
]
# `consolidated.safetensors` (~23 Go) = format Mistral Inference,
# redondant avec les shards `model-0000X-of-...` du format HF Transformers
# qu'on veut pour le fine-tune. Économie ~23 Go.
IGNORE_PATTERNS = [
    "consolidated.safetensors",
    "*.bin", "*.pt", "*.h5", "*.msgpack",
]


@app.function(
    image=image,
    volumes={"/models": volume},
    secrets=[modal.Secret.from_name("huggingface")],
    timeout=1800,  # 30 minutes plafond — sécurité runaway
)
def download_mistral_nemo() -> dict:
    """Télécharge Mistral Nemo Base 2407 sur le volume Modal.

    Idempotent : skip si `config.json` du modèle est déjà présent.
    Commit explicite du volume après download pour persistance.
    """
    from huggingface_hub import snapshot_download

    config_path = Path(LOCAL_DIR) / "config.json"
    index_path = Path(LOCAL_DIR) / "model.safetensors.index.json"
    consolidated_path = Path(LOCAL_DIR) / "consolidated.safetensors"

    # === Étape repair : nettoyer un éventuel état dégradé ===
    # Issue connue : un premier run avec allow_patterns trop permissifs
    # a pu tirer consolidated.safetensors (~23 Go, format Mistral
    # Inference) en plus des shards HF. On le supprime ici.
    actions: list[str] = []
    if consolidated_path.exists():
        gain_gb = consolidated_path.stat().st_size / (1024 ** 3)
        consolidated_path.unlink()
        actions.append(f"consolidated.safetensors supprimé "
                       f"({gain_gb:.2f} Go libérés)")
        print(f"[REPAIR] consolidated.safetensors supprimé "
              f"({gain_gb:.2f} Go libérés)")

    # === Check idempotence COMPLÈTE ===
    # Le modèle est utilisable seulement si on a config.json ET le
    # manifest des shards (model.safetensors.index.json).
    fully_ok = config_path.exists() and index_path.exists()

    if fully_ok and not actions:
        # Tout déjà OK, rien à faire
        files = _lister_fichiers(LOCAL_DIR)
        total_bytes = sum(f["size_bytes"] for f in files)
        return {
            "status": "skipped",
            "model_path": LOCAL_DIR,
            "files": [{"name": f["name"],
                       "size_mb": f"{f['size_bytes'] / (1024 ** 2):.1f}"}
                      for f in files],
            "total_size_gb": f"{total_bytes / (1024 ** 3):.2f}",
            "download_time_seconds": 0,
            "actions": [],
        }

    # === Download (ou complétion) ===
    # snapshot_download avec local_dir ne re-télécharge pas les fichiers
    # déjà présents → si on a déjà tous les shards et qu'il manque
    # juste l'index.json, seul ce dernier sera tiré (< 1 Mo).
    if fully_ok:
        print("[CONTAINER] Fichiers principaux déjà présents, "
              "rien à compléter en download.")
        elapsed = 0.0
    else:
        print(f"[CONTAINER] Téléchargement (ou complétion) "
              f"{HF_REPO_ID} vers {LOCAL_DIR}...")
        print(f"[CONTAINER] allow_patterns = {ALLOW_PATTERNS}")
        print(f"[CONTAINER] ignore_patterns = {IGNORE_PATTERNS}")
        start = time.perf_counter()
        snapshot_download(
            repo_id=HF_REPO_ID,
            local_dir=LOCAL_DIR,
            token=os.environ["HF_TOKEN"],
            allow_patterns=ALLOW_PATTERNS,
            ignore_patterns=IGNORE_PATTERNS,
        )
        elapsed = time.perf_counter() - start
        print(f"[CONTAINER] Download/complétion terminé en {elapsed:.1f} s")
        actions.append(f"snapshot_download exécuté ({elapsed:.1f} s)")

    # === Commit du volume ===
    # CRITIQUE : persiste à la fois la suppression de consolidated et
    # les nouveaux fichiers téléchargés.
    print("[CONTAINER] volume.commit() — persistance des changements...")
    volume.commit()
    print("[CONTAINER] Volume committé.")

    files = _lister_fichiers(LOCAL_DIR)
    total_bytes = sum(f["size_bytes"] for f in files)

    return {
        "status": "downloaded" if not fully_ok else "repaired",
        "model_path": LOCAL_DIR,
        "files": [{"name": f["name"],
                   "size_mb": f"{f['size_bytes'] / (1024 ** 2):.1f}"}
                  for f in files],
        "total_size_gb": f"{total_bytes / (1024 ** 3):.2f}",
        "download_time_seconds": f"{elapsed:.1f}",
        "actions": actions,
    }


def _lister_fichiers(racine: str) -> list[dict]:
    """Liste récursivement les fichiers de `racine`, avec leur taille
    en bytes. Trié alphabétiquement. Exclut `.cache/huggingface/`
    (cache interne HF de quelques kilo-octets, non utile à afficher).
    """
    p = Path(racine)
    fichiers: list[dict] = []
    for f in sorted(p.rglob("*")):
        if not f.is_file():
            continue
        rel = str(f.relative_to(p))
        if rel.startswith(".cache/"):
            continue
        fichiers.append({"name": rel, "size_bytes": f.stat().st_size})
    return fichiers


@app.local_entrypoint()
def main() -> None:
    """Point d'entrée local : lance le download et formate la sortie."""
    print("[LOCAL] Lancement download Mistral Nemo Base 2407...")
    print("[LOCAL] (premier run : 5-15 min ; runs suivants : instantané)")
    print()

    resultat = download_mistral_nemo.remote()

    status = resultat["status"]
    print()
    if status == "skipped":
        print(f"[LOCAL] Modèle déjà présent dans {resultat['model_path']},"
              " skip download.")
    elif status == "repaired":
        print(f"[LOCAL] Réparation effectuée sur {resultat['model_path']} :")
        for action in resultat.get("actions", []):
            print(f"        - {action}")
    else:
        print(f"[LOCAL] Modèle téléchargé en "
              f"{resultat['download_time_seconds']} s.")

    print(f"[LOCAL] Taille totale : {resultat['total_size_gb']} Go")
    print(f"[LOCAL] {len(resultat['files'])} fichiers dans "
          f"{resultat['model_path']} :")
    for f in resultat["files"]:
        print(f"  {f['name']:50s} {f['size_mb']:>10s} Mo")

    print()
    print("Palier 2 validé : Mistral Nemo Base 2407 disponible sur le "
          "volume persistant `mots-fleches-models`.")
    print("Le volume sera réutilisé tel quel au palier 3 (fine-tune).")
