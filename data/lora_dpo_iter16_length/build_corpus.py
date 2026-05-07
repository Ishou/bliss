#!/usr/bin/env python3
"""Build the iter16 DPO preference corpus for length preference.

Each entry is (lemma, pos, chosen, rejected, category):

  A: length-only contrast — both ≤25 chars, chosen meaningfully shorter.
     Teaches "shorter equivalent is preferred even when both fit".
  B: cap-violation contrast — chosen ≤25, rejected >25 chars (won't ship).
     Teaches "stay under the cap when an equally-good shorter exists".
  C: preamble-strip contrast — chosen is a bare clue, rejected wraps the
     same content in formulaic scaffolding ("Action de", "Pratique de",
     "Personne qui", "Qui est", "État de", "Caractère de ce qui est").
     Teaches "drop the boilerplate".

Both `chosen` and `rejected` must:
  - be in citation form (verb infinitive, masc-sing noun/adj)
  - pass `validate_lemma_clue` (no self-leak, stem-leak, pleonasm,
    head-not-lemma, pos-mismatch, no-head, too-long for `chosen`).
    Note: `rejected` is allowed to fail `too-long` in category B by
    construction; we only enforce structural validity for the rest.

Splits: 80% train / 10% valid / 10% test, stratified by category.

Run: python data/lora_dpo_iter16_length/build_corpus.py
Outputs: train.jsonl, valid.jsonl, test.jsonl in this directory.
"""

from __future__ import annotations

import json
import random
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts" / "eval"))

from morphology_index import MorphologyIndex  # noqa: E402
from validate_clue import validate_lemma_clue  # noqa: E402


# --- CATEGORY A: length-only contrasts (60) -----------------------------
# Both clues are ≤25 chars and structurally valid; chosen is shorter.
# Both have lemma-form heads matching the target POS — that's the
# validator's contract on LoRA output.
A_PAIRS: list[tuple[str, str, str, str]] = [
    # (lemma, pos, chosen, rejected)
    # ---- Nouns ----
    ("amour",       "nom",   "Affection",           "Affection vive et durable"),
    ("peur",        "nom",   "Crainte",             "Crainte vive et soudaine"),
    ("bonté",       "nom",   "Gentillesse",         "Gentillesse bienveillante"),
    ("colère",      "nom",   "Ire",                 "Irritation vive"),
    ("paresse",     "nom",   "Fainéantise",         "Fainéantise notoire"),
    ("richesse",    "nom",   "Opulence",            "Opulence et abondance"),
    ("ennui",       "nom",   "Lassitude",           "Lassitude et désœuvrement"),
    ("courage",     "nom",   "Bravoure",            "Bravoure devant le péril"),
    ("douleur",     "nom",   "Souffrance",          "Souffrance physique vive"),
    ("désir",       "nom",   "Envie",               "Envie pressante"),
    ("orage",       "nom",   "Tempête",             "Tempête avec éclairs"),
    ("aube",        "nom",   "Aurore",              "Aurore matinale"),
    ("ouragan",     "nom",   "Cyclone",             "Cyclone tropical violent"),
    ("essaim",      "nom",   "Nuée",                "Nuée d'insectes en vol"),
    ("hangar",      "nom",   "Remise",              "Remise spacieuse"),
    ("oasis",       "nom",   "Îlot vert",           "Îlot vert au désert"),
    ("paroi",       "nom",   "Cloison",             "Cloison verticale lisse"),
    ("vestibule",   "nom",   "Antichambre",         "Antichambre d'entrée"),
    ("frisson",     "nom",   "Tremblement",         "Tremblement bref"),
    ("brouillard",  "nom",   "Brume",               "Brume dense et basse"),
    ("crépuscule",  "nom",   "Pénombre",            "Pénombre du soir"),
    ("ourlet",      "nom",   "Lisière",             "Lisière cousue à l'étoffe"),
    ("jument",      "nom",   "Cavale",              "Cavale au pré"),
    # ---- Adjectives ----
    ("rapide",      "adj",   "Véloce",              "Véloce et alerte"),
    ("triste",      "adj",   "Mélancolique",        "Mélancolique et morose"),
    ("joyeux",      "adj",   "Gai",                 "Gai et enjoué"),
    ("calme",       "adj",   "Paisible",            "Paisible et serein"),
    ("propre",      "adj",   "Net",                 "Net et soigné"),
    ("doux",        "adj",   "Suave",               "Suave et délicat"),
    ("fragile",     "adj",   "Délicat",             "Délicat et cassant"),
    ("solide",      "adj",   "Robuste",             "Robuste et résistant"),
    ("brave",       "adj",   "Vaillant",            "Vaillant et hardi"),
    ("rusé",        "adj",   "Malin",               "Malin et madré"),
    ("avide",       "adj",   "Cupide",              "Cupide et insatiable"),
    ("nerveux",     "adj",   "Anxieux",             "Anxieux et agité"),
    ("placide",     "adj",   "Serein",              "Serein et imperturbable"),
    ("scrupuleux",  "adj",   "Méticuleux",          "Méticuleux et appliqué"),
    ("loquace",     "adj",   "Bavard",              "Bavard et volubile"),
    ("opiniâtre",   "adj",   "Tenace",              "Tenace et persévérant"),
    # ---- Verbs (rejected = head + adverb / coordinated synonym) ----
    ("bavarder",    "verbe", "Jaser",               "Jaser et caqueter"),
    ("hurler",      "verbe", "Vociférer",           "Vociférer fortement"),
    ("chuchoter",   "verbe", "Murmurer",            "Murmurer doucement"),
    ("trembler",    "verbe", "Frémir",              "Frémir nerveusement"),
    ("ramper",      "verbe", "Glisser",             "Glisser à plat ventre"),
    ("graver",      "verbe", "Buriner",             "Buriner profondément"),
    ("nettoyer",    "verbe", "Récurer",             "Récurer avec soin"),
    ("broyer",      "verbe", "Pulvériser",          "Pulvériser finement"),
    ("ranger",      "verbe", "Ordonner",            "Ordonner et classer"),
    ("trier",       "verbe", "Sélectionner",        "Sélectionner avec soin"),
    ("éclater",     "verbe", "Crever",              "Crever bruyamment"),
    ("flotter",     "verbe", "Surnager",            "Surnager paisiblement"),
    ("gratter",     "verbe", "Égratigner",          "Égratigner légèrement"),
    ("ronfler",     "verbe", "Râler",               "Râler en dormant"),
    ("trotter",     "verbe", "Cheminer",            "Cheminer à vive allure"),
    ("griffonner",  "verbe", "Gribouiller",         "Gribouiller hâtivement"),
    ("ronchonner",  "verbe", "Bougonner",           "Bougonner sans fin"),
    ("frémir",      "verbe", "Tressaillir",         "Tressaillir d'émoi"),
    ("pâlir",       "verbe", "Blêmir",              "Blêmir de stupeur"),
    ("rougir",      "verbe", "S'empourprer",        "S'empourprer de honte"),
    ("scintiller",  "verbe", "Étinceler",           "Étinceler vivement"),
    ("ricaner",     "verbe", "Glousser",            "Glousser méchamment"),
]

