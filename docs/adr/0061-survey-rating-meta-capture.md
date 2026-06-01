# ADR-0061: Survey rating meta capture — bottom-up freeform glosses + sub-tags

## Status
Accepted

## Context

Per ADR-0056 the survey context owns ratings of candidate clues at
`/sondage`. Today's row schema carries `pos`, `categorie` (48 values),
`style`, `forceClaimed`, `longueur`. The CSV export
(`StyleGuideCsvWriter.kt`) already reserves an empty pipe-delimited
`meta` column for forward expansion.

Two pressures argue for richer per-rating annotation now:

1. **Gold-window economics.** The 2026-05-30 cutoff (ADR-0056 amendment
   for per-row training weights) means every maintainer correctif from
   here on is training-quality signal. The marginal cost of capturing
   extra meta during a rating is small compared to the cost of re-rating
   later when training wants the meta but it isn't there.
2. **Sense ambiguity is currently silent.** A polysemous lemma like
   `chat` or `banque` is generated against an unspecified sense; raters
   conflate "wrong sense" with "bad clue" in a single qualité score.
   Without a target-sense annotation we can't distinguish a clue that's
   bad-in-its-own-right from a clue that's fine but landed on the wrong
   sense — and we can't condition the generator on the sense we want.

### Options considered for the sense inventory

| Option | Pros | Cons |
|---|---|---|
| **Bottom-up freeform glosses (chosen)** | Zero license risk; clue-targeting granularity; matches maintainer mental model; per-lemma sense vocabulary grown from real ratings | No external KB linkage; needs autocomplete + soft normalization to avoid drift |
| DBnary sense IDs (CC BY-SA) | Rich coverage, machine-readable, joinable to existing DBnary data | ShareAlike contagion on training corpus; senses too fine-grained for clue use; many dead-weight sense IDs maintainer never uses |
| WOLF / WoNeF synsets (CeCILL-C) | License-permissive, ML-friendly identifiers | PWN-derived senses are poor fit for clue disambiguation; coverage uneven on everyday French; same dead-weight problem |
| Maintainer-curated controlled vocab up front | Predictable schema, model-trainable | Bootstrap cost; foreseeably wrong taxonomy; defeats the "build-as-you-go" goal |

### Why bottom-up freeform

DBnary is permitted under ADR-0058 with the CC BY-SA posture, but using
its sense IDs as input conditioning for the trained model would taint
the resulting weights with SA obligations on what is otherwise a
commercial product. WOLF/WoNeF avoid that contagion but their
PWN-derived granularity is the wrong shape — clue authors care about
"river bank vs financial institution", not Princeton-WordNet
hypernym-trees.

Freeform glosses sidestep both problems. The maintainer types what they
mean (`"animal félin"`, `"conversation digitale"`); autocomplete keeps
the vocabulary consistent across ratings of the same lemma; soft
normalization (strip determiners, lowercase, strip diacritics) prevents
near-duplicate drift at the matching layer while storing the original
spelling. The resulting per-lemma sense inventory is exactly the size
and shape the corpus actually needs.

Sub-domain tags follow the same pattern but at the lemma level: a
multi-label freeform list that layers on top of `categorie` (e.g.
`categorie=animaux` + `subTags=[felin, mammifere, domestique]`),
reused across all clues for that word.

## Decision

Extend the survey context with two structured meta surfaces:

1. **Per-rating sense glosses** — `ratings.target_senses JSONB`
   (list of freeform strings). Multi-select to encode calembours; a
   single-sense clue carries one gloss, a pun carries two or more.
2. **Per-lemma word meta** — new table `survey_word_meta(mot, sub_tags,
   sense_inventory, updated_at)`. `sub_tags` is the maintainer's
   multi-label taxonomy on top of `categorie`. `sense_inventory` is a
   rolling list of gloss strings observed across all ratings of this
   lemma — the source of truth for autocomplete suggestions.

Both columns are JSONB to keep the schema light while the vocabulary is
discovered.

**Gloss authoring rules** (binding on the UI):

