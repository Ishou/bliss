#!/usr/bin/env python3
"""Add hand-authored clues for common 2- and 3-letter French words.

These are the high-frequency short fillers that mots-fléchés grids
depend on. The LoRA wasn't run on length 2-3 (sample ranged 4-11), so
we provide them by hand. CC0-1.0 — original Claude/Bliss authorship.

Idempotent: if a (word, language) row already exists in the wordlist,
its clue is updated; otherwise the row is appended. Length is computed
from the word.
"""
from __future__ import annotations
import csv
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
WORDLIST = REPO / "grid/api/src/main/resources/words/words-fr.csv"

# (word, clue) pairs. Words are lowercase to match grammalecte's casing.
ENTRIES: list[tuple[str, str]] = [
    # 2-letter (common French monosyllabic words / homonyms)
    ("ai", "Forme du verbe avoir"),
    ("an", "Période de douze mois"),
    ("as", "Carte maîtresse"),
    ("au", "Article contracté"),
    ("ce", "Démonstratif neutre"),
    ("de", "Préposition d'origine"),
    ("du", "Article contracté masculin"),
    ("en", "Préposition de lieu"),
    ("es", "Verbe être à la 2e personne"),
    ("et", "Conjonction de coordination"),
    ("eu", "Participe du verbe avoir"),
    ("if", "Arbre à baies rouges"),
    ("il", "Pronom masculin singulier"),
    ("ils", "Pronom masculin pluriel"),
    ("la", "Article féminin ou note"),
    ("le", "Article masculin"),
    ("les", "Article défini pluriel"),
    ("lu", "Participe de lire"),
    ("ma", "Possessif féminin singulier"),
    ("me", "Pronom personnel"),
    ("mi", "Note de musique"),
    ("mu", "Mis en mouvement"),
    ("ne", "Particule de négation"),
    ("ni", "Conjonction de négation"),
    ("on", "Pronom indéfini"),
    ("ô", "Interjection lyrique"),
    ("ou", "Conjonction d'alternative"),
    ("oh", "Cri d'étonnement"),
    ("ré", "Note de musique"),
    ("sa", "Possessif féminin"),
    ("se", "Pronom réfléchi"),
    ("si", "Conjonction de condition"),
    ("su", "Connu et appris"),
    ("ta", "Possessif féminin"),
    ("te", "Pronom personnel"),
    ("tu", "Pronom de la 2e personne"),
    ("un", "Article ou chiffre"),
    ("us", "Coutumes anciennes"),
    ("va", "Verbe aller"),
    ("vu", "Constaté de ses yeux"),
    # 3-letter (high-frequency nouns, verbs, adjectives, function words)
    ("âge", "Période de la vie"),
    ("aïe", "Cri de douleur"),
    ("ail", "Bulbe à gousses"),
    ("air", "Mélange respirable"),
    ("ami", "Compagnon de confiance"),
    ("ans", "Mesure de la durée d'une vie"),
    ("arc", "Arme à corde tendue"),
    ("are", "Cent mètres carrés"),
    ("art", "Création esthétique"),
    ("axe", "Ligne droite centrale"),
    ("bac", "Cuve ou ferry"),
    ("bal", "Soirée dansante"),
    ("ban", "Décret public"),
    ("bar", "Établissement à boire"),
    ("bas", "Vêtement de jambe"),
    ("bec", "Pointe d'oiseau"),
    ("bis", "Encore une fois"),
    ("blé", "Céréale dorée"),
    ("bol", "Récipient creux"),
    ("bon", "Coupon ou agréable"),
    ("bus", "Transport en commun"),
    ("but", "Objectif à atteindre"),
    ("cap", "Pointe rocheuse"),
    ("car", "Bus interurbain"),
    ("cas", "Situation particulière"),
    ("cep", "Pied de vigne"),
    ("cet", "Démonstratif masculin"),
    ("cil", "Poil de paupière"),
    ("clé", "Outil de serrure"),
    ("col", "Passage de montagne"),
    ("coq", "Mâle de la poule"),
    ("cor", "Instrument à vent"),
    ("cou", "Lien tête-tronc"),
    ("cri", "Vocalisation forte"),
    ("cru", "Vin d'un terroir"),
    ("cul", "Postérieur familier"),
    ("dam", "Préjudice subi"),
    ("duc", "Titre de noblesse"),
    ("dur", "Solide et résistant"),
    ("eau", "Liquide vital"),
    ("élu", "Mandataire choisi"),
    ("est", "Point cardinal du levant"),
    ("été", "Saison la plus chaude"),
    ("eux", "Pronom pluriel masculin"),
    ("fan", "Admirateur passionné"),
    ("fer", "Métal magnétique"),
    ("feu", "Combustion vive"),
    ("fil", "Brin allongé"),
    ("foi", "Croyance profonde"),
    ("fou", "Privé de raison"),
    ("gaz", "État de la matière"),
    ("gel", "Eau solidifiée"),
    ("gré", "Volonté ou consentement"),
    ("ici", "En ce lieu"),
    ("île", "Terre entourée d'eau"),
    ("jeu", "Activité ludique"),
    ("kit", "Ensemble à monter"),
    ("lac", "Étendue d'eau intérieure"),
    ("las", "Las et fatigué"),
    ("lie", "Dépôt au fond du fût"),
    ("lin", "Plante textile"),
    ("lis", "Fleur royale"),
    ("lit", "Meuble pour dormir"),
    ("loi", "Règle obligatoire"),
    ("lot", "Part attribuée"),
    ("lui", "Pronom de la 3e personne"),
    ("lys", "Fleur héraldique"),
    ("mal", "Souffrance"),
    ("mas", "Maison provençale"),
    ("mat", "Sans éclat"),
    ("mer", "Vaste étendue salée"),
    ("mie", "Partie tendre du pain"),
    ("mil", "Céréale africaine"),
    ("mou", "Sans fermeté"),
    ("mur", "Cloison verticale"),
    ("nef", "Vaisseau d'église"),
    ("nez", "Organe de l'odorat"),
    ("nid", "Abri d'oiseau"),
    ("nom", "Mot qui désigne"),
    ("non", "Refus net"),
    ("nos", "Possessif pluriel"),
    ("nul", "Aucun et inutile"),
    ("œil", "Organe de la vue"),
    ("oie", "Oiseau aquatique blanc"),
    ("ont", "Possèdent au présent"),
    ("ose", "Risque ou sucre simple"),
    ("oui", "Affirmation"),
    ("pal", "Pieu pointu"),
    ("pan", "Côté ou chute"),
    ("par", "Préposition d'agent"),
    ("pas", "Mouvement ou négation"),
    ("pic", "Sommet escarpé"),
    ("pie", "Oiseau noir et blanc"),
    ("pin", "Conifère résineux"),
    ("pis", "Mamelle ou pire"),
    ("pli", "Marque de couture"),
    ("pou", "Insecte parasite"),
    ("pré", "Terrain herbeux"),
    ("pus", "Liquide d'infection"),
    ("qui", "Pronom interrogatif"),
    ("rai", "Rayon lumineux"),
    ("ras", "Coupe très courte"),
    ("rat", "Petit rongeur gris"),
    ("raz", "Courant marin"),
    ("riz", "Céréale asiatique"),
    ("roc", "Pierre dure"),
    ("roi", "Souverain monarque"),
    ("rot", "Renvoi gastrique"),
    ("roue", "(4 letters — skip)"),
    ("rue", "Voie urbaine"),
    ("sac", "Contenant souple"),
    ("sas", "Pièce de transition"),
    ("sec", "Sans humidité"),
    ("ses", "Possessif pluriel"),
    ("ski", "Glisse sur neige"),
    ("soi", "Pronom réfléchi"),
    ("sol", "Plancher ou note"),
    ("son", "Possessif ou bruit"),
    ("sou", "Petite monnaie ancienne"),
    ("sud", "Point cardinal du midi"),
    ("sur", "Préposition de position"),
    ("tas", "Amas désordonné"),
    ("ter", "Trois fois"),
    ("thé", "Infusion chaude"),
    ("tic", "Geste involontaire"),
    ("tir", "Action de viser"),
    ("toi", "Pronom de la 2e personne"),
    ("ton", "Possessif ou hauteur"),
    ("top", "Sommet ou signal"),
    ("tôt", "En avance"),
    ("tri", "Sélection ordonnée"),
    ("tué", "Mort violemment"),
    ("une", "Article féminin"),
    ("usa", "Utilisa jadis"),
    ("van", "Tamis ou camionnette"),
    ("vif", "Plein de vie"),
    ("vil", "Méprisable"),
    ("vin", "Boisson du raisin"),
    ("vis", "Tige filetée"),
    ("vol", "Larcin ou trajet aérien"),
    ("vos", "Possessif pluriel"),
    ("vue", "Sens visuel"),
    ("yen", "Monnaie japonaise"),
    ("zoo", "Parc animalier"),
]


