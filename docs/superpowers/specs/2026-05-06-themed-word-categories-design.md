# Themed word categories — corpus enrichment + grid generation cap

Date: 2026-05-06
Status: Implemented
Branch: `feat/grid-themed-words`
Author: Claude (autonomous session)

## 1. Problem

Mots-fléchés grids on the production corpus are dominated by short specialty
words at length-2/3 slots: chemical symbols (`AG`, `AL`, `FE`), Roman
numerals (`II`, `IV`, `XII`), title abbreviations (`DR`, `MR`, `MME`).
There's no mechanism to limit how many of each category appear in one grid,
so a small grid can end up filled with `AG / FE / II / DR` — recognizable
shapes but low-quality solver experience.

## 2. Goal

1. Tag each `Word` with at most one theme drawn from a closed set of 8
   categories (Roman numerals, chemical symbols, title abbreviations,
   country codes, interjections, musical notes, units, compass bearings).
2. Add per-grid theme caps (e.g. "at most 1 Roman numeral per grid")
   enforced during the CSP fill.
3. Expand the curated corpus with categories that don't yet have entries
   (countries, interjections, musical notes, units) — the theme-tagging
   work is the natural forcing function.

Success signal: visually inspect 20 generated 10×10 grids before/after.
Before: many grids contain ≥3 chem symbols. After: ≤2 chem, plus more
diverse short-word categories overall.

## 3. Non-goals

- **No multi-valued themes per word.** Real conflicts (e.g. `LA` = note
  vs determiner) are <5 edge cases; pick the more user-recognizable one.
- **No auto-detection.** The user explicitly rejected a detector — themes
  apply only to **hand-curated** entries authored as themed lists. We do
  not infer themes from word shape, regex, or closed-set membership at
  load time. A word ships with a theme iff it appears in a per-theme
  curated CSV.
- **No per-API theme cap override** initially. Defaults are baked into
  `GridConstraints.DEFAULT_THEME_LIMITS`; lobby owners get whatever
  defaults ship. Surface as override later if there's demand.
- **No proper-noun themes** (cities, people, mythology). Those need real
  hand-curation and aren't in scope here; defer to a future iter.
- **No retroactive cap enforcement on existing committed grids.** Caps
  apply only to future generations.

## 4. Architecture

### 4.1 Theme set + per-theme CSVs

Each theme is one curated CSV under
`grid/api/src/main/resources/words/themed/<theme>.csv` — the classpath
path is the canonical editorial location. Same schema as the main
`data/curated/fr.csv` (no `theme` column in the schema — the file path
*is* the theme). The repository overlays the theme onto the matching word
at load time.

| theme | curated file | examples | typical-grid cap |
|---|---|---|---|
| `roman` | `roman.csv` | II, III, IV, VI, …, XV, XX, XXX | 1 |
| `chem` | `chem.csv` | AG, AU, FE, NA, HG, … | 2 |
| `abbrev` | `abbrev.csv` | DR, MR, PR, ST, MME, MLLE, PROF | 2 |
| `country` | `country.csv` | FR, US, UK, ITA, DEU, CAN, … | 1 |
| `interjection` | `interjection.csv` | AH, OH, EH, BAH, ZUT, OUF | 1 |
| `note` | `note.csv` | DO, RE, MI, FA, SOL, UT | 1 |
| `unit` | `unit.csv` | KG, KM, KO, GO, CV, MWH, KWH | 1 |
| `compass` | `compass.csv` | NE, NO, SO, ENE, NNO, SSE, … | 1 |

Constants for theme keys live in `Themes.kt` (`Themes` object) so
callers reference `Themes.ROMAN` rather than the raw `"roman"` string.

### 4.2 Domain: `Word.theme: String?`

`Word` gains a single nullable column. Default `null` (no theme = uncapped).
Constructor + factory take an optional `theme` param. The validation
already in `Word.init` stays unchanged; theme is informational, not a
correctness invariant.

```kotlin
data class Word private constructor(
    val text: String,
    val definition: String,
    val lemma: String,
    val compact: Boolean = true,
    val theme: String? = null,   // ← new
)
```

### 4.3 Domain: `GridConstraints.themeLimits`

```kotlin
data class GridConstraints(
    val width: Int,
    val height: Int,
    val minWordLength: Int = 2,
    val themeLimits: Map<String, Int> = DEFAULT_THEME_LIMITS,  // ← new
)

val DEFAULT_THEME_LIMITS: Map<String, Int> = mapOf(
    Themes.ROMAN to 1,
    Themes.CHEM to 2,
    Themes.ABBREV to 2,
    Themes.COUNTRY to 1,
    Themes.INTERJECTION to 1,
    Themes.NOTE to 1,
    Themes.UNIT to 1,
    Themes.COMPASS to 1,
)
```

A theme not present in the map = uncapped. A theme with cap 0 = banned.
The map is the **only** runtime configuration surface for caps; callers
override per-grid by passing a custom map (e.g. `DEFAULT_THEME_LIMITS +
mapOf(Themes.CHEM to 0)` to ban chem entirely for one grid).

### 4.4 `SkeletonFiller` cap enforcement

