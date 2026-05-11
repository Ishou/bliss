#!/usr/bin/env python3
"""Hand-authored (chosen, rejected) DPO pairs for iter18.

Three buckets, by failure mode discovered after iter17 shipped:

1. CROSS_LINGUAL_HOMOGRAPH — French lemmas whose token spelling
   matches an English word with a different meaning. The multilingual
   command-r base leaks toward the English sense when there's no
   strong French anchor. iter17's `cane → Bâton` and `user →
   Consommateur` belong here. We teach a generalizable pattern by
   covering ~20 such homographs across nouns / verbs / adjectives,
   each with multiple chosen variants (the right French sense) and
   multiple rejected variants (the English-leak gloss).

2. POS_GLOSS_MISMATCH — adj lemmas where the LoRA emits an adverbial
   gloss (`clair → Évidemment`) or noun lemmas where it emits a
   verb-form gloss. We teach "match the head's POS to the lemma's
   POS" by pairing adj lemmas against adverb-form rejected and
   ad-noun lemmas against verb-form rejected.

3. NEAR_SYNONYM_CONFUSION — when the LoRA picks a near-synonym
   lemma's clue (`errer → Égarer son chemin`, where égarer is a
   different verb). We pair the right disambiguation against the
   wrong-lemma rendering.

This file is consumed by build_iter18_dpo_corpus.py — DOES NOT import
heavy deps so the file is fast to import and the pair lists are easy
to diff. The chosen/rejected values are hand-written to cover the
breadth of plausible right/wrong renderings rather than memorizing a
single (lemma, clue) mapping.

CC0-1.0 — Bliss-authored / Claude-Code-collab. No DBnary, no
external corpora.
"""

# --------------------------------------------------------------------- #
# Bucket 1 — CROSS_LINGUAL_HOMOGRAPH                                    #
# Each entry: (lemma, pos, [chosen_variants...], [rejected_variants...])  #
# We expand to the cartesian product in the consumer script so the      #
# corpus carries len(chosen) × len(rejected) pairs per lemma.           #
# --------------------------------------------------------------------- #
CROSS_LINGUAL_HOMOGRAPH = [
    # cane (FR: female duck; EN: walking stick → canne)
    ("cane",  "nom",
     ["Femelle du canard", "Anatidé femelle", "Mère des canetons"],
     ["Bâton de marche", "Bâton", "Canne", "Roseau", "Stick"]),

    # user (FR: to wear out / make use of repeatedly; EN: utilisateur)
    ("user",  "verbe",
     ["Avoir recours à", "Détériorer en se servant", "Émousser",
      "Consommer", "Abîmer", "Élimer"],
     ["Consommateur", "Client", "Utilisateur du service", "Personne qui se sert"]),

    # chat (FR: cat; EN: online conversation)
    ("chat",  "nom",
     ["Félin domestique", "Animal qui ronronne", "Mammifère carnassier"],
     ["Conversation en ligne", "Discussion sur internet", "Échange de messages"]),

    # pile (FR: battery / heap; EN: pile/stack)
    ("pile",  "nom",
     ["Source d'énergie portable", "Petite batterie", "Accumulateur sec"],
     ["Tas d'objets", "Amas", "Empilement de feuilles"]),

    # coin (FR: corner; EN: coin)
    ("coin",  "nom",
     ["Angle d'une pièce", "Recoin de la maison", "Endroit retiré"],
     ["Pièce de monnaie", "Petite monnaie métallique", "Sou"]),

    # pain (FR: bread; EN: suffering)
    ("pain",  "nom",
     ["Aliment de boulangerie", "Mie cuite au four", "Aliment du boulanger"],
     ["Douleur intense", "Souffrance", "Mal physique"]),

    # son (FR: sound; EN: male offspring)
    ("son",   "nom",
     ["Onde sonore", "Bruit perçu", "Vibration audible"],
     ["Fils mâle", "Garçon de la famille", "Enfant masculin"]),

    # or (FR: gold; EN: logical or)
    ("or",    "nom",
     ["Métal précieux jaune", "Métal des bijoutiers", "Élément du tableau Au"],
     ["Conjonction de coordination", "Mot de liaison alternative"]),

    # but (FR: goal / objective; EN: but as conjunction)
    ("but",   "nom",
     ["Objectif visé", "Cible à atteindre", "Score du footballeur"],
     ["Mais en anglais", "Conjonction adversative"]),

    # fin (FR: end; EN: fish fin)
    ("fin",   "nom",
     ["Aboutissement", "Conclusion d'un récit", "Terme final"],
     ["Aileron de poisson", "Nageoire"]),

    # four (FR: oven; EN: the number 4)
    ("four",  "nom",
     ["Appareil de cuisson", "Chambre de cuisson", "Brûleur de boulanger"],
     ["Quatre", "Chiffre 4", "Nombre après trois"]),

    # chair (FR: flesh; EN: chair)
    ("chair", "nom",
     ["Tissu des muscles", "Partie comestible", "Pulpe d'un fruit"],
     ["Chaise", "Siège à dossier", "Meuble pour s'asseoir"]),

    # dent (FR: tooth; EN: dent / bump)
    ("dent",  "nom",
     ["Os de la mâchoire", "Élément qui mord", "Pièce du sourire"],
     ["Bosse dans le métal", "Marque d'impact", "Renfoncement"]),

    # car (FR: because; EN: car/automobile)
    ("car",   "nom",
     ["Autobus de tourisme", "Véhicule collectif", "Bus longue distance"],
     ["Voiture en anglais", "Automobile anglo-saxonne"]),

    # bras (FR: arm; EN: bras = brassiere — rare leak but possible)
    ("bras",  "nom",
     ["Membre supérieur", "Partie de l'épaule", "Bras et avant-bras"],
     ["Soutien-gorge en anglais", "Sous-vêtement féminin"]),

    # plat (FR: flat / dish; EN: same word for "flat", also surname)
    ("plat",  "adj",
     ["Sans relief", "À surface unie", "Sans saillie"],
     ["Appartement en anglais", "Logement", "Habitation"]),

    # fer (FR: iron; EN: distance unit)
    ("fer",   "nom",
     ["Métal du forgeron", "Élément Fe", "Métal gris"],
     ["Loin en anglais", "À distance"]),

    # mer (FR: sea; EN: same — generally aligned but worth pinning)
    ("mer",   "nom",
     ["Étendue d'eau salée", "Océan plus petit", "Vaste mer"],
     ["Maire en mauvais français", "Mère phonétique"]),

    # nation (FR: nation; EN: same, but English crossword conventions differ)
    ("nation","nom",
     ["État souverain", "Peuple uni", "Communauté politique"],
     ["Peuplade", "Tribu", "Groupe ethnique en anglais"]),
]