def main() -> None:
    # Skip rows whose clue is "(... — skip)" placeholder.
    pairs = [(w, c) for w, c in ENTRIES if "skip" not in c.lower()]
    # Deduplicate (last wins).
    pair_map = {w.lower(): c for w, c in pairs}

    with WORDLIST.open(encoding="utf-8", newline="") as f:
        rows = list(csv.DictReader(f))
        fieldnames = list(rows[0].keys())
    by_word = {r["word"].lower(): r for r in rows}

    updated = 0
    added = 0
    for word, clue in pair_map.items():
        L = len(word)
        if word in by_word:
            r = by_word[word]
            r["clue"] = clue
            r["source"] = "bliss"
            r["source_license"] = "CC0-1.0"
            updated += 1
        else:
            new = {k: "" for k in fieldnames}
            new["word"] = word
            new["language"] = "fr"
            new["length"] = str(L)
            new["frequency"] = "0"
            new["difficulty"] = ""
            new["clue"] = clue
            new["source"] = "bliss"
            new["source_license"] = "CC0-1.0"
            new["lemma"] = word
            rows.append(new)
            added += 1

    with WORDLIST.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow({k: r.get(k, "") for k in fieldnames})

    print(f"updated existing rows: {updated}")
    print(f"added new rows:        {added}")
    print(f"total wordlist rows:   {len(rows)}")


if __name__ == "__main__":
    main()