- Glosses are freeform French strings authored by the rater.
- Soft normalization for autocomplete and inventory-merge dedup only:
  strip leading determiner (`/^(l['’]|la |le |les )\s*/i`), NFD,
  strip combining marks, lowercase, collapse whitespace. Display and
  storage retain the original.
- A gloss must not repeat the lemma. `"animal félin"` is correct;
  `"chat (animal félin)"` is rejected client-side (the lemma is already
  in the row — repetition would force the trained model to learn to
  ignore the duplicate).

**Difficulty UI**: the existing 1–5 difficulté field defaults to `3`
on the slider so quick raters who agree with "medium" don't need to
click. Storage unchanged.

**Out of scope**: external sense IDs (no DBnary / WOLF binding),
free-text catch-all rater note (sense gloss serves that intuition),
alt-clue suggestions, register / connotation enums, cultural-knowledge
flag, vocab-vs-clue difficulty split. Any of these may be added later
behind their own ADR amendment.

**Auth posture**: `GET /v1/lemma-meta/{mot}` is open (matches the
existing `/v1/survey-items` posture under ADR-0048 CORS); the
sub-tag upsert path is maintainer-only via the role check landed
under ADR-0060. The sense-inventory side-effect on rating submit
piggybacks on the existing rating auth path.

## Threat Model

Incremental over ADR-0056's STRIDE coverage — only the deltas
introduced by the new endpoints and JSONB columns are restated here.

**Information disclosure — `GET /v1/lemma-meta/{mot}`.** The response
carries the maintainer-curated `subTags` list and the rolling
`senseInventory` for a single French lemma. No PII, no rater identity,
no rating values, no per-user data. The contents are vocabulary the
maintainer would publish openly in a clue style guide anyway. Auth
posture matches the existing `/v1/survey-items` reads (open under
ADR-0048 CORS); no new disclosure surface beyond that precedent.

**Enumeration / probe risk — `{mot}` path parameter.** The endpoint is
keyed by an attacker-controlled French surface form. Worst case: an
attacker walks a ~100k-word dictionary against the endpoint and learns
which lemmas have non-empty meta. That signal is equivalent to "which
words the maintainer has rated", which is already inferable from
`/v1/survey-items` traffic patterns. Existing ingress rate limiting
(`limit-rps: 5`, `limit-connections: 30`, ADR-0056) bounds the
dictionary-walk to ~18k probes/hour per IP. No new mitigation required.

**Tampering — sub-tag upsert under a bypassed role check.** If the
maintainer role gate on `PUT /v1/lemma-meta/{mot}` were circumvented
(e.g. an identity-api role-cache race, an ADR-0060 implementation
defect), an attacker could inject garbage sub-tags. The blast radius
is the maintainer's curated taxonomy, not the trained model — training
reads sub-tags as auxiliary signal at corpus-build time, and the
maintainer inspects the corpus before kicking off a training run.
Mitigations baked into the schema: (a) merge-only semantics (no
delete via API in this iteration); (b) `updated_at` provides a
forensic trail for manual revert; (c) the role gate reuses the
identity-api role-cache plumbing whose threat model lives in
ADR-0060, not a new bespoke check.

**Repudiation / poisoning — sense-inventory side-effect.** Glosses
flow into `survey_word_meta.sense_inventory` as a side-effect of
`POST /v1/ratings/{itemId}` when the body carries `targetSenses`.
A bad-actor could flood the inventory with garbage strings, degrading
autocomplete signal quality. The attack surface inherits the existing
rate-limited rating endpoint and the partial-unique
`(item_id, user_id)` index from ADR-0056. The merge is by normalized
key, so trivial variants collapse rather than each adding noise.
No new elevation-of-privilege surface beyond the rating path.

## Consequences

**Easier:**
- Polysemous-clue evaluation becomes precise: "given target sense X,
  was this clue good?" replaces a quality score that conflated
  sense-correctness with overall quality.
- LoRA / DPO can condition on the sense gloss as input. The base model
  (`command-r-08-2024`) already knows French polysemy structurally;
  fine-tuning just teaches the control surface, which transfers from
  hundreds of examples — full sense coverage is not required.