# --- CATEGORY B: cap-violation contrasts (50) --------------------------
# chosen ≤25 chars, rejected >25 chars. Same sense; rejected just wordy.
B_PAIRS: list[tuple[str, str, str, str]] = [
    ("ascèse",      "nom",   "Privation",                 "Pratique d'une discipline morale rigoureuse"),
    ("phobie",      "nom",   "Peur intense",              "Crainte irrationnelle d'un objet ou d'une situation"),
    ("vertige",     "nom",   "Tournis",                   "Sensation de déséquilibre face au vide"),
    ("vacarme",     "nom",   "Tapage",                    "Bruit assourdissant et désordonné"),
    ("hurlement",   "nom",   "Cri perçant",               "Très long cri poussé avec violence"),
    ("torrent",     "nom",   "Cascade puissante",         "Cours d'eau rapide et tumultueux des montagnes"),
    ("bourrasque",  "nom",   "Coup de vent",              "Brutale et brève rafale de vent"),
    ("génie",       "nom",   "Esprit brillant",           "Qualité intellectuelle hors du commun"),
    ("ampleur",     "nom",   "Vastitude",                 "Caractère de ce qui est très grand"),
    ("morosité",    "nom",   "Tristesse",                 "Humeur sombre et mélancolique persistante"),
    ("clémence",    "nom",   "Indulgence",                "Disposition à pardonner les fautes commises"),
    ("frugalité",   "nom",   "Sobriété",                  "Habitude de manger très peu et simplement"),
    ("véracité",    "nom",   "Authenticité",              "Caractère de ce qui est vrai et avéré"),
    ("perspicacité","nom",   "Sagacité",                  "Qualité de discerner ce qui échappe aux autres"),
    ("longanimité", "nom",   "Patience",                  "Disposition à supporter sans se plaindre"),
    # Adjectives — rejected is verbose, chosen short.
    ("compliqué",   "adj",   "Difficile",                 "Qui présente beaucoup de complications"),
    ("magnanime",   "adj",   "Indulgent",                 "Qui pardonne avec grandeur d'âme"),
    ("inflammable", "adj",   "Combustible",               "Qui peut prendre feu très facilement"),
    ("crédule",     "adj",   "Naïf",                      "Qui croit trop aisément ce qu'on dit"),
    ("éphémère",    "adj",   "Passager",                  "Qui ne dure que très peu de temps"),
    ("récalcitrant","adj",   "Rebelle",                   "Qui résiste opiniâtrement à toute injonction"),
    ("succinct",    "adj",   "Bref",                      "Qui est dit en peu de mots"),
    ("perspicace",  "adj",   "Sagace",                    "Qui voit ce que les autres ignorent"),
    ("véhément",    "adj",   "Violent",                   "Qui est emporté par la passion ardente"),
    ("placide",     "adj",   "Serein",                    "Qui ne se laisse jamais émouvoir"),
    ("indolent",    "adj",   "Apathique",                 "Qui montre peu d'ardeur au travail"),
    ("intrépide",   "adj",   "Vaillant",                  "Qui n'a peur d'aucun péril physique"),
    ("loquace",     "adj",   "Bavard",                    "Qui aime parler longuement de tout"),
    ("opiniâtre",   "adj",   "Tenace",                    "Qui persiste dans ses idées sans céder"),
    ("scrupuleux",  "adj",   "Méticuleux",                "Qui agit avec une grande conscience morale"),
    ("hagard",      "adj",   "Farouche",                  "Qui a l'air égaré et confus profondément"),
    ("véhémence",   "nom",   "Fougue",                    "Force impétueuse de l'expression passionnée"),
    ("sagacité",    "nom",   "Finesse",                   "Vivacité de l'intelligence à comprendre"),
    ("opulence",    "nom",   "Faste",                     "Très grande abondance des biens matériels"),
    # Verbs — rejected wordy.
    ("démissionner","verbe", "Renoncer",                  "Quitter volontairement ses fonctions"),
    ("compatir",    "verbe", "S'apitoyer",                "Partager les peines et chagrins d'autrui"),
    ("consoler",    "verbe", "Réconforter",               "Adoucir le chagrin de quelqu'un par des paroles"),
    ("réfléchir",   "verbe", "Méditer",                   "Examiner longuement par la pensée une question"),
    ("subjuguer",   "verbe", "Captiver",                  "Soumettre par la force du charme et de l'éloquence"),
    ("vagabonder",  "verbe", "Errer",                     "Aller çà et là sans destination définie"),
    ("conjecturer", "verbe", "Présumer",                  "Émettre une supposition fondée sur des indices"),
    ("morfondre",   "verbe", "Languir",                   "Attendre longuement dans la tristesse et le froid"),
    ("rebrousser",  "verbe", "Revenir",                   "Faire demi-tour et retourner sur ses pas"),
    ("sermonner",   "verbe", "Réprimander",               "Faire un long discours moralisateur à quelqu'un"),
    ("subodorer",   "verbe", "Soupçonner",                "Pressentir vaguement quelque chose qu'on ignore"),
    ("transgresser","verbe", "Enfreindre",                "Aller à l'encontre d'un ordre ou d'une loi"),
    ("vituperer",   "verbe", "Blâmer",                    "Critiquer avec véhémence et amertume"),
    ("éconduire",   "verbe", "Rejeter",                   "Refuser de recevoir poliment un visiteur"),
    ("rabrouer",    "verbe", "Rembarrer",                 "Repousser quelqu'un avec des paroles dures"),
]