`fill()` initialises a `themeUsed: HashMap<String, Int>` shared across the
backtracking search. `domainFor()` filters cap-busted candidates out of
each slot's domain (so MRV doesn't pick a slot whose only candidates are
all cap-busted). `search()` increments `themeUsed[theme]` on placement
and decrements on backtrack — symmetric with `usedWords` / `usedLemmas`.

Cost: O(1) per word at filter time, O(1) per placement/backtrack. Fits
the existing structure.

### 4.5 `CsvWordRepository` change

The repository's `frenchFromClasspath()` factory now:

1. Loads the main `words/words-fr.csv` as before.
2. For each `(theme, path)` in `FRENCH_THEMED_OVERLAY_PATHS`, loads the
   themed CSV and **overlays** the theme onto matching words. A match is
   `(text, lemma)` equality. Overlay-only words (themed entries not
   present in the main CSV) are appended to the tail.
3. Returns the merged list.

The `byLengthPosLetter` index is unchanged — theme isn't part of the
slot-pattern lookup; it's a post-find filter at fill time.

The main `words-fr.csv` schema is **not** changed. Themes live entirely
in the per-theme overlays.

## 5. Components

- `grid/domain/.../Themes.kt` — `Themes` constants object only
  (no detector). ~30 lines.
- `grid/domain/.../Word.kt` — add `theme: String?` field. ~5 lines.
- `grid/domain/.../GridConstraints.kt` — add `themeLimits` + default
  map. ~15 lines.
- `grid/domain/.../SkeletonFiller.kt` — `themeUsed` map + filter in
  `domainFor` + symmetric inc/dec in `search`. ~25 lines.
- `grid/domain/.../GridGenerator.kt` — pass `constraints.themeLimits`
  to `SkeletonFiller.fill`. 1 line.
- `grid/infrastructure/.../CsvWordRepository.kt` — `loadThemeOverlays`
  + `FRENCH_THEMED_OVERLAY_PATHS` + overlay merge in
  `frenchFromClasspath`. ~50 lines.
- `grid/api/src/main/resources/words/themed/{roman,chem,abbrev,country,interjection,note,unit,compass}.csv`
  — 8 new files, ~120 rows total. This directory is the canonical editorial
  location; curators edit here directly.
- Tests: `SkeletonFillerThemeTest.kt` (4 cases: cap-of-1 forces
  alternative, cap-of-0 bans, missing-theme-uncapped, backtrack-symmetric).

Total: ~150 lines of code + ~120 CSV rows.

## 6. Data flow

```
grid/api/src/main/resources/words/themed/<theme>.csv  (hand-curated, canonical)
                          │
                          ▼
CsvWordRepository.frenchFromClasspath
   - load main words-fr.csv
   - for each (theme, path) overlay: tag matching words, append extras
                          │
                          ▼
GridGenerator           SkeletonFiller filters domain by cap;
                        themeUsed tracks per-grid counts
                          │
                          ▼
generated grid          respects the per-theme caps
```

## 7. Tests

**New:**
- `SkeletonFillerThemeTest` — 4 cases on tiny hand-built slot graphs +
  in-memory `ListWordRepository`:
  - cap of 1 forces the second slot off the themed candidate
  - cap of 0 with only themed candidates fails the fill
  - theme without an entry in limits map is uncapped
  - theme counter symmetric across backtracks

Slots in these tests are non-intersecting parallel `DOWN_RIGHT` slots so
the tiny candidate set doesn't have to agree on cross-letters.

**Existing tests:**
- `Word` constructor calls keep working thanks to `theme = null` default.
- `GridGeneratorPropertyTest` — still passes; default theme limits don't
  make any test grid infeasible.

## 8. Rollout — single PR

Branch `feat/grid-themed-words`. Title: `feat(grid-lexicon): theme-tagged
words + per-grid theme caps`. Tests in the same PR.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Theme cap makes some grids infeasible | Caps interact with the early-abandon retry from PR #201 — a cap-blocked attempt fails fast and the next seed retries. Worst case: success rate drops slightly. Run the bench before/after to quantify. |
| Curated theme list mis-classifies an entry (e.g. `LA` could be note or determiner) | Per-theme files are hand-authored; if a string is ambiguous, leave it off the themed list and it stays uncapped. The relevance is editorial, not algorithmic. |
| Per-theme CSVs drift from main `fr.csv` (a themed entry has different definition than the main row) | Repository overlay is by `(text, lemma)`; on match it sets the theme but does not overwrite definition. Overlay-only entries (no main-CSV match) ship with the themed CSV's definition. |
| Schema change to main CSV | None. The main `words-fr.csv` schema is unchanged; themes live entirely in the per-theme overlays. |

## 10. Pivot history

The first iteration of this design (sections 4.3–4.7 in the previous
revision) included an auto-detector (`ThemeDetector`) that classified
words by closed-list membership at load time, with curated rows able to
override the detector. The user rejected this approach during
implementation: themes should apply **only** to hand-curated entries
explicitly placed in per-theme CSVs. The detector and the
`words-fr.csv`-side schema change were removed; this revision documents
the implemented design.
