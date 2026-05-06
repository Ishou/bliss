# Themed word categories — corpus enrichment + grid generation cap

Date: 2026-05-06
Status: Draft (auto-mode authored)
Branch: `feat/grid-themed-words` (to be created)
Author: Claude (autonomous session)

## 1. Problem

Mots-fléchés grids on the production corpus are dominated by short specialty
words at length-2/3 slots: chemical symbols (`AG`, `AL`, `FE`), Roman
numerals (`II`, `IV`, `XII`), title abbreviations (`DR`, `MR`, `MME`).
There's no mechanism to limit how many of each category appear in one grid,
so a small grid can end up filled with `AG / FE / II / DR` — recognizable
shapes but low-quality solver experience.

## 2. Goal

1. Tag each `Word` with at most one theme drawn from a closed set of ~8
   categories (Roman numerals, chemical symbols, title abbreviations,
   country codes, interjections, musical notes, units, single letters).
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
- **No per-API theme cap override** initially. Defaults are baked into
  `defaultPuzzleConstraints()`; lobby owners get whatever defaults ship.
  Surface as override later if there's demand.
- **No proper-noun themes** (cities, people, mythology). Those need real
  hand-curation and aren't auto-detectable; defer to a future iter.
- **No retroactive cap enforcement on existing committed grids.** Caps
  apply only to future generations.

## 4. Architecture

### 4.1 Theme set (closed)

| theme | matcher | examples | typical-grid cap |
|---|---|---|---|
| `roman` | regex `^[IVXLCDM]+$` and value parses | II, IV, XII | 1 |
| `chem` | closed list of ~118 IUPAC symbols (uppercased) | AG, AU, FE, NA | 2 |
| `abbrev` | closed list of ~30 French title abbreviations | DR, MR, MME, PR, PROF | 2 |
| `country` | closed list of ~250 ISO-3166-1 alpha-2 + alpha-3 codes | FR, US, ITA, DEU | 1 |
| `interjection` | closed list of ~25 French interjections | AH, OH, EH, BAH, FI | 1 |
| `note` | fixed 7 entries | DO, RE, MI, FA, SOL, LA, SI | 1 |
| `unit` | closed list of ~30 SI / common units | KG, KM, KO, GO, CV, MWH | 1 |
| `letter` | regex `^[A-Z]$` (single-letter words) | A, B, … Z | 1 |

The closed lists live in a single Kotlin file
(`grid/domain/.../ThemeCatalog.kt`) — pure data, no runtime deps.

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

Equality / hashCode generated from `data class` semantics; theme
participates in equality, which means dedup keyed on `(text, lemma)`
needs to ignore theme (handled in dedup sets, not at object equality).

### 4.3 Theme detection — `ThemeDetector`

```kotlin
object ThemeDetector {
    fun detect(text: String): String? = when {
        text in chemSymbols -> "chem"
        text in countryCodes -> "country"
        text in titleAbbreviations -> "abbrev"
        text in interjections -> "interjection"
        text in notes -> "note"
        text in unitSymbols -> "unit"
        text.length == 1 && text.first() in 'A'..'Z' -> "letter"
        text.matches(romanRegex) -> "roman"
        else -> null
    }
}
```

Order matters when categories overlap: more specific first
(`chem` before `letter` for words like `H`, `O`, `K`, `S`, `B`, `Y`,
`U`, `V`, `W`, `I` — chem symbol wins; the cap on `chem` is what we
care about). Curated rows with explicit theme override the detector.

### 4.4 Domain: `GridConstraints.themeLimits`

```kotlin
data class GridConstraints(
    val width: Int,
    val height: Int,
    val minWordLength: Int = 2,
    val themeLimits: Map<String, Int> = DEFAULT_THEME_LIMITS,  // ← new
)

val DEFAULT_THEME_LIMITS: Map<String, Int> = mapOf(
    "roman" to 1,
    "chem" to 2,
    "abbrev" to 2,
    "country" to 1,
    "interjection" to 1,
    "note" to 1,
    "unit" to 1,
    "letter" to 1,
)
```

A theme not present in the map = uncapped. A theme with cap 0 = banned
(not a typical use case but the data type allows it).

### 4.5 `SkeletonFiller` cap enforcement

The fill phase tracks per-theme counts during search. After the existing
intersection forward-check, before recursion:

```kotlin
// New: theme-cap check.
val theme = word.theme
if (theme != null) {
    val cap = themeLimits[theme] ?: Int.MAX_VALUE
    if ((themeUsed[theme] ?: 0) >= cap) {
        // Try next word; this one violates the cap.
        // (Counted as a constraint-driven skip, not a backtrack.)
        continue@wordLoop
    }
}
```

Increment `themeUsed[theme]` on placement, decrement on backtrack
(symmetric with `usedWords` / `usedLemmas`). The check is O(1) per word
and fits the existing structure.

**Important:** cap-violations should be filtered earlier — at
`domainFor` — so MRV doesn't pick a slot whose only candidates are all
cap-busted. Do the filter inside `domainFor`'s post-find filter chain
(adjacent to `usedWords` / `compact` filters).

### 4.6 Curated CSV schema change

`data/curated/fr.csv` gains a `theme` column (added between
`source_license` and `lemma` to keep `lemma` as the trailing existing
column). Existing 64 rows backfilled — most rows have a clear theme
(`AG → chem`, `AL → chem`, `AN → unit` — `Année`).