# --- CATEGORY C: preamble-strip contrasts (60) --------------------------
# chosen is a bare clue; rejected wraps the same content in formulaic
# scaffolding (Action de / Pratique de / Personne qui / État de / Caractère
# de / Manière de). Restricted to NOUN targets — only nouns can have a
# noun-headed preamble that passes the validator's POS check.
C_PAIRS: list[tuple[str, str, str, str]] = [
    # "Action de [V]" preamble — head=Action (nom), passes POS gate.
    ("vol",         "nom",   "Larcin",                "Action de soustraire un bien"),
    ("pari",        "nom",   "Mise",                  "Action de gager"),
    ("apport",      "nom",   "Contribution",          "Action de fournir"),
    ("envoi",       "nom",   "Expédition",            "Action de remettre au loin"),
    ("appui",       "nom",   "Soutien",               "Action de secourir"),
    ("rejet",       "nom",   "Refus",                 "Action de repousser"),
    ("départ",      "nom",   "Sortie",                "Action de partir"),
    ("oubli",       "nom",   "Méprise",               "Action de négliger"),
    ("recherche",   "nom",   "Quête",                 "Action de chercher"),
    ("élan",        "nom",   "Impulsion",             "Action de bondir"),
    ("écho",        "nom",   "Réverbération",         "Action de renvoyer un son"),
    ("achat",       "nom",   "Emplette",              "Action d'acheter"),
    ("vente",       "nom",   "Cession",               "Action de céder un bien"),
    ("don",         "nom",   "Largesse",              "Action de donner gratuitement"),
    # "Pratique de [N]" / "Pratique régulière de" preamble
    ("foi",         "nom",   "Croyance",              "Pratique religieuse profonde"),
    ("ferveur",     "nom",   "Zèle",                  "Pratique avec ardeur"),
    ("piété",       "nom",   "Dévotion",              "Pratique religieuse fervente"),
    ("rite",        "nom",   "Cérémonial",            "Pratique solennelle codifiée"),
    ("ascèse",      "nom",   "Privation",             "Pratique de la sobriété"),
    ("hygiène",     "nom",   "Salubrité",             "Pratique de la propreté"),
    # "Personne qui [V]" preamble — head=Personne (nom).
    ("voleur",      "nom",   "Larron",                "Personne qui dérobe"),
    ("menteur",     "nom",   "Hâbleur",               "Personne qui ment"),
    ("rêveur",      "nom",   "Songe-creux",           "Personne qui imagine"),
    ("orateur",     "nom",   "Tribun",                "Personne qui harangue"),
    ("juge",        "nom",   "Magistrat",             "Personne qui arbitre"),
    ("amoureux",    "nom",   "Soupirant",             "Personne qui idolâtre"),
    ("avare",       "nom",   "Grippe-sou",            "Personne qui thésaurise"),
    ("ivrogne",     "nom",   "Soûlard",               "Personne qui s'enivre"),
    ("savant",      "nom",   "Érudit",                "Personne qui sait beaucoup"),
    ("paresseux",   "nom",   "Fainéant",              "Personne qui flemmarde"),
    ("intrus",      "nom",   "Importun",              "Personne qui s'immisce"),
    ("héros",       "nom",   "Brave",                 "Personne qui s'illustre"),
    ("complice",    "nom",   "Acolyte",               "Personne qui aide en secret"),
    ("témoin",      "nom",   "Spectateur",            "Personne qui assiste à un fait"),
    ("traître",     "nom",   "Félon",                 "Personne qui trahit"),
    ("guide",       "nom",   "Cicérone",              "Personne qui mène"),
    ("messager",    "nom",   "Émissaire",             "Personne qui transmet"),
    ("élève",       "nom",   "Disciple",              "Personne qui apprend"),
    # "État de [N]" / "Caractère de [N]" preamble
    ("fatigue",     "nom",   "Lassitude",             "État de profonde lassitude"),
    ("joie",        "nom",   "Allégresse",            "État d'allégresse"),
    ("misère",      "nom",   "Indigence",             "État d'indigence extrême"),
    ("pauvreté",    "nom",   "Dénuement",             "État de dénuement total"),
    ("rareté",      "nom",   "Pénurie",               "État de pénurie"),
    ("bêtise",      "nom",   "Sottise",               "Caractère de sottise"),
    ("propreté",    "nom",   "Netteté",               "État de parfaite netteté"),
    ("ivresse",     "nom",   "Ébriété",               "État d'ébriété avancée"),
    ("torpeur",     "nom",   "Somnolence",            "État de somnolence"),
    ("nudité",      "nom",   "Mise à nu",             "État de mise à nu complète"),
    ("solitude",    "nom",   "Isolement",             "État d'isolement choisi"),
    ("liberté",     "nom",   "Délivrance",            "État d'émancipation pleine"),
    ("douceur",     "nom",   "Suavité",               "Qualité de suavité"),
    # "Manière de [V]" / "Façon de [V]" preamble
    ("démarche",    "nom",   "Allure",                "Manière de marcher"),
    ("habitude",    "nom",   "Coutume",               "Manière régulière d'agir"),
    ("style",       "nom",   "Touche",                "Manière personnelle"),
    ("technique",   "nom",   "Méthode",               "Manière de procéder"),
    ("dialecte",    "nom",   "Patois",                "Manière régionale de parler"),
    ("politesse",   "nom",   "Civilité",              "Manière courtoise d'agir"),
    ("élégance",    "nom",   "Distinction",           "Manière raffinée"),
    ("brutalité",   "nom",   "Rudesse",               "Manière violente d'agir"),
    ("raideur",     "nom",   "Rigidité",              "Manière compassée"),
    ("nonchalance", "nom",   "Indolence",             "Manière indolente"),
]


