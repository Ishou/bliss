# Pipeline_v2 pilot validation

> **Lane scope:** Pipeline_v2 calibration report for the Modal lane
> gold pilot v1 corpus, ported from bliss-clue-ai's
> `docs/pipeline_test_pilot_v1.md`. This logbook entry sits alongside
> the MLX-lane logbook `docs/eval/clue-gen-v0.md`; the two are
> independent but the MLX-lane stem-leak / pleonasm cases that
> motivated pipeline_v2 filters 9 + 10 cross-reference there.

## §9. Calibration update after bliss pilot run

_To be filled in after the maintainer runs `modal run modal_jobs/03b_finetune.py`
end-to-end on the fused corpus and re-runs pipeline_v2 against the
resulting clue candidates. Track any additional rejects, warnings, or
threshold recalibrations here so the Modal-lane logbook stays current._

---

# Rapport pipeline §8.3 + §8.4 — test sur gold pilote v1

## 1. Résumé exécutif

**Double validation confirmée** : les 114 lignes du gold pilote v1
passent la pipeline sans aucun reject, warning ou normalisation
appliquée (114/114 accept clean), et la batterie de 27 tests négatifs
déclenche correctement chaque filtre et chaque normalisation
(27/27 OK). La pipeline est opérationnelle, calibrée juste, et le gold
pilote est propre à 100 %. Deux bugs d'implémentation détectés et
corrigés au cours du test (filtre 2 NFD, calibrage du seuil lingua),
illustrant le rôle indispensable des tests négatifs.

## 2. Contexte

- **Date du test** : 2026-05-24
- **Pipeline testée** : §8.3 (8 filtres) + §8.4 (8 normalisations)
- **Fichier d'entrée** : `data/seed/gold_pilot_v1.csv` (114 lignes,
  43 mots uniques)
- **Fichier de sortie** : `data/seed/gold_pilot_v1_pipeline.csv`
  (114 lignes augmentées de 3 colonnes pipeline)
- **Suite de tests négatifs** : `scripts/pipeline/test_negative_cases.py`
  (27 cas, réutilisable)
- **Version pipeline** : v1 — première implémentation conforme au
  style_guide.md §8.3-§8.4

### Objectif

Double validation par confrontation : (a) vérifier que le gold est
propre (peu/pas de rejets attendus), (b) vérifier que la pipeline est
calibrée juste — ni trop stricte (faux positifs sur gold) ni trop
laxiste (cas problématiques non détectés). Un test qui ne détecte rien
ne prouve pas que les filtres fonctionnent ; les tests négatifs sont
indispensables pour valider la calibration.

## 3. Résultats sur le gold (114 entrées)

### Filtres §8.3 — 114 lignes traitées

| Filtre | Accept | Warning | Reject |
|---|---:|---:|---:|
| `filter_1_typographiques` | 114 | 0 | 0 |
| `filter_2_caracteres_interdits` | 114 | 0 | 0 |
| `filter_3_longueur` | 114 | 0 | 0 |
| `filter_4_stereotypes_ia` | 114 | 0 | 0 |
| `filter_5_auto_reference` | 114 | 0 | 0 |
| `filter_6_langue_fr` | 114 | 0 | 0 |
| `filter_7_tautologie` | 114 | 0 | 0 |
| `filter_8_llm_juge_mock` | 114 | 0 | 0 |

### Normalisations §8.4

| Normalisation | Lignes affectées |
|---|---:|
| `norm_1_apostrophe` | 0 |
| `norm_2_espaces_multiples` | 0 |
| `norm_3_trim` | 0 |
| `norm_4_initiale_majuscule` | 0 |
| `norm_5_point_final` | 0 |
| `norm_6_guillemets_enveloppants` | 0 |
| `norm_7_nfc` | 0 |
| `norm_8_tabs_newlines` | 0 |

