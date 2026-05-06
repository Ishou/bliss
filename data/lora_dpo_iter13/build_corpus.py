#!/usr/bin/env python3
"""Build the iter13 DPO preference corpus for PP-form reframing.

Each lemma below is a PP-productive French verb. The `rejected` clue uses a
verb + direct-object (DObj) frame that does NOT PP-inflate cleanly. The
`chosen` clues use either a single-word verb synonym lemma or a verb + de/à
+ N frame, both of which DO PP-inflate to a grammatical state-clue.

Splits: 64 train verbs + 8 valid + 8 test = 80 verbs × 2 chosen variants
= 160 pairs total. Verbs in valid/test are unseen at training to measure
pattern-generalization (not verb-specific memorization).

Run: python data/lora_dpo_iter13/build_corpus.py
Outputs: train.jsonl, valid.jsonl, test.jsonl in this directory.
"""

from __future__ import annotations

import json
import random
import sys
from pathlib import Path

# Each entry: (lemma, rejected_DObj_clue, [chosen_variant_1, chosen_variant_2])
# rejected = verb+DObj framing (the bug pattern).
# chosen = single-word lemma synonym OR verb+de/à+N reframing.
# Both chosen variants must:
#   - be in citation form (verb infinitive)
#   - PP-inflate via grammalecte to a grammatical state-clue
#   - not contain the lemma or its inflected forms (self-leak)
#   - not share a >=5-char prefix with the lemma (stem-leak, when both >=5)

PAIRS: list[tuple[str, str, list[str]]] = [
    # ---- canonical user-cited examples (3) ----
    ("forer",       "Percer un trou",            ["Trouer", "Munir d'un trou"]),
    ("accorder",    "Donner une faveur",         ["Octroyer", "Concéder"]),
    ("aggraver",    "Empirer la situation",      ["Détériorer", "Empirer"]),
    # ---- TRAIN block (61 more verbs, 64 train total) ----
    ("armer",       "Donner des armes à",        ["Équiper", "Doter d'un fusil"]),
    ("élever",      "Faire grandir un enfant",   ["Hisser", "Pousser vers le haut"]),
    ("charger",     "Mettre une charge sur",     ["Lester", "Garnir d'un poids"]),
    ("priver",      "Retirer un bien à",         ["Démunir", "Dépouiller de tout"]),
    ("employer",    "Donner du travail",         ["Engager", "Recruter"]),
    ("nommer",      "Donner un nom à",           ["Désigner", "Baptiser"]),
    ("fixer",       "Poser en place",            ["Arrimer", "Sceller"]),
    ("nécessiter",  "Demander un effort",        ["Exiger", "Imposer"]),
    ("placer",      "Mettre en position",        ["Disposer", "Installer"]),
    ("citer",       "Donner une référence",      ["Mentionner", "Évoquer"]),
    ("arrêter",     "Stopper la marche",         ["Interrompre", "Suspendre"]),
    ("présenter",   "Faire connaître à",         ["Exposer", "Soumettre à l'examen"]),
    ("composer",    "Faire un assemblage",       ["Élaborer", "Construire"]),
    ("assembler",   "Mettre en un tout",         ["Réunir", "Joindre"]),
    ("fonder",      "Donner une base",           ["Établir", "Bâtir"]),
    ("déterminer",  "Fixer une valeur",          ["Définir", "Cerner"]),
    ("former",      "Donner une éducation",      ["Façonner", "Instruire"]),
    ("recevoir",    "Prendre un courrier",       ["Accueillir", "Obtenir"]),
    ("soumettre",   "Donner à l'examen",         ["Présenter", "Proposer"]),
    ("situer",      "Mettre en place",           ["Positionner", "Localiser"]),
    ("découvrir",   "Voir une chose",            ["Dénicher", "Mettre au jour"]),
    ("mettre",      "Poser en place",            ["Installer", "Disposer"]),
    ("appeler",     "Donner un nom",             ["Nommer", "Convoquer"]),
    ("exposer",     "Mettre à la vue",           ["Présenter", "Étaler"]),
    ("utiliser",    "Faire un usage de",         ["Employer", "Manier"]),
    ("perdre",      "Égarer un objet",           ["Égarer", "Dilapider"]),
    ("créer",       "Faire une œuvre",           ["Concevoir", "Bâtir"]),
    ("permettre",   "Donner le droit",           ["Autoriser", "Habiliter à agir"]),
    ("obliger",     "Forcer une action",         ["Contraindre", "Astreindre"]),
    ("consacrer",   "Donner du temps à",         ["Vouer", "Dédier"]),
    ("contenir",    "Avoir une chose",           ["Renfermer", "Comporter"]),
    ("comprendre",  "Saisir un sens",            ["Concevoir", "Englober"]),
    ("prévoir",     "Voir un événement",         ["Pressentir", "Anticiper"]),
    ("proposer",    "Faire une offre",           ["Suggérer", "Soumettre"]),
    ("réaliser",    "Mener une œuvre",           ["Accomplir", "Concrétiser"]),
    ("constituer",  "Faire un ensemble",         ["Composer", "Bâtir"]),
    ("adopter",     "Prendre un enfant",         ["Choisir", "Faire sien"]),
    ("destiner",    "Donner un but",             ["Vouer", "Réserver à"]),
    ("observer",    "Regarder une scène",        ["Examiner", "Scruter"]),
    ("acquérir",    "Avoir un bien",             ["Obtenir", "Conquérir"]),
    ("indiquer",    "Donner une direction",      ["Signaler", "Désigner"]),
    ("marquer",     "Faire une trace",           ["Imprégner", "Estamper"]),
    ("lier",        "Faire un nœud",             ["Attacher", "Unir"]),
    ("garantir",    "Donner une assurance",      ["Assurer", "Cautionner"]),
    ("occuper",     "Prendre une place",         ["Investir", "Tenir"]),
    ("appliquer",   "Mettre une couche",         ["Apposer", "Étaler"]),
    ("poser",       "Mettre une chose",          ["Établir", "Installer"]),
    ("conserver",   "Garder un objet",           ["Préserver", "Maintenir"]),
    ("limiter",     "Mettre une borne à",        ["Borner", "Restreindre"]),
    ("organiser",   "Mettre en ordre",           ["Agencer", "Structurer"]),
    ("étudier",     "Faire un examen de",        ["Analyser", "Approfondir"]),
    ("développer",  "Faire grandir une œuvre",   ["Étoffer", "Amplifier"]),
    ("opposer",     "Mettre face à face",        ["Confronter", "Dresser contre"]),
    ("attacher",    "Mettre un lien",            ["Lier", "Arrimer"]),
    ("effectuer",   "Faire une tâche",           ["Accomplir", "Exécuter"]),
    ("séparer",     "Mettre à l'écart",          ["Disjoindre", "Diviser"]),
    ("traiter",     "Donner un soin à",          ["Soigner", "Médicamenter"]),
    ("trouver",     "Voir un objet",             ["Dénicher", "Repérer"]),
    ("rendre",      "Faire un retour",           ["Restituer", "Redonner"]),
    ("établir",     "Mettre une base",           ["Instaurer", "Asseoir"]),
    ("considérer",  "Voir un fait",              ["Examiner", "Estimer"]),
    ("obtenir",     "Avoir un résultat",         ["Décrocher", "Conquérir"]),
    ("revenir",     "Faire un retour",           ["Repasser", "Rentrer"]),
    ("vouloir",     "Avoir un désir",            ["Souhaiter", "Désirer"]),
    # ---- VALID block (8 verbs) ----
    ("démolir",     "Faire une ruine",           ["Détruire", "Réduire en poussière"]),
    ("éclairer",    "Donner de la lumière",      ["Illuminer", "Inonder de clarté"]),
    ("blesser",     "Faire une plaie à",         ["Meurtrir", "Frapper d'un coup"]),
    ("salir",       "Mettre une tache",          ["Souiller", "Maculer"]),
    ("renforcer",   "Donner de la force",        ["Consolider", "Étayer"]),
    ("polir",       "Donner un éclat",           ["Lustrer", "Lisser"]),
    ("dorer",       "Mettre de l'or sur",        ["Auréoler", "Couvrir d'or"]),
    ("vernir",      "Mettre une couche brillante", ["Lustrer", "Enduire d'une laque"]),
    # ---- TEST block (8 verbs) — used for held-out structural eval ----
    ("ouvrir",      "Faire un passage",          ["Déboucher", "Percer"]),
    ("fermer",      "Mettre un obstacle",        ["Boucler", "Verrouiller"]),
    ("vider",       "Faire le vide dans",        ["Évacuer", "Désemplir"]),
    ("remplir",     "Mettre un contenu",         ["Combler", "Garnir"]),
    ("user",        "Faire une usure",           ["Émousser", "Affaiblir"]),
    ("nettoyer",    "Enlever la saleté",         ["Récurer", "Décrasser"]),
    ("brûler",      "Mettre une flamme à",       ["Calciner", "Embraser"]),
    ("geler",       "Mettre dans le froid",      ["Congeler", "Pétrifier"]),
]

