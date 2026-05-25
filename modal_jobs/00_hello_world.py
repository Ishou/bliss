import os
import platform
import socket
import sys

import modal


# Nom de l'app Modal — visible dans le dashboard Modal
app = modal.App("mots-fleches-hello-world")

# Minimal image: debian slim + Python 3.11.
image = modal.Image.debian_slim(python_version="3.11")


@app.function(image=image)
def hello_from_modal(name: str = "monde") -> dict:
    """Return execution environment info from the remote Modal container."""
    return {
        "message": f"Bonjour {name} depuis Modal",
        "python_version": platform.python_version(),
        "platform": platform.platform(),
        "hostname": socket.gethostname(),
        # MODAL_TASK_ID is only set inside Modal containers.
        "is_modal_env": "MODAL_TASK_ID" in os.environ,
        "modal_task_id": os.environ.get("MODAL_TASK_ID", "non défini"),
    }


@app.local_entrypoint()
def main(name: str = "Isho") -> None:
    """Call the remote function and print its result; runs locally, not in Modal."""
    print(f"[LOCAL] Appel de hello_from_modal avec name='{name}'")
    resultat = hello_from_modal.remote(name)

    print("[LOCAL] Résultat reçu depuis Modal :")
    for cle, valeur in resultat.items():
        print(f"  {cle}: {valeur}")

    print()
    print("Si tu vois ce message, le palier 0 est validé.")
