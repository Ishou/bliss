#!/usr/bin/env python3
"""Hand-authored (chosen, rejected) DPO pairs for iter19 — English-leak rejections.

Failure mode targeted
---------------------
iter18's CROSS_LINGUAL_HOMOGRAPH bucket pinned a specific class of bug:
French lemmas whose token spelling matches an English word with a
different meaning (`cane`, `chat`, `pain`, …). iter19 widens the
attack surface to a different and more pervasive leak: lemmas that
are **not** FR/EN homographs but where the multilingual command-r
base still emits an English clue (or a clue with English words
mid-sentence) despite the iter18 system prompt's explicit
"Tu réponds toujours en français, jamais en anglais."

Two observed sub-modes:

1. WHOLLY_ENGLISH_CLUE — model returns an English sentence wholesale.
   e.g.  `chien → "Man's best friend"`, `voiture → "Motor vehicle"`,
         `dormir → "To take a nap"`.

2. MIXED_LANGUAGE_LEAK — French syntax with one or two English content
   words substituted in. e.g.  `ordinateur → "Le computer"`,
   `portable → "Phone portable"`,  `voyage → "Long travel"`.

DPO signal
----------
Each entry pairs a tight idiomatic French clue (chosen) against a
realistic English-leak rendering (rejected). We aim for diversity on
both sides — the model needs to learn the *category* "stay in French"
rather than memorise a single (lemma → French clue) mapping. The
cartesian product expansion in `all_pairs()` mirrors iter18 so each
chosen × rejected variant emits a separate training row.

Coverage spans common nouns (concrete + abstract), verbs (action +
state), and adjectives — deliberately broad rather than leak-mode
specific, because the model's English-leak prior is not lemma-bound.

Validator hygiene
-----------------
All chosen variants:
  • ≤ 25 chars (MAX_CLUE_CHARS — surface-cell budget).
  • No self-leak (clue does not contain the lemma or its known
    inflected forms).
  • No stem-leak ≥ 5 chars vs the lemma.
  • No pleonasm patterns from `validate_clue._find_pleonasm`.

Rejected variants intentionally violate the "French only" rule — they
do NOT need to pass the validator, since DPO is preference, not
correctness. They must, however, be plausible failures the iter18 v10
adapter would actually produce in spot-checks (English noun/verb head,
or French syntax with an English content word). Hallucinated
"impossible" rejections weaken the preference gradient.

Consumed by a future `build_iter19_corpus.py` (sibling of
`build_iter18_corpus.py`) which concatenates iter18 authored pairs +
these iter19 English-leak pairs into a fresh DPO corpus on top of the
v9 SFT base.

CC0-1.0 — Bliss-authored / Claude-Code-collab. No DBnary, no external
corpora.
"""

