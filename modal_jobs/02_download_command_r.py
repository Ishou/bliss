"""Download Command-R-08-2024 (35B) to Modal volume. Fork of 02_download_mistral.py for the command-r experiment."""

import os
import time
from pathlib import Path

import modal


app = modal.App("mots-fleches-download-command-r")

volume = modal.Volume.from_name(
    "mots-fleches-models",
    create_if_missing=True,
)

image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install("huggingface_hub>=0.24")
)

# Command-R 35B from Cohere — unsloth's pre-quantized bnb-4bit redistribution (typically un-gated).
HF_REPO_ID = "unsloth/c4ai-command-r-08-2024-bnb-4bit"
LOCAL_DIR = "/models/c4ai-command-r-08-2024-bnb-4bit"

ALLOW_PATTERNS = [
    "*.safetensors",
    "model.safetensors.index.json",
    "config.json",
    "generation_config.json",
    "tokenizer*",
    "special_tokens_map.json",
]
IGNORE_PATTERNS = ["*.bin", "*.pt", "*.h5", "*.msgpack"]


@app.function(
    image=image,
    volumes={"/models": volume},
    secrets=[modal.Secret.from_name("huggingface")],
    timeout=3600,  # 60 minutes — command-r is larger than Mistral-Nemo
)
def download() -> dict:
    from huggingface_hub import snapshot_download

    config_path = Path(LOCAL_DIR) / "config.json"
    index_path = Path(LOCAL_DIR) / "model.safetensors.index.json"
    fully_ok = config_path.exists() and index_path.exists()

    if fully_ok:
        files = list(Path(LOCAL_DIR).iterdir())
        total_gb = sum(f.stat().st_size for f in files if f.is_file()) / (1024 ** 3)
        return {"status": "skipped", "model_path": LOCAL_DIR, "total_size_gb": f"{total_gb:.2f}"}

    print(f"[CONTAINER] downloading {HF_REPO_ID} → {LOCAL_DIR}")
    start = time.perf_counter()
    snapshot_download(
        repo_id=HF_REPO_ID,
        local_dir=LOCAL_DIR,
        token=os.environ["HF_TOKEN"],
        allow_patterns=ALLOW_PATTERNS,
        ignore_patterns=IGNORE_PATTERNS,
    )
    elapsed = time.perf_counter() - start
    print(f"[CONTAINER] download done in {elapsed:.1f}s")

    volume.commit()
    print("[CONTAINER] volume committed")

    total_gb = sum(
        f.stat().st_size for f in Path(LOCAL_DIR).iterdir() if f.is_file()
    ) / (1024 ** 3)
    return {"status": "downloaded", "model_path": LOCAL_DIR, "total_size_gb": f"{total_gb:.2f}", "elapsed_s": round(elapsed, 1)}


@app.local_entrypoint()
def main():
    result = download.remote()
    print(result)