- Sub-tag mining grows a useful secondary taxonomy without committing
  to one up front.
- No DBnary SA contagion in the training corpus or model weights.

**Harder:**
- The rating UI grows two chip-style inputs and a sense-inventory
  fetch. Per-rating time-on-task increases; the maintainer has
  explicitly opted into this trade.
- Gloss drift is possible if normalization misses an equivalence
  (regional spelling, hyphenation). Mitigated by autocomplete
  surfacing prior glosses at typing time; a real drift pattern
  surfacing after the first few hundred ratings becomes a follow-up
  promotion to a structured field.
- The maintainer is the only practical author of sub-tags for now;
  if the fleet-rater pattern grows we'll need to open the upsert
  endpoint up under a follow-up amendment with a moderation story.

**Migration shape**: V10 pure-expand (add column to `ratings`, add new
`survey_word_meta` table, both default-empty). No backfill, no contract
phase needed — existing rows carry empty lists and remain valid.

## Amendment (2026-06-01) — high-level taxonomy + all meta per-rating

Hands-on use of the shipped UI surfaced three model errors. This
amendment corrects them; the bottom-up-freeform-gloss and
no-external-KB decisions above stand unchanged.

### What was wrong

1. **`Categorie` was at the wrong altitude.** The 48-value enum was
   derived from a 114-definition gold pilot. In practice it is both
   *too large to scan and select from* during a rating and *still
   incomplete* — it is a flat list mixing genuine high-level classes
   (`ARTS`, `NATURE_PAYSAGE`) with sub-tag-grade detail
   (`ROMAN_NUMERALS`, `MUSIC_NOTES`, `COUNTRY_CODES`, `CARDINAL_POINTS`),
   and it is entirely noun-centric — nothing tags an answer that is a
   verb or an adjective, which are a large share of *mots fléchés*
   answers.
2. **`survey_word_meta` (per-lemma) did not match how the maintainer
   works.** Rating happens one definition at a time; the maintainer
   thinks "this definition, the answer is `chat`-the-animal, tag it",
   not "let me go edit the word `chat`". Sub-tags and sense belong on
   the rating, not on a per-word side-table.
3. **Multi-select sense list went unused.** A clue targets one sense.
   The only multi-sense case is a deliberate pun (calembour), which
   needs a *marker*, not an enumerated list — enumerating the senses of
   a pun adds authoring cost with no training signal the single flag
   doesn't already carry.

### Decision (amended)

**1. Replace the 48-value `Categorie` with 18 high-level classes + `AUTRE`.**
The rule: a category names a *broad class* in one word; all granularity
moves to free-form sub-tags. The set spans nouns, verbs and adjectives:

`PERSONNE`, `FAUNE_FLORE`, `GEOGRAPHIE`, `METEO`, `OBJET`, `NOURRITURE`,
`CORPS`, `CULTURE`, `HISTOIRE`, `JEU`, `SPORT`, `RELIGION`, `SOCIETE`,
`SCIENCE`, `CONCEPTUEL`, `LANGUE`, `ACTION`, `QUALIFICATIF`, `AUTRE`.

`ACTION` (answer is a verb) and `QUALIFICATIF` (answer is an adjective)
are net-new and close the noun-only gap. The goal is the *smallest set
that almost never forces `AUTRE`* while staying scannable.

Old → new collapse (drives the data migration of `survey_items.categorie`):