PROMPT_TEMPLATE = "Génère une définition mots-fléchés courte pour: {} [verbe]"


def main() -> int:
    # Deduplicate by lemma (the data above had `salir` twice — keep first).
    seen: set[str] = set()
    deduped: list[tuple[str, str, list[str]]] = []
    for lemma, rejected, chosen in PAIRS:
        if lemma in seen:
            print(f"warn: duplicate lemma `{lemma}`, dropping second", file=sys.stderr)
            continue
        seen.add(lemma)
        deduped.append((lemma, rejected, chosen))

    if len(deduped) < 80:
        # Top up — caller can re-run with more.
        print(f"warn: only {len(deduped)} unique verbs, expected 80",
              file=sys.stderr)

    # Split: 64 train, 8 valid, 8 test (deterministic order from PAIRS).
    train = deduped[:64]
    valid = deduped[64:72]
    test = deduped[72:80]

    here = Path(__file__).parent

    def write_split(name: str, split: list[tuple[str, str, list[str]]]) -> None:
        out = here / f"{name}.jsonl"
        n = 0
        with out.open("w", encoding="utf-8") as fh:
            for lemma, rejected, chosen_list in split:
                prompt = PROMPT_TEMPLATE.format(lemma.upper())
                for chosen in chosen_list:
                    fh.write(json.dumps({
                        "prompt": prompt,
                        "chosen": chosen,
                        "rejected": rejected,
                    }, ensure_ascii=False) + "\n")
                    n += 1
        print(f"{name}.jsonl: {n} pairs ({len(split)} verbs)", file=sys.stderr)

    write_split("train", train)
    write_split("valid", valid)
    write_split("test", test)

    print(f"total: {len(deduped)} verbs, "
          f"{sum(len(c) for _, _, c in deduped)} pairs", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
