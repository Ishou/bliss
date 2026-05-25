import os
import time
from pathlib import Path

import modal


# Nom de l'app Modal — visible dans le dashboard
app = modal.App("mots-fleches-download")

# Persistent Modal volume; create_if_missing ensures it exists on first use.
volume = modal.Volume.from_name(
    "mots-fleches-models",
    create_if_missing=True,
)

# Minimal image for HF download; heavy deps (torch etc.) added at palier 3.
image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install("huggingface_hub>=0.24")
)

# Constantes du modèle
HF_REPO_ID = "mistralai/Mistral-Nemo-Base-2407"
LOCAL_DIR = "/models/Mistral-Nemo-Base-2407"

# Include safetensors + tokenizer/config; exclude redundant formats (.bin, .pt, .h5).
ALLOW_PATTERNS = [
    "*.safetensors",
    "model.safetensors.index.json",  # shard manifest required by transformers
    "config.json",
    "generation_config.json",
    "tokenizer*",
    "special_tokens_map.json",
    "merges.txt",
]
# consolidated.safetensors is the Mistral Inference format (~23 GB), redundant with HF shards.
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
    """Download Mistral Nemo Base 2407 to the Modal volume; idempotent, commits volume after download."""
    from huggingface_hub import snapshot_download

    config_path = Path(LOCAL_DIR) / "config.json"
    index_path = Path(LOCAL_DIR) / "model.safetensors.index.json"
    consolidated_path = Path(LOCAL_DIR) / "consolidated.safetensors"

    # Repair: remove consolidated.safetensors if a previous run pulled it erroneously.
    actions: list[str] = []
    if consolidated_path.exists():
        gain_gb = consolidated_path.stat().st_size / (1024 ** 3)
        consolidated_path.unlink()
        actions.append(f"consolidated.safetensors supprimé "
                       f"({gain_gb:.2f} Go libérés)")
        print(f"[REPAIR] consolidated.safetensors supprimé "
              f"({gain_gb:.2f} Go libérés)")

    # Model is fully usable only if both config.json and the shard manifest are present.
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

    # snapshot_download skips already-present files; only missing ones are fetched.
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

    # Commit persists both the repair (consolidated removal) and any new downloads.
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
    """List files under `racine` recursively, sorted, excluding .cache/."""
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