# --------------------------------------------------------------------- #
# Sub-bucket A — WHOLLY_ENGLISH_CLUE                                    #
# Each entry: (lemma, pos, [chosen_variants...], [rejected_variants...])  #
# Rejected variants are full English clues. Chosen variants are tight    #
# idiomatic French clues.                                                #
# --------------------------------------------------------------------- #
WHOLLY_ENGLISH_CLUE = [
    # Common nouns — concrete
    ("chien",   "nom",
     ["Compagnon de l'homme", "Animal fidèle", "Toutou domestique", "Meilleur ami humain"],
     ["Man's best friend", "Loyal pet", "Domestic canine", "Pet dog at home"]),

    ("maison",  "nom",
     ["Habitation", "Logis", "Demeure", "Toit familial"],
     ["House to live in", "Home dwelling", "Family residence", "Living place"]),

    ("arbre",   "nom",
     ["Végétal à tronc", "Végétal feuillu", "Géant du bois", "Végétal à racines"],
     ["Tall tree", "Forest plant", "Wooden plant with leaves", "Tree with branches"]),

    ("soleil",  "nom",
     ["Astre du jour", "Étoile centrale", "Source de lumière", "Boule de feu céleste"],
     ["Day star", "Sun in the sky", "Bright daylight source", "Burning sphere above"]),

    ("lune",    "nom",
     ["Astre de la nuit", "Satellite terrestre", "Croissant nocturne"],
     ["Night satellite", "Moon in the sky", "Lunar body"]),

    ("eau",     "nom",
     ["Liquide vital", "Onde transparente", "Boisson naturelle", "Élément de la rivière"],
     ["Drinking liquid", "Pure water source", "Clear wet liquid"]),

    ("feu",     "nom",
     ["Flamme vive", "Combustion ardente", "Brasier rougeoyant"],
     ["Burning flame", "Hot fire", "Red flame source"]),

    ("voiture", "nom",
     ["Automobile", "Véhicule motorisé", "Bagnole familiale", "Tacot du dimanche"],
     ["Motor vehicle", "Driving machine", "Four-wheel car", "Personal automobile"]),

    ("cheval",  "nom",
     ["Équidé monté", "Coursier", "Étalon", "Bête de selle"],
     ["Riding horse", "Galloping animal", "Horse for riding", "Equine mount"]),

    ("livre",   "nom",
     ["Ouvrage relié", "Recueil de pages", "Volume imprimé"],
     ["Reading book", "Book to read", "Printed pages bound", "Bound paper book"]),

    ("table",   "nom",
     ["Meuble plat", "Plan horizontal", "Plateau sur pieds"],
     ["Wooden table", "Flat eating surface", "Dining furniture"]),

    ("école",   "nom",
     ["Lieu scolaire", "Lieu d'apprentissage", "Maison d'instruction"],
     ["School building", "Learning place for kids", "Education house"]),

    ("pomme",   "nom",
     ["Fruit du verger", "Reinette croquante", "Fruit rouge ou vert"],
     ["Apple fruit", "Red sweet apple", "Crunchy round fruit", "Tree fruit red"]),

    ("ami",     "nom",
     ["Camarade proche", "Confident", "Compagnon fidèle"],
     ["Best friend close", "Close mate", "Buddy of mine", "Dear friend"]),

    ("mère",    "nom",
     ["Maman aimante", "Parent féminin", "Femme qui enfante"],
     ["Mother dear", "Loving mom", "Female parent", "Mama at home"]),

    ("père",    "nom",
     ["Papa", "Géniteur", "Homme qui engendre"],
     ["Father figure", "Loving dad", "Male parent", "Daddy at home"]),

    ("jour",    "nom",
     ["Période diurne", "Durée diurne", "Lumière entre deux nuits"],
     ["Bright day", "Daytime period", "Sunny day", "Day of the week"]),

    ("nuit",    "nom",
     ["Période nocturne", "Moment du sommeil", "Période sombre"],
     ["Dark night", "Sleeping hours", "Night time", "Late dark hours"]),

    ("main",    "nom",
     ["Extrémité du bras", "Préhension humaine", "Outil pour saisir"],
     ["Human hand", "Hand with fingers", "Five-finger grip"]),

    ("œil",     "nom",
     ["Organe de la vue", "Pupille humaine", "Globe de l'iris"],
     ["Seeing eye", "Eye to see with", "Eyeball with lens"]),

    # Common nouns — abstract
    ("amour",   "nom",
     ["Sentiment tendre", "Affection ardente", "Passion du cœur"],
     ["Romantic love", "Deep love feeling", "Strong love", "Love and affection"]),

    ("argent",  "nom",
     ["Monnaie sonnante", "Métal blanc", "Capital sonnant"],
     ["Cash money", "Silver metal", "Money for spending", "Coins and bills"]),

    ("guerre",  "nom",
     ["Conflit armé", "Bataille générale", "Lutte entre nations"],
     ["Armed war", "War between countries", "Military conflict", "Big battle"]),

    ("paix",    "nom",
     ["Absence de conflit", "Calme général", "Concorde des peuples"],
     ["World peace", "Peace and quiet", "No more war", "Calm peaceful state"]),

    # Verbs — action
    ("manger",  "verbe",
     ["Avaler des aliments", "Se nourrir", "Prendre un repas"],
     ["To eat food", "Have a meal", "Consume nourishment", "Eat dinner"]),

    ("dormir",  "verbe",
     ["Reposer la nuit", "Faire un somme", "Sommeiller"],
     ["To sleep at night", "Take a nap", "Get some sleep", "Catch some Zs"]),

    ("courir",  "verbe",
     ["Aller à vive allure", "Filer rapidement", "Galoper sur ses jambes"],
     ["To run fast", "Sprint along", "Move at high speed", "Run quickly"]),

    ("aimer",   "verbe",
     ["Chérir", "Avoir de l'affection", "Adorer tendrement"],
     ["To love deeply", "Hold dear", "Feel affection for", "Care about"]),

    ("savoir",  "verbe",
     ["Connaître par étude", "Maîtriser un sujet", "Détenir une science"],
     ["To know facts", "Have knowledge of", "Be aware of", "Know about"]),

    ("finir",   "verbe",
     ["Achever", "Mettre un terme", "Conclure une tâche"],
     ["To finish a task", "Complete fully", "Bring to an end", "Wrap things up"]),

    ("ouvrir",  "verbe",
     ["Désobstruer", "Donner accès", "Déverrouiller"],
     ["To open up", "Make open wide", "Unlock the door", "Open the lid"]),

    ("parler",  "verbe",
     ["S'exprimer oralement", "Discourir", "Échanger des mots"],
     ["To speak words", "Talk aloud", "Say something out", "Have a chat"]),

    ("écrire",  "verbe",
     ["Tracer des lettres", "Rédiger un texte", "Noter sur papier"],
     ["To write text", "Pen down words", "Inscribe on paper", "Write a note"]),

    ("lire",    "verbe",
     ["Parcourir un livre", "Déchiffrer un texte", "Suivre des lignes"],
     ["To read books", "Read aloud", "Look at written words", "Read a page"]),

    ("jouer",   "verbe",
     ["S'amuser à un jeu", "Se divertir", "Pratiquer un sport"],
     ["To play games", "Have fun playing", "Game and play", "Play around"]),

    ("penser",  "verbe",
     ["Réfléchir", "Méditer", "Cogiter en silence"],
     ["To think hard", "Use the brain", "Think deeply", "Have a thought"]),

    # Adjectives
    ("beau",    "adj",
     ["Magnifique", "Splendide", "Charmant à voir"],
     ["Very beautiful", "Pretty to look at", "Good looking", "Nice and pretty"]),

    ("grand",   "adj",
     ["Imposant", "Vaste de stature", "Gigantesque"],
     ["Big in size", "Tall and large", "Quite tall", "Very big"]),

    ("petit",   "adj",
     ["De faible taille", "Minuscule", "Modeste de taille"],
     ["Small in size", "Tiny little thing", "Very small", "Little and short"]),

    ("vieux",   "adj",
     ["Âgé de longue date", "Suranné", "Ancien"],
     ["Very old", "Old aged", "Aged a lot", "Old and worn"]),

    ("jeune",   "adj",
     ["En bas âge", "Adolescent", "Nouveau venu"],
     ["Young aged", "Not old yet", "Quite young", "Just a kid"]),

    ("bon",     "adj",
     ["Savoureux au goût", "Agréable au palais", "Excellent au goût"],
     ["Good quality", "Tasting nice", "Quite good", "Pretty good"]),

    ("noir",    "adj",
     ["Sombre comme l'encre", "Ténébreux", "Obscur"],
     ["Black color", "Dark like coal", "Pitch dark", "Coal black"]),

    ("blanc",   "adj",
     ["Pâle comme neige", "Immaculé", "Clair comme lait"],
     ["White color", "Snow white", "Pure white", "Milk white"]),

    ("rouge",   "adj",
     ["Écarlate", "Vermillon", "Cramoisi"],
     ["Red color", "Like blood red", "Cherry red", "Bright red"]),

    ("chaud",   "adj",
     ["Brûlant au toucher", "Ardent au contact", "Tiède et plus"],
     ["Hot to touch", "Very warm", "Heated up", "Quite hot"]),

    ("froid",   "adj",
     ["Glacial", "Polaire", "Frais comme l'hiver"],
     ["Cold weather", "Very cold", "Chilly cold", "Cold and icy"]),

    ("dur",     "adj",
     ["Solide au toucher", "Coriace", "Difficile à briser"],
     ["Hard to break", "Solid hard", "Tough material", "Very tough"]),
]