# --------------------------------------------------------------------- #
# Bucket 2 — POS_GLOSS_MISMATCH                                         #
# Adj lemmas where the LoRA tends to emit an adverbial gloss; we pair    #
# the adjectival rendering against the adverb-form rejected.             #
# Same pattern for noun lemmas vs verb-form rejected.                    #
# --------------------------------------------------------------------- #
POS_GLOSS_MISMATCH = [
    # clair — already a known leak (iter17 had it as adj but model emitted adv)
    ("clair",   "adj",
     ["Limpide", "Lumineux", "Sans nuage", "Net et précis"],
     ["Évidemment", "Clairement", "De façon nette"]),

    ("rapide",  "adj",
     ["Véloce", "Prompt", "Qui ne tarde pas"],
     ["Rapidement", "À vive allure", "En peu de temps"]),

    ("triste",  "adj",
     ["Mélancolique", "Chagriné", "Peiné par un événement"],
     ["Tristement", "Avec chagrin", "De manière maussade"]),

    ("doux",    "adj",
     ["Suave", "Tendre au toucher", "Sans aspérité"],
     ["Doucement", "Avec délicatesse", "En finesse"]),

    ("net",     "adj",
     ["Distinct", "Bien défini", "Sans bavure"],
     ["Nettement", "Clairement", "Sans ambiguïté"]),

    ("fort",    "adj",
     ["Robuste", "Puissant", "À la grande énergie"],
     ["Fortement", "Avec vigueur", "Énergiquement"]),

    ("mou",     "adj",
     ["Flasque", "Sans tonus", "Peu ferme"],
     ["Mollement", "Avec nonchalance", "Sans énergie"]),

    ("vif",     "adj",
     ["Plein d'énergie", "Animé", "Plein d'éclat"],
     ["Vivement", "Avec entrain", "Rapidement"]),

    ("calme",   "adj",
     ["Paisible", "Sans agitation", "Serein"],
     ["Calmement", "Avec tranquillité", "Doucement et posément"]),

    ("franc",   "adj",
     ["Sincère", "Sans détour", "Direct dans ses propos"],
     ["Franchement", "Avec sincérité", "Sans dissimulation"]),

    # Nouns where the LoRA might give a verb form
    ("course",  "nom",
     ["Compétition pédestre", "Trajet à vive allure", "Épreuve sportive"],
     ["Courir vite", "Action de courir", "Aller à toute vitesse"]),

    ("danse",   "nom",
     ["Art chorégraphique", "Ballet en mouvement", "Mouvement rythmé"],
     ["Danser sur la musique", "Bouger en rythme"]),

    ("vol",     "nom",
     ["Trajet aérien", "Déplacement dans les airs", "Voyage en avion"],
     ["Voler en avion", "Se déplacer dans le ciel"]),

    ("chant",   "nom",
     ["Mélodie vocale", "Air chanté", "Morceau lyrique"],
     ["Chanter une chanson", "Émettre des notes"]),
]