**Conclusion** : le gold pilote est propre à 100 %. Aucune
normalisation n'a été nécessaire (apostrophes typographiques, NFC,
casse, ponctuation déjà conformes au style guide §2.4). Aucun filtre
n'a déclenché de warning ou reject.

## 4. Tests négatifs (27 cas)

### Filtres §8.3 — 19 tests, tous OK

| Filtre | # Tests | Cas testés |
|---|---:|---|
| `filter_1_typographiques` | 3 | emoji 🍞, gras markdown `**`, balise HTML `<b>` → tous rejetés ✓ |
| `filter_3_longueur` | 2 | 12+ mots → reject ; 9 mots ≤ 60 chars → warning ✓ |
| `filter_4_stereotypes_ia` | 3 | « Action de », « Quelqu'un qui », « Chose qui » → tous rejetés ✓ |
| `filter_5_auto_reference` | 4 | exact match, match accent-strip (pommé→pomme), faux positif RIO/carioca (accept), exception cryptique_morphologique (accept) ✓ |
| `filter_6_langue_fr` | 2 | « The friendly cat at home », « Bread of the bakery » → tous rejetés ✓ |
| `filter_7_tautologie` | 2 | « Animal » nu → reject ; « Animal commun » → warning ✓ |
| `filter_8_llm_juge_mock` | 3 | pos / style / force invalides → tous rejetés ✓ |

### Normalisations §8.4 — 8 tests, tous OK

| Normalisation | Test |
|---|---|
| N1 apostrophe | `Forme d'avoir` → `Forme d’avoir` ✓ |
| N2 espaces multiples | `Aliment  de  boulangerie` → `Aliment de boulangerie` ✓ |
| N3 trim | `  Aliment de boulangerie  ` → `Aliment de boulangerie` ✓ |
| N4 initiale majuscule | `aliment de boulangerie` → `Aliment de boulangerie` ✓ |
| N5 point final | `Aliment de boulangerie.` → `Aliment de boulangerie` ✓ |
| N6 guillemets | `« Aliment de boulangerie »` → `Aliment de boulangerie` ✓ |
| N7 NFC | NFD `Épaule` → NFC `Épaule` ✓ |
| N8 tabs/newlines | `Aliment\nde boulangerie` → `Aliment de boulangerie` ✓ |

**Conclusion** : la pipeline détecte correctement les 27 cas
problématiques (rejet, warning, ou normalisation selon le cas). Aucun
filtre ni normalisation ne reste silencieux face à un input qui devrait
le déclencher.

## 5. Bugs découverts et corrigés pendant les tests

### 5.1 Filtre 2 et formes NFD-décomposées

**Symptôme initial.** Le test N7 (`Épaule` en NFD → NFC) échouait à
l'étage du filtre 2 (caractères interdits), avant d'atteindre la
normalisation `norm_7_nfc`. La cause : Python `re.\w` ne matche pas
les combining marks (catégorie Unicode `Mn`), donc une chaîne NFD avec
diacritiques décomposés (E + U+0301 + paule) contenait des caractères
hors regex `\w`.

**Correction.** Pré-normalisation NFC dans
`filter_2_caracteres_interdits` avant le check des caractères
autorisés. Robustesse augmentée sans violation de la spec §8.4 (qui
spécifie l'ordre des normalisations finales, pas l'interdiction d'une
pré-NFC défensive en filtre).

**Leçon.** Le test sur gold (où tout est déjà NFC) n'aurait jamais
révélé ce bug : seul le test négatif délibéré sur NFD l'a fait
émerger. C'est exactement la raison d'être des tests négatifs.

### 5.2 Filtre 6 et seuils lingua

**Symptôme initial.** L'implémentation initiale du filtre 6 utilisait
les seuils `FR < 0.5 ET EN > 0.5` suggérés dans la spec. Sur le gold,
**deux faux positifs** ont émergé :
- `GLACE → « S'y regarder le matin »` : lingua FR=0.49 EN=0.51
- `CHAPEAU → « Salut du gentleman »` : lingua FR=0.44 EN=0.56