# --------------------------------------------------------------------- #
# Sub-bucket B — MIXED_LANGUAGE_LEAK                                    #
# French syntax with one or two English content words substituted in.   #
# Each chosen renders the same idea fully in French; each rejected      #
# leaks at least one English content word inside French grammar.        #
# This is the subtler leak — the validator can't catch it because       #
# grammalecte typically flags the English token as unknown-head, but    #
# DPO can push the model to prefer the all-French rendering.            #
# --------------------------------------------------------------------- #
MIXED_LANGUAGE_LEAK = [
    ("ordinateur", "nom",
     ["Machine à calculer", "Engin numérique", "Appareil informatique"],
     ["Le computer", "Machine computer", "Computer électronique", "Appareil computer"]),

    ("portable",   "nom",
     ["Téléphone mobile", "Combiné cellulaire", "Mobile de poche"],
     ["Smartphone moderne", "Phone portable", "Cell phone moderne", "Mobile phone de poche"]),

    ("semaine",    "nom",
     ["Sept jours", "Période de 7 jours", "Suite hebdomadaire"],
     ["Une week complète", "Week de sept jours", "Période d'une week"]),

    ("voyage",     "nom",
     ["Déplacement lointain", "Périple", "Excursion organisée"],
     ["Long travel", "Travel à l'étranger", "Trip de loisir", "Périple en travel"]),

    ("bureau",     "nom",
     ["Lieu de travail", "Cabinet professionnel", "Pupitre de travail"],
     ["L'office du patron", "Bureau office", "Office moderne", "Desk professionnel"]),

    ("courrier",   "nom",
     ["Pli postal", "Envoi postal", "Acheminement de plis"],
     ["Mail postal", "Postal mail", "Le mail du jour", "Mail à distribuer"]),

    ("réunion",    "nom",
     ["Assemblée de travail", "Rencontre planifiée", "Séance collective"],
     ["Meeting officiel", "Le meeting de travail", "Business meeting", "Meeting de l'équipe"]),

    ("entraîneur", "nom",
     ["Chef d'équipe sportive", "Mentor de l'athlète", "Préparateur du joueur"],
     ["Le coach sportif", "Coach de l'équipe", "Sports coach"]),

    ("courriel",   "nom",
     ["Message électronique", "Pli numérique", "Lettre par réseau"],
     ["Email reçu", "Le mail du matin", "Email électronique", "Email à lire"]),

    ("fin de semaine", "nom",
     ["Samedi et dimanche", "Repos hebdomadaire", "Congé de deux jours"],
     ["Le week-end", "Week-end de repos", "Weekend tranquille"]),
]


def all_pairs():
    """Yield (lemma, pos, chosen, rejected) tuples from both sub-buckets.

    Cartesian product on (chosen × rejected) per lemma, mirroring
    `iter18_authored_pairs.all_pairs()` so the iter19 corpus builder
    can concatenate the two sources without bespoke logic.
    """
    for bucket in (WHOLLY_ENGLISH_CLUE, MIXED_LANGUAGE_LEAK):
        for lemma, pos, chosens, rejecteds in bucket:
            for c in chosens:
                for r in rejecteds:
                    yield (lemma, pos, c, r)


if __name__ == "__main__":
    from collections import Counter
    counts = Counter()
    lemma_counts = Counter()
    for lemma, pos, c, r in all_pairs():
        counts[pos] += 1
        lemma_counts[lemma] += 1
    print(f"total pairs: {sum(counts.values())}")
    print(f"unique lemmas: {len(lemma_counts)}")
    for pos, n in counts.most_common():
        print(f"  {pos}: {n}")
