# Stale-clue leak in runtime CSV — session notes (2026-05-07)

User-reported failure: `compliqué → "Ajouté de la difficulté"` ships in the
runtime `words-fr.csv`. The clue treats `compliqué` as a past participle of
`compliquer` ("had difficulty added to it"), but the bare crossword surface
points at the stative adjective reading ("Difficile, complexe").

Investigation revealed the immediate cause is a leak, not a v7 model gap.
The deeper design questions (PP framings, adj-lane) remain open but are
narrower in scope than initially thought.

## Diagnostic taxonomy

For ppas surfaces of verb lemmas, the lemma clue's syntactic shape
determines whether the bare-PP head-only inflation produces a natural
reading. Five buckets:

| bucket | shape | example | bare-PP reading |
|---|---|---|---|
| `reflexive` | `Se …` / `S'…` | `Se déplacer vite` → `couru` | broken (gated) |
| `dobj-bare` | head + bare det + N | `Faire un trou` → `troué` | broken (gated) |
| `dobj-partitive` | head + `de la`/`de l'` + N | `Ajouter de la difficulté` → `compliqué` | **broken (NOT gated)** |
| `oblique` | head + bridge prep + N | `Cuire au four` → `cuit` | works |
| `intransitive` | head alone / head + adv | `Cacher` → `caché` | mostly works |

`dobj-partitive` is the v7 gap: the existing `_has_verb_dobj_frame` detector
in `scripts/eval/inflect_clue.py:145` explicitly excludes `de la` / `de l'`
because they're treated as bridge preps, by analogy with `Munir d'un trou`
→ `Munie d'un trou`. The analogy doesn't hold for action-event verbs whose
explicit partitive direct object can't fold into a passive-state reading.

Diagnostic implementation: `scripts/eval/classify_ppas_framing.py`.

## What was actually wrong

Running the classifier against the runtime CSV before any fix:

| bucket | groups | surfaces | %-surf |
|---|---|---|---|
| reflexive | 137 | 137 | 3.5% |
| dobj-bare | 845 | 862 | 22.3% |
| dobj-partitive | 62 | 71 | 1.8% |
| oblique | 791 | 865 | 22.4% |
| intransitive | 1841 | 1929 | 49.9% |
| **total** | **3676** | **3864** | |

`reflexive` (137 surfaces) and `dobj-bare` (862 surfaces) should not be
shipping at all — both are gated by `pp-reflexive-skipped` and
`pp-only-skipped` in the v7 inflater. Their presence in runtime smelled
like leaked stale clues.

Confirmed: `compliquer`'s lemma clue has filter score `0.5741`, below the
0.65 ship threshold. v7 dropped it in `surface_clues_dropped.csv` —
correctly. Yet the runtime row carried "Ajouté de la difficulté" anyway.

Root cause: `scripts/clue_generation/merge_clues_into_wordlist.py` had a
docstring–code mismatch.

> Docstring (line 5–6): "Other rows keep the placeholder (clue == word)
> so generation still works."

The code only **wrote** clues for surfaces present in `surface_clues.csv`;
it did not **reset** clues for surfaces missing from the shipped output.
Surfaces that v7 dropped (filter score below threshold) silently retained
clues from prior iters' merge runs.

## Fix

`merge_clues_into_wordlist.py` now resets `clue` to the placeholder
convention (`clue == word`) for any `source = grammalecte` row whose
surface is missing from `surface_clues.csv`. Curated `source = bliss`
rows (CC0 short-fr / roman numerals — held in `bliss-worker`'s curated
lane) are preserved untouched.

Re-running merge:

```
updated 18718/46663 rows (40.1%)        ← matches v7 commit's headline
reset to placeholder (stale clue scrub): 27538
```

Re-running the classifier post-fix:

| bucket | before | after | leak rate |
|---|---|---|---|
| dobj-bare | 862 | 6 | 99% |
| dobj-partitive | 71 | 10 | 86% |
| reflexive | 137 | 0 | 100% |
| oblique | 865 | 169 | 80% |
| intransitive | 1929 | 1226 | 36% |
| **total ppas with clue** | **3864** | **1411** | **63%** |

User's specific complaints all reverted to placeholder:

