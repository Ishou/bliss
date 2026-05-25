# Style Guide — Définitions de mots fléchés français

> **Lane scope:** This style guide drives the Modal + HuggingFace
> clue-AI lane (ADR-0057, Mistral-Nemo-Base-2407 target). The
> existing local-MLX / Command-R lane is documented in
> `.claude/skills/clue-ai/SKILL.md` and `docs/eval/clue-gen-v0.md`;
> the two lanes share `validate_clue.py`'s structural gates only
> through the fused `pipeline_v2` validator (this doc's §8.3
> filters 9 + 10).

## Préambule

Ce document est le référentiel de style pour la génération, l'annotation
et l'évaluation des définitions de mots fléchés en français. Il a trois
usages :

1. **Prompt système** pour la génération synthétique du dataset
   (fine-tuning d'un LLM cible : Mistral Nemo 12B ou Aya Expanse 8B).
2. **Référentiel d'annotation** pour la validation humaine des
   définitions.
3. **Critères d'évaluation** pour le LLM-juge en boucle de revue.

Le périmètre est strictement le français de presse fléchée tel qu'il
s'écrit dans FX, Larousse, Sport Cérébral, *20 Minutes*, *Le Parisien*
et *Le Monde*. Toute définition produite doit pouvoir paraître sans
retouche dans une grille publiée par l'un de ces canaux.

Toute définition est classifiable selon trois axes indépendants :

```
  • STYLE      — la mécanique rhétorique         (9 valeurs, §4)
  • FORCE      — la difficulté de résolution     (1 à 5, partie 2)
  • CATÉGORIE  — le champ sémantique du mot      (partie 2)
```

Force et catégorie sont détaillées dans la **partie 2** du présent
guide ; le présent document couvre les principes, la structure, les
marqueurs canoniques et la taxonomie des styles.

La base curée `data/seed/short_words_unified.csv` (475 entrées validées,
mots de 2 à 7 lettres) sert de référence canonique. Les exemples qui en
sont extraits sont marqués `[curé]`. Les exemples inventés respectent
rigoureusement les conventions observées dans la base et sont marqués
`[inventé]`. Quelques exemples notables, validés hors corpus court, sont
marqués `[ancre validée]`.

## 1. Principes fondamentaux

Sept principes inviolables encadrent la production d'une définition.
Ils s'appliquent à tous les styles (§4), à toutes les forces et à toutes
les catégories sémantiques. Une définition qui viole l'un de ces
principes doit être rejetée ou réécrite.

### 1.1 Non-auto-référence

**Le mot défini ne figure pas dans sa définition, même partiellement,
même déguisé par dérivation ou troncature.**

C'est la règle la plus absolue du genre : la définition est un indice,
pas une paraphrase circulaire. Une première exception documentée
concerne le style `cryptique_morphologique` (§4.8), dans lequel
l'opération sur la graphie est précisément l'objet du jeu. Une seconde
exception concerne les sigles et acronymes, dont la définition canonique
développe les initiales (ex. BO → « Bulletin officiel »). Cette
exception est inhérente à la nature même du sigle.

- ❌ COQ → « Coq de la basse-cour »
- ✅ COQ → « Mâle de la basse-cour » `[curé]`

### 1.2 Langue exclusivement française

**La définition est rédigée en français. Les éléments d'autres langues
ne sont admis que comme objet de la définition, explicitement marqués.**

Le résolveur attend du français : tout vocable étranger doit être
introduit par un marqueur (« anglais », « italien », « slave »,
« occitan », etc., voir §3.1). Les anglicismes non lexicalisés et les
calques syntaxiques sont proscrits.

- ❌ COOL → « Cool » (anglicisme nu, sans marqueur de langue étrangère)
- ❌ JOB → « Boulot ou job » (terme anglais maintenu dans la version
  française)
- ✅ NO → « Refus anglais » `[curé]` (sens étranger correctement marqué)

### 1.3 Brièveté

**La définition tient en 1 à 6 mots, médiane attendue autour de 2-3
mots. 7 ou 8 mots restent tolérés en exception justifiée ; au-delà,
c'est trop long.**

Les grilles imposent une économie typographique stricte : une
définition longue brise le rythme de lecture du joueur et trahit
souvent une définition encyclopédique mal condensée. Si une définition
déborde, c'est presque toujours qu'elle décrit au lieu de désigner.

- ❌ AÏ → « Animal arboricole d'Amérique du Sud connu pour sa
  lenteur extrême »
- ✅ AÏ → « Paresseux » `[curé]`

### 1.4 Pas de phrase complète

**La définition n'a pas la forme sujet explicite + verbe conjugué +
complément. Un verbe conjugué seul (sans sujet exprimé) reste admis ;
le sujet implicite est le mot défini.**

C'est la convention canonique du mot fléché : « Chante au matin » est
correct (sujet implicite = COQ), « Il chante au matin » ne l'est pas.
La forme nominale, infinitive ou participiale est préférable.

- ❌ COQ → « Il chante au matin »
- ✅ COQ → « Chante au matin » `[curé]`

### 1.5 Accord grammatical transparent

**Quand l'accord est exprimé dans la définition (participe accordé,
adjectif accordé), il est cohérent avec le genre et le nombre du mot.
L'absence d'accord (forme nominale neutre, infinitif, adverbe) est
tolérée et fréquente. Quand l'accord est exprimé, c'est un signal pour
le résolveur.**

L'accord du participe ou de l'adjectif accordé dans la définition
contraint la forme du mot solution. La définition n'est pas obligée
d'exprimer cet accord — un syntagme nominal neutre, un infinitif ou un
adverbe sont des formes courantes et acceptées. Mais quand l'accord
*est* exprimé, il doit être correct. Les cas détaillés (masc/fém ×
sing/plur) sont traités en §2.3.

- ❌ NU → « Dépouillée » (accord exprimé mais incohérent : impose NUE)
- ✅ NU → « Dépouillé » `[curé]` (masc. sing. cohérent, exclut NUE, NUS,
  NUES)
- ✅ NU → « Sans vêtement » `[curé]` (forme nominale neutre, accord non
  exprimé : accepté)

**Note** : certaines définitions historiques utilisent un syntagme
nominal de genre différent du mot (ex. ÂNE masculin → « Bête têtue »
féminin). C'est une tolérance ancienne ; la génération moderne vise
l'accord cohérent quand l'accord est exprimé.

### 1.6 Casse et ponctuation normalisées

**Initiale en majuscule, reste en minuscule sauf nom propre. Pas de
point final. Pas de guillemets. Apostrophe typographique `’` (U+2019)
obligatoire.**

Les tolérances ponctuationnelles (parenthèses, virgule unique, tiret,
`?` final pour le calembour) sont définies en §2.4. La règle minimale,
inviolable, est ce triplet : majuscule initiale, pas de point final,
apostrophe typographique.

- ❌ COQ → « mâle de la basse-cour. »
- ✅ COQ → « Mâle de la basse-cour » `[curé]`

### 1.7 Discrimination suffisante

**Une définition doit, soit isolément, soit avec l'aide des
croisements en grille, ramener à un mot unique parmi les candidats de
la longueur cible.**

Deux niveaux de discrimination coexistent :