| New class | Absorbs (old 48) |
|---|---|
| `PERSONNE` | FIRST_NAMES, TITLES, MYTHOLOGY, PROFESSIONS, FAMILLE_RELATIONS |
| `FAUNE_FLORE` | ANIMALS, FLORE |
| `GEOGRAPHIE` | CARDINAL_POINTS, CITIES, COUNTRIES, COUNTRY_CODES, GEOGRAPHY, NATURE_PAYSAGE |
| `METEO` | METEO_CLIMAT |
| `OBJET` | VETEMENTS, MOBILIER_OBJET, OUTILS, TRANSPORTS, MATERIAUX |
| `NOURRITURE` | ALIMENTS |
| `CORPS` | BODY_PARTS, SENSES |
| `CULTURE` | ARTS, MUSIC_NOTES |
| `HISTOIRE` | *(net-new)* |
| `JEU` | CARD_GAME, GAMES |
| `SPORT` | *(net-new)* |
| `RELIGION` | *(net-new; mythology stays under PERSONNE)* |
| `SOCIETE` | CURRENCIES, ORGANIZATIONS |
| `SCIENCE` | CHEMICAL_SYMBOLS, UNITS, CELESTIAL_OBJECTS, NOMBRES, ROMAN_NUMERALS |
| `CONCEPTUEL` | SENTIMENTS_ETATS, TEMPS_DUREE, COULEURS |
| `LANGUE` | ABBREVIATIONS, ETRANGER, EXPRESSIONS, GRAMMAR, INTERJECTIONS, ORTHOGRAPHE |
| `ACTION` | *(net-new)* |
| `QUALIFICATIF` | *(net-new)* |
| `AUTRE` | AUTRE |

The granular old values become sub-tag *suggestions* (e.g. `felin`,
`capitale`, `note de musique`, `symbole chimique`), not enum members.

**2. All meta moves onto the rating row; `survey_word_meta` is dropped.**
New `ratings` columns:
- `target_categories JSONB` — list of `Categorie` names; the maintainer's
  authoritative, **editable multi-select**. Pre-filled in the UI from the
  item's single machine prior (see below) but freely overridden.
- `target_sense TEXT NULL` — single freeform sense gloss (gloss rules
  above still bind: no determiner-only normalization at storage, must not
  repeat the lemma).
- `is_multisense BOOLEAN NOT NULL DEFAULT false` — the calembour marker.
  When `true`, `target_sense` is optional and the clue is understood to
  play on several senses at once.
- `sub_tags JSONB` — free-form multi-label refinement, per-rating.

The old per-rating `target_senses JSONB` list is **removed** (replaced by
`target_sense` + `is_multisense`).

**3. `survey_items.categorie` stays, as a single machine prior.**
It is migrated in place via the collapse table (old value → new class;
unmapped/unknown → `AUTRE`). It remains the ingest-time machine guess and
the pre-fill seed for the per-rating multi-select, so unrated items keep
category coverage for downstream generation. The per-rating
`target_categories` is the human source of truth where present.

**4. Autocomplete now reads back from prior ratings.**
With `survey_word_meta` gone, `GET /v1/lemma-meta/{mot}` becomes an
aggregation query: distinct `target_sense` values and distinct `sub_tags`
across prior ratings of items whose `mot` matches, soft-normalized for
dedup. `priorSenses` / `priorSubTags` response shape is unchanged.
`PUT /v1/lemma-meta/{mot}` and `SubTagsRequest` are **removed** — sub-tags
are no longer upserted per-word; they are authored per-rating. The
sense-inventory side-effect in `SubmitRatingUseCase` is removed.

### Consequences (amended)

- **Dropped surface**: `survey_word_meta` table, `WordMeta` aggregate,
  `WordMetaRepository` / `PgWordMetaRepository` / in-memory fake,
  `UpsertSubTagsUseCase`, `PUT /v1/lemma-meta`, `SubTagsRequest`. This is
  a teardown of code that landed in #719–#724 days earlier; acceptable
  because no production ratings carry the old shape yet (gold window
  opens 2026-05-30; this is the same maintainer iterating pre-data).
- **Migration shape**: V11 is *not* pure-expand. It drops a table and a
  column and rewrites `survey_items.categorie` values. Safe because the
  affected data is pre-gold sandbox data with no external consumers.
- **Tag granularity is now uniform**: one place (sub_tags, per-rating)
  carries all refinement; categories are a stable, trainable, low-card
  control surface. Generation conditioning gets a clean 19-way class plus
  open-vocabulary sub-tags, instead of a 48-way class with no verb/adj
  coverage.