New curated rows added (~50-100 total) for the missing categories:
- ~30 country codes that aren't already covered (FR, US, etc. — already in?
  let me check; if not, add them)
- ~10 interjections (AH, OH, EH, BAH, FI, NA, GUE, …)
- 7 notes (DO, RE, MI, FA, SOL, LA, SI)
- ~20 units (KG, KM, KO, GO, CV, MO, NO, OZ, MWH, KWH, etc.)
- A few more abbreviations to round out coverage.

### 4.7 Runtime CSV (`words-fr.csv`)

Schema gets a new column `theme` between `compact` and (if last) end.
Existing rows: `theme` is empty (= no theme). At export time
(`bliss-worker export-words`), `ThemeDetector.detect` runs over the
output and fills theme for rows where the curated source didn't supply
one. Existing `compact`-style migration pattern handles backward compat.

### 4.8 `CsvWordRepository` change

The repository reads the `theme` column when present (defaults to `null`
for older CSVs). Theme passes through into the indexed `Word` objects.
The `byLengthPosLetter` index is unchanged — theme isn't part of the
slot-pattern lookup; it's a post-find filter at fill time.

## 5. Components (~ size estimate)

- `grid/domain/.../ThemeCatalog.kt` — closed lists + the detector.
  ~100 lines (mostly data).
- `grid/domain/.../Word.kt` — add field. ~5 lines.
- `grid/domain/.../GridConstraints.kt` — add field + default. ~15 lines.
- `grid/domain/.../SkeletonFiller.kt` — theme-cap check in `domainFor` +
  themeUsed tracking in `search`. ~25 lines.
- `grid/domain/.../GenerationMetrics.kt` — optional: count cap-driven
  skips for the perf bench. ~5 lines.
- `grid/infrastructure/.../CsvWordRepository.kt` — read theme column.
  ~10 lines.
- `grid/worker/.../ExportWordsCommand.kt` — write theme column;
  call `ThemeDetector.detect` for rows without explicit theme. ~10 lines.
- `data/curated/fr.csv` — schema change + new rows. ~80 lines (50-100 new
  rows + the 64 backfilled existing rows get a theme column).
- Tests: `ThemeDetectorTest.kt` (~30 lines), `SkeletonFillerThemeTest.kt`
  (~80 lines).

Total: ~350-400 lines of code + ~80 CSV lines.

## 6. Data flow

```
data/curated/fr.csv (theme column, hand-curated)
                          │
                          ▼
worker import → clue_candidates (theme passes through if available)
                          │
                          ▼
worker export-words →   for each row:
                          if curated theme: keep
                          else: ThemeDetector.detect(word.text)
                        →
words-fr.csv (theme column populated)
                          │
                          ▼
CsvWordRepository       reads theme into Word.theme
                          │
                          ▼
GridGenerator           SkeletonFiller filters domain by cap;
                        usedThemes tracks per-grid counts
                          │
                          ▼
generated grid          respects the per-theme caps
```

## 7. Tests

**New:**
- `ThemeDetectorTest` — every theme has positive examples; collisions
  resolved per spec ordering; `null` for arbitrary strings.
- `SkeletonFillerThemeTest` — small in-memory repository with exactly N
  words at theme T, build a skeleton with M slots (M > cap_T), assert
  that no more than cap_T slots get the T-themed words; assert
  backtracking still works (cap is reversible).
- `WordRepositoryTest` — CsvWordRepository round-trips theme column;
  rows without theme get `null` (not empty string).

**Existing tests that need updating:**
- `WordTest` (if any) — new field with default `null` shouldn't break
  existing constructor calls thanks to the default.
- `GridGeneratorPropertyTest` — should still pass; default theme limits
  shouldn't make any test grid infeasible.

## 8. Rollout — single PR

This is small enough for one PR (~400 lines code + 80 CSV). Branch
`feat/grid-themed-words`. Title: `feat(grid-lexicon): theme-tagged
words + per-grid theme caps`.

Tests in the same PR.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Theme cap makes some grids infeasible (no valid fill exists) | Caps interact with the early-abandon retry from PR #201 — a cap-blocked attempt fails fast and the next seed retries. Worst case: the success rate drops slightly. Run the bench before/after to quantify. |
| Auto-detection mis-tags a word (e.g. `MI` flagged as note when intended as adj) | The hand-curated row's explicit theme overrides the detector. For the LoRA-generated lemma corpus, mis-tags are tolerable — they just impose an unwarranted cap that's mostly invisible. |
| Schema change breaks production runtime CSV | The new `theme` column is appended (backward compat); rows missing it parse as `null`. Existing API consumers don't touch the column. |
| Curation work is heavier than estimated | Drop the 4.6 corpus expansion to a follow-up PR; ship the mechanism first, expand later. |

## 10. Spec self-review

Placeholder scan: no TBD / TODO. Internal consistency: §4.4's
`themeLimits: Map<String, Int>` matches §4.5's lookup. Scope: 400 LOC +
80 CSV is tight for a single PR; if the curation work blows up, split
the corpus expansion into a follow-up. Ambiguity: §4.3 spells out
detection precedence (chem before letter). §4.4 says "theme not in map
= uncapped"; explicit, no surprise.