ALL_PAIRS: list[tuple[str, str, str, str, str]] = (
    [(l, p, c, r, "A") for l, p, c, r in A_PAIRS]
    + [(l, p, c, r, "B") for l, p, c, r in B_PAIRS]
    + [(l, p, c, r, "C") for l, p, c, r in C_PAIRS]
)


def main() -> None:
    print(f"loaded {len(A_PAIRS)} A + {len(B_PAIRS)} B + {len(C_PAIRS)} C = "
          f"{len(ALL_PAIRS)} candidate pairs")

    # Validate via grammalecte morphology + validate_lemma_clue.
    lex = Path.home() / "Downloads/grammalecte/lexique-grammalecte-fr-v7.7.txt"
    print(f"loading morphology index from {lex}...")
    index = MorphologyIndex.load(lex)

    accepted: list[tuple[str, str, str, str, str]] = []
    rejected: list[tuple[str, str, str, str, str, str]] = []  # + reason

    for lemma, pos, chosen, reject, cat in ALL_PAIRS:
        # `chosen` MUST pass validate_lemma_clue end-to-end.
        c_res = validate_lemma_clue(chosen, lemma, pos, index)
        if c_res.flag != "ok":
            rejected.append((lemma, pos, chosen, reject, cat,
                             f"chosen failed: {c_res.flag}: {c_res.reason}"))
            continue
        # `rejected` must pass structural checks (no self-leak, no
        # pleonasm, no head-not-lemma) but is allowed to fail `too-long`
        # in category B by construction.
        r_res = validate_lemma_clue(reject, lemma, pos, index)
        if r_res.flag not in ("ok", "too-long"):
            rejected.append((lemma, pos, chosen, reject, cat,
                             f"rejected failed: {r_res.flag}: {r_res.reason}"))
            continue
        if cat == "B" and r_res.flag != "too-long":
            # Category B promises a cap violation — if the rejected
            # clue actually fits, the contrast collapses to category A.
            # Reclassify to category A rather than dropping.
            cat = "A"
        if cat != "B" and r_res.flag == "too-long":
            # Category A/C expects rejected to fit — if it doesn't,
            # promote to category B (the contrast is still meaningful).
            cat = "B"
        accepted.append((lemma, pos, chosen, reject, cat))

    print(f"\naccepted: {len(accepted)}/{len(ALL_PAIRS)}")
    print(f"rejected: {len(rejected)}")
    for lemma, pos, c, r, cat, reason in rejected[:25]:
        print(f"  {cat} {lemma} [{pos}]: {reason}")
        print(f"      chosen={c!r}  rejected={r!r}")
    if len(rejected) > 25:
        print(f"  ... ({len(rejected) - 25} more)")

    # Stratified split: 80/10/10 per category, then concatenate.
    rng = random.Random(20260507)
    by_cat: dict[str, list[tuple[str, str, str, str, str]]] = {"A": [], "B": [], "C": []}
    for entry in accepted:
        by_cat[entry[4]].append(entry)

    train: list[tuple] = []
    valid: list[tuple] = []
    test: list[tuple] = []
    for cat in ("A", "B", "C"):
        rows = by_cat[cat]
        rng.shuffle(rows)
        n_test = max(1, len(rows) // 10)
        n_valid = max(1, len(rows) // 10)
        test += rows[:n_test]
        valid += rows[n_test:n_test + n_valid]
        train += rows[n_test + n_valid:]

    print(f"\nsplits: train={len(train)} valid={len(valid)} test={len(test)}")
    print(f"category distribution per split:")
    for split_name, split in [("train", train), ("valid", valid), ("test", test)]:
        counts = {"A": 0, "B": 0, "C": 0}
        for e in split:
            counts[e[4]] += 1
        print(f"  {split_name:5s}: A={counts['A']:3d}  B={counts['B']:3d}  C={counts['C']:3d}")

    out_dir = Path(__file__).resolve().parent
    for name, rows in (("train", train), ("valid", valid), ("test", test)):
        path = out_dir / f"{name}.jsonl"
        with path.open("w", encoding="utf-8") as f:
            for lemma, pos, chosen, reject, cat in rows:
                # Match iter13's prompt format. POS is upper-case in tag.
                pos_tag = pos
                prompt = f"Génère une définition mots-fléchés courte pour: {lemma.upper()} [{pos_tag}]"
                f.write(json.dumps(
                    {"prompt": prompt, "chosen": chosen, "rejected": reject},
                    ensure_ascii=False,
                ) + "\n")
        print(f"wrote {path}: {len(rows)} pairs")


if __name__ == "__main__":
    main()