Les deux définitions sont parfaitement françaises : la première est
un syntagme court qui devient ambigu pour le modèle ; la seconde
contient « gentleman », emprunt anglais lexicalisé en français qui
remonte le score EN.

**Correction.** Seuils calibrés à `FR < 0.3 ET EN > 0.7`. Marge de
sécurité contre les segments FR courts ambigus, tout en préservant la
détection de l'anglais clair :
- « The friendly cat at home » : FR=0.034 EN=0.966 → reject ✓
- « Bread of the bakery » : FR=0.020 EN=0.980 → reject ✓
- Cas borderline gold : FR≥0.44 EN≤0.56 → accept ✓

**Leçon.** La spec initiale (`0.5/0.5`) semblait raisonnable mais
était trop stricte pour les définitions courtes typiques de mots
fléchés. Les valeurs ont été ajustées au vu des données réelles. Sans
le test confrontant la pipeline au gold, le déploiement aurait causé
des rejets injustifiés en production.

## 6. Choix méthodologiques

### 6.1 Détecteur de langue (filtre 6)

**Bibliothèque retenue** : `lingua-language-detector` v2.2.0
(installé via `pip install lingua-language-detector`). Détecteur
configuré pour deux langues uniquement (`French`, `English`) afin de
maximiser la précision sur le cas d'usage.

**Alternatives évaluées et écartées** :
- `ftlangdetect` / `fasttext` : installé mais cassé par
  incompatibilité numpy 2.x (erreur `np.array(obj, copy=False)`
  obsolète). Fallback nécessaire.
- Heuristique stopwords EN : conservée comme voie de secours dans le
  code (si lingua devient indisponible), mais non utilisée en
  production.

**Seuils EXACTS utilisés** :
- `reject` si **FR < 0.3 ET EN > 0.7**
- `accept` sinon

Ces seuils sont calibrés sur le gold (114 lignes FR, toutes acceptées)
et sur les négatifs (2 lignes EN, toutes rejetées). Voir §5.2 pour
l'historique du calibrage.

### 6.1 bis — Calibration du filtre 6 (lingua) — recalibrage des seuils

**Seuils initiaux** : `FR < 0.5 ET EN > 0.5` (reject)
**Seuils retenus** : `FR < 0.3 ET EN > 0.7` (reject)

**Justification du recalibrage** : les seuils initiaux ont produit
**2 faux positifs sur le gold pilote** (sur 114 entrées) :

1. **GLACE → « S'y regarder le matin »**
   - Lingua FR = 0.4947, EN = 0.5053 (marge 0.011)
   - Faux positif par **brièveté** : 4 mots seulement, lingua hésite
     sur si peu de matériel linguistique

2. **CHAPEAU → « Salut du gentleman »**
   - Lingua FR = 0.4429, EN = 0.5571 (marge 0.114)
   - Faux positif par **emprunt lexicalisé** : « gentleman » est
     attesté en français standard depuis le 19e siècle, présent dans
     Le Robert et Le Petit Robert sans marqueur d'anglicisme requis

Les deux définitions sont 100 % valides selon §1.2 (langue
exclusivement française) et §3.1 (marqueurs de langue étrangère non
requis pour les emprunts lexicalisés).

**Marge de sécurité du nouveau seuil** :

| Distribution | Score FR pire cas | Marge au seuil FR<0.3 |
|---|---:|---:|
| Pire faux positif gold (CHAPEAU) | 0.4429 | 0.143 (≈ 32 %) |
| Pire vrai négatif tests (Bread of the bakery) | 0.020 | 0.280 (≈ 93 %) |

Le seuil 0.3 est bien placé entre les deux distributions sans
chevauchement. Recalibrage validé empiriquement.

**À surveiller pendant la campagne contributeurs** : si les
contributeurs produisent des emprunts plus marqués ou des définitions
très courtes (1-2 mots), revoir les seuils. Possibilités d'évolution :

