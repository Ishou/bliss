#!/usr/bin/env python3
"""Phase-1 batch of hand-authored DPO pairs for iter14.

100 PP-adjectivally-productive verbs, each paired with:
  - y_clue: a good lemma-form clue that PP-inflates cleanly to the
    intended adjectival state (single-word verb synonym OR verb+de/à+N
    framing, meaning-preserving for the PP-as-adj reading).
  - n_clue: a deliberately bad lemma-form clue representing one of the
    known failure modes the model emits — verb+DObj, reflexive head,
    noun-shape, wrong-sense, self-leak, or compound `verb et verb`.

Each rejected n is a different failure mode where reasonable, to give
DPO broad coverage. Each pair must pass validate_lemma_clue on the y
side (chosen) — the n side may legitimately fail (it's the rejected).

Source pool: data/eval/pp_adj_verbs.csv top entries by total_pp_freq.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

# (lemma, y_clue, n_clue, n_failure_mode_label)
PAIRS: list[tuple[str, str, str, str]] = [
    # --- High-frequency batch (top of pp_adj_verbs.csv) ---
    ("armer",        "Équiper",                        "Donner des armes",          "verb+DObj"),
    ("écrire",       "Rédiger",                        "Tracer des lettres",        "verb+DObj"),
    ("entreprendre", "Lancer",                         "Mettre en chantier",        "verb+det+N+chain"),
    ("traiter",      "Soigner",                        "Donner un traitement",      "verb+DObj"),
    ("conduire",     "Mener",                          "Tenir le volant",           "verb+det+N"),
    ("élever",       "Hisser",                         "Faire grandir",             "verb+adj"),
    ("charger",      "Lester",                         "Mettre une charge",         "verb+det+N"),
    ("porter",       "Soutenir",                       "Avoir sur soi",             "verb+adv+pron"),
    ("trouver",      "Dénicher",                       "Mettre la main dessus",     "idiomatic-DObj"),
    ("rendre",       "Restituer",                      "Faire un retour",           "verb+DObj"),
    ("établir",      "Instaurer",                      "Mettre en place",           "verb+en+N(borderline)"),
    ("priver",       "Démunir",                        "Retirer un droit",          "verb+DObj"),
    ("employer",     "Engager",                        "Donner du travail",         "verb+du+N(partitive)"),
    ("nécessiter",   "Exiger",                         "Demander un effort",        "verb+DObj"),
    ("placer",       "Disposer",                       "Mettre en position",        "verb+en+N"),
    ("considérer",   "Examiner",                       "Tenir pour vrai",           "idiomatic-pour-N"),
    ("obtenir",      "Décrocher",                      "Avoir un résultat",         "verb+DObj"),
    ("citer",        "Mentionner",                     "Donner en exemple",         "verb+en+N"),
    ("nommer",       "Désigner",                       "Donner un nom",             "verb+DObj"),
    ("présenter",    "Exposer",                        "Faire connaître",           "verb+adj"),
    ("former",       "Façonner",                       "Donner une forme",          "verb+DObj"),
    ("recevoir",     "Accueillir",                     "Prendre livraison",         "verb+det+N"),
    ("soumettre",    "Subjuguer",                      "Mettre sous pression",      "verb+DObj+prep"),
    ("composer",     "Élaborer",                       "Mettre ensemble",           "pleonasm"),
    ("assembler",    "Réunir",                         "Joindre ensemble",          "pleonasm"),
    ("fonder",       "Bâtir",                          "Mettre en place",           "verb+en+N(borderline)"),
    ("déterminer",   "Définir",                        "Fixer un point",            "verb+DObj"),
    ("découvrir",    "Dénicher",                       "Mettre au jour",            "idiomatic-DObj"),
    ("mettre",       "Poser",                          "Faire entrer",              "verb+verb"),
    ("appeler",      "Convoquer",                      "Donner un nom",             "verb+DObj"),
    ("exposer",      "Étaler",                         "Mettre à la vue",           "verb+à+det+N"),
    ("utiliser",     "Manier",                         "Faire usage de",            "verb+det+N+de"),
    ("perdre",       "Égarer",                         "Ne plus avoir",             "verb+adv+verb"),
    ("créer",        "Concevoir",                      "Mettre au monde",           "idiomatic"),
    ("permettre",    "Autoriser",                      "Donner le droit",           "verb+det+N"),
    ("obliger",      "Contraindre",                    "Forcer une action",         "verb+DObj"),
    ("consacrer",    "Vouer",                          "Donner du temps",           "verb+du+N"),
    ("contenir",     "Renfermer",                      "Avoir à l'intérieur",       "verb+det+N"),
    ("comprendre",   "Saisir",                         "Avoir une idée claire",     "verb+det+N+adj"),
    ("prévoir",      "Anticiper",                      "Voir à l'avance",           "verb+adv"),
    ("proposer",     "Suggérer",                       "Mettre en avant",           "verb+en+adv"),
    ("réaliser",     "Accomplir",                      "Mettre en œuvre",           "verb+en+N"),
    ("constituer",   "Composer",                       "Faire partie de",           "idiomatic-de"),
    ("adopter",      "Choisir",                        "Prendre pour sien",         "verb+det+N"),
    ("destiner",     "Vouer",                          "Mettre de côté",            "idiomatic-de+N"),
    ("observer",     "Scruter",                        "Garder un œil",             "idiomatic-DObj"),
    ("acquérir",     "Conquérir",                      "Devenir propriétaire",      "verb+adj"),
    ("indiquer",     "Signaler",                       "Faire un signe",            "verb+DObj"),
    ("marquer",      "Imprégner",                      "Faire une marque",          "verb+DObj"),
    ("lier",         "Attacher",                       "Faire un nœud",             "verb+DObj"),
    ("garantir",     "Assurer",                        "Donner sa parole",          "verb+det+N"),
    ("occuper",      "Investir",                       "Prendre une place",         "verb+det+N"),
    ("appliquer",    "Apposer",                        "Mettre une couche",         "verb+det+N"),
    ("conserver",    "Préserver",                      "Garder en stock",           "verb+en+N"),
    ("limiter",      "Borner",                         "Mettre une borne",          "verb+det+N"),
    ("organiser",    "Agencer",                        "Mettre en ordre",           "verb+en+N(borderline)"),
    ("étudier",      "Analyser",                       "Faire un examen",           "verb+DObj"),
    ("développer",   "Étoffer",                        "Faire grandir",             "verb+adj"),
    ("opposer",      "Confronter",                     "Mettre face à face",        "verb+adv+adj"),
    ("attacher",     "Lier",                           "Mettre un lien",            "verb+det+N"),
    ("effectuer",    "Accomplir",                      "Mener à bien",              "verb+adv"),
    ("séparer",      "Disjoindre",                     "Mettre à l'écart",          "verb+à+det+N"),
    ("forer",        "Trouer",                         "Percer un trou",            "verb+DObj"),
    ("accorder",     "Octroyer",                       "Donner une faveur",         "verb+DObj"),
    ("aggraver",     "Détériorer",                     "Empirer la situation",      "verb+DObj"),
    ("ratifier",     "Confirmer",                      "Approbation formelle",      "POS-noun"),
    ("traverser",    "Franchir",                       "Franc-tireur",              "POS-noun"),
    ("bouger",       "Déplacer",                       "Changements d'emplacement", "POS-noun"),
    ("vouer",        "Dédier",                         "Engagement total",          "POS-noun"),
    ("effacer",      "Rayer",                          "Suppression définitive",    "POS-noun"),
    ("brûler",       "Calciner",                       "Combustion totale",         "POS-noun"),
    ("attribuer",    "Octroyer",                       "Distribution",              "POS-noun"),
    ("décider",      "Statuer",                        "Choix tranché",             "POS-noun"),
    ("relever",      "Soulever",                       "S'élever",                  "reflexive"),
    ("mouvoir",      "Bouger",                         "Se déplacer",               "reflexive"),
    ("baigner",      "Tremper",                        "Se baigner",                "reflexive"),
    ("coucher",      "Étendre",                        "Se coucher",                "reflexive"),
    ("réveiller",    "Tirer du sommeil",               "Se réveiller",              "reflexive"),
    ("cacher",       "Dissimuler",                     "Se cacher",                 "reflexive"),
    ("enfuir",       "Fuir",                           "S'enfuir",                  "reflexive"),
    ("blesser",      "Meurtrir",                       "Faire une blessure",        "verb+DObj"),
    ("salir",        "Souiller",                       "Faire une tache",           "verb+DObj"),
    ("renforcer",    "Consolider",                     "Donner de la force",        "verb+det+N"),
    ("polir",        "Lustrer",                        "Donner un éclat",           "verb+DObj"),
    ("dorer",        "Auréoler",                       "Mettre de l'or",            "verb+det+N"),
    ("vernir",       "Lustrer",                        "Mettre une couche",         "verb+det+N"),
    ("ouvrir",       "Déboucher",                      "Faire un passage",          "verb+DObj"),
    ("fermer",       "Boucler",                        "Mettre un obstacle",        "verb+det+N"),
    ("vider",        "Évacuer",                        "Faire le vide",             "verb+det+N"),
    ("remplir",      "Combler",                        "Mettre un contenu",         "verb+det+N"),
    ("nettoyer",     "Récurer",                        "Enlever la saleté",         "verb+det+N"),
    ("geler",        "Solidifier",                     "Mettre dans le froid",      "verb+det+N"),
    ("démolir",      "Détruire",                       "Faire une ruine",           "verb+DObj"),
    ("éclairer",     "Illuminer",                      "Donner de la lumière",      "verb+det+N"),
    ("lever",        "Hausser",                        "Se lever",                  "reflexive"),
    ("plier",        "Courber",                        "Donner une courbe",         "verb+DObj"),
    ("hacher",       "Émincer",                        "Couper en morceaux",        "verb+en+N"),
    ("tailler",      "Sculpter",                       "Couper à la forme",         "verb+à+det+N"),
    ("clouer",       "Fixer",                          "Mettre un clou",            "verb+det+N"),
    ("piquer",       "Percer",                         "Donner un coup",            "verb+det+N"),
    ("rincer",       "Nettoyer",                       "Passer à l'eau",            "verb+à+det+N"),
    ("colorer",      "Teindre",                        "Donner une couleur",        "verb+det+N"),
    ("graver",       "Buriner",                        "Faire une marque",          "verb+DObj"),
    ("tisser",       "Tramer",                         "Faire un tissu",            "verb+DObj"),
]

PROMPT = "Génère une définition mots-fléchés courte pour: {} [verbe]"


def main() -> int:
    here = Path(__file__).parent
    out = here / "batch1.jsonl"
    with out.open("w", encoding="utf-8") as fh:
        for lemma, y, n, _mode in PAIRS:
            fh.write(json.dumps({
                "prompt": PROMPT.format(lemma.upper()),
                "chosen": y,
                "rejected": n,
            }, ensure_ascii=False) + "\n")
    print(f"wrote {len(PAIRS)} pairs to {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
