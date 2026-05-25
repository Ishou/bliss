import time

import modal


# Nom de l'app Modal — visible dans le dashboard
app = modal.App("mots-fleches-gpu-check")

# debian_slim + torch 2.5.0 with CUDA 12.x runtime.
image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install("torch==2.5.0")
)


@app.function(image=image, gpu="A100-40GB", timeout=60)
def check_gpu() -> dict:
    """Check A100-40GB access and run a real CUDA matmul; returns plain Python types only."""
    import torch  # import dans le container (pas en local)

    info: dict = {
        "torch_version": str(torch.__version__),
        "cuda_disponible": bool(torch.cuda.is_available()),
    }

    if not torch.cuda.is_available():
        # GPU requested but not visible to torch.
        info["erreur"] = "torch.cuda.is_available() = False malgré gpu=A100-40GB"
        return info

    # Cast GPU metadata to plain Python types for serialization.
    props = torch.cuda.get_device_properties(0)
    info["gpu_nom"] = str(torch.cuda.get_device_name(0))
    info["gpu_memoire_gb"] = f"{int(props.total_memory) / (1024 ** 3):.2f}"
    info["cuda_version_pytorch"] = str(torch.version.cuda)
    info["nb_gpus_visibles"] = int(torch.cuda.device_count())

    # Real computation: 1000×1000 matmul to confirm GPU is operational.
    a = torch.randn(1000, 1000).to("cuda")
    b = torch.randn(1000, 1000).to("cuda")

    # Warm-up run excluded from timing (CUDA kernel init overhead).
    _ = a @ b
    torch.cuda.synchronize()

    # Synchronize before/after to capture real GPU duration (CUDA calls are async).
    start = time.perf_counter()
    resultat = a @ b
    torch.cuda.synchronize()
    elapsed_ms = (time.perf_counter() - start) * 1000

    info["matmul_1000x1000_ms"] = f"{float(elapsed_ms):.3f}"
    # Cast dimensions to int (torch.Size may contain SymInt).
    info["matmul_resultat_shape"] = [int(x) for x in resultat.shape]

    return info


@app.local_entrypoint()
def main() -> None:
    """Point d'entrée local — orchestre le test GPU et formate la sortie."""
    print("[LOCAL] Demande d'un GPU A100-40GB à Modal...")
    resultat = check_gpu.remote()

    print("[LOCAL] Résultat reçu depuis Modal :")
    for cle, valeur in resultat.items():
        print(f"  {cle}: {valeur}")
    print()

    # Validation finale lisible
    if resultat.get("cuda_disponible"):
        print("Palier 1 validé : GPU A100-40GB opérationnel et calcul réel "
              "effectué sur cuda.")
    else:
        print("ATTENTION : torch.cuda.is_available() = False. Le GPU "
              "n'a pas été correctement attribué. Vérifier :")
        print("  - Le décorateur gpu=\"A100-40GB\" (typo ?)")
        print("  - La version de Modal (modal --version)")
        print("  - Le quota GPU sur ton compte Modal")