- Abaisser le seuil minimum de tokens (actuellement 3 implicitement
  par lingua) ou définir une condition spéciale `len(tokens) < 3 →
  accept par défaut`.
- Utiliser une analyse mot par mot pour identifier les emprunts
  lexicalisés du français (gentleman, parking, week-end, stop,
  match…) et les exclure du calcul lingua.
- Ajouter une whitelist d'emprunts français reconnus, consultée
  avant le filtre langue.

### 6.2 Auto-référence (filtre 5)

**Mécanique** : boundary match `\b` sur le mot normalisé NFD avec
strip des diacritiques. Le strip d'accents permet de détecter les
variantes morphologiques courantes (POMME → « pommé » est rejeté car
pommé → pomme après strip), tout en évitant les faux positifs sur
sous-chaîne fortuite (RIO → « carioca » est accepté car `\brio\b` ne
matche pas la sous-chaîne « rio » dans « carioca »).

**Exceptions** :
- `pos = sigle_abreviation` : développement des initiales légitime
  (§1.1 seconde exception)
- `style = cryptique_morphologique` : l'opération sur la graphie est
  la mécanique du jeu (§1.1 première exception)

### 6.3 LLM-juge (filtre 8)

**Implémentation** : mock — pas d'appel API réel. Validation des
enums métadonnées (pos / catégorie / style / force) uniquement, sans
heuristique d'accord §1.5 active (trop de faux positifs sur cas
frontière).

## 7. Limitations connues

- **Filtre 6 non testé sur français très court ou franglais.** Les
  cas comme « week-end », « parking », « stop », « OK » lexicalisés
  en français pourraient déclencher lingua. À tester pendant la
  campagne contributeurs et ajuster si besoin (ajout d'une whitelist
  ou augmentation du seuil EN).
- **LLM-juge en mock (étape 8 §8.3)** : implémentation réelle à faire
  avec un appel API (Anthropic ou OpenAI) quand budget disponible.
  Le mock actuel ne couvre pas les checks sémantiques (adéquation
  mot/définition, hallucination factuelle §6.3, cohérence d'accord
  §6.6).
- **Tests négatifs : 27 cas, à enrichir.** Cas absents :
  - Combinaisons multi-filtres (e.g. emoji + auto-référence + long)
  - Faux négatifs subtils (e.g. auto-référence cachée par flexion)
  - Cas frontières du filtre 7 (étiquette générique + 2+ qualificatifs)
- **Stop-words EN du fallback heuristique non exhaustifs** : ~35
  mots, à élargir si lingua devient indisponible et que le fallback
  est sollicité en production.

## 8. Recommandations

1. **Pipeline prête pour la campagne 500 mots.** Aucun ajustement
   immédiat nécessaire. La pipeline est calibrée juste et le gold
   pilote sert de référence stable.
2. **Maintenir la suite de tests négatifs** au fil des évolutions.
   Toute modification d'un filtre ou d'une normalisation doit être
   accompagnée d'un test négatif correspondant. Exécuter
   `python3 scripts/pipeline/test_negative_cases.py` en CI.
3. **Re-tester filtre 6 sur la campagne réelle** : surveiller les
   rejets sur les premières 100-200 productions contributeurs. Si
   le taux de faux positifs dépasse 1 %, recalibrer les seuils ou
   ajouter une whitelist d'emprunts français (gentleman, parking,
   week-end, etc.).
4. **Implémenter le vrai LLM-juge** quand la campagne dépasse les
   1000 entrées. Le mock suffit pour les volumes pilote mais
   l'évaluation sémantique automatique deviendra critique pour
   maintenir la qualité à grande échelle.
5. **Documenter dans le style guide** la calibration finale des
   seuils lingua (§8.3 filtre 6) et la pré-normalisation NFC dans
   le filtre 2 (§8.3 étape 2), pour traçabilité.