```
compliqué,fr,9,1235101,0.8962632,compliqué,grammalecte,MPL-2.0,compliquer,true
compliquée,fr,10,1321313,0.91290903,compliquée,grammalecte,MPL-2.0,compliquer,true
couru,fr,5,729921,0.803929,couru,grammalecte,MPL-2.0,courir,true
```

`pytest scripts/eval/` — 104/104 pass.

## What remains genuine v7 failure mode (not leak)

After the scrub, the residual broken-clue counts give the actual surface
area of the problem v7 didn't solve:

- **dobj-partitive: ~10 surfaces** (genuinely shipped). Examples:
  - `puisé / puisée / puisés / puisées → "Tiré(e/s/es) de l'eau"` (puiser, 4 surfaces)
  - `revalorisé / -ée / -és / -ées → "Redonné(e/s/es) de la valeur"` (revaloriser, 4)
  - `éclos → "Émergé de l'œuf"` (éclore — debatable, être-PC unaccusative reads OK)

  Fix is small: widen `_has_verb_dobj_frame` to detect `de` followed by
  `la` / `l'` / `les`. ADR-0024-style closed-set extension; same in spirit
  as v7's existing gate.

- **dobj-bare: 6 edge cases** that slipped past `pp-only-skipped`. Mostly
  malformed clues (`philosopher → "Sage du su"` is a noun clue, not a
  verb clue). Not a structural issue with the gate.

- **intransitive: 1226 surfaces.** Mixed:
  - Single-word PP synonyms (`Caché`, `Choquée`, `Concédées`) read cleanly
    as adjectival passives. Working as designed.
  - **New failure mode**: multi-head clues (V1 + et + V2) where the
    head-only inflater conjugates V1 to ppas but leaves V2 verbatim.
    Examples:
    `attrapé → "Saisi et retenir"`,
    `polis → "Lustres et rendre brillant"`,
    `juger → "Évaluée et prononcer un verdict"`.
    These are LoRA-corpus issues (the model emits coordinated verbs
    instead of single-head clues for some lemmas). Either a DPO theme
    against V+et+V framing, or an inflater extension that walks all
    heads.

## Open design questions (deferred)

The PP-framing redesign sketched in chat — dual-lane (verb-PP + adj-lane)
with active-PC framing for intransitive ppas (`couru → "S'est déplacé
vite"`) — remains the right long-term answer for **populating** ppas
surfaces that currently revert to placeholder. The leak fix only stops
us shipping wrong clues; it doesn't fill the gap.

Numbers for sizing that work:
- ~3879 ppas surfaces total in runtime
- 1411 currently have a clue (post-fix)
- 2468 are placeholder

Of the 2468 placeholders, an unknown fraction would be clue-able by:
1. Adj-lane clues for ppas-adj forms whose stative reading dominates
   (`compliqué`, `gelé`, `usé`, `prononcé`).
2. Active-PC framing for active-verb ppas
   (`couru → "S'est déplacé vite"`, `mangé → "A consommé"`).
3. ipre-3sg framing for surfaces tagged both `ppas` and `ipre 3sg`
   (`cuit`, `prend`, `vend`).

Sizing the buckets requires a separate diagnostic on the placeholder
rows. Not done in this session.

## Files changed

- `scripts/clue_generation/merge_clues_into_wordlist.py` — source-aware
  scrub of stale clues.
- `scripts/eval/classify_ppas_framing.py` — new diagnostic.
- `grid/api/src/main/resources/words/words-fr.csv` — regenerated. Stale
  v3-era PP clues now revert to placeholder; v7 clues unchanged; curated
  `source=bliss` rows untouched.

## Next session

In priority order:

1. **Widen `_has_verb_dobj_frame`** to catch `de la`/`de l'`/`les`
   partitive after head verb. ~10-surface impact today; protects against
   regression on future iters.
2. **Strip V+et+V framing** from LoRA training data (DPO theme), or
   route those rows to dropped at corpus-build time. Affects an unknown
   fraction of the 1226 intransitive surfaces.
3. **Sizing diagnostic** for the 2468 ppas placeholders by bucket
   (adj-lane / active-PC / ipre-3sg / no-clue-possible).
4. Then decide whether the dual-lane / multi-framing redesign is worth
   the iter15 corpus + inflater work, or whether hosted-Haiku top-up via
   `bliss-worker generate-clues` is sufficient for the high-value subset.