# --------------------------------------------------------------------- #
# Bucket 3 — NEAR_SYNONYM_CONFUSION                                     #
# The LoRA picks a related but distinct lemma's clue. We pair the right  #
# disambiguation (chosen) against the wrong-lemma rendering (rejected).  #
# --------------------------------------------------------------------- #
NEAR_SYNONYM_CONFUSION = [
    # errer (wander) ↔ égarer (lose, get lost)
    ("errer",   "verbe",
     ["Vagabonder", "Aller sans but", "Marcher au hasard", "Flâner"],
     ["Égarer son chemin", "Perdre la route", "S'égarer en route", "Égarer ses pas"]),

    # advenir (happen) ↔ multi-token coord
    ("advenir", "verbe",
     ["Survenir", "Arriver", "Se produire", "Avoir lieu"],
     ["Arriver, se produire", "Survenir et se produire", "Arriver et se passer"]),

    # triple (multiplied by 3) ↔ "trip with three"
    ("triple",  "adj",
     ["Multiplié par trois", "Au cube", "Trois fois plus", "Triplé"],
     ["Voyage à trois", "Sortie pour trois personnes", "Excursion à trois"]),

    # éclore (hatch) ↔ "open up"
    ("éclore",  "verbe",
     ["Sortir de l'œuf", "Naître poussin", "Briser sa coquille"],
     ["S'ouvrir et grandir", "Éclater au grand jour", "Apparaître soudainement"]),

    # boucher (block) ↔ "make a hole"
    ("boucher", "verbe",
     ["Obstruer", "Combler une ouverture", "Fermer hermétiquement"],
     ["Percer un trou", "Faire une cavité", "Creuser une brèche"]),

    # élire (elect) ↔ "select" / "pick"
    ("élire",   "verbe",
     ["Désigner par vote", "Choisir par scrutin", "Voter pour"],
     ["Choisir au hasard", "Prendre au pif", "Sélectionner à la chance"]),

    # supporter (endure / fan) ↔ "carry"
    ("supporter", "verbe",
     ["Endurer une peine", "Tolérer une charge", "Subir patiemment"],
     ["Porter sur ses épaules", "Tenir en l'air", "Soutenir physiquement"]),

    # croire (believe) ↔ "trust"
    ("croire",  "verbe",
     ["Tenir pour vrai", "Donner foi", "Estimer véridique"],
     ["Faire confiance aveuglément", "S'en remettre à"]),

    # essayer (try) ↔ "test"
    ("essayer", "verbe",
     ["Tenter", "Faire un essai", "S'efforcer de"],
     ["Tester en laboratoire", "Mettre à l'épreuve scientifique"]),

    # commencer (begin) ↔ "open" / "start out"
    ("commencer", "verbe",
     ["Débuter", "Entamer", "Mettre en marche"],
     ["Ouvrir la voie", "Inaugurer officiellement", "Couper le ruban"]),

    # sucre (the substance) ↔ "Produit sucré" (the derived adjective form
    # of sucre — sucré means "sweetened" / describes things containing
    # sucre, not the substance itself). The LoRA confuses lemma → derived-
    # form gloss. Same word-family confusion pattern as errer ↔ égarer.
    ("sucre", "nom",
     ["Édulcorant naturel", "Glucide cristallisé", "Aliment du café",
      "Substance des bonbons"],
     ["Produit sucré", "Aliment qui sucre", "Chose au goût sucré",
      "Produits sucrés"]),
]


def all_pairs():
    """Yield (lemma, pos, chosen, rejected) tuples from all three buckets.
    The (chosen, rejected) cartesian product is expanded so each variant
    pair appears as a separate training example — this is the diversity
    the iter17 corpus lacked."""
    for bucket in (CROSS_LINGUAL_HOMOGRAPH, POS_GLOSS_MISMATCH, NEAR_SYNONYM_CONFUSION):
        for lemma, pos, chosens, rejecteds in bucket:
            for c in chosens:
                for r in rejecteds:
                    yield (lemma, pos, c, r)


if __name__ == "__main__":
    # Smoke test: count pairs per bucket.
    from collections import Counter
    counts = Counter()
    for lemma, pos, c, r in all_pairs():
        counts[pos] += 1
    print(f"total pairs: {sum(counts.values())}")
    for pos, n in counts.most_common():
        print(f"  {pos}: {n}")