- **Discrimination forte** *(règle par défaut, définitions vedettes)* —
  la définition fait converger seule vers le mot, sans aide des
  croisements. C'est le standard exigé pour toute définition mise en
  avant et pour toute définition générée par défaut.

  - ❌ RAT → « Animal » (s'applique à des centaines de mots)
  - ✅ RAT → « Rongeur » `[curé]` (très peu de mots de 3 lettres entrent)

- **Discrimination par croisement** *(tolérance pour définitions de
  remplissage)* — la définition seule laisse plusieurs candidats, mais
  les lettres déjà imposées par les mots croisés dans la grille suffisent
  à lever l'ambiguïté. Ce niveau n'est admis que pour les cases
  difficiles de la grille et reste minoritaire dans la production.

  - ✅ AN → « Année » `[curé]` (forte) plutôt que « Durée » (faible isolément, acceptable seulement avec croisements)

**Par défaut, la génération vise la discrimination forte.** La
discrimination par croisement est une concession éditoriale, pas un
mode de production.

---

### Note de conformité — exclusions de la génération automatique

Le style `calembour` (§4.5), qui s'identifie au marqueur `?` final, est
**hors périmètre de la génération automatique v1**. Le modèle est
explicitement entraîné à ne pas le produire. **Toute définition
contenant `?` final en sortie du modèle est rejetée au filtrage.** Ce
style reste documenté dans le présent guide pour cadrer l'enrichissement
manuel de la base curée, mais le LLM n'en émet aucune occurrence. Voir
§4.5 pour le détail de cette exclusion.

## 2. Caractéristiques structurelles

Les principes (§1) disent ce qu'une définition *doit être* ou *ne pas
être*. Cette section dit comment elle *s'écrit* : formes syntaxiques
attendues, élisions canoniques, accord, et règles typographiques. Les
fréquences indiquées ci-dessous reflètent l'observation de la base curée
`short_words_unified.csv`, biaisée vers les mots de 2-3 lettres.

### 2.1 Formes syntaxiques privilégiées

Six formes syntaxiques structurent l'essentiel des définitions de mots
fléchés. Le choix de la forme dépend du mot à définir, de son rôle
grammatical et du style visé.

**Syntagme nominal** — nom-tête éventuellement accompagné d'un
complément (adjectif, complément de nom). Forme la plus courante :
concise, signal d'accord clair, neutralité stylistique. *Fréquence dans
la base : haute.*

- COQ → « Mâle de la basse-cour » `[curé]`
- AS → « Carte forte » `[curé]`
- LUNE → « Astre nocturne » `[curé]`

**Infinitif** — verbe sous sa forme non conjuguée, désignant l'action
ou l'état que le mot dénote. Cible naturelle des mots qui sont
eux-mêmes des verbes à l'infinitif. *Fréquence dans la base : basse*
(les infinitifs sont en général de 4+ lettres, peu représentés dans le
corpus court ; la convention reste pleinement canonique en mots
fléchés).

- TOUCHER → « Percevoir par contact » `[inventé]` (TOUCHER existe en
  base comme nom du sens ; cet usage verbal est attendu pour les 4+
  lettres)
- MANGER → « Avaler » `[inventé]`
- LUIRE → « Briller faiblement » `[inventé]`

**Participe présent** — forme verbale en -ant, parfois adjectivée.
Désigne un agent ou un caractère actif. *Fréquence dans la base : très
basse* (forme essentiellement portée par des mots de 5+ lettres,
absents du corpus court).

- DORMANT → « Endormi profond » `[inventé]`
- MORDANT → « Acide en propos » `[inventé]`
- BRILLANT → « Éclatant » `[inventé]`

**Participe passé** — forme adjectivée du verbe au passé, accordée en
genre et nombre. Forme dominante pour définir des mots eux-mêmes
participes passés (BU, NU, LU, SU, VU, NÉ…). *Fréquence dans la base :
haute.*

- BU → « S'est désaltéré » `[curé]`
- LU → « Déchiffré » `[curé]`
- NÉ → « Venu au monde » `[curé]`

**Adjectif seul** (ou adjectif minimalement complété) — qualité ou
état exprimé sans support nominal. Compact, souvent porteur d'un signal
d'accord direct. *Fréquence dans la base : moyenne.*

- EX → « Ancien » `[curé]`
- IN → « Branché » `[curé]`
- NU → « Dépouillé » `[curé]`

**Adverbe court** (ou locution adverbiale/prépositionnelle brève) —
pour les mots qui sont eux-mêmes des adverbes, prépositions ou
locutions figées. Forme épicène par construction, sans signal d'accord.
*Fréquence dans la base : moyenne.*

- AP → « Après » `[curé]`
- EN → « Dans » `[curé]`
- IN → « À la mode » `[curé]`

À côté de ces six formes, une septième tournure ultra-fréquente dans la
base mérite mention : le **verbe conjugué à la 3e personne du
singulier sans sujet exprimé** (« Chante au matin », « Possède »,
« Se rend »), où le sujet implicite est le mot défini. C'est une
variante particulière de la forme verbale, autorisée par §1.4.
*Fréquence dans la base : haute.*

### 2.2 Élisions et omissions

La concision (§1.3) impose la suppression systématique des mots
porteurs de zéro information discriminante. Quatre élisions sont
canoniques.

**Article défini initial** — l'article qui introduit un syntagme nominal
en tête de définition est omis sauf expression figée.

- ❌ COQ → « Le mâle de la basse-cour »
- ✅ COQ → « Mâle de la basse-cour » `[curé]`

Exceptions : expression figée (« L'Olympe », « La Une »), désambiguïsation
indispensable.

**Verbe « être » implicite** — toute proposition relative en « qui
est » est ramenée à son attribut.

- ❌ IN → « Qui est branché »
- ✅ IN → « Branché » `[curé]`

**Périphrases d'action superflues** — « Action de », « Fait de »,
« Manière de » sont supprimées quand la forme directe suffit.

- ❌ BA → « Action de faire une bonne action »
- ✅ BA → « Bonne action » `[curé]`
- ❌ RI → « Action d'avoir ri »
- ✅ RI → « S'est esclaffé » `[curé]`

**Démonstratifs et déictiques redondants** — « Ce », « Cette »,
« Celui qui » en tête de syntagme sont omis.

- ❌ RAT → « Ce rongeur des égouts »
- ✅ RAT → « Habitant des égouts » `[curé]`

### 2.3 Accord en genre et nombre

Quand l'accord est exprimé (§1.5), il porte un signal grammatical fort
pour le résolveur : il restreint l'ensemble des candidats au seul mot
respectant les marques morphologiques. Voici cinq cas couvrant les
quatre configurations masc/fém × sing/plur, plus un cas frontière où
l'accord n'est volontairement pas exprimé.

| # | Cas | Mot | Définition | Signal porté |
|---|---|---|---|---|
| 1 | Masculin singulier | NU | « Dépouillé » `[curé]` | Participe masc. sg. : exclut NUE, NUS, NUES |
| 2 | Féminin singulier | MER | « Grande étendue d’eau » `[curé]` | Substantif + adjectif fém. sg. (« étendue » → exclut les candidats masculins ou pluriels) |
| 3 | Masculin pluriel | WC | « Lieux d'aisance » `[curé]` | Substantif masc. pl. (WC traité comme pluriel d'usage) |
| 4 | Féminin pluriel | US | « Habitudes anciennes » `[curé]` | Substantif + adjectif fém. pl. |
| 5 | Cas frontière — accord non exprimé | COQ | « Chante au matin » `[curé]` | Forme verbale conjuguée, aucun signal nominal d'accord (toléré par §1.5) |

Le cas frontière mérite un mot. La forme verbale conjuguée à la 3e
personne du singulier — abondante dans la base — ne porte aucun signal
nominal d'accord, et c'est intentionnel : le mot défini est le sujet
implicite, et le verbe seul suffit à le caractériser sémantiquement
sans contraindre sa morphologie. C'est une exception structurelle, pas
une faiblesse.

**Cas particulier** : pour les noms épicènes (mêmes formes au masc et
au fém : élève, enfant, artiste…) ou les mots dont le pluriel est
strictement identique au singulier (souris, croix…), l'accord exprimé
sur l'adjectif ou le participe reste un signal utile même si le mot
lui-même est invariable.

### 2.4 Casse et ponctuation

Checklist opérationnelle, applicable telle quelle comme règle de
validation automatique. Chaque règle est inviolable sauf mention
explicite.

| # | Règle | Exemple conforme |
|---|---|---|
| 1 | Initiale en majuscule | ✅ « Mâle de la basse-cour » |
| 2 | Reste en minuscule, sauf nom propre | ✅ « Mars grec » ; ✅ « Notre-Dame » |
| 3 | Pas de point final | ✅ « Bonne action »  · ❌ « Bonne action. » |
| 4 | Pas de guillemets autour de la définition | ❌ « "Carte forte" » |
| 5 | `?` final uniquement pour calembour (§4.5, hors-IA) | Réservé hand-curated |
| 6 | Parenthèses autorisées pour précision courte | ✅ « Note (de musique) » |
| 7 | Virgule autorisée, max 1 par définition | ✅ « Note avant ré » ; ❌ « Note, première de la gamme, avant ré » |
| 8 | Tiret pour mots composés | ✅ « Sud-est abrégé » `[curé]` ; ✅ « Notre-Seigneur » `[curé]` |
| 9 | Apostrophe typographique `’` (U+2019) en sortie finale. L'apostrophe droite `'` (U+0027) qui apparaîtrait dans une génération brute est normalisée en `’` par la pipeline de post-traitement, pas rejetée au filtrage. | ✅ « Forme d’avoir » `[curé]` ; ❌ « Forme d'avoir » |
| 10 | Pas de point d'exclamation, sauf interjection lexicalisée | ✅ HOP → « Allez ! » `[curé]` (interjection) ; ❌ « Carte forte ! » |
| 11 | Pas d'emoji, pas de caractère non imprimable | — |
| 12 | Pas de chiffre arabe en début de définition, sauf cas chiffré canonique | ✅ PI → « Nombre 3,14 » `[curé]` ; ❌ « 1 mâle de la basse-cour » |

Cette checklist est exhaustive pour la validation typographique. Tout
autre signe (chevrons, dièse, slash, étoile, soulignement, gras,
italique) est interdit en définition.

## 3. Conventions implicites — marqueurs canoniques

Les marqueurs canoniques sont ce qui fait sonner une définition « mots
fléchés FR » plutôt que « dictionnaire général ». Ils sont implicites :
le résolveur les reconnaît sans effort conscient, le LLM doit les
produire spontanément. Cette section catalogue les neuf familles de
marqueurs observées dans la base curée. Pour chaque famille : rôle,
patterns canoniques, exemples (curés prioritaires), pièges à éviter,
signal éventuel sur style et force.

### 3.1 Marqueurs de langue étrangère

**Rôle** — signaler que le sens visé n'est pas français mais provient
d'une langue identifiée. Sans le marqueur, la définition violerait §1.2
(langue exclusivement française).

**Patterns canoniques :**

- `Y anglais` / `Y anglaise` — Y est un substantif ou un adjectif français
- `Y italien` / `Y italienne` / `Y nippon` / `Y japonais(e)` / `Y égyptien(ne)` — gentilé adjectival
- `Y slave` / `Y grec(que)` / `Y occitan(e)` — langues sans accord variable
- `Y en X` — Y est la traduction française, X la langue (« Un en italien »)
- `Langue de X` / `Dialecte X` — pour les mots qui sont eux-mêmes des langues

**Exemples :**

- NO → « Refus anglais » `[curé]`
- UNO → « Un en italien » `[curé]`
- DA → « Oui slave » `[curé]`
- OC → « Oui occitan » `[curé]`
- MR → « Monsieur anglais » `[curé]`
- ST → « Rue abrégée en anglais » `[curé]`
- NÔ → « Théâtre japonais » `[curé]`
- NÔ → « Art dramatique nippon » `[curé]`
- WU → « Dialecte chinois » `[curé]`
- WU → « Langue de Shanghai » `[curé]`

**Pièges à éviter :**

- Laisser le mot étranger sans marqueur : ❌ COOL → « Cool »
- Confondre langue (`anglais`) et culture/origine (`britannique`,
  `anglo-saxon`) — ces dernières sont des marqueurs culturels (§4.6),
  pas linguistiques
- Multiplier les marqueurs : ❌ « Mot anglais d'origine anglo-saxonne »
- **Confondre `Y en X` (langue, marqueur postposé : « Un en italien »)
  et `En X, Y` (domaine, marqueur antéposé : « En biologie, cellule
  sexuelle »).** La position du marqueur tranche : postposé = langue
  (§3.1), antéposé = domaine (§3.8)

**Signal de style/force** — typique du style `définition_directe`
quand le marqueur est explicite ; force 1-2 (la traduction est immédiate
pour le résolveur).

### 3.2 Marqueurs d'abréviation et de sigle

**Rôle** — signaler que le mot est une forme tronquée ou un sigle dont
la définition développe l'origine. Cette famille couvre deux mécaniques
distinctes : la **troncature** d'un mot plein (AM = ante meridiem,
DOL = dollar) et le **sigle** dont les lettres sont les initiales d'un
syntagme (BO = Bulletin Officiel).

**Patterns canoniques :**

- `Y abrégé` / `Y abrégée` — Y est le mot complet
- `Y en abrégé`
- `Abréviation de Y` / `Abrév. de Y` *(forme verbeuse, plus rare)*
- `Abréviation X` — où X qualifie le domaine (« Abréviation médicale »,
  « Abréviation religieuse »)
- *Sigles* : développement direct des initiales sans marqueur explicite
  (BO → « Bulletin officiel »), conformément à la seconde exception du
  principe 1.1
- `Sigle de X` *(forme explicite, peu utilisée dans la base curée)*

**Exemples :**

- AM → « Matin abrégé » `[curé]`
- CI → « Ici abrégé » `[curé]`
- SE → « Sud-est abrégé » `[curé]`
- ST → « Saint abrégé » `[curé]`
- NO → « Numéro abrégé » `[curé]`
- DOL → « Dollar abrégé » `[curé]`
- DOL → « Devise américaine abrégée » `[curé]`
- USA → « Amérique abrégée » `[curé]`
- USD → « Billet vert abrégé » `[curé]`
- VS → « Versus abrégé » `[curé]`
- DR → « Abréviation médicale » `[curé]`
- ND → « Abréviation religieuse » `[curé]`
- BO → « Bulletin officiel » `[curé]` *(sigle, développement direct)*
- ONU → « Nations unies » `[curé]` *(sigle)*
- WC → « Toilettes » `[curé]` *(sigle d'origine anglaise, traduit)*

**Pièges à éviter :**

- Omettre `abrégé` quand la définition donne le mot plein : ❌ DOL →
  « Dollar » (manque le marqueur de troncature)
- Confondre sigle (initiales) et abréviation (troncature) dans la
  formulation : ❌ BO → « Bulletin officiel abrégé »
- Définir un sigle par paraphrase quand le développement direct
  suffirait : ❌ ONU → « Grande organisation internationale » (préférer
  « Nations unies »)
- Mettre le sigle en majuscules dans la définition (le mot défini est
  déjà en majuscules) : ❌ TV → « TV signifie télévision »

**Signal de style/force** — typique de `définition_directe`, force
1-3 selon la transparence du sigle ou de la troncature.

### 3.3 Marqueurs grammaticaux

**Rôle** — signaler que le mot est une particule grammaticale (pronom,
article, préposition, conjonction, possessif…) plutôt qu'un mot
lexical. Sans ce marqueur, une définition lexicale échoue à atteindre
le bon registre.

**Patterns canoniques :**

- `Forme de Y` — Y est un verbe à l'infinitif (avoir, être, aller)
- `Première / Deuxième / Troisième personne (de Y)`
- `Pronom X` — X = masculin, féminin, complément, sujet, réfléchi,
  indéfini, neutre, démonstratif
- `Article X` — X = masculin, féminin, indéfini, contracté, défini
- `Possessif X` — X = féminin, masculin
- `Préposition` / `Conjonction` / `Démonstratif` *(étiquettes seules,
  pour mots très courts)*
- `Conjonction X` / `Conjonction de X` — X = négative, opposition,
  choix
- `Particule X` — X = négative
- `Marque de X` — X = négation
- `Préfixe de X` / `X en composition`

**Exemples :**

- AI → « Forme d'avoir » `[curé]`
- VA → « Forme d'aller » `[curé]`
- ES → « Forme d'être » `[curé]`
- ES → « Deuxième personne d'être » `[curé]`
- JE → « Première personne » `[curé]`
- TU → « Deuxième personne » `[curé]`
- IL → « Pronom masculin » `[curé]`
- LE → « Article masculin » `[curé]`
- LA → « Article féminin » `[curé]`
- DU → « Article contracté » `[curé]`
- UN → « Article indéfini » `[curé]`
- ON → « Pronom indéfini » `[curé]`
- SE → « Pronom réfléchi » `[curé]`
- ME → « Pronom complément » `[curé]`
- CE → « Démonstratif » `[curé]`
- DE → « Préposition » `[curé]`
- ET → « Conjonction » `[curé]`
- OR → « Conjonction d'opposition » `[curé]`
- OU → « Conjonction de choix » `[curé]`
- NI → « Conjonction négative » `[curé]`
- NE → « Marque de négation » `[curé]`
- MA → « Possessif féminin » `[curé]`
- MI → « Demi en composition » `[curé]`
- BI → « Préfixe de dualité » `[curé]`

**Pièges à éviter :**

- Étiquette grammaticale seule trop générique : ❌ « Pronom » sans
  préciser masc/fém/complément (sauf cas où il n'y a aucune ambiguïté
  parmi les candidats de même longueur)
- Confondre `Forme de avoir` (signal grammatical : flexion d'avoir) et
  `Possède` (signal sémantique : synonyme) — les deux sont admis mais
  visent des styles différents
- Effacer le genre du possessif : ❌ « Possessif » sans `féminin` ou
  `masculin` (efface le signal d'accord §1.5)
- `Article` seul vs `Article masculin` : la mention du genre est
  presque toujours nécessaire pour discriminer LE / LA / UN / UNE

**Signal de style/force** — typique de `définition_directe`, force
1-2.

### 3.4 Marqueurs de désuétude et d'archaïsme

**Rôle** — signaler que le sens du mot est obsolète, désuet, archaïque,
ou que le mot lui-même appartient à un état ancien de la langue ou à
une culture historique. Ce marqueur évite la confusion avec un emploi
contemporain qui serait erroné.

**Patterns canoniques :**

- `Ancien Y` / `Ancienne Y` *(préposé)*
- `Y ancien` / `Y ancienne` *(postposé, plus fréquent dans la base)*
- `Y désuet` / `Y désuète`
- `Y archaïque`
- `Y antique` *(spécifiquement pour références à l'Antiquité)*
- `Symbole désuet de Y` / `Forme désuète de Y`

**Exemples :**

- DA → « Ancienne affirmation » `[curé]`
- UT → « Ancien do » `[curé]`
- UT → « Note ancienne » `[curé]`
- AZ → « Azote ancien » `[curé]`
- AZ → « Symbole désuet de l'azote » `[curé]`
- US → « Habitudes anciennes » `[curé]`
- UR → « Ville antique » `[curé]`
- VI → « Nombre antique » `[curé]`

**Pièges à éviter :**

- Confondre `ancien` temporel (le sens est désuet) et `ancien`
  relationnel (« ex-mari », rapport antérieur) : EX → « Ancien » `[curé]`
  utilise « ancien » au sens relationnel, ce n'est pas un marqueur
  d'archaïsme mais la définition du mot lui-même
- Doubler les marqueurs : ❌ « Ancien symbole désuet de l'azote »
- Utiliser `antique` pour un sens simplement vieilli (réserver à
  l'Antiquité historique)

**Signal de style/force** — typique de `définition_directe` ou
`culturel`, force 2-3 (le résolveur doit connaître la forme désuète).

### 3.5 Marqueurs de troncature et de modification graphique

**Rôle** — signaler que le mot résulte d'une opération sur la graphie
d'un autre mot : suppression de lettres, suppression d'un diacritique,
contraction, palindrome. Cette section documente la **formulation**
des marqueurs ; le style cryptique qui en fait sa mécanique de jeu est
traité en §4.8.

> **Distinction §3.5 / §4.8** — §3.5 dit *comment se formule* un
> marqueur de modification graphique, §4.8 dit *quel style* utilise
> cette mécanique comme son ressort principal. Une définition qui
> emploie un marqueur §3.5 relève typiquement du style §4.8, mais pas
> nécessairement (cf. BO → « Beau familier », qui mélange réduction
> graphique et registre).

**Patterns canoniques :**

- `Y sans Z` — Y est un mot, Z une lettre, un diacritique ou un suffixe
  (« sans p », « sans cédille », « sans accent », « sans s »)
- `Y tronqué` / `Y tronquée`
- `Y familier` — quand la familiarité résulte d'une contraction
  graphique (BO ← beau) et non d'un registre social
- `Y en raccourci` / `Forme courte de Y` *(plus rare)*
- `X palindrome` / `Palindrome X` — pour signaler un motif graphique
  particulier
- `Y avec Z en tête` / `Z + Y` *(addition graphique, plus rare)*

**Exemples :**

- LOU → « Loup sans p » `[curé]`
- LOU → « Canidé tronqué » `[curé]`
- CA → « Ça sans cédille » `[curé]`
- BO → « Beau familier » `[curé]` *(réduction graphique présentée
  comme registre familier)*
- ANNA → « Prénom palindrome » `[curé]`
- AMER → « Amers sans s » `[inventé]` *(pattern « Y sans Z »
  productif pour 4+ lettres)*

**Pièges à éviter :**

- Confondre **§3.5 marqueur** et **§4.8 style** : §3.5 cible la
  formulation, §4.8 cible la mécanique stylistique du jeu
- Auto-référence apparente : LOU → « Loup sans p » contient « loup » qui
  contient LOU comme préfixe — c'est exactement le cas de la première
  exception du principe 1.1, signalé en §4.8
- Multiplier les opérations : ❌ « Loup sans p ni accent ni majuscule »
- Utiliser `familier` pour un registre social quand il s'agit en
  réalité d'une réduction graphique (et inversement). Le contexte tranche :
  BO ← beau (graphique), OK = expression d'usage familier (registre)

**Signal de style/force** — pratiquement réservé au style `cryptique_morphologique`
(§4.8), force 2-4 selon la lisibilité de l'opération.

### 3.6 Marqueurs de registre

**Rôle** — signaler que le sens visé appartient à un registre
socio-stylistique non neutre : familier, populaire, argot, vulgaire,
soutenu, technique. Le marqueur de registre porte sur **comment c'est
dit** dans la langue d'usage, distinct du marqueur §3.9 qui porte sur
**ce qui est exprimé** (l'acte de langage).

> **Distinction §3.6 / §3.9** — §3.6 : socio-stylistique (« familier »,
> « populaire », « en argot »). §3.9 : valeur expressive ou acte de
> langage (« Cri de X », « Marque X », « Demande X », « Appel de X »).
> Une interjection peut cumuler les deux : HEIN → « Interjection
> familière » (§3.6 registre) + « Demande de répétition » (§3.9 acte).

**Patterns canoniques :**

- `Y familier` / `Y familière`
- `Y populaire`
- `En argot` / `Argot pour Y`
- `Vulgairement` / `Y vulgaire`
- `Y jeune` / `Mot des jeunes` *(registre générationnel)*
- `Y soutenu` *(registre opposé, rare en mots fléchés)*

**Exemples :**

- OK → « Accord familier » `[curé]`
- HEIN → « Interjection familière » `[curé]`
- HÉ → « Appel familier » `[curé]`
- YO → « Salut familier » `[curé]`
- YO → « Interjection jeune » `[curé]`
- PV → « Contravention familière » `[curé]`
- BO → « Beau familier » `[curé]` *(cas mixte : réduction graphique
  présentée comme registre, cf. §3.5)*

**Pièges à éviter :**

- Confondre `familier` (registre, §3.6) avec `familier` (réduction
  graphique, §3.5). Le contexte sémantique tranche : si la modification
  est graphique sur un mot plein, c'est §3.5 ; si l'emploi est un
  usage social, c'est §3.6
- Confondre `argot` (registre socio-marginal) avec `familier` (usage
  ordinaire détendu) — l'argot est plus fortement marqué
- Empiler les marqueurs : ❌ « Salut familier jeune populaire »

**Signal de style/force** — typique de `définition_directe` ou
`périphrase`, force 1-3.

### 3.7 Parenthèses de précision

**Rôle** — désambiguïser le sens visé d'un mot polysémique par une
précision courte entre parenthèses, sans alourdir la définition
principale. Le contenu parenthétique est minimal (1 à 3 mots).

> **Convention valide mais minoritaire.** La base curée
> `short_words_unified.csv` ne contient aucun exemple de parenthèses ;
> cette absence est révélatrice. La parenthèse de précision est plus
> présente dans les anciennes grilles Larousse que dans la presse
> fléchée FR contemporaine. L'adjectif postposé (cf. §3.3, §3.8) est
> préféré quand il suffit. Réserver les parenthèses aux cas où
> l'adjectif postposé serait ambigu ou alourdirait.

**Patterns canoniques :**

- `Y (Z)` — Z est une précision lexicale courte (catégorie, domaine)
- `Y (en Z)` / `Y (de Z)` — précision de domaine
- `Y (abrév.)` / `Y (sigle)` *(précision de nature)*

**Exemples :**

- ARES → « Mars (grec) » `[inventé]` *(désambiguïsation dieu/planète :
  l'adjectif postposé « grec » seul reste ambigu sans cadre déictique
  ; la parenthèse force la lecture « le Mars grec », c'est-à-dire le
  dieu)*
- ST → « Saint (abrév.) » `[inventé]` *(la parenthèse marque le
  statut de troncature de manière compacte, distincte d'une définition
  par le mot saint lui-même)*

**Pièges à éviter :**

- **Préférer la parenthèse à l'adjectif postposé quand celui-ci suffit
  *(piège principal de la section)*.** La convention base curée
  privilégie systématiquement l'adjectif postposé : « Note de musique »
  plutôt que « Note (de musique) », « Prénom biblique » plutôt que
  « Prénom (biblique) ». La parenthèse n'apporte une valeur ajoutée
  que quand l'adjectif postposé créerait une ambiguïté de lecture ou
  une lourdeur stylistique
- Parens trop longues : ❌ « Note (terme musical désignant une fréquence
  sonore particulière) » — la parenthèse doit rester en 1-3 mots
- Parens nichées : interdit
- Parens en début de définition : ❌ « (en musique) note avant ré »
- Doublonner avec un marqueur déjà présent : ❌ « Article féminin
  (féminin) »

**Signal de style/force** — neutre, la parenthèse est un outil
typographique transversal à tous les styles. Compatible avec
`définition_directe`, `périphrase`, `technique`.

### 3.8 Marqueurs de domaine technique

**Rôle** — signaler que le sens visé appartient à un domaine
spécialisé (biologie, physique, sport, musique, informatique, etc.).
Le marqueur de domaine restreint le champ d'interprétation et permet
de définir des termes techniques sans verbiage encyclopédique.

> Dans la base curée 2-3 lettres, le marqueur de domaine est rarement
> explicite : le domaine est porté par le **substantif catégoriel** de
> la définition (« Métal léger », « Gaz noble », « Unité de pression »).
> Les marqueurs explicites « En X » / « Au X » deviennent utiles à
> partir des 4+ lettres, où la polysémie augmente. Les exemples 4+
> lettres ci-dessous sont des `[inventé]` conformes au pattern.

**Patterns canoniques :**

- `En X, Y` — X = domaine, Y = définition (« En biologie, cellule
  sexuelle »). **Marqueur antéposé** : c'est ce qui distingue le
  domaine de la langue (§3.1, marqueur postposé)
- `Au X, Y` — X = sport ou activité (« Au tennis, balle gagnante »).
  Marqueur antéposé également
- `Domaine X` / `X spécialisé` *(rare, plus formel)*
- **Implicite via substantif catégoriel** — `Métal X` (chimie), `Gaz X`
  (chimie), `Unité de X` (physique), `Note X` (musique), `Pièce X`
  (jeu), `Carte X` (cartes)

**Exemples :**

- HZ → « Unité de fréquence » `[curé]` *(domaine implicite : physique)*
- ATM → « Unité de pression » `[curé]` *(physique)*
- KWH → « Unité d'énergie » `[curé]` *(physique)*
- LUX → « Unité d'éclairement » `[curé]` *(physique)*
- AL → « Métal léger » `[curé]` *(chimie)*
- AR → « Gaz noble » `[curé]` *(chimie)*
- HA → « Mesure agraire » `[curé]` *(agronomie)*
- BAR → « Pression au comptoir » `[curé]` *(physique + métonymie
  cf. §4.3)*
- ACE → « Au tennis, balle gagnante » `[inventé]` *(pattern explicite
  « Au X, Y »)*
- GAMETE → « En biologie, cellule sexuelle » `[inventé]`
- BIT → « En informatique, unité binaire » `[inventé]`

**Pièges à éviter :**

- **Confondre `En X, Y` (domaine, marqueur antéposé : « En biologie,
  cellule sexuelle ») et `Y en X` (langue, marqueur postposé : « Un en
  italien »).** La position du marqueur tranche : antéposé = domaine
  (§3.8), postposé = langue (§3.1). La nature de X (langue vs domaine)
  ne suffit pas, c'est la position qui est discriminante
- Marquer un domaine pour un mot polysémique alors que le sens visé
  est courant : ❌ COQ → « En aviculture, mâle de la basse-cour »
  (surinterprété)
- Confondre domaine et registre : ❌ « En argot, X » — l'argot relève
  du registre §3.6
- Utiliser un marqueur de domaine sans définition discriminante : ❌
  « En biologie, organisme » (trop générique)

**Signal de style/force** — typique du style `technique` (§4.9),
force 2-4 selon la spécialisation du domaine. Compatible aussi avec
`définition_directe` quand le terme est courant.

### 3.9 Marqueurs d'acte de langage et de valeur expressive

**Rôle** — signaler qu'un mot est un acte de langage (cri, appel,
exclamation, interjection, demande) ou une particule expressive
porteuse d'une valeur énonciative spécifique. Ce marqueur cible **ce
qui est exprimé**, non **comment c'est dit socialement** (qui relève
de §3.6).

> **Distinction §3.6 / §3.9** déjà signalée en §3.6 ; rappelée ici :
> §3.6 = registre socio-stylistique, §3.9 = valeur expressive ou acte
> de langage. Une même définition peut combiner les deux (« Interjection
> familière » → §3.9 « interjection » + §3.6 « familière »).

**Patterns canoniques :**

- `Cri de X` — X = douleur, surprise, étonnement, dégoût, arrêt, joie…
- `Cri d'X` *(forme élidée devant voyelle)*
- `Marque (de) X` — X = négation, mépris, surprise, possession,
  interrogation
- `Exprime X` / `Expression de X`
- `Demande X` — de répétition, d'aide, d'attention
- `Appel X` — bref, familier, marin, militaire
- `Interpellation`
- `Interjection X` — familière, marine, jeune, militaire
- `Syllabe de X` — pour les onomatopées (« Syllabe de rire »)
- `Interrogatif X` / `X interrogatif` — locatif, temporel, modal
  (cf. OÙ)

**Exemples :**

- AÏE → « Cri de douleur » `[curé]`
- AH → « Cri de surprise » `[curé]`
- OH → « Cri d'étonnement » `[curé]`
- HO → « Cri d'arrêt » `[curé]`
- FI → « Cri de dégoût » `[curé]`
- EH → « Appel bref » `[curé]`
- HÉ → « Appel familier » `[curé]` *(cumule §3.9 « appel » et §3.6
  « familier »)*
- HEIN → « Demande de répétition » `[curé]`
- FI → « Marque de mépris » `[curé]`
- NE → « Marque de négation » `[curé]` *(cumule §3.9 « marque » et
  §3.3 grammatical)*
- OÙ → « Interrogatif locatif » `[curé]`
- OÙ → « Marque le lieu » `[curé]`
- QUAND → « Interrogatif temporel » `[inventé]` *(pattern productif
  pour les interrogatifs 4+ lettres)*
- COMMENT → « Interrogatif modal » `[inventé]`
- HI → « Syllabe de rire » `[curé]`
- MU → « Meuglement bref » `[curé]` *(onomatopée animale)*
- HO → « Interjection marine » `[curé]`

**Pièges à éviter :**

- Étiquette `Interjection` seule (trop générique : OH/AH/EH/HÉ/HEIN
  partagent l'étiquette ; ajouter une qualification : familière,
  marine, de surprise…)
- Confondre `Cri` (acte vocal humain) et `Bruit` (son non vocal) — un
  cri suppose un locuteur, un bruit non
- Confondre §3.9 (ce qui est exprimé) et §3.6 (comment c'est dit) :
  « Salut familier » → §3.6 (registre) plutôt que §3.9 (acte de
  salutation) si le marqueur dominant est « familier »
- Surinterpréter le marqueur émotionnel : ❌ AH → « Cri d'extase
  amoureuse profonde » (la précision sémantique du marqueur doit rester
  reconnaissable comme valeur lexicale standard)

**Signal de style/force** — typique de `définition_directe` ou
`périphrase`, force 1-3. Pour les onomatopées et interjections,
force souvent 1 (transparence forte).

## 4. Taxonomie des styles

### Introduction

Le **style** est la *mécanique rhétorique* d'une définition — ce
qu'elle fait pour amener le résolveur au mot. Il se distingue
strictement des deux autres axes de classification :

```
  • STYLE      — mécanique rhétorique         (9 valeurs, §4)
  • FORCE      — difficulté de résolution     (1 à 5, partie 2)
  • CATÉGORIE  — champ sémantique du mot      (partie 2)
```

Les trois axes sont **orthogonaux** : un mot de catégorie `animals`
peut être défini en style `définition_directe` (force 1) ou en style
`métonymie` (force 3) ou en style `cryptique_morphologique` (force 4).
La force et la catégorie ne déterminent pas le style ; le style ne
détermine pas la force, même si certaines combinaisons sont plus
fréquentes (cf. la colonne « Force typique » du tableau récapitulatif).

**Polystylisme.** Un même mot admet plusieurs définitions de styles
différents. La base curée illustre ce principe : KO appartient
simultanément à plusieurs sens définis par marqueurs distincts
(« Hors combat » → définition_directe ; « Kilooctet » → définition_directe
technique). Pour un mot donné, le style retenu pour une définition
particulière est *une* caractérisation parmi d'autres possibles, pas
une étiquette intrinsèque du mot.

Les neuf styles documentés ci-dessous épuisent la typologie utilisée
pour le projet. L'étiquetage d'une définition se fait sur la
*mécanique dominante* : quand deux mécaniques sont combinées (cf. BAR
→ « Pression au comptoir », qui mêle technique et métonymie), on
retient celle qui porte le ressort principal de l'indice.

### Encadré frontière — Comment distinguer fonction_rôle, métonymie, cryptique et calembour ?

Les quatre styles ci-dessous constituent la zone la plus glissante de
la taxonomie. Le tableau ci-dessous condense leur critère
discriminant ; les exemples frontières ci-après illustrent la limite.

| Style | Marqueur visible | Mécanisme |
|---|---|---|
| `fonction_rôle` | Verbe d'action, syntagme désignant un usage ou un emploi (« Porte X », « Sert à Y », « Fait Z ») | Le mot **est ce qui accomplit l'action** ou occupe la fonction décrite |
| `métonymie` | Substantif désignant un élément contigu (lieu, partie, contenant, attribut emblématique) — sans verbe d'action | Substitution par **contiguïté sémantique** (contenant → contenu, lieu → activité, partie → tout) |
| `cryptique` | Phrase à double lecture **sans `?` final**, juxtaposition ou tournure orientante | Double sens implicite : le résolveur doit percevoir **simultanément** les deux lectures |
| `calembour` | **`?` final** explicite | Jeu de mots **signalé** : le `?` met le résolveur en alerte sur une lecture détournée |

**Exemples frontières :**

> COU → « Porte la tête » `[curé]`
> *fonction_rôle* (le COU porte effectivement la tête : action exprimée
> par un verbe). Si on disait COU → « Sous la tête », ce serait
> *métonymie* (rapport spatial sans verbe d'action).

> NO → « Côté breton » `[curé]`
> *métonymie* (le point cardinal NO est désigné par la région
> géographique qu'il pointe : direction → lieu géographique pointé).
> Si on disait NO → « Indique la Bretagne », ce serait *fonction_rôle*
> (verbe d'action « indique »).

> AVOCAT → « Robe noire ou peau verte » `[ancre validée]`
> *cryptique* (juxtaposition de deux sens — l'avocat juriste et le
> fruit — sans `?` signalant le jeu de mots). Si on disait AVOCAT →
> « Robe noire ou peau verte ? », ce serait *calembour* (signalé par
> le `?`).

> VENT → « Met les voiles ? » `[ancre validée]`
> *calembour* (jeu de mots signalé par `?` : VENT comme phénomène
> météo qui fait avancer le voilier, et « mettre les voiles » comme
> expression idiomatique signifiant partir). Sans le `?` final, la
> définition tomberait en *cryptique*.

### Tableau récapitulatif des 9 styles

| Style | Marqueur typique | Force typique | Exemple court (base curée ou ancre) |
|---|---|---:|---|
| `définition_directe` | Synonyme, paraphrase courte, étiquette grammaticale, traduction marquée | 1-2 | RAT → « Rongeur » `[curé]` |
| `périphrase` | Substantif catégoriel + qualificatif emblématique | 2-3 | COQ → « Mâle de la basse-cour » `[curé]` |
| `métonymie` | Désignation par lieu, partie, attribut contigu | 2-4 | NO → « Côté breton » `[curé]` |
| `fonction_rôle` | Verbe d'action ou syntagme d'usage | 2-4 | COU → « Porte la tête » `[curé]` |
| `calembour` *(hand-curated only)* | `?` final explicite — **hors-IA, §1 note de conformité** | 4-5 | VENT → « Met les voiles ? » `[ancre validée]` |
| `culturel` | Référence onomastique, patrimoniale ou historique | 2-4 | NOÉ → « Rescapé du déluge » `[curé]` |
| `cryptique` | Double sens implicite, **pas de `?`** | 3-5 | AVOCAT → « Robe noire ou peau verte » `[ancre validée]` |
| `cryptique_morphologique` | « Y sans Z », palindrome, troncature §3.5 | 2-4 | LOU → « Loup sans p » `[curé]` |
| `technique` | Marqueur de domaine antéposé §3.8, ou substantif catégoriel | 2-4 | HZ → « Unité de fréquence » `[curé]` |

### 4.1 définition_directe

**Définition** — la définition vise directement le sens premier du mot
par un synonyme, une paraphrase courte, une étiquette grammaticale ou
une traduction marquée. C'est le style par défaut, le plus fréquent du
corpus court, et celui qui présente le meilleur ratio
brièveté/discrimination.

**Marqueurs identifiables :**

- Synonyme nominal ou verbal isolé (« Rongeur », « Année »)
- Étiquette grammaticale (« Préposition », « Article masculin » — cf.
  §3.3)
- Traduction marquée par langue (« Refus anglais » — cf. §3.1)
- Développement direct de sigle (« Bulletin officiel » — cf. §3.2)
- Paraphrase courte non périphrastique (la paraphrase ne sélectionne
  pas une caractéristique emblématique mais reformule directement le
  sens)

**Exemples :**

- AN → « Année » `[curé]`
- NO → « Refus anglais » `[curé]`
- RAT → « Rongeur » `[curé]`
- EURO → « Monnaie européenne » `[curé]`
- TOUCHER → « Sens de la peau » `[curé]` *(7 lettres, paraphrase
  catégorielle)*

**Pièges à éviter :**

- Paraphrase circulaire ou tautologique : ❌ RAT → « Rat des champs »
  (réintroduit le mot)
- Définition trop générique qui rate la discrimination forte §1.7 : ❌
  RAT → « Animal » (s'applique à des centaines de candidats)
- Définition encyclopédique débordant §1.3 : ❌ RAT → « Petit
  mammifère rongeur de la famille des muridés à longue queue »
- Glisser vers périphrase en cherchant un trait emblématique alors
  qu'un synonyme suffit : la définition_directe reste neutre, sans
  charge stylistique

**Compatibilité avec la force** — typiquement 1-2. Peut monter à 3 si
le synonyme est rare ou savant (par exemple un terme scientifique
remplaçant le mot courant). Rarement au-delà.

### 4.2 périphrase

**Définition** — désigne le mot par une caractéristique typique ou un
attribut emblématique, sans donner d'équivalent lexical direct. La
périphrase capte ce qui *fait la particularité* du référent parmi les
autres membres de sa catégorie.

**Marqueurs identifiables :**

- Substantif catégoriel + qualificatif discriminant (« Mâle de la
  basse-cour » : « Mâle » catégorie sexuelle, « basse-cour »
  localisation discriminante)
- Structure « X de Y » où X situe la catégorie et Y le trait distinctif
- Trait physique, comportemental, fonctionnel ou symbolique typique du
  référent

**Exemples :**

- OR → « Jaune précieux » `[curé]` *(2 lettres : couleur + valeur)*
- COQ → « Mâle de la basse-cour » `[curé]`
- ÂNE → « Porteur à grandes oreilles » `[curé]`
- LUNE → « Astre nocturne » `[curé]`
- LOTO → « Tirage à numéros » `[curé]`

**Pièges à éviter :**

- Glisser vers définition_directe en perdant le qualificatif
  emblématique : ❌ COQ → « Mâle » (catégorie sans discrimination)
- Caractéristique non discriminante : ❌ COQ → « Oiseau de la
  basse-cour » (s'applique aussi à OIE, CANE, DINDE…)
- Caractéristique trop subjective ou inattendue : ❌ LUNE → « Astre
  inspirateur » (vague et orienté)
- Confondre périphrase (caractéristique typique du référent) avec
  métonymie (élément contigu non typique)

**Compatibilité avec la force** — typiquement 2-3. La périphrase
demande au résolveur d'identifier la caractéristique comme
emblématique du mot ; elle est plus exigeante qu'une définition
directe mais reste accessible.

### 4.3 métonymie

**Définition** — désigne le mot par substitution de contiguïté
sémantique : contenant pour contenu, lieu pour activité, partie pour
tout, attribut emblématique pour objet. La métonymie *pointe* le mot
par un voisin, elle ne le décrit pas.

**Marqueurs identifiables :**

- Désignation par lieu géographique ou repère spatial (« Côté X »,
  « Vers Y », « Au Z »)
- Substantif désignant un élément contigu **sans verbe d'action** (le
  verbe d'action ferait basculer en fonction_rôle, cf. encadré
  frontière §4)
- Substitution capitale ↔ pays, attribut ↔ porteur, contenant ↔
  contenu

**Exemples :**

- NO → « Côté breton » `[curé]` *(direction → lieu pointé)*
- SE → « Côté provençal » `[curé]` *(direction → lieu pointé)*
- COU → « Entre tête et tronc » `[curé]` *(partie → position
  spatiale)*
- BAR → « Pression au comptoir » `[curé]` *(unité de pression
  désignée par le lieu de consommation)*
- IRAN → « État de Téhéran » `[curé]` *(capitale → pays)*

**Pièges à éviter :**

- Confondre métonymie (substitution par voisin) avec périphrase
  (caractérisation par trait emblématique) : « Capitale nordique » →
  OSLO est périphrase (catégorie + qualificatif), « Capitale de la
  Norvège » → OSLO serait métonymie (pays → capitale)
- Confondre métonymie (substantif contigu, sans verbe d'action) avec
  fonction_rôle (verbe d'action) — la présence d'un verbe d'action
  fait basculer le style (cf. encadré frontière §4)
- Métonymie non reconnaissable comme contiguïté sémantique standard :
  ❌ « Forme de l'aiguille » → COU (rapport arbitraire)
- Substitution trop indirecte (plus de deux degrés de contiguïté)

**Compatibilité avec la force** — typiquement 2-4. La métonymie demande
au résolveur d'identifier le rapport de contiguïté, ce qui élève la
difficulté quand le rapport n'est pas évident.

### 4.4 fonction_rôle

**Définition** — désigne le mot par son usage, son rôle social, sa
fonction caractéristique ou l'action qu'il accomplit. La définition
contient typiquement un **verbe d'action** ou un syntagme désignant
l'emploi du référent.

**Marqueurs identifiables :**

- Verbe d'action conjugué à la 3e personne sans sujet exprimé
  (« Porte X », « Chante au matin », « Sert à Y »)
- Syntagme prépositionnel à valeur d'usage (« à mordre », « à
  ménager »)
- Personnification minimale (le mot défini est sujet implicite de
  l'action)

**Exemples :**

- COQ → « Chante au matin » `[curé]` *(action emblématique)*
- COU → « Porte la tête » `[curé]` *(fonction anatomique)*
- DOS → « Partie à ménager » `[curé]` *(syntagme d'usage)*
- BRAS → « Porte la main » `[curé]` *(fonction anatomique)*
- BERGER → « Conduit le troupeau » `[inventé]` *(6 lettres, fonction
  professionnelle)*

**Pièges à éviter :**

- Glisser vers métonymie en supprimant le verbe d'action : « Porte la
  tête » (fonction_rôle) vs « Sous la tête » (métonymie spatiale)
- Substituer le verbe par un substantif d'action lourd : ❌ COU →
  « Action de porter la tête » (viole §2.2 sur l'omission de « Action
  de »)
- Surinterpréter le rôle en description anatomique ou encyclopédique :
  ❌ COU → « Élément reliant la tête au tronc, contenant vertèbres et
  trachée »
- Confondre fonction_rôle (action du référent) avec définition_directe
  (équivalence sémantique) — la fonction_rôle implique toujours un
  faire ou un servir, pas un être

**Compatibilité avec la force** — typiquement 2-4. Le verbe d'action
oriente nettement le résolveur ; la difficulté dépend de la
spécificité de l'action.

### 4.5 calembour

> **Style hors périmètre de la génération automatique v1.** Réservé à
> l'enrichissement manuel de la base curée. Le LLM est explicitement
> entraîné à NE PAS produire ce style. Toute définition contenant `?`
> final en sortie du modèle est rejetée au filtrage (cf. §1 note de
> conformité, §2.4 règle 5). Cette sous-section reste documentée pour
> cadrer l'enrichissement manuel.

**Définition** — jeu de mots reposant sur un double sens explicitement
signalé par le marqueur `?` final. Le calembour exploite l'homonymie,
la polysémie ou un sens idiomatique détourné, et le `?` met le
résolveur en alerte sur la lecture biaisée.

**Marqueurs identifiables :**

- `?` final obligatoire (c'est le marqueur définitionnel du style)
- Tournure invitant explicitement à une double lecture
- Expression idiomatique mobilisée pour son sens littéral détourné, en
  contraste avec le sens propre du mot solution

**Exemples :**

- VENT → « Met les voiles ? » `[ancre validée]`
- GRENADE → « Fruit qui explose ? » `[ancre validée]`

**Pièges à éviter (pour l'enrichissement manuel) :**

- Définition cryptique faussement balisée par `?` (sans véritable jeu
  de mots) — le `?` doit signaler un double sens réel
- Marqueur `?` placé ailleurs qu'en fin (ex. milieu de phrase) :
  interdit
- Calembour trop obscur où le résolveur ne perçoit qu'un seul sens
- Calembour reposant sur une expression idiomatique trop régionale ou
  trop datée pour le corpus partagé
- Mélanger plusieurs jeux de mots dans une même définition

**Compatibilité avec la force** — typiquement 4-5. Les calembours sont
par construction parmi les définitions les plus difficiles, c'est leur
fonction stylistique : ralentir la résolution par une fausse piste
explicitement annoncée.

### 4.6 culturel

**Définition** — désigne le mot par une référence à une œuvre, un
personnage, un lieu, un fait historique, une religion ou un patrimoine
culturel reconnaissable. Le résolveur doit identifier la référence, ce
qui présuppose une connaissance partagée — typiquement le socle
culturel français contemporain (mythologie gréco-romaine, Bible, grands
auteurs, géographie de la France et de l'Europe).

**Marqueurs identifiables :**

- Nom propre dans la définition (personnage, lieu, œuvre)
- Adjectif culturel ou patrimonial (« biblique », « mythologique »,
  « grec », « égyptien », « nordique »)
- Renvoi à un événement historique connu (« déluge », « Olympe »,
  « Réforme »)
- Référence onomastique à un titre d'œuvre ou une figure célèbre

**Exemples :**

- RA → « Dieu solaire » `[curé]` *(mythologie égyptienne)*
- NOÉ → « Rescapé du déluge » `[curé]` *(référence biblique)*
- THOR → « Dieu au marteau » `[curé]` *(mythologie nordique)*
- ZEUS → « Maître des dieux » `[curé]` *(mythologie grecque)*
- VERNE → « Auteur de 20 000 lieues » `[inventé]` *(5 lettres,
  référence à *Vingt mille lieues sous les mers*)*

**Pièges à éviter :**

- Référence trop obscure pour le corpus partagé : ❌ APOLLONIUS →
  « Philosophe de Tyane » (hors du socle culturel grand public)
- Confondre culturel (référence onomastique) avec définition_directe
  augmentée d'un marqueur de langue §3.1 : « Refus anglais » → NO est
  §3.1 + définition_directe, pas culturel
- Référence anachronique ou hors corpus français contemporain
- Empiler plusieurs références culturelles : ❌ « Asgardien célèbre
  de Marvel inspiré de la mythologie nordique »
- Donner la définition lexicographique du nom propre sans en faire la
  référence active : ❌ NOÉ → « Personnage biblique » (étiquette
  catégorielle plate)

**Compatibilité avec la force** — typiquement 2-4 selon la notoriété
de la référence. NOÉ ou ZEUS restent en 2-3 (très connus), un
personnage secondaire d'une œuvre moins canonique monterait à 4-5.

### 4.7 cryptique

**Définition** — définit le mot par un double sens *implicite* : le
résolveur doit percevoir simultanément deux lectures sans qu'aucun
marqueur typographique ne le signale. À la différence du calembour
(§4.5, hors-IA), il n'y a **pas de `?`** final. La définition
fonctionne par juxtaposition, tournure orientante ou polysémie
exploitée en filigrane.

**Marqueurs identifiables :**

- Juxtaposition de deux sens contrastés (« X ou Y » où X et Y
  appartiennent à des champs sémantiques disjoints qui se recoupent
  sur le mot)
- Tournure susceptible de deux lectures concurrentes
- Polysémie exploitée sans étiquetage explicite
- Absence stricte de `?` final

**Exemples :**

- AVOCAT → « Robe noire ou peau verte » `[ancre validée]` *(juriste
  vs fruit)*
- SOURIS → « Petite bête sur un bureau » `[ancre validée]` *(animal
  vs périphérique)*
- PIQUE → « Tête à l'épée » `[ancre validée]` *(arme vs couleur de
  carte)*
- PASTIS → « Trouble qui éclaircit » `[ancre validée]` *(boisson
  vs embarras)*
- POIRE → « Bonne à mordre » `[ancre validée]` *(fruit vs personne
  naïve)*

**Pièges à éviter :**

- Ajouter `?` final : la définition bascule en calembour §4.5
  (hors-IA), rejetée au filtrage
- Double sens trop opaque où le résolveur ne perçoit qu'une seule
  lecture
- Confondre cryptique (deux lectures simultanées) avec périphrase
  (caractérisation univoque)
- Imposer une lecture orientée qui ferme l'autre : ❌ AVOCAT → « Robe
  noire du juriste, comme la peau verte du fruit » (la juxtaposition
  perd sa puissance quand le rapport est explicité)
- Cryptique reposant sur une polysémie marginale plutôt que sur un
  homonyme ou un sens partagé fort

**Compatibilité avec la force** — typiquement 3-5. Le cryptique est
parmi les styles les plus difficiles parce qu'il ne signale pas son
mécanisme ; le résolveur doit *deviner* qu'il y a double sens.

### 4.8 cryptique_morphologique

**Définition** — définit le mot par une opération sur la graphie d'un
autre mot : suppression de lettres, suppression de diacritique,
contraction, palindrome, anagramme, troncature. L'opération
morphologique est la mécanique principale de l'indice, signalée par
un marqueur de §3.5.

> **Ce style constitue l'unique exception documentée au principe 1.1
> de non-auto-référence** (la seconde exception, sigles et acronymes,
> relève de §3.2). Quand LOU est défini par « Loup sans p », le mot
> « loup » contient LOU comme préfixe — c'est précisément le ressort
> du jeu.

**Marqueurs identifiables :**

- `Y sans Z` — suppression de lettre, de diacritique, de suffixe
  (« Loup sans p », « Ça sans cédille »)
- `Y tronqué/tronquée` — suppression de fin
- `Y avec Z en tête` / `Z + Y` — addition graphique
- `X palindrome` / `Palindrome X` — motif graphique réversible
- `Anagramme de Y` — réarrangement des lettres
- Tous les patterns recensés en §3.5

**Exemples :**

- CA → « Ça sans cédille » `[curé]`
- LOU → « Loup sans p » `[curé]`
- ANNA → « Prénom palindrome » `[curé]`
- TIGE → « Tigre sans r » `[inventé]` *(4 lettres, le pattern « Y sans
  Z » s'applique à n'importe quelle lettre interne, pas seulement la
  suppression du s final)*
- KAYAK → « Embarcation palindrome » `[inventé]` *(5 lettres,
  palindrome attesté en français)*

**Pièges à éviter :**

- Confondre §4.8 (style à mécanique morphologique) avec §3.5
  (marqueurs de troncature). §3.5 documente la *formulation* du
  marqueur ; §4.8 documente le *style* qui en fait son ressort
  principal. Une définition qui emploie un marqueur §3.5 relève
  presque toujours du style §4.8, mais le cas inverse n'est pas
  garanti (cf. BO → « Beau familier », qui mélange réduction graphique
  et registre, classé périphrase ou définition_directe)
- Opération non transparente : ❌ LOU → « Loup raccourci » (le marqueur
  reste vague — la lettre supprimée doit être identifiable)
- Multiplier les opérations sur un même mot : ❌ « Loup sans p ni
  accent ni majuscule »
- Donner le résultat sans signaler l'opération : ❌ ANNA → « Prénom
  réversible » (vague — préférer « palindrome » qui est lexicalisé)
- Auto-référence apparente non maîtrisée : l'exception §1.1 ne vaut
  que si l'opération morphologique est explicitement la mécanique de
  l'indice

**Compatibilité avec la force** — typiquement 2-4. Le palindrome est
plus accessible (2-3), la suppression de lettre sur un mot peu connu
ou la troncature multiple monte à 4.

### 4.9 technique

**Définition** — désigne le mot par référence à un domaine spécialisé
(sciences, sport, musique, informatique, médecine…) au moyen d'un
marqueur de domaine antéposé (cf. §3.8) ou via un substantif
catégoriel à fonction de domaine implicite. Le résolveur doit
reconnaître le terme dans sa spécialité, distinct de tout usage
courant.

**Marqueurs identifiables :**

- Marqueur de domaine antéposé : `En X, Y` / `Au X, Y` (cf. §3.8) —
  c'est la forme la plus explicite
- Substantif catégoriel à fonction de domaine implicite : `Métal X`
  (chimie), `Gaz X` (chimie), `Unité de X` (physique), `Note X`
  (musique), `Carte X` (jeux), `Pièce X` (jeux ou anatomie)
- Vocabulaire spécialisé univoque dans son domaine

**Exemples :**

- HZ → « Unité de fréquence » `[curé]` *(physique, domaine implicite
  par substantif catégoriel)*
- AR → « Gaz noble » `[curé]` *(chimie, domaine implicite)*
- ATM → « Unité de pression » `[curé]` *(physique)*
- LUX → « Unité d'éclairement » `[curé]` *(physique)*
- MITOSE → « En biologie, division cellulaire » `[inventé]`
  *(6 lettres, marqueur de domaine antéposé explicite §3.8)*

**Pièges à éviter :**

- Surmarquer le domaine pour un mot polysémique alors que le sens
  visé est courant : ❌ COQ → « En aviculture, mâle de la basse-cour »
  (suringénierie)
- Confondre marqueur de domaine (§3.8, antéposé) et marqueur de
  langue (§3.1, postposé) — la position tranche (cf. §3.8 piège
  principal)
- Définition technique trop générique : ❌ « En biologie, organisme »
  (ne discrimine pas, viole §1.7)
- Empiler les marqueurs de domaine : ❌ « En biologie cellulaire, en
  cytologie, division »
- Utiliser un terme technique sans s'assurer que le résolveur partage
  le vocabulaire de spécialité — la « technique grand public » (HZ,
  ATM, GAZ NOBLE) reste accessible, la « technique experte » devient
  rapidement injouable

**Compatibilité avec la force** — typiquement 2-4 selon le degré de
spécialisation. Les unités du système international et les éléments
chimiques courants restent en 2-3 ; un terme de biologie cellulaire
ou de droit notarial monte à 4-5.

# Partie 2 — Force, anti-patterns, cas particuliers, format

La partie 1 a posé les principes (§1), la structure (§2), les
marqueurs (§3) et la taxonomie stylistique (§4). La partie 2 outille
la production opérationnelle : calibration de la difficulté (§5),
anti-patterns à filtrer (§6), cas particuliers et taxonomies POS et
catégories (§7), format de sortie et pipeline (§8).

## 5. Échelle de force (1 à 5)

### 5.1 Principes de calibration

**Définition opérationnelle.** La **force** d'une définition est sa
*difficulté de résolution* pour le joueur. Elle s'exprime sur une
échelle entière de 1 (très facile) à 5 (expert). Elle est attachée à
une définition donnée pour un mot donné, pas au mot seul ni au style
seul.

**Force = troisième axe orthogonal.** Comme rappelé en préambule et
en §4, la force est indépendante du style et de la catégorie
sémantique. Un même mot, défini en plusieurs styles, peut couvrir
plusieurs niveaux de force ; un même style admet des productions à
plusieurs forces selon la finesse de l'indice rédigé. La calibration
croisée mot × style × force est illustrée en §5.7.

**Six critères concourent à élever la force :**

1. **Rareté du vocabulaire** employé dans la définition (mot savant,
   terme désuet, néologisme spécialisé)
2. **Complexité du mécanisme rhétorique** (les styles `définition_directe`
   et `périphrase` sont en moyenne plus bas que `métonymie`, `cryptique`
   ou `cryptique_morphologique`)
3. **Niveau d'inférence** demandé au résolveur — de la transparence
   immédiate à la chaîne d'inférences à plusieurs étapes
4. **Type de connaissance présupposée** : générale (force 1-2),
   culturelle grand public (force 2-3), spécialisée ou culturelle
   pointue (force 4-5)
5. **Présence d'un double sens** ou d'un jeu de mots (typiquement les
   styles `cryptique` §4.7 et `calembour` §4.5)
6. **Dépendance aux croisements** en grille : une définition à
   discrimination forte (§1.7) est plus haute en force qu'une
   définition de remplissage tolérée par discrimination par
   croisement

**Critère de cohérence interne.** Pour un même mot et un même style,
la force varie selon la finesse de l'indice. COQ → « Mâle de la
basse-cour » (curé, force 2) et COQ → « Crête rouge à l'aube »
(inventé, force 3) sont tous deux des périphrases ; le second monte
parce qu'il introduit un détour métonymique implicite (la crête comme
attribut, l'aube comme moment de chant).

**Référent éditorial.** Les forces 1-3 sont calibrées sur la base
curée `short_words_unified.csv`, qui s'étale presque entièrement sur
cette plage. Les forces 4-5 nécessitent des ancres complémentaires
(largement inventées ou hors corpus court) ; les 5 ancres validées
du style `cryptique` (§4.7) constituent l'épine dorsale du niveau 5.

### 5.2 Force 1 — très facile

**Critères opérationnels.** Définition à transparence immédiate.
Synonyme direct, étiquette grammaticale, traduction marquée ou
développement de sigle dont l'origine est immédiatement reconnue par
n'importe quel locuteur français adulte.

**Profil joueur cible.** Joueur occasionnel, débutant. La grille type
*20 Minutes* ou *Le Parisien* du dimanche relève largement de ce
niveau.

**Styles typiquement associés.** `définition_directe` principalement.
Quelques `périphrase` triviales (caractéristique évidente, non
discriminante au-delà du candidat évident).

**Exemples ancres :**

- AN → « Année » `[curé]`
- TV → « Télévision » `[curé]`
- LE → « Article masculin » `[curé]`
- KM → « Kilomètre » `[curé]`
- RAT → « Rongeur » `[curé]`
- USA → « États-Unis » `[curé]`
- EURO → « Monnaie européenne » `[curé]`
- TOUCHER → « Sens de la peau » `[curé]`

### 5.3 Force 2 — facile

**Critères opérationnels.** Définition à transparence rapide mais
demandant une étape d'identification (caractéristique emblématique,
périphrase courte, métonymie immédiate, action canonique du référent).
Le résolveur identifie le mot en quelques secondes sans recours à la
grille.

**Profil joueur cible.** Amateur régulier. Niveau dominant des grilles
quotidiennes de la presse FR contemporaine.

**Styles typiquement associés.** `définition_directe` enrichi d'un
qualificatif, `périphrase` à trait emblématique reconnu,
`fonction_rôle` à verbe d'action canonique, `métonymie` directe.

**Exemples ancres :**

- BU → « S'est désaltéré » `[curé]` *(participe passé, fonction_rôle
  rétrospective)*
- HÉ → « Appel familier » `[curé]` *(interjection avec registre marqué)*
- COQ → « Mâle de la basse-cour » `[curé]` *(périphrase à trait
  emblématique)*
- ÂNE → « Bête têtue » `[curé]` *(périphrase comportementale)*
- NEZ → « Milieu du visage » `[curé]` *(métonymie spatiale)*
- LUNE → « Astre nocturne » `[curé]` *(périphrase temporelle)*
- HEIN → « Demande de répétition » `[curé]` *(acte de langage §3.9)*
- OSLO → « Capitale nordique » `[curé]` *(périphrase géographique)*

### 5.4 Force 3 — moyen

**Critères opérationnels.** Définition demandant une inférence non
triviale ou une connaissance culturelle grand public. Le résolveur a
besoin de quelques secondes pour faire le lien ; il peut s'appuyer
sur un croisement déjà résolu pour confirmer. Sens secondaire,
archaïsme balisé, référence mythologique ou biblique très connue,
réduction graphique reconnaissable.

**Profil joueur cible.** Amateur confirmé. Niveau dominant des grilles
hebdomadaires et des grilles de niveau « intermédiaire » dans la
presse spécialisée (FX, Sport Cérébral).

**Styles typiquement associés.** `périphrase` fine, `métonymie`
nécessitant identification du voisin, `culturel` grand public,
`cryptique_morphologique` à opération transparente, `définition_directe`
avec marqueur d'archaïsme.

**Exemples ancres :**

- BO → « Beau familier » `[curé]` *(réduction graphique, cf. §3.5)*
- UT → « Ancien do » `[curé]` *(archaïsme musical balisé §3.4)*
- KA → « Âme égyptienne » `[curé]` *(culturel, civilisation
  identifiée)*
- NOÉ → « Rescapé du déluge » `[curé]` *(culturel biblique grand
  public)*
- ARES → « Mars grec » `[curé]` *(culturel + traduction par
  équivalence mythologique)*
- ZEUS → « Maître des dieux » `[curé]` *(périphrase culturelle)*
- ISIS → « Mère d'Horus » `[curé]` *(culturel égyptien, lien
  généalogique requis)*
- IRAN → « État de Téhéran » `[curé]` *(métonymie capitale → pays)*

### 5.5 Force 4 — difficile

**Critères opérationnels.** Définition demandant une inférence longue,
un détour rhétorique non balisé, ou une connaissance culturelle non
grand public. Le résolveur a presque toujours besoin d'un ou deux
croisements résolus pour confirmer, ou d'un temps de réflexion
notable. Sont caractéristiques de ce niveau : métonymie à rapport de
contiguïté indirect, référence culturelle de seconde couche, terme
technique reconnaissable mais hors quotidien, cryptique léger (un
seul sens latent à débusquer), cryptique_morphologique à opération
non triviale.

**Profil joueur cible.** Cruciverbiste de niveau intermédiaire élevé,
amateur expert. Niveau dominant des grilles « expert » ou « niveau 3 »
de FX et Larousse, et des grilles cryptiques abordables.

**Styles typiquement associés.** `métonymie` à substitution indirecte,
`culturel` intermédiaire, `cryptique` léger (§4.7), `cryptique_morphologique`
complexe (§4.8), `technique` non grand public (§4.9).

**Exemples ancres :**

- AZ → « Symbole désuet de l'azote » `[curé]` *(archaïsme chimique
  combiné)*
- LOU → « Loup sans p » `[curé]` *(cryptique_morphologique
  transparent)*
- INO → « Nourrice de Dionysos » `[curé]` *(culturel mythologique
  pointu)*
- THOR → « Asgardien célèbre » `[curé]` *(culturel à référence
  pop-mythologique)*
- ANNA → « Prénom palindrome » `[curé]` *(cryptique_morphologique
  notion lexicalisée)*
- STADE → « Voisin des vestiaires » `[ancre validée]` *(métonymie
  à contiguïté spatiale)*
- TIBIA → « Os de la jambe avant » `[ancre validée]` *(technique
  anatomique reconnaissable)*
- PINCE → « Outil de crabe » `[ancre validée]` *(cryptique léger,
  un seul détour)*

### 5.6 Force 5 — expert

**Critères opérationnels.** Définition à double sens implicite,
référence culturelle pointue, vocabulaire technique de spécialité
confirmée, ou opération morphologique multiple. Le résolveur ne peut
généralement pas valider sans plusieurs croisements résolus ; le
mécanisme exige un effort intellectuel sustained. Le `?` final
(calembour, §4.5) appartient également à ce niveau mais reste
hors-IA.

**Profil joueur cible.** Cruciverbiste expert, niveau championnat,
abonné aux grilles cryptiques pures. Niveau dominant des grilles
« force maximale » des hebdos spécialisés et des cahiers de vacances
cryptiques.

**Styles typiquement associés.** `cryptique` (§4.7) — style natif de
ce niveau —, `culturel` pointu (§4.6), `technique` expert (§4.9),
`cryptique_morphologique` à double opération ou mot peu fréquent
(§4.8). `calembour` (§4.5, hand-curated only) appartient également à
ce niveau.

**Exemples ancres :**

- EVA → « Veau mêlé » `[ancre validée]` *(cryptique_morphologique à
  double opération : anagramme combiné à une suppression)*
- POIRE → « Bonne à mordre » `[ancre validée]` *(cryptique : fruit
  vs personne naïve)*
- PIQUE → « Tête à l'épée » `[ancre validée]` *(cryptique : arme vs
  couleur de carte)*
- AVOCAT → « Robe noire ou peau verte » `[ancre validée]`
  *(cryptique : juriste vs fruit)*
- SOURIS → « Petite bête sur un bureau » `[ancre validée]`
  *(cryptique : animal vs périphérique)*
- PASTIS → « Trouble qui éclaircit » `[ancre validée]` *(cryptique :
  boisson vs embarras)*
- MITOSE → « Division cellulaire indirecte » `[ancre validée]`
  *(technique expert : biologie cellulaire)*
- RIMBAUD → « Auteur du Bateau ivre » `[ancre validée]` *(culturel
  pointu : œuvre littéraire identifiée)*

### 5.7 Calibration croisée — table mot × style × force

Cette sous-section illustre l'orthogonalité des trois axes en
montrant qu'un même mot peut être défini à plusieurs forces selon le
style retenu. Les définitions ci-dessous ne sont pas en concurrence :
elles couvrent chacune un sens utile dans un contexte de grille
différent. C'est la démonstration la plus parlante de la mécanique
de classification du projet.

**LUNE** *(4 lettres, catégorie `celestial_objects`)*

| Force | Style | Définition |
|:---:|---|---|
| 1 | `définition_directe` | « Satellite terrestre » `[curé]` |
| 2 | `périphrase` | « Astre nocturne » `[curé]` |
| 3 | `métonymie` | « Voisine des étoiles » `[inventé]` |
| 4 | `cryptique` | « Rêveuse en pleine nuit » `[inventé]` |

**COQ** *(3 lettres, catégorie `animals`)*

| Force | Style | Définition |
|:---:|---|---|
| 1 | `définition_directe` | « Volaille mâle » `[inventé]` |
| 2 | `périphrase` | « Mâle de la basse-cour » `[curé]` |
| 3 | `fonction_rôle` | « Chante au matin » `[curé]` |
| 4 | `culturel` | « Symbole gaulois » `[inventé]` |

**CHAT** *(4 lettres, catégorie `animals`)*

| Force | Style | Définition |
|:---:|---|---|
| 1 | `définition_directe` | « Félin domestique » `[inventé]` |
| 2 | `périphrase` | « Ennemi de la souris » `[inventé]` |
| 3 | `fonction_rôle` | « Ronronneur du foyer » `[inventé]` |
| 4 | `cryptique` | « Compagnon poilu ou virtuel » `[inventé]` |

**VERRE** *(5 lettres, catégorie `mobilier_objet` ou `materiaux` selon le sens)*

| Force | Style | Définition |
|:---:|---|---|
| 1 | `définition_directe` | « Contenant transparent » `[inventé]` |
| 2 | `périphrase` | « Cristal du buffet » `[inventé]` |
| 3 | `fonction_rôle` | « On y trinque » `[inventé]` |
| 4 | `cryptique` | « À voir… ou à boire » `[inventé]` |

**MANGER** *(6 lettres, POS `verbe_infinitif`)*

| Force | Style | Définition |
|:---:|---|---|
| 1 | `définition_directe` | « Avaler » `[inventé]` |
| 2 | `périphrase` | « Prendre un repas » `[inventé]` |
| 3 | `fonction_rôle` | « Mettre les pieds sous la table » `[inventé]` |
| 4 | `périphrase` | « Faire honneur au repas » `[inventé]` |

> **Note pédagogique.** MANGER illustre que la force 4 n'est pas
> l'apanage des styles « jeu de mots ». Une `périphrase` soutenue ou
> idiomatique (« Faire honneur au repas ») peut atteindre la force 4
> sans recourir au cryptique, simplement parce que la formulation
> exige une connaissance du registre élégant et un détour
> sémantique non balisé. Chaque style admet sa propre profondeur de
> force, indépendamment du recours au double sens.

**Lectures opérationnelles de cette table.**

1. **Le style n'impose pas la force.** LUNE en `périphrase` peut être
   force 2 (« Astre nocturne », immédiat) ou force 3 (« Compagne de
   la marée », inférence requise). Le style dit *comment* la
   définition opère, la force dit *à quel point* elle est exigeante.
2. **La force ne se devine pas du mot seul.** CHAT à force 1 et CHAT
   à force 4 partagent le même mot ; seule la définition rédigée
   range la production dans un niveau.
3. **Les styles « jeu de mots » (cryptique, cryptique_morphologique,
   calembour) sont structurellement plus hauts en force,** mais cela
   reste une tendance, pas une règle : un cryptique léger (PINCE →
   « Outil de crabe ») reste en force 4, et un cryptique_morphologique
   transparent (LOU → « Loup sans p ») aussi.
4. **La force 5 n'est pas réservée au style cryptique.** RIMBAUD →
   « Auteur du Bateau ivre » est `culturel` pointu et atteint la
   force 5 par exigence de connaissance fine.

Cette orthogonalité guide la composition du dataset : on tire des
échantillons selon les trois axes indépendamment, ce qui maximise la
diversité stylistique et la couverture de force.

## 6. Anti-patterns

Cette section consolide les pièges récurrents disséminés en §1-§4 et
les complète par des anti-patterns spécifiques à la génération par
LLM. Chaque anti-pattern est documenté par : description, signaux de
détection, 2 contre-exemples ❌ avec correction ✅, et méthode de
filtrage. Le pipeline de filtrage automatique qui les enchaîne est
décrit en §8.3.

**Principe directeur.** Les anti-patterns ne réinventent pas de
règles : ils renvoient systématiquement aux principes (§1), aux
caractéristiques structurelles (§2) et aux marqueurs (§3) déjà posés.
Cette section sert de référentiel opérationnel pour le filtrage et
l'évaluation.

### 6.1 Auto-référence

**Description.** Le mot défini apparaît dans sa définition, sous
forme exacte, dérivée (singulier/pluriel, conjugaison), tronquée ou
recomposée. Viole le principe 1.1.

**Signaux de détection :**

- Match exact : le mot apparaît littéralement dans la définition
  (case-insensitive, accents normalisés)
- Match par radical : un dérivé partage un radical de 4+ lettres avec
  le mot
- Match flexionnel : pluriel, féminin, conjugaison reconnaissable

**Exceptions documentées :**

- Sigles et acronymes (§3.2) : le développement des initiales
  réintroduit légitimement les lettres du mot (BO → « Bulletin
  officiel »)
- Style `cryptique_morphologique` (§4.8) : l'opération graphique sur
  le mot est précisément la mécanique de l'indice (LOU → « Loup sans
  p »)

**Exemples :**

- ❌ RAT → « Rat des champs » → ✅ RAT → « Rongeur » `[curé]`
- ❌ COQ → « Coq de la basse-cour » → ✅ COQ → « Mâle de la basse-cour »
  `[curé]`

**Méthode de filtrage.** Regex case-insensitive avec normalisation NFD
sur le mot et la définition. Stem matching pour les variantes
flexionnelles. Exclusion explicite via le flag `pos = sigle_abreviation`
ou `style = cryptique_morphologique` qui désactive le filtre.

### 6.2 Confusion langue FR/EN

**Description.** Présence de tokens anglais non marqués, calques
syntaxiques de l'anglais, ou orthographe anglaise. Viole le principe
1.2 (langue exclusivement française).

**Signaux de détection :**

- Tokens anglais isolés (« cool », « job », « weekend ») sans
  marqueur de langue (§3.1)
- Orthographe EN d'un mot français (« color » au lieu de « couleur »)
- Calques syntaxiques (« Je suis fini » au lieu de « J'ai fini »)
- Détection automatique par fasttext-langid sur la définition seule

**Exemples :**

- ❌ COOL → « Cool » → ✅ COOL → « À la mode » `[inventé]`
- ❌ JOB → « Boulot ou job » → ✅ JOB → « Emploi anglais » `[inventé]`

**Méthode de filtrage.** Pipeline en deux étapes :

1. fasttext-langid sur la définition : si score FR < 0,8 et score EN
   significatif, suspecter une contamination
2. Dictionnaire de stop-words EN couramment confondus (« cool »,
   « job », « game », « color », « center »…) : si présent sans
   marqueur §3.1 dans la définition, rejeter

### 6.3 Hallucination factuelle

**Description.** Information factuelle erronée dans la définition.
Particulièrement critique pour les styles `culturel` (§4.6) et
`technique` (§4.9), qui exigent l'exactitude de la référence.

**Signaux de détection :**

- Fausse attribution d'œuvre, de personnage ou de fait historique
- Propriété chimique, physique ou biologique inventée
- Date, lieu ou contexte erronés
- Difficile à détecter par regex pur : nécessite LLM-juge avec accès
  à une base de connaissances

**Exemples :**

- ❌ ZOLA → « Auteur des Misérables » (faux — c'est Hugo)
  → ✅ ZOLA → « Auteur de Germinal » `[inventé]`
- ❌ AU → « Métal alcalin » (faux — c'est l'or, métal noble)
  → ✅ AU → « Symbole de l'or » `[inventé]`

**Méthode de filtrage.** LLM-juge avec instruction de vérification
factuelle. Idéalement avec accès à Wikipedia, à une base
chimique/biologique pour les `technique`, et à un index des œuvres
littéraires pour les `culturel`. Filtrage coûteux : à placer en
dernier dans le pipeline (§8.3).

### 6.4 Verbiage et longueur excessive

**Description.** Définition débordant le format mots fléchés : style
encyclopédique, descriptions à rallonge, listes énumératives. Viole
le principe 1.3 (brièveté, cible 1-6 mots, médiane 2-3).

**Signaux de détection :**

- Comptage de mots (séparateurs : espace, tiret, apostrophe)
- Seuil de warning : > 8 mots
- Seuil de rejet : > 12 mots
- Seuil de longueur caractères : > 60 caractères (signal complémentaire)

**Exemples :**

- ❌ RAT → « Petit mammifère rongeur de la famille des muridés à
  longue queue habitant les égouts et les caves »
  → ✅ RAT → « Rongeur » `[curé]`
- ❌ LUNE → « Satellite naturel de la Terre, visible la nuit, qui
  tourne autour de notre planète en environ vingt-huit jours »
  → ✅ LUNE → « Astre nocturne » `[curé]`

**Méthode de filtrage.** Compte de mots automatique sur la définition
tokenisée. Reject hard à 12 mots ou plus. Entre 8 et 11, warning
soumis au LLM-juge pour décision de tolérance (cas exceptionnel
justifié).

### 6.5 Stéréotypes IA (formulations qui sonnent LLM)

**Description.** Préfixes et tournures stéréotypés caractéristiques
des générations LLM non spécialisées sur la presse fléchée FR. Ces
formulations sont grammaticalement correctes mais stylistiquement
indésirables : elles signalent une production « générique »
plutôt qu'un travail rédactionnel cruciverbiste. Cette sous-section
est probablement la plus utile au filtrage automatique.

**Patterns à proscrire (liste exhaustive) :**

1. `Quelqu'un qui …` — ❌ COQ → « Quelqu'un qui se lève tôt »
2. `Personne qui …` — ❌ AS → « Personne qui excelle »
3. `Action de + infinitif` (déjà §2.2) — ❌ BA → « Action de faire
   le bien »
4. `Fait de + infinitif` — ❌ BU → « Fait d'avoir bu »
5. `Action consistant à …` — ❌ RI → « Action consistant à
   s'esclaffer »
6. `Manière de + infinitif` — ❌ NU → « Manière d'être sans habits »
7. `Chose qui sert à …` — ❌ BAR → « Chose qui sert à mesurer la
   pression »
8. `Objet qui sert à …` — ❌ NEZ → « Objet qui sert à sentir »
9. `Élément faisant partie de …` — ❌ COU → « Élément faisant partie
   du corps »
10. `Partie du corps qui …` (acceptable seulement si concis) — ❌
    NEZ → « Partie du corps qui sert à respirer et à sentir »
11. `Type de + substantif` — ❌ LOTO → « Type de jeu de hasard »
12. `Sorte de + substantif` — ❌ OIE → « Sorte d'oiseau aquatique »
13. `Variété de + substantif` — ❌ IF → « Variété d'arbre toxique »
14. `Animal de la famille des …` (verbiage taxonomique) — ❌ RAT →
    « Animal de la famille des muridés »
15. `Mot désignant + substantif` — ❌ COQ → « Mot désignant le mâle
    de la poule »
16. `Terme employé pour désigner …` — ❌ EX → « Terme employé pour
    désigner un ancien »
17. `Personnage qui …` — ❌ NOÉ → « Personnage qui a sauvé les
    animaux »

**Exemples de correction :**

- ❌ COQ → « Quelqu'un qui se lève tôt »
  → ✅ COQ → « Chante au matin » `[curé]`
- ❌ NEZ → « Partie du corps qui sert à respirer »
  → ✅ NEZ → « Organe de l'odorat » `[curé]`

**Méthode de filtrage.** Regex sur préfixes (`^(Quelqu'un qui|Personne
qui|Action de|Fait de|Action consistant|Manière de|Chose qui sert|Objet
qui sert|Élément faisant partie|Type de|Sorte de|Variété de|Mot
désignant|Terme employé)`). Reject immédiat sur match. Cette liste sera
enrichie au fil du projet à mesure que de nouveaux patterns LLM
émergent.

### 6.6 Erreurs d'accord et grammaire

**Description.** Le genre ou le nombre du participe, de l'adjectif ou
du substantif accordé dans la définition ne correspond pas à la
morphologie du mot. Viole le principe 1.5 quand l'accord est exprimé.

**Signaux de détection :**

- Difficile à détecter par regex pure : exige une analyse
  morphologique du mot et de la définition
- Indices : terminaison du mot (-e, -es, -s, -aux) vs accord de
  l'adjectif/participe dans la définition
- LLM-juge avec prompt ciblé sur l'accord est la méthode la plus
  fiable

**Exemples :**

- ❌ NU → « Dépouillée » (impose NUE par accord féminin)
  → ✅ NU → « Dépouillé » `[curé]`
- ❌ WC → « Toilette » (singulier alors que WC est traité comme
  pluriel d'usage en mots fléchés, cf. §2.3)
  → ✅ WC → « Toilettes » `[curé]`

**Méthode de filtrage.** Préfiltre par règles morphologiques simples
(terminaison -e du mot → recherche accord fém. dans la définition,
etc.), puis LLM-juge avec prompt explicite : « Le genre/nombre du
participe ou de l'adjectif accordé dans la définition est-il
cohérent avec le mot ? Réponds OUI ou NON. » Filtre coûteux, à placer
en fin de pipeline.

### 6.7 Tautologies et définitions vides

**Description.** Définition qui n'apporte aucune information
discriminante : étiquette catégorielle générique sans qualificatif,
paraphrase circulaire, ou définition qui s'applique à tant de mots
qu'elle ne sélectionne aucun candidat. Viole le principe 1.7
(discrimination forte).

**Signaux de détection :**

- Définition appartenant à une liste fermée d'étiquettes génériques :
  « Animal », « Plante », « Objet », « Personne », « Chose », « Lieu »,
  « Outil », « Mot », « Terme », « Élément », « Partie », « Type »,
  « Sorte »
- Définition contenant strictement 1 mot et appartenant à cette liste
- Paraphrase circulaire détectable par stem-matching renforcé

**Exemples :**

- ❌ RAT → « Animal » → ✅ RAT → « Rongeur » `[curé]`
- ❌ LÉA → « Prénom » → ✅ LÉA → « Courte héroïne biblique » `[curé]`

**Méthode de filtrage.** Croisement avec la liste fermée d'étiquettes
catégorielles génériques. Si la définition est exactement l'une de
ces étiquettes sans qualificatif, rejeter. Si elle est l'étiquette +
un seul qualificatif non discriminant (« Animal commun »), warning
LLM-juge.

### 6.8 Erreurs typographiques

**Description.** Violations des règles typographiques §2.4 :
apostrophe droite, casse incorrecte, point final, guillemets externes,
espaces parasites, caractères interdits. **La plupart sont
auto-correctibles** par normalisation (cf. §2.4 règle 9, §8.4) — la
règle de filtrage privilégie la normalisation au rejet.

**Signaux de détection :**

- Apostrophe droite `'` (U+0027) à normaliser en `’` (U+2019)
- Espaces multiples consécutifs, espaces en début/fin
- Point final ou ponctuation finale non autorisée
- Initiale en minuscule
- Guillemets externes `« … »` ou `"…"` enveloppant toute la définition
- Présence d'emoji, gras `**`, italique `*`, balises HTML

**Exemples :**

- ❌ « mâle de la basse-cour. » → ✅ « Mâle de la basse-cour »
  *(initiale capitalisée, point final supprimé, guillemets retirés)*
- ❌ « Forme d'avoir  » *(apostrophe droite, double espace, espace
  final)* → ✅ « Forme d’avoir » `[curé]`

**Méthode de filtrage.** Normalisation automatique en post-traitement
(§8.4), pas de rejet sauf pour caractères strictement interdits
(emoji, gras markdown, HTML), qui restent rejetés au filtrage
typographique initial (§8.3 étape 1).

## 7. Cas particuliers

Cette section formalise les taxonomies structurelles du dataset (POS
en §7.1, catégories sémantiques en §7.2), discute les combinaisons
attendues et les cas particuliers de polysémie et d'homonymie POS.
Elle est foundationnelle pour la composition et l'annotation du
dataset final.

### 7.1 Taxonomie POS (nature grammaticale)

**Justification.** La nature grammaticale (`pos`) du mot est
indépendante de son champ sémantique (`categorie`) et de la mécanique
stylistique (`style`) de la définition. Le `pos` sert au LLM pour
produire des définitions cohérentes avec :

- l'accord en genre/nombre quand exprimé (§1.5, §2.3)
- les formes syntaxiques privilégiées par type de mot (§2.1)
- les marqueurs grammaticaux canoniques (§3.3)

La taxonomie compte **12 étiquettes** après fusion de `sigle` et
`abreviation`. L'étiquette `autre` est strictement résiduelle.

#### Les 12 étiquettes POS

| # | Étiquette | Description | Critère discriminant | Exemples |
|---|---|---|---|---|
| 1 | `verbe_infinitif` | Verbe sous forme non conjuguée. | Terminaison -er, -ir, -re, -oir, sens d'action ou d'état. | MANGER, COURIR, AIMER, LUIRE `[inventé]` ; TOUCHER `[curé]` *(sens verbal)* |
| 2 | `verbe_conjugue` | Verbe conjugué autre que participe. | Forme fléchée temps/personne. Sous-tag personne+temps optionnel. **Pour les 4+ lettres, attendus à tous temps :** présent (MANGEONS), imparfait (MANGEAIT), passé simple (MANGEA), futur (MANGERA), conditionnel (MANGERAIT), subjonctif (MANGEÂT). | AI `[curé]` *(1S avoir)*, VA `[curé]` *(3S aller)*, ES `[curé]` *(2S être)* |
| 3 | `participe_passe` | Forme verbale au participe passé, accordée en genre/nombre. | Terminaison -é, -i, -u, -s, -t à valeur participiale ; admet une lecture adjectivale. | BU, LU, NU, NÉ, VU, EU, PU, SU `[curé]` ; MANGÉ, COURU `[inventé]` |
| 4 | `participe_present` | Forme verbale en -ant. | Terminaison -ant à valeur verbale (parfois adjectivée). | DORMANT, MORDANT, BRILLANT `[inventé]` *(absents du corpus court)* |
| 5 | `nom_commun` | Substantif désignant une classe d'objets, d'êtres ou de concepts. | Admet un article (« un X », « une X », « des X »). | COQ, ÂNE, COU, NEZ, BAR `[curé]` ; CHAT, TABLE, AMOUR, FLEUR `[inventé]` |
| 6 | `nom_propre` | Substantif désignant un référent unique (personne, lieu, marque, œuvre). | Référent identifiable, capitale même hors mots fléchés. | NOÉ, THOR, ZEUS, OSLO, IRAN, ARES `[curé]` |
| 7 | `adjectif` | Qualifie un substantif, varie en genre/nombre. | Admet l'accord, peut être épithète ou attribut. | NU, IN, EX `[curé]` ; GRAND, JOLI, AMÈRE `[inventé]` |
| 8 | `adverbe` | Modifie un verbe, un adjectif, une phrase ; généralement épicène. | Invariable, sens circonstantiel ou modal. | EN, OÙ, AP `[curé]` ; VITE, ICI, SOUVENT `[inventé]` |
| 9 | `interjection` | Acte de langage isolé, valeur expressive. | Forme courte, expression d'émotion ou d'appel. | AH, OH, EH, HÉ, AÏE, HEIN, HO, HOP, FI `[curé]` |
| 10 | `mot_outil` | Pronom, article, préposition, conjonction, possessif, démonstratif. | Particule grammaticale sans contenu lexical autonome. | DE, LE, LA, JE, MA, NI, SE, ET, OR, CE, ON, IL, EN, OU `[curé]` |
| 11 | `sigle_abreviation` | Sigle (initiales d'un syntagme) ou abréviation (troncature). | Forme tronquée ou acronymique d'un mot/syntagme plus long. Distinction sigle/abréviation au niveau du marqueur §3.2, pas du POS. | BO, ND, ONU, OTAN, OMS, USA, USD, DOL, ST, DR, AM, NB `[curé]` |
| 12 | `autre` | Résiduel pour mots dont la nature grammaticale ne relève d'aucune des 11 catégories ci-dessus. **Strictement résiduel.** | Inclassifiable ou nature hybride non standard. | *(à utiliser avec parcimonie — en cas de doute, privilégier `nom_commun`)* |

#### Règle de priorité pour `autre`

`pos = autre` est réservé aux **vrais cas inclassifiables**. Pour les
mots qui sembleraient hybrides à première vue :

- KA → « Âme égyptienne » : malgré l'origine culturelle marquée, KA
  est un nom commun en français (« un ka »). **POS = `nom_commun`**.
- AA → « Lave rugueuse » : malgré l'origine hawaïenne, AA est un nom
  commun (« un aa »). **POS = `nom_commun`**.
- AÏ → « Paresseux » : nom commun d'un animal. **POS = `nom_commun`**.
- NÔ → « Théâtre japonais » : nom commun désignant un genre théâtral.
  **POS = `nom_commun`**.

En cas de doute, privilégier `nom_commun`. La taxonomie POS n'est pas
étanche en français : c'est un classement utilitaire pour la
génération, pas une vérité grammaticale absolue.

#### Arbre de décision POS pour annotation

```
1. Le mot est-il un acronyme ou une troncature d'un mot/syntagme
   plus long ?
   → OUI  : pos = sigle_abreviation
   → NON  : passer à 2

2. Le mot est-il un nom propre (référent unique : personne, lieu,
   marque, œuvre) ?
   → OUI  : pos = nom_propre
   → NON  : passer à 3

3. Le mot est-il une interjection ou une onomatopée ?
   → OUI  : pos = interjection
   → NON  : passer à 4a

4a. Le mot est-il une forme verbale en -ant ?
    → OUI : pos = participe_present
    → NON : passer à 4b

4b. Le mot est-il un participe passé (terminaison -é, -i, -u, -s, -t
    à valeur participiale) ?
    → OUI : pos = participe_passe
    → NON : passer à 4c

4c. Le mot est-il un verbe à l'infinitif (terminaison -er, -ir, -re,
    -oir) ?
    → OUI : pos = verbe_infinitif
    → NON : passer à 4d

4d. Le mot est-il un verbe conjugué (autre que participe et
    infinitif) ?
    → OUI : pos = verbe_conjugue
    → NON : passer à 5

5. Le mot est-il un mot-outil grammatical (pronom, article,
   préposition, conjonction, possessif, démonstratif) ?
   → OUI  : pos = mot_outil
   → NON  : passer à 6

6. Le mot est-il un adverbe (invariable, modifie verbe/adj/phrase) ?
   → OUI  : pos = adverbe
   → NON  : passer à 7

7. Le mot est-il un adjectif (qualifie un substantif, accord en
   genre/nombre) ?
   → OUI  : pos = adjectif
   → NON  : passer à 8

8. Le mot admet-il un article (« un X », « une X », « des X »)
   et désigne-t-il une classe ?
   → OUI  : pos = nom_commun
   → NON  : pos = autre
```

#### Implications par POS sur la définition

| POS | Formes syntaxiques privilégiées (§2.1) | Marqueurs typiques (§3) | Signal d'accord (§1.5) |
|---|---|---|---|
| `verbe_infinitif` | Infinitif, syntagme verbal | §3.3 « Forme de Y » exceptionnellement | non exprimé (cas frontière §2.3) |
| `verbe_conjugue` | Verbe conjugué sans sujet exprimé, ou « Forme de Y » | §3.3 forme verbale | accord avec sujet implicite |
| `participe_passe` | Participe passé accordé, parfois adjectivé | §3.3 ou §3.5 | masc/fém sg/pl exprimé |
| `participe_present` | Participe présent (-ant), parfois adjectivé | — | accord adjectival si exprimé |
| `nom_commun` | Syntagme nominal | §3 transverses | masc/fém sg/pl du substantif |
| `nom_propre` | Référence onomastique, marqueur culturel | §3.1, §3.4, style §4.6 | accord facultatif |
| `adjectif` | Adjectif seul, ou syntagme adjectival | §3.6 si registre marqué | masc/fém sg exprimé |
| `adverbe` | Adverbe, locution adv/prép | §3.6 si registre marqué | épicène |
| `interjection` | Interjection lexicalisée, syntagme acte de langage | §3.9 acte de langage, §3.6 registre | épicène |
| `mot_outil` | Étiquette grammaticale, paraphrase grammaticale | §3.3 marqueurs grammaticaux | genre marqué si pertinent |
| `sigle_abreviation` | Développement direct des initiales, ou paraphrase | §3.2 marqueurs d'abréviation | accord facultatif |
| `autre` | Variable selon le cas | — | variable |

### 7.2 Taxonomie des 43 catégories sémantiques

**Structure.** Les 43 catégories sont organisées en 8 groupes pour
les 28 catégories existantes et 4 macro-domaines pour les 15 nouvelles,
soit **12 groupes au total**. Pour chaque catégorie : description,
exemples (curés en priorité), styles privilégiés, marqueurs canoniques
(avec renvois §3 et §4).

> **Note importante pour les 15 nouvelles catégories.** Les marqueurs
> canoniques associés sont des **patterns prévisibles, à raffiner au
> fil du projet** au fur et à mesure que la génération produit du
> corpus pour ces catégories. Aucun marqueur des 15 nouvelles
> catégories n'est figé.

#### Catégories existantes (28)

##### Groupe A — Sciences, mesures, ciel (5)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs canoniques |
|---|---|---|---|---|
| `chemical_symbols` | Symboles d'éléments chimiques. | AG, AL, FE, NA, CU `[curé]` | définition_directe + technique | « Métal X », « Gaz X », « Élément X » (§3.8 implicite) |
| `units` | Unités de mesure (SI et usuelles). | KM, HZ, ATM, LUX, KWH `[curé]` | technique | « Unité de X » (§3.8) |
| `celestial_objects` | Astres et corps célestes. | LUNE `[curé]` | périphrase, culturel | « Astre X », « Satellite X » |
| `nombres` | Nombres et numéraux. | DIX `[curé]` | définition_directe | « Nombre X », « Chiffre X » |
| `roman_numerals` | Chiffres romains. | VI `[curé]` | définition_directe | « X romain », « Nombre antique » (§3.4) |

##### Groupe B — Géographie et lieux (5)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs canoniques |
|---|---|---|---|---|
| `cardinal_points` | Points cardinaux et directions. | NE, NO, ESE, ONO, SSO `[curé]` | métonymie | « Côté X », « Vers Y », « Entre X et Y », « Cap vers Z » |
| `cities` | Villes. | OSLO, RIO `[curé]` | périphrase, métonymie, culturel | « Capitale X », « Ville X », « Port X » |
| `countries` | Pays. | IRAN, MALI `[curé]` | périphrase, métonymie, culturel | « Pays X », « État de Y » |
| `country_codes` | Codes ISO de pays. | FR, DE, IT, UK, ES `[curé]` | définition_directe | Nom du pays, « Code X » (§3.2) |
| `geography` | Géographie générale (mer, montagne, cap…). | MER, CAP `[curé]` | définition_directe, périphrase | « X de Y », repère géographique |

##### Groupe C — Personnes et mythes (3)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs canoniques |
|---|---|---|---|---|
| `first_names` | Prénoms. | ANNA, LÉA, ÉVA, NOÉ, LUC `[curé]` | définition_directe (lookup), périphrase, culturel | « Prénom X », « Variante de Y », « Personnage biblique » (§4.6) |
| `titles` | Titres et fonctions abrégés. | DR, ST, MR `[curé]` | définition_directe | §3.2 (X abrégé) |
| `mythology` | Mythologie (grecque, romaine, égyptienne, nordique). | ZEUS, THOR, ISIS, ARES, RA `[curé]` | culturel | « Dieu X », « Déesse Y », « X grec/égyptien » (§4.6) |

##### Groupe D — Langue et énoncé (6)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs canoniques |
|---|---|---|---|---|
| `abbreviations` | Sigles et abréviations du français usuel. | BO, NB, VS, CD, PV `[curé]` | définition_directe | §3.2 (X abrégé, sigle) |
| `etranger` | Mots de langue étrangère. | NO, UNO `[curé]` | définition_directe + marqueur langue | §3.1 (Y en X, Y X-gentilé) |
| `expressions` | Expressions familières ou idiomatiques courtes. | BO, GO `[curé]` | définition_directe, périphrase | §3.6 (registre) |
| `grammar` | Mots-outils grammaticaux. | DE, ES, NE, LA, SI `[curé]` | définition_directe | §3.3 (étiquettes grammaticales) |
| `interjections` | Interjections lexicalisées. | AH, AÏE, OH, EH, HEIN, HOP `[curé]` | définition_directe | §3.9 (acte de langage) |
| `orthographe` | Variantes orthographiques. | CA `[curé]` | cryptique_morphologique | §3.5 (X sans Y) |

##### Groupe E — Vivant et perception (3)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs canoniques |
|---|---|---|---|---|
| `animals` | Animaux courants. | COQ, OIE, ÂNE, RAT, LOU `[curé]` | périphrase, fonction_rôle, définition_directe | « Volatile X », « Rongeur », substantif catégoriel + qualificatif |
| `body_parts` | Parties du corps humain. **Élargie aux 4+ lettres.** | COU, DOS, NEZ, BRAS, ŒIL `[curé]` ; ÉPAULE, GENOU, CŒUR, POUMON, COUDE `[inventé]` | périphrase, fonction_rôle, métonymie | « Organe de X », « Partie de Y », « Porte X » |
| `senses` | Sens et perceptions. **Catégorie potentiellement extensible aux noms d'actes perceptifs (REGARD, CARESSE, RUMEUR…) pour les 4+ lettres.** | VUE, OUÏE, GOÛT, ODORAT, TOUCHER `[curé]` | définition_directe, fonction_rôle | « Sens de X », « Perception Y » |

##### Groupe F — Économie et institutions (2)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs canoniques |
|---|---|---|---|---|
| `currencies` | Monnaies. | EURO, YEN, USD, CHF, DOL `[curé]` | définition_directe | « Monnaie X », « Devise X », « X abrégée » (§3.2) |
| `organizations` | Organisations et institutions. | ONU, OTAN, OMS, UE `[curé]` | définition_directe | « Organisation X », « Pacte X » (sigles §3.2) |

##### Groupe G — Jeux et musique (3)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs canoniques |
|---|---|---|---|---|
| `card_game` | Cartes à jouer. | AS, ROI, DIX `[curé]` | définition_directe, métonymie | « Carte X », « Figure couronnée » |
| `games` | Jeux. | LOTO, UNO, GO `[curé]` | périphrase | « Jeu X », « Tirage X » |
| `music_notes` | Notes de musique. | DO, RE, MI, FA, SOL, LA, SI, UT `[curé]` | définition_directe | « Note X », « Ancien Y » (§3.4 pour UT) |

##### Groupe H — Résiduel (1)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs canoniques |
|---|---|---|---|---|
| `autre` | Mots sans champ sémantique clair ou catégorie absente. | HI, MU, AÏ, AA, KA `[curé]` | variable | variable |

> `autre` est l'étiquette par défaut pour les mots du corpus court non
> classés finement. Pour les futures entrées du dataset, on essaiera
> d'utiliser une catégorie plus précise quand possible. Cf. fin de §7.2
> pour les critères de proposition d'une nouvelle catégorie.

#### Catégories nouvelles (15)

> Les marqueurs ci-dessous sont **prévisibles, à raffiner au fil du
> projet**. Aucun n'est figé.

##### Macro-domaine I — Quotidien matériel (6)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs prévisibles |
|---|---|---|---|---|
| `aliments` | Produits comestibles, plats, boissons. | POMME, PAIN, FROMAGE, RIZ, ŒUF, SOUPE | définition_directe, périphrase | « Fruit X », « Plat de Y », « Boisson Z » |
| `vetements` | Vêtements et accessoires. | ROBE, PULL, JEAN, BOTTE, GANT, CHAPEAU | définition_directe, périphrase | « Vêtement X », « Couvre Y », « Tenue Z » |
| `mobilier_objet` | Mobilier et objets du foyer. | TABLE, CHAISE, LAMPE, CLEF, MIROIR | définition_directe, périphrase, fonction_rôle | « Meuble X », « Objet Y », « Sert à Z » |
| `outils` | Outils et instruments manuels. | MARTEAU, SCIE, PINCE, VIS, CLOU | définition_directe, fonction_rôle | « Outil X », « Sert à Y » |
| `transports` | Moyens de transport. | VOITURE, TRAIN, AVION, VÉLO, BATEAU | définition_directe, périphrase | « Véhicule X », « Transport Y » |
| `materiaux` | Matériaux et matières. | BOIS, FER, CUIR, VERRE, PIERRE | définition_directe, périphrase | « Matière X », « Composé Y » |

##### Macro-domaine J — Humain et social (3)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs prévisibles |
|---|---|---|---|---|
| `professions` | Métiers et fonctions sociales. | MÉDECIN, COIFFEUR, BERGER, JUGE, MAIRE | définition_directe, fonction_rôle | « X qui Y », « Métier de Z » |
| `famille_relations` | Famille et liens parentaux/sociaux. | MÈRE, PÈRE, ONCLE, ENFANT, ÉPOUX | définition_directe, périphrase | « Parent X », « Lien de Y » |
| `sentiments_etats` | Émotions et états d'esprit. | JOIE, AMOUR, COLÈRE, PEUR, ENNUI | définition_directe, périphrase | « Sentiment X », « État Y » |

##### Macro-domaine K — Nature (3)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs prévisibles |
|---|---|---|---|---|
| `nature_paysage` | Éléments du paysage et reliefs. | FORÊT, MONTAGNE, RIVIÈRE, MER, PLAGE | définition_directe, périphrase | « Étendue X », « Relief Y » |
| `flore` | Plantes, arbres, fleurs. **Bien que biologiquement apparentée aux organismes vivants (Groupe E), `flore` est rattachée au macro-domaine Nature pour refléter son usage typique en mots fléchés (élément du paysage et du décor).** | ARBRE, FLEUR, ROSE, CHÊNE, ALGUE | définition_directe, périphrase | « Plante X », « Arbre Y », « Fleur Z » |
| `meteo_climat` | Météo et phénomènes climatiques. | PLUIE, NEIGE, ORAGE, BRISE, FROID | définition_directe, périphrase | « Précipitation X », « Vent Y » |

##### Macro-domaine L — Abstractions et arts (3)

| Catégorie | Description | Exemples | Styles privilégiés | Marqueurs prévisibles |
|---|---|---|---|---|
| `temps_duree` | Temps, durée, périodes. | SIÈCLE, MOIS, HEURE, MINUTE, JADIS | définition_directe, périphrase | « Durée X », « Période Y » |
| `couleurs` | Couleurs et teintes. | ROUGE, BLEU, VERT, JAUNE, ROSE | définition_directe, périphrase | « Couleur X », « Teinte Y » |
| `arts` | Arts et formes d'expression artistique. **Nommée sans suffixe `_culture` pour éviter la confusion avec le style §4.6 culturel.** | POÉSIE, CINÉMA, OPÉRA, ROMAN, DANSE | définition_directe, périphrase | « Art X », « Forme d'art Y » |

#### Critères pour proposer une nouvelle catégorie en cours de projet

Une nouvelle catégorie peut être proposée si elle remplit **les trois
critères suivants** :

1. **Volume représentable.** Deux seuils définissent le statut de la
   catégorie :
   - **Catégorie expérimentale (statut provisoire) : 15 à 20 mots.**
     Tolérance pour démarrer une catégorie naissante et en évaluer la
     cohérence en conditions réelles.
   - **Catégorie consolidée (statut stable) : 40 à 50 mots.** Seuil
     d'intégration durable dans la taxonomie projet.

   Une catégorie expérimentale doit être confirmée (passage à
   consolidée) ou fusionnée avec une catégorie adjacente après une
   review projet.

2. **Style cohérent.** Les mots de la catégorie partagent un
   répertoire de marqueurs canoniques discriminant les autres
   catégories.

3. **Non-recouvrement.** La catégorie n'est pas substantiellement
   couverte par une catégorie existante. Si recouvrement à 50% ou
   plus, fusionner avec la catégorie existante.

Toute proposition de nouvelle catégorie doit être documentée dans une
note dédiée et faire l'objet d'une décision projet avant ouverture
dans le dataset.

### 7.3 Combinaison POS × catégorie

Le `pos` et la `categorie` sont indépendants par construction : ils
relèvent de dimensions différentes (nature grammaticale vs champ
sémantique). En pratique, certaines combinaisons sont attendues comme
archétypales, d'autres sont rares ou suspectes (signal d'erreur
d'annotation probable).

#### Combinaisons archétypales

| POS | Catégorie | Description | Exemples |
|---|---|---|---|
| `nom_commun` | `animals` | Substantif désignant un animal. | COQ, ÂNE, RAT, OIE, LOU `[curé]` |
| `nom_commun` | `body_parts` | Substantif désignant une partie du corps. | COU, NEZ, DOS, BRAS, ŒIL `[curé]` |
| `nom_commun` | `senses` | Substantif désignant un sens. | VUE, OUÏE, GOÛT, ODORAT, TOUCHER `[curé]` |
| `nom_propre` | `mythology` | Personnage mythologique. | ZEUS, THOR, ISIS, ARES, RA `[curé]` |
| `nom_propre` | `first_names` | Prénom. | NOÉ, ANNA, LUC, LÉA, ÉVA `[curé]` |
| `nom_propre` | `cities` | Ville. | OSLO, RIO `[curé]` |
| `sigle_abreviation` | `organizations` | Sigle d'organisation. | ONU, OTAN, OMS `[curé]` |
| `sigle_abreviation` | `country_codes` | Code ISO de pays. | FR, DE, IT, UK, ES `[curé]` |
| `sigle_abreviation` | `currencies` | Code de devise. | USD, CHF, GBP, DOL `[curé]` |
| `interjection` | `interjections` | Interjection lexicalisée. | AH, OH, EH, HÉ, AÏE, HOP `[curé]` |
| `mot_outil` | `grammar` | Particule grammaticale. | DE, LE, LA, NE, SI, SE `[curé]` |
| `participe_passe` | `autre` | Participe passé du corpus court. | BU, LU, NU, NÉ, VU, EU, SU `[curé]` |
| `verbe_infinitif` | (catégorie sémantique adaptée) | Verbe à l'infinitif. | MANGER (`aliments` ou `autre`), TOUCHER (`senses`) `[curé pour TOUCHER]` |

#### Combinaison archétypale particulière : `(mot_outil, grammar)`

La combinaison `pos = mot_outil` + `categorie = grammar` est
**redondante en apparence** : les deux étiquettes recouvrent la même
zone (mots-outils grammaticaux). Cette redondance est **assumée et
documentée** : le `pos` donne la *nature grammaticale fine* (utile
pour le LLM sur la conjugaison et l'accord), tandis que la
`categorie` donne le *champ sémantique* (utile pour la cohérence
stylistique par champ). Les deux dimensions restent indépendantes par
construction du dataset, même si elles convergent sur cette
combinaison archétypale.

#### Combinaisons rares ou impossibles (signal d'erreur d'annotation)

| Combinaison | Pourquoi suspecte | Action attendue |
|---|---|---|
| `verbe_infinitif` × `first_names` | Un verbe n'est pas un prénom. | Réannoter : probablement nom_propre × first_names, ou verbe_infinitif × autre |
| `verbe_infinitif` × `country_codes` | Un verbe n'est pas un code de pays. | Réannoter le POS (probablement sigle_abreviation) ou la catégorie |
| `interjection` × `units` | Une interjection n'est pas une unité de mesure. | Réannoter : conflit entre signaux POS et sémantique |
| `mot_outil` × `mythology` | Un mot-outil n'est pas un personnage mythologique. | Réannoter, sauf cas particulier rare à documenter |
| `nom_propre` × `grammar` | Un nom propre n'est pas un mot-outil grammatical. | Réannoter le POS (probablement nom_commun si désignation par caractéristique grammaticale) |

Le filtrage automatique du dataset peut signaler ces combinaisons
comme « warning, à vérifier » sans les rejeter d'emblée : il existe
toujours des cas frontières qui méritent décision manuelle.

### 7.4 Cas spécifiques par POS

Sous-sections courtes par type de mot. Chaque sous-section indique
les particularités à respecter en génération.

#### Verbes à l'infinitif (`pos = verbe_infinitif`)

Les définitions peuvent suivre deux patterns dominants : un infinitif
synonyme (« Avaler » → MANGER `[inventé]`) ou une périphrase verbale
(« Prendre un repas » → MANGER `[inventé]`). Pas d'accord nominal
exprimé ; le verbe-pivot dans la définition reste à l'infinitif.
Privilégier les synonymes verbaux pour la force 1-2, les périphrases
ou idiotismes pour la force 3-4.

#### Verbes conjugués (`pos = verbe_conjugue`)

L'accord se fait avec le **sujet implicite** (qui est le mot défini
lui-même). « Chante au matin » → COQ `[curé]` : sujet implicite =
COQ, accord 3S présent. La définition n'a pas de sujet explicite
(violerait §1.4). Pour les 4+ lettres, on diversifie les temps :
MANGEAIT (imparfait), MANGERA (futur), MANGEÂT (subjonctif).

#### Adjectifs (`pos = adjectif`)

L'adjectif seul ou minimalement complété est la forme la plus directe
(« Branché » → IN `[curé]`). Le genre exprimé contraint la forme :
« Dépouillé » → NU `[curé]` (l'accord masc. sg. exclut NUE, NUS,
NUES). Pour les adjectifs épicènes (rouge, jaune), pas de signal
d'accord exprimable ; la définition reste neutre.

#### Noms propres (`pos = nom_propre`)

Capitalisation interne respectée dans la définition (« Notre-Dame »,
« Mars grec »). Marqueur culturel ou géographique souvent porté par
un adjectif (« biblique », « grec », « nordique »). Style typique :
`culturel` (§4.6) avec référence onomastique. Pour les premières
lettres, l'accord en genre peut être omis quand le prénom le rend
implicite.

#### Participes (`pos = participe_passe` ou `participe_present`)

Double nature verbal et adjectival. Le participe passé du corpus
court (BU, LU, NU, NÉ, VU…) est typiquement défini par un autre
participe accordé (« S'est désaltéré » → BU `[curé]`). L'accord
masc/fém sg/pl est porté par la terminaison ; définition cohérente
avec cet accord (cf. §1.5). Participes présents en -ant
essentiellement absents du corpus court ; à utiliser pour 5+ lettres
(DORMANT, BRILLANT).

#### Pluriels intrinsèques vs flexionnels

- **Pluriel intrinsèque** (mot toujours pluriel, jamais singulier) :
  US, WC en français contemporain. La définition reflète la
  pluralité : « Habitudes anciennes » → US `[curé]`, « Toilettes »
  → WC `[curé]`. Singularité dans la définition crée une incohérence
  avec la pluralité intrinsèque du mot. La convention de la base
  curée privilégie le pluriel cohérent.
- **Pluriel flexionnel** (mot admettant singulier et pluriel) : CHATS
  vs CHAT. Le pluriel flexionnel suit l'accord standard et reçoit en
  général sa propre entrée dans le dataset si les définitions
  pertinentes diffèrent du singulier.

### 7.5 Polysémie — un mot, plusieurs sens

Un même mot peut admettre plusieurs sens, chacun avec sa propre
définition, sa propre catégorie sémantique, et possiblement son
propre style.

**Politique du dataset.** Une ligne par couple (mot, sens) dans le
dataset. Le même mot apparaît donc plusieurs fois si plusieurs sens
sont retenus. La `categorie` peut différer d'une ligne à l'autre ;
le `pos` reste typiquement constant si les sens sont du même type
grammatical (cas standard), mais peut changer en cas d'homonymie POS
(cf. §7.6).

**Exemples curés frappants :**

- **KO** :
  - KO ; « Hors combat » ; sigle_abreviation ; abbreviations `[curé]`
  - KO ; « Kilooctet » ; sigle_abreviation ; units `[curé]`

- **FR** :
  - FR ; « Francium » ; sigle_abreviation ; chemical_symbols `[curé]`
  - FR ; « France » ; sigle_abreviation ; country_codes `[curé]`

- **GO** :
  - GO ; « Gigaoctet » ; sigle_abreviation ; units `[curé]`
  - GO ; « Jeu asiatique » ; nom_commun ; games `[curé]`
  - GO ; « Signal de départ » ; nom_commun ; expressions `[curé]`

- **MG, CL** : même mécanique (deux catégories disjointes :
  chemical_symbols + units).

La polysémie n'est pas une exception : c'est une composante normale
du corpus. La base curée recense déjà 5 mots avec 4+ définitions
réparties sur 2+ catégories.

### 7.6 Homonymie POS — un mot, plusieurs natures grammaticales

Un même mot peut admettre plusieurs natures grammaticales. C'est un
cas plus rare que la polysémie sémantique (§7.5), mais déterminant
pour la cohérence du dataset.

**Politique du dataset.** Une ligne par couple (mot, POS) quand
l'homonymie POS est attestée et que les définitions associées sont
non triviales. Le `pos` change d'une ligne à l'autre ; la `categorie`
suit l'usage dominant du sens correspondant.

**Cas archétypal : TOUCHER**

- TOUCHER ; « Sens de la peau » ; nom_commun ; senses `[curé]`
- TOUCHER ; « Percevoir par contact » ; verbe_infinitif ; autre `[inventé]`

Le premier emploi est nominal (sens du toucher) ; le second est
verbal (action de toucher). Les deux entrées sont légitimes et
documentent deux sens distincts du même mot.

**Vraies homonymies POS attestées :**

- **TOUCHER** : nom_commun (sens) ET verbe_infinitif (action).
  Documenté en cas archétypal ci-dessus.
- **POUVOIR** : verbe_infinitif (capacité d'action) ET nom_commun
  (autorité, le pouvoir exécutif). Homonymie attestée, à étiqueter
  sur deux lignes si les deux sens sont retenus.

**Cas potentiels écartés :**

- **GOÛT** : nom_commun uniquement. Le verbe correspondant est GOÛTER,
  distinct graphiquement.
- **LOUER** : verbe_infinitif uniquement, polysémique (cf. §7.5).
- **PORTER** : verbe_infinitif uniquement. Pas de sens nominal en
  français contemporain.

L'homonymie POS est un cas marginal mais structurant : elle prouve
l'indépendance des trois axes (POS / catégorie / style / force) et
justifie la duplication des lignes au sein du dataset.

## 8. Format de sortie

Cette section spécifie le format opérationnel du dataset, les règles
de sortie du LLM en inférence, et le pipeline de filtrage/normalisation
qui produit le dataset final. Elle est foundationnelle pour
l'industrialisation du projet : tout outil qui consomme ou produit le
dataset s'appuie sur les contrats définis ici.

### 8.1 Schéma du dataset final

Le dataset principal est un CSV UTF-8 NFC, séparateur `;`,
**8 colonnes principales** plus un champ `meta` optionnel.

#### Spécification colonne par colonne

| # | Colonne | Type | Contraintes | Valeurs admises |
|---|---|---|---|---|
| 1 | `mot` | str | MAJUSCULES, lettres + diacritiques + apostrophe typographique `’` | Regex : `^[A-ZÀÁÂÄÆÇÉÈÊËÎÏÔÖŒÙÛÜŸ’\-]+$` après NFC ; longueur 2-12 codepoints (extensible 13+ ultérieurement) |
| 2 | `definition` | str | Casse normalisée §2.4 (initiale en majuscule, reste en minuscule sauf nom propre), pas de point final, apostrophe typographique. UTF-8 NFC | Longueur 2-60 caractères ; 1-8 mots (cible médiane 2-3 mots, cf. §1.3) |
| 3 | `pos` | str enum | Une des 12 étiquettes §7.1 | `verbe_infinitif`, `verbe_conjugue`, `participe_passe`, `participe_present`, `nom_commun`, `nom_propre`, `adjectif`, `adverbe`, `interjection`, `mot_outil`, `sigle_abreviation`, `autre` |
| 4 | `categorie` | str enum | Une des 43 catégories §7.2 | Liste complète §7.2 (existantes + nouvelles) |
| 5 | `style` | str enum | Un des 9 styles §4 | `définition_directe`, `périphrase`, `métonymie`, `fonction_rôle`, `calembour` *(hand-curated only)*, `culturel`, `cryptique`, `cryptique_morphologique`, `technique` |
| 6 | `force` | int | Entier dans [1, 5] | 1, 2, 3, 4, 5 (§5) |
| 7 | `longueur` | int | Codepoints NFC du mot | Entier > 0, dérivé du champ `mot` (cohérence à valider en pipeline) |
| 8 | `source` | str enum | Traçabilité de la ligne | `gold` (référence éditoriale), `curated_v1` (base curée short_words_unified.csv), `synthetic_v1` (génération LLM v1), `manual` (saisie humaine ad hoc), ou identifiant projet ultérieur |
| 9 *(opt.)* | `meta` | str | Clés-valeurs au format `clé:valeur|clé:valeur|…` | Liaison clé-valeur par `:`, séparateur entre paires par `|`. Valeurs ne contiennent ni `|` ni `:` (échappement non géré). Toutes les clés sont optionnelles. Le champ peut être vide (`""`). Convention détaillée ci-dessous. |

#### Convention du champ `meta`

Le champ `meta` est un format clés-valeurs léger, lisible par humain
et exploitable automatiquement. Toutes les clés ci-dessous sont
**optionnelles** ; le champ entier peut être vide.

| Clé | Description | Valeurs admises |
|---|---|---|
| `annotator` | Identifiant de la personne ou du système qui a créé/proposé la définition | Identifiant libre court (ex. `alice`, `marc`, `mere`, `papa`, `llm_synth_v1`) |
| `reviewed` | Indique si la définition a été relue par un autre annotateur | `true` ou `false` |
| `reviewer` | Identifiant du relecteur (renseigné si `reviewed:true`) | Identifiant libre court |
| `confidence` | Estimation qualité par l'annotateur | `low`, `medium`, `high` |
| `notes` | Texte libre additionnel (justification, contexte) | Texte libre sans `|` ni `:` |

**Exemples :**

```
meta = "annotator:alice|reviewed:true|reviewer:marc|confidence:high"
meta = "annotator:mere|notes:trouve trop dur"
meta = "annotator:llm_synth_v1|confidence:medium"
meta = ""
```

**Contraintes :**

- Séparateur entre paires : `|` (pipe)
- Liaison clé-valeur : `:`
- Les valeurs **ne contiennent ni `|` ni `:`** — l'échappement n'est
  pas géré pour rester simple
- Aucune clé n'est obligatoire ; le champ entier peut être vide
- Les clés non listées ci-dessus sont tolérées comme extensions
  projet, à condition de respecter le format `clé:valeur`

#### Encodage et séparateur

- **Encodage** : UTF-8 strict, normalisation **NFC** appliquée à
  toutes les chaînes
- **Séparateur de colonnes** : `;`
- **Échappement** : si une cellule contient `;`, `"` ou un saut de
  ligne, encadrer la cellule par `"…"` selon RFC 4180
- **Saut de ligne** : `\n` (LF) entre lignes
- **En-tête** : ligne 1 contenant les noms des 8 colonnes (ou 9 si
  `meta` est présent)

#### Lignes complètes d'exemple

> Format : `mot;definition;pos;categorie;style;force;longueur;source`

```
COQ;Mâle de la basse-cour;nom_commun;animals;périphrase;2;3;curated_v1
KO;Hors combat;sigle_abreviation;abbreviations;définition_directe;1;2;curated_v1
NO;Refus anglais;sigle_abreviation;etranger;définition_directe;2;2;curated_v1
LOU;Loup sans p;nom_commun;animals;cryptique_morphologique;4;3;curated_v1
TIBIA;Os de la jambe avant;nom_commun;body_parts;technique;4;5;synthetic_v1
RIMBAUD;Auteur du Bateau ivre;nom_propre;first_names;culturel;5;7;synthetic_v1
```

Les 4 premières lignes sont curées de `short_words_unified.csv` ; les
2 dernières sont des exemples synthétiques illustrant le futur dataset
4+ lettres avec ancres validées (§5.5, §5.6).

### 8.2 Règles de sortie d'inférence

Quand le LLM produit une définition en inférence (mode production
v1), la sortie attendue est **strictement la définition seule**, sans
décoration. Le pipeline en aval ajoute les autres champs (pos,
catégorie, style, force, longueur, source) sur la base de la consigne
d'inférence ou d'une étape d'annotation séparée.

**Contrat de sortie :**

- **Format** : la définition seule, en texte brut, sans préambule
  (« Voici la définition : … »), sans guillemets autour, sans balise
  XML/JSON
- **Encodage** : UTF-8 NFC. L'apostrophe peut être droite (U+0027) en
  sortie brute du modèle ; la normalisation en `’` (U+2019) est
  effectuée par le post-traitement §8.4
- **Pas de saut de ligne** dans la définition
- **Pas de marqueur** type « Définition : », « Réponse : », « → »
- **Pas de redite du mot** dans la sortie (sauf exceptions §1.1 :
  sigles, cryptique_morphologique)

**Exemple de prompt et sortie attendue :**

```
PROMPT  : Donne une définition mots fléchés pour le mot LUNE,
          style périphrase, force 2.
SORTIE  : Astre nocturne
```

Toute déviation par rapport à ce contrat est traitée par le pipeline
(§8.3) : soit normalisation auto-correctible, soit rejet.

### 8.3 Pipeline de filtrage automatique

Le pipeline filtre les sorties du LLM par étapes successives, du
moins coûteux au plus coûteux. Chaque étape rejette, normalise ou
remonte un warning ; le LLM-juge reste en dernière position pour
préserver le coût.

| # | Étape | Input | Opération | Output / Action | Renvoi |
|---|---|---|---|---|---|
| 1 | **Filtres typographiques stricts** | str brut | Détection emoji, gras markdown `**`, italique `*`, balises HTML, caractères non imprimables | reject si présent | §2.4, §6.8 |
| 2 | **Filtres caractères interdits** | str | Détection de caractères hors `[\p{L}\p{N}\s\p{P}]` standard | reject | §2.4 |
| 3 | **Filtres longueur** | str | Comptage mots (tokenisation simple sur espaces, apostrophes, tirets) | warning si > 8 mots ; reject si > 12 mots ou > 60 caractères | §1.3, §6.4 |
| 4 | **Filtres stéréotypes IA** | str | Regex sur préfixes proscrits (`^(Quelqu'un qui|Personne qui|Action de|Fait de|…)`) | reject immédiat sur match | §6.5 |
| 5 | **Filtres auto-référence** | str + mot | Stem matching case-insensitive NFD entre mot et définition ; exclusion via flag `pos = sigle_abreviation` ou `style = cryptique_morphologique` | reject si match et pas d'exception | §1.1, §6.1 |
| 6 | **Filtres langue FR/EN** | str | fasttext-langid sur la définition ; dictionnaire stop-words EN couramment confondus | reject si score FR < 0,8 ET tokens EN détectés sans marqueur §3.1 | §1.2, §6.2 |
| 7 | **Filtres tautologie / définitions vides** | str | Croisement avec liste fermée d'étiquettes catégorielles génériques (« Animal », « Prénom », « Plante », « Objet »…) ; détection paraphrase circulaire renforcée | reject si match strict ; warning si étiquette + 1 qualificatif faible | §1.7, §6.7 |
| 8 | **LLM-juge** | str + mot + métadonnées (pos, catégorie, style, force) | Évaluation sémantique : adéquation, accord §1.5, hallucination §6.3, style cohérent §4, force calibrée §5 | accept / reject / warning, avec justification | §1.5, §4, §5, §6.3, §6.6 |

**Normalisations** (cf. §8.4) sont appliquées **après** le pipeline
de filtrage : un input qui passe les filtres 1-8 est ensuite
normalisé pour la sortie finale.

**Coût relatif** des étapes : 1-7 sont en regex ou structures
légères (microsecondes par ligne) ; 8 est un appel LLM (~100 ms à
quelques secondes par ligne). Placer 8 en dernier réduit le coût
total d'un ordre de grandeur sur un grand corpus.

### filter_9_stem_leak — interdiction stem-leak (porté du lane MLX)

**Critère.** Rejette si un token de la définition partage un préfixe
≥ 5 caractères avec le lemme, OU est une sous-chaîne du lemme quand
les deux ont ≥ 5 caractères. Seuil délibéré à 5 chars : 4 catche
`couvrir → "Protéger avec une couverture"` mais déclenche sur les
affixes latins / romans (`pre-`, `con-`, `de-`, `re-`).

**Provenance.** Porté verbatim de
`scripts/eval/validate_clue.py::_find_stem_leak` (iter7+). Ne pas
abaisser le seuil sans rejouer la 5-sample variance check de iter7
(86.0 % ± 2.5 pp sur seeds 20260601-05).

### filter_10_pleonasm — interdiction pleonasm (porté du lane MLX)

**Critère.** Rejette si la définition matche un pattern de pleonasme
de l'ensemble fermé documenté ci-dessous : `Associer ensemble`,
`Monter en haut`, `Prévoir à l'avance`, etc. La liste vit en clair
dans `pipeline_v2/filters.py::_PLEONASM_PATTERNS`.

**Provenance.** Porté verbatim de
`scripts/eval/validate_clue.py::_find_pleonasm` (PR #192). Le pattern
set est fermé par construction : l'élargir par analogie a déjà causé
des faux positifs sur des définitions à deux syntagmes légitimes.
Toute extension exige une iter row dans `docs/eval/clue-gen-v0.md`
documentant le clue échec concret qui motive l'ajout.

### 8.4 Pipeline de post-traitement (normalisations)

Les normalisations sont appliquées en sortie finale du pipeline, après
les filtres §8.3. **Toutes les normalisations sont auto-correctibles
et jamais reject** : leur rôle est de produire un format propre, pas
d'écarter du contenu.

| # | Normalisation | Opération | Exemple |
|---|---|---|---|
| 1 | **Apostrophe droite → typographique** | `'` (U+0027) → `’` (U+2019) | « Forme d'avoir » → « Forme d’avoir » |
| 2 | **Espaces multiples → unique** | Sequence `\s+` → ` ` (un espace) | « Bonne   action » → « Bonne action » |
| 3 | **Trim début/fin** | Suppression `\s+` en début et en fin de chaîne | «   Carte forte   » → « Carte forte » |
| 4 | **Initiale en majuscule** | Premier caractère alphabétique mis en majuscule (sauf déjà en majuscule pour nom propre) | « mâle de la basse-cour » → « Mâle de la basse-cour » |
| 5 | **Suppression point final** | Point final supprimé (sauf abréviation médiane type « abrév. ») | « Carte forte. » → « Carte forte » |
| 6 | **Suppression guillemets enveloppants** | Guillemets `« … »` ou `"…"` englobant toute la définition supprimés | `« Carte forte »` (en tant que valeur cellule) → `Carte forte` |
| 7 | **Normalisation Unicode NFC** | `unicodedata.normalize("NFC", text)` | Séquences NFD → forme précomposée |
| 8 | **Suppression tabs et retours de ligne internes** | `\t`, `\n`, `\r` → espace ou suppression | « Carte\nforte » → « Carte forte » |

Le pipeline applique ces 8 normalisations en cascade dans cet ordre.
L'ordre minimise les interactions : la NFC en avant-dernier garantit
que les comparaisons en aval portent sur des chaînes canonicalisées.

### 8.5 Tests de validation

Batterie de 10 cas-tests minimaux qu'une définition doit passer pour
être acceptée dans le dataset final. Chaque test renvoie à la règle
§1-§6 qu'il enforce. Format exprimé en prose précise pour
réimplémentation autonome.

> Convention de notation : `def` désigne la définition à valider,
> `mot` désigne le mot cible, `len_words(def)` compte les mots
> séparés par espace/apostrophe/tiret, `len_chars(def)` compte les
> codepoints NFC.

**Test 1 — Aucun caractère interdit.** `def` ne contient ni emoji, ni
gras markdown `**`, ni italique `*`, ni balise HTML, ni caractère non
imprimable. *(§2.4, §6.8)*

**Test 2 — Longueur caractères dans [2, 60].** `2 ≤ len_chars(def) ≤ 60`.
*(§1.3, §6.4)*

**Test 3 — Nombre de mots ≤ 8.** `len_words(def) ≤ 8` (warning entre 8
et 12 ; reject au-delà). *(§1.3, §6.4)*

**Test 4 — Pas d'auto-référence (sauf exceptions).** Le mot n'apparaît
pas dans `def` en match case-insensitive NFD, **sauf si**
`pos = sigle_abreviation` ou `style = cryptique_morphologique`.
*(§1.1, §6.1)*

> **Note de méthode pour le Test 4.** Pour minimiser les faux positifs
> sur sous-chaîne fortuite (ex. RIO dans « carioca », IF dans
> « conifère »), la détection doit utiliser un boundary match (regex
> `\b` ou équivalent) plutôt qu'une simple recherche de sous-chaîne.
> Le radical doit former un mot ou un préfixe de mot pour être
> considéré comme auto-référence.

**Test 5 — Langue détectée = français.** Détection automatique sur
`def` donne score FR ≥ 0,8 (fasttext-langid), ou tokens étrangers
explicitement marqués via §3.1. *(§1.2, §6.2)*

**Test 6 — Initiale en majuscule, pas de point final.** `def[0]` est
en majuscule (sauf nom propre minuscule explicite) ; `def[-1]` n'est
ni `.` ni `;` ni `:`. *(§1.6, §2.4 règle 3, §6.8)*

**Test 7 — Apostrophe typographique.** Toute occurrence d'apostrophe
dans `def` est `’` (U+2019), aucune `'` (U+0027). *(§2.4 règle 9)* Si
violation, normalisation automatique §8.4 #1, pas de reject.

**Test 8 — Pas de préfixe stéréotype IA.** `def` ne commence par
aucun des préfixes proscrits §6.5 (« Quelqu'un qui », « Personne
qui », « Action de », « Fait de », « Chose qui sert »…). *(§6.5)*

**Test 9 — Champ `pos` dans l'énumération §7.1.** Vérification que la
valeur de la colonne `pos` est dans
`{verbe_infinitif, verbe_conjugue, participe_passe, participe_present,
nom_commun, nom_propre, adjectif, adverbe, interjection, mot_outil,
sigle_abreviation, autre}`. *(§7.1)*

**Test 10 — Champs `categorie` et `style` dans leurs énumérations.**
`categorie` est l'une des 43 catégories §7.2 ; `style` est l'un des 9
styles §4. *(§4, §7.2)*

**Cohérence transverse :**

- `force` est un entier dans [1, 5] *(§5)*
- `longueur` est égale à `len(unicodedata.normalize("NFC", mot))`
  *(§8.1)*
- `mot` respecte la regex `^[A-ZÀÁÂÄÆÇÉÈÊËÎÏÔÖŒÙÛÜŸ’\-]+$` *(§8.1)*

Ces 10 tests + 3 cohérences transverses forment la base de validation
automatique du dataset. Toute extension future (nouvelles catégories,
nouveaux POS, nouveaux styles) doit respecter ces invariants ou
mettre à jour explicitement la spec §8.

---

Fin du Style Guide v1. Document complet : préambule + 8 sections,
~2648 lignes markdown. Sert de référentiel pour :
(a) la génération synthétique du dataset,
(b) l'annotation humaine,
(c) le LLM-juge en évaluation,
(d) le pipeline de filtrage et de normalisation.

---
