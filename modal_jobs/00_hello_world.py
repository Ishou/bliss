"""Palier 0 — Hello World Modal pour le projet bliss-clue-ai.

OBJECTIF
--------
Valider que l'environnement Modal local est correctement configuré
avant d'attaquer les paliers ultérieurs (GPU, téléchargement Mistral
Nemo, fine-tune sur le gold pilote).

CE QUE CE SCRIPT VALIDE
-----------------------
1. Le CLI Modal est installé et trouve son token d'auth.
2. L'app Modal peut être créée et déployée à la volée.
3. Une image Docker minimale (debian_slim, Python 3.11) peut être
   construite et démarrée par Modal.
4. Une fonction Python s'exécute correctement dans le container.
5. Le local_entrypoint communique correctement avec la fonction
   distante (appel `.remote(...)` et retour de valeur).

CE QUE CE SCRIPT NE FAIT PAS
----------------------------
- Pas de GPU (palier 1).
- Pas de téléchargement de modèles HuggingFace (palier 2).
- Pas de fine-tune (palier 3).

COMMANDE POUR LANCER
--------------------
Depuis la racine du projet :

    modal run modal_jobs/00_hello_world.py

Ou avec un paramètre :

    modal run modal_jobs/00_hello_world.py --name "Alice"

Si tu vois s'afficher le résultat formaté et le message
« Si tu vois ce message, le palier 0 est validé », l'environnement
Modal local est OK et tu peux passer au palier 1.
"""

import os
import platform
import socket
import sys

import modal


# Nom de l'app Modal — visible dans le dashboard Modal
app = modal.App("mots-fleches-hello-world")

# Image Docker minimale : debian slim + Python 3.11, rien d'autre
# (pas de pip install nécessaire pour ce palier).
image = modal.Image.debian_slim(python_version="3.11")


@app.function(image=image)
def hello_from_modal(name: str = "monde") -> dict:
    """Fonction qui tourne dans le container Modal distant.

    Retourne un dict décrivant l'environnement d'exécution. Le but
    est de prouver qu'on est bien dans un container Modal et non
    sur la machine locale.
    """
    return {
        "message": f"Bonjour {name} depuis Modal",
        "python_version": platform.python_version(),
        "platform": platform.platform(),
        "hostname": socket.gethostname(),
        # MODAL_TASK_ID n'est défini que dans les containers Modal :
        # c'est notre marqueur « on est bien distant » le plus fiable.
        "is_modal_env": "MODAL_TASK_ID" in os.environ,
        "modal_task_id": os.environ.get("MODAL_TASK_ID", "non défini"),
    }


@app.local_entrypoint()
def main(name: str = "Isho") -> None:
    """Point d'entrée local — lancé sur la machine de l'utilisateur.

    Appelle la fonction distante via `.remote(...)` et affiche le
    résultat de manière lisible. Ce code-ci s'exécute en local
    (machine de l'utilisateur), pas dans Modal.
    """
    print(f"[LOCAL] Appel de hello_from_modal avec name='{name}'")
    resultat = hello_from_modal.remote(name)

    print("[LOCAL] Résultat reçu depuis Modal :")
    for cle, valeur in resultat.items():
        print(f"  {cle}: {valeur}")

    print()
    print("Si tu vois ce message, le palier 0 est validé.")
