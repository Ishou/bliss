"""Palier 1 — Vérification de l'accès GPU sur Modal.

OBJECTIF
--------
Valider que Modal nous attribue bien un GPU T4 et que PyTorch peut
y faire tourner du calcul réel. Étape intermédiaire entre le hello
world (palier 0) et le téléchargement de Mistral Nemo (palier 2).

CE QUE CE SCRIPT VALIDE
-----------------------
1. Modal accepte la demande de GPU `T4` dans le décorateur.
2. L'image avec PyTorch CUDA se construit correctement (~2-3 min au
   premier build, puis cachée).
3. `torch.cuda.is_available()` retourne True dans le container.
4. Le nom du GPU et sa mémoire sont récupérables.
5. Une vraie multiplication matricielle 1000×1000 s'exécute sur GPU
   en quelques ms (preuve que le GPU calcule, pas seulement détecté).

COÛT ATTENDU
------------
T4 facturé ≈ 0,59 $/h chez Modal. Cette tâche tourne en quelques
secondes (≈ 10-30 s avec démarrage container) → quelques centimes,
moins de 1 ¢ par exécution. Le décorateur `timeout=60` plafonne
durement à 60 s en cas de runaway.

CE QUE CE SCRIPT NE FAIT PAS
----------------------------
- Pas de téléchargement de modèle HuggingFace (palier 2).
- Pas de fine-tune (palier 3).

COMMANDE POUR LANCER
--------------------
Depuis la racine du projet :

    modal run modal_jobs/01_gpu_check.py

Premier lancement : ~3 minutes (build de l'image PyTorch CUDA).
Lancements suivants : ~10-30 secondes (image cachée).
"""

import time

import modal


# Nom de l'app Modal — visible dans le dashboard
app = modal.App("mots-fleches-gpu-check")

# Image Docker : debian_slim Python 3.11 + PyTorch (sans torchvision /
# torchaudio inutiles pour ce palier). torch 2.5.0 inclut le runtime
# CUDA 12.x compatible T4 (architecture Turing, CUDA arch 7.5).
image = (
    modal.Image.debian_slim(python_version="3.11")
    .pip_install("torch==2.5.0")
)


@app.function(image=image, gpu="T4", timeout=60)
def check_gpu() -> dict:
    """Vérifie l'accès GPU et exécute un calcul réel sur cuda.

    Tourne dans un container Modal avec un GPU T4 attaché. Retourne
    un dict de types Python purs (str, int, float, bool, list de int)
    — pas de types torch dans le retour, sinon la désérialisation
    locale plante (le venv local n'a pas torch installé).
    """
    import torch  # import dans le container (pas en local)

    info: dict = {
        "torch_version": str(torch.__version__),
        "cuda_disponible": bool(torch.cuda.is_available()),
    }

    if not torch.cuda.is_available():
        # Cas anomalie : GPU demandé mais non visible côté torch.
        info["erreur"] = "torch.cuda.is_available() = False malgré gpu=T4"
        return info

    # Métadonnées GPU — cast explicite en types Python purs pour
    # éviter les objets torch dans le dict de retour.
    props = torch.cuda.get_device_properties(0)
    info["gpu_nom"] = str(torch.cuda.get_device_name(0))
    info["gpu_memoire_gb"] = f"{int(props.total_memory) / (1024 ** 3):.2f}"
    info["cuda_version_pytorch"] = str(torch.version.cuda)
    info["nb_gpus_visibles"] = int(torch.cuda.device_count())

    # Calcul réel : multiplication matricielle 1000×1000 sur GPU.
    # On charge explicitement avec .to("cuda") pour éviter toute
    # ambiguïté CPU/GPU.
    a = torch.randn(1000, 1000).to("cuda")
    b = torch.randn(1000, 1000).to("cuda")

    # Warm-up : la première multiplication inclut souvent l'init des
    # kernels CUDA, on l'exclut du chronométrage.
    _ = a @ b
    torch.cuda.synchronize()

    # Mesure réelle : on synchronise avant et après pour capturer la
    # vraie durée GPU (les appels CUDA sont asynchrones par défaut).
    start = time.perf_counter()
    resultat = a @ b
    torch.cuda.synchronize()
    elapsed_ms = (time.perf_counter() - start) * 1000

    info["matmul_1000x1000_ms"] = f"{float(elapsed_ms):.3f}"
    # Cast explicite des dimensions en int Python (torch.Size peut
    # contenir des torch.SymInt selon la version).
    info["matmul_resultat_shape"] = [int(x) for x in resultat.shape]

    return info


@app.local_entrypoint()
def main() -> None:
    """Point d'entrée local — orchestre le test GPU et formate la sortie."""
    print("[LOCAL] Demande d'un GPU T4 à Modal...")
    resultat = check_gpu.remote()

    print("[LOCAL] Résultat reçu depuis Modal :")
    for cle, valeur in resultat.items():
        print(f"  {cle}: {valeur}")
    print()

    # Validation finale lisible
    if resultat.get("cuda_disponible"):
        print("Palier 1 validé : GPU T4 opérationnel et calcul réel "
              "effectué sur cuda.")
    else:
        print("ATTENTION : torch.cuda.is_available() = False. Le GPU "
              "n'a pas été correctement attribué. Vérifier :")
        print("  - Le décorateur gpu=\"T4\" (typo ?)")
        print("  - La version de Modal (modal --version)")
        print("  - Le quota GPU sur ton compte Modal")
