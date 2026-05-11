# Integrated Grid Generator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the two-phase `SlotPlanner.planVariable` + `SkeletonFiller.fill` with a single integrated backtracking search that commits `(arrow, length, word)` atomically, so the planner can never produce a CSP the filler then fails on.

**Architecture:** `PlanState` is extended with letter/placement/dedup/theme state and an atomic `commit(arrow, length, word, clue)` method. A new `IntegratedSearch` class runs MRV-arrow-ordered backtracking with strict-descending length preference and frequency-descending head-shuffled words. `GridGenerator` switches to call it; old `SkeletonFiller` and `SlotPlanner.planVariable`/`solveVariable` + their internal tests are deleted in the same wave.

**Tech Stack:** Kotlin, JUnit 5, assertk, Gradle (Spotless on Kotlin). No new dependencies.

**Spec:** `docs/superpowers/specs/2026-05-11-integrated-generator-design.md`.

---

## File Structure

**Modify:**
- `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/PlanState.kt` — add `letters`, `placements`, `usedWords`, `usedLemmas`, `themeUsed` fields; add `commit(arrow, length, word, clue)` and `patternFor(arrow, length)` methods.
- `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/GridGenerator.kt` — swap `SlotPlanner.planVariable` + `SkeletonFiller(...).fill` for `IntegratedSearch(...).solve`.
- `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/SlotPlanner.kt` — delete `planVariable`, `solveVariable`, `planFullLength`, `letterCells` (no callers after switch). Keep `validLengths`, `corpusAwareLengthPolicy`, `orphanSafeLengths`, `orderForBias`.

**Create:**
- `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/IntegratedSearch.kt` — the unified backtracking search + helpers (`filterCandidates`, `pickClue`, `headShuffle`).
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/IntegratedSearchTest.kt` — unit tests for the new search.

**Delete (after switch):**
- `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/SkeletonFiller.kt`
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerTest.kt`
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerCooldownTest.kt`
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerThemeTest.kt`
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SlotPlannerVariableTest.kt`

**Touch (test additions):**
- `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/PlanStateTest.kt` — add tests for `commit`, `patternFor`, rollback of new state.

---

## Task 1 — Extend `PlanState` with integrated-search state

**Files:**
- Modify: `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/PlanState.kt`
- Test: `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/PlanStateTest.kt`

Pure additive change. No existing behaviour modified — `SkeletonFiller` and `planVariable` still exist and still work. This task just adds the new fields and methods so Task 2 can use them.

### Step 1 — Add the failing tests to `PlanStateTest`

Open `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/PlanStateTest.kt`. Add these imports at the top alongside the existing ones (keep alphabetical):

```kotlin
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
```

Append these tests inside the existing `PlanStateTest` class (just before the closing brace):

```kotlin
    @Test
    fun `commit places a slot and writes letters`() {
        val state = PlanState(width = 5, height = 5)
        val arrow = ClueArrow(Position(Row(0), Column(0)), Direction.DOWN_RIGHT)
        state.addClueCell(arrow.cluePosition)
        state.addArrow(arrow.cluePosition, arrow.direction)

        val word = Word(text = "MOTS", lemma = "MOT", clues = listOf(WordClue(text = "Paroles")))
        val ok = state.commit(arrow, length = 4, word = word, clue = word.clues.first())

        assertThat(ok).isTrue()
        assertThat(state.letters[Position(Row(1), Column(0))]).isEqualTo('M')
        assertThat(state.letters[Position(Row(1), Column(1))]).isEqualTo('O')
        assertThat(state.letters[Position(Row(1), Column(2))]).isEqualTo('T')
        assertThat(state.letters[Position(Row(1), Column(3))]).isEqualTo('S')
        assertThat(state.placements).hasSize(1)
        assertThat(state.placements[0].word.text).isEqualTo("MOTS")
        assertThat(state.usedWords).containsExactly("MOTS")
        assertThat(state.usedLemmas).containsExactly("MOT")
    }

    @Test
    fun `commit fails when a letter conflicts with an existing one at the same cell`() {
        val state = PlanState(width = 5, height = 5)
        val a = ClueArrow(Position(Row(0), Column(0)), Direction.DOWN_RIGHT)
        val b = ClueArrow(Position(Row(0), Column(0)), Direction.RIGHT_DOWN)
        state.addClueCell(a.cluePosition)
        state.addArrow(a.cluePosition, a.direction)
        state.addArrow(b.cluePosition, b.direction)

        val cp1 = state.checkpoint()
        // First slot places 'M' at (1, 0) and 'O' at (1, 1)...
        val wordA = Word(text = "MO", lemma = "MO", clues = listOf(WordClue(text = "anything")))
        assertThat(state.commit(a, length = 2, word = wordA, clue = wordA.clues.first())).isTrue()

        // Second slot starts at (0, 1) DOWN. Its first letter is (1, 1). If we
        // try a word whose first char is not 'O', the commit must fail.
        val cp2 = state.checkpoint()
        val arrowDown = ClueArrow(Position(Row(0), Column(0)), Direction.RIGHT_DOWN)
        // RIGHT_DOWN's first letter is (0, 1); index 1 in this slot lands at (1, 1).
        val wordC = Word(text = "AB", lemma = "AB", clues = listOf(WordClue(text = "x")))
        // AB at (0, 1) DOWN → (0,1)='A', (1,1)='B'. (1,1) already 'O' → conflict.
        val ok = state.commit(arrowDown, length = 2, word = wordC, clue = wordC.clues.first())
        assertThat(ok).isFalse()
        state.rollback(cp2)
        // Other slot still intact.
        assertThat(state.placements).hasSize(1)
        assertThat(state.letters[Position(Row(1), Column(1))]).isEqualTo('O')

        state.rollback(cp1)
        assertThat(state.placements).isEmpty()
    }

    @Test
    fun `commit increments themeUsed only when the clue carries a theme`() {
        val state = PlanState(width = 5, height = 5)
        val arrow = ClueArrow(Position(Row(0), Column(0)), Direction.DOWN_RIGHT)
        state.addClueCell(arrow.cluePosition)
        state.addArrow(arrow.cluePosition, arrow.direction)

        val themedClue = WordClue(text = "Direction", theme = "compass")
        val word = Word(text = "NORD", lemma = "NORD", clues = listOf(themedClue))

        assertThat(state.commit(arrow, length = 4, word = word, clue = themedClue)).isTrue()
        assertThat(state.themeUsed["compass"]).isEqualTo(1)
    }

    @Test
    fun `commit does not touch themeUsed when the chosen clue has no theme`() {
        val state = PlanState(width = 5, height = 5)
        val arrow = ClueArrow(Position(Row(0), Column(0)), Direction.DOWN_RIGHT)
        state.addClueCell(arrow.cluePosition)
        state.addArrow(arrow.cluePosition, arrow.direction)

        val plainClue = WordClue(text = "Mots prononcés")
        val word = Word(text = "MOTS", lemma = "MOT", clues = listOf(plainClue))

        assertThat(state.commit(arrow, length = 4, word = word, clue = plainClue)).isTrue()
        assertThat(state.themeUsed).isEmpty()
    }

    @Test
    fun `rollback after commit restores all integrated-search state`() {
        val state = PlanState(width = 5, height = 5)
        val arrow = ClueArrow(Position(Row(0), Column(0)), Direction.DOWN_RIGHT)
        state.addClueCell(arrow.cluePosition)
        state.addArrow(arrow.cluePosition, arrow.direction)

        val cp = state.checkpoint()
        val themedClue = WordClue(text = "Direction", theme = "compass")
        val word = Word(text = "NORD", lemma = "NORD", clues = listOf(themedClue))
        assertThat(state.commit(arrow, length = 4, word = word, clue = themedClue)).isTrue()

        state.rollback(cp)

        assertThat(state.letters).isEmpty()
        assertThat(state.placements).isEmpty()
        assertThat(state.usedWords).isEmpty()
        assertThat(state.usedLemmas).isEmpty()
        assertThat(state.themeUsed).isEmpty()
    }

    @Test
    fun `patternFor reads known letters at slot positions`() {
        val state = PlanState(width = 5, height = 5)
        val a = ClueArrow(Position(Row(0), Column(0)), Direction.DOWN_RIGHT)
        val b = ClueArrow(Position(Row(0), Column(0)), Direction.RIGHT_DOWN)
        state.addClueCell(a.cluePosition)
        state.addArrow(a.cluePosition, a.direction)
        state.addArrow(b.cluePosition, b.direction)

        // Place a horizontal word on row 1 → letters at (1,0)..(1,3).
        val word = Word(text = "MOTS", lemma = "MOT", clues = listOf(WordClue(text = "x")))
        assertThat(state.commit(a, length = 4, word = word, clue = word.clues.first())).isTrue()

        // For the RIGHT_DOWN slot (first letter at (0,1), DOWN), at length 4:
        // its positions are (0,1), (1,1), (2,1), (3,1). Index 1 lands on (1,1) which has 'O'.
        val pattern = state.patternFor(b, length = 4)
        assertThat(pattern[1]).isEqualTo('O')
        assertThat(pattern[0]).isNull() // (0, 1) is still UNSET (the planner hasn't placed there)
    }
```

### Step 2 — Run tests, confirm red

```bash
./gradlew :grid:domain:compileTestKotlin
```
Expected: compilation errors referencing `state.commit`, `state.letters`, `state.placements`, `state.usedWords`, `state.usedLemmas`, `state.themeUsed`, `state.patternFor` — none of these exist yet.

### Step 3 — Add the new fields to `PlanState`

Open `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/PlanState.kt`. Add these imports near the top, alongside the existing ones:

```kotlin
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import com.bliss.grid.domain.model.WordPlacement
```

After the existing `private val _slots: ArrayList<WordSlot> = ArrayList()` and its public accessor, but before `private val undo: ArrayDeque<() -> Unit>`, insert:

```kotlin
    private val _letters: HashMap<Position, Char> = HashMap()
    val letters: Map<Position, Char> get() = _letters

    private val _placements: ArrayList<WordPlacement> = ArrayList()
    val placements: List<WordPlacement> get() = _placements

    private val _usedWords: HashSet<String> = HashSet()
    val usedWords: Set<String> get() = _usedWords

    private val _usedLemmas: HashSet<String> = HashSet()
    val usedLemmas: Set<String> get() = _usedLemmas

    private val _themeUsed: HashMap<String, Int> = HashMap()
    val themeUsed: Map<String, Int> get() = _themeUsed
```

### Step 4 — Add `patternFor` to `PlanState`

In `PlanState.kt`, after `availableLength(arrow)` and before `arrowState(pos, direction)`, insert:

```kotlin
    /**
     * Build the position-index → letter pattern for a candidate slot rooted at
     * [arrow] with [length] cells. Only positions whose [letters] entry is set
     * contribute to the pattern. Used by the integrated search to query the
     * word repository for matches.
     */
    fun patternFor(
        arrow: ClueArrow,
        length: Int,
    ): Map<Int, Char> {
        val first = firstLetter(arrow)
        val dr = arrow.direction.step.row.value
        val dc = arrow.direction.step.column.value
        val pattern = HashMap<Int, Char>(length)
        for (i in 0 until length) {
            val r = first.row.value + dr * i
            val c = first.column.value + dc * i
            val ch = _letters[Position(Row(r), Column(c))] ?: continue
            pattern[i] = ch
        }
        return pattern
    }
```

### Step 5 — Add `commit` to `PlanState`

In `PlanState.kt`, after the `materialize(...)` method (its closing brace) and before `nextPending()`, insert:

```kotlin
    /**
     * Atomically commit (arrow, length, word, clue) — the integrated search's
     * one structural-mutation step. Builds on [materialize] for cell/arrow
     * bookkeeping, then writes letters, records the placement, and updates the
     * dedup + theme counters.
     *
     * Returns `false` on any structural conflict (letter conflict at an
     * intersection, trailing-clue lands on a letter, etc.). Partial mutations
     * remain in the undo log; caller must [checkpoint] before and [rollback]
     * on false.
     */
    fun commit(
        arrow: ClueArrow,
        length: Int,
        word: Word,
        clue: WordClue,
    ): Boolean {
        require(word.text.length == length) {
            "word length ${word.text.length} must equal slot length $length"
        }
        if (!materialize(arrow, length)) return false

        val first = firstLetter(arrow)
        val dr = arrow.direction.step.row.value
        val dc = arrow.direction.step.column.value
        for (i in 0 until length) {
            val r = first.row.value + dr * i
            val c = first.column.value + dc * i
            val pos = Position(Row(r), Column(c))
            val ch = word.text[i]
            val existing = _letters[pos]
            if (existing != null) {
                if (existing != ch) return false
                // Letter already present and matches — no write, no undo.
            } else {
                _letters[pos] = ch
                undo.addLast { _letters.remove(pos) }
            }
        }

        _placements += WordPlacement(word, arrow.cluePosition, arrow.direction, clue)
        undo.addLast { _placements.removeAt(_placements.size - 1) }

        _usedWords += word.text
        undo.addLast { _usedWords -= word.text }

        _usedLemmas += word.lemma
        undo.addLast { _usedLemmas -= word.lemma }

        val theme = clue.theme
        if (theme != null) {
            val prev = _themeUsed[theme] ?: 0
            _themeUsed[theme] = prev + 1
            undo.addLast {
                if (prev == 0) _themeUsed.remove(theme) else _themeUsed[theme] = prev
            }
        }

        return true
    }
```

### Step 6 — Verify imports and run tests

```bash
./gradlew :grid:domain:test --tests "com.bliss.grid.domain.generation.PlanStateTest"
```
Expected: BUILD SUCCESSFUL. All existing PlanStateTest tests + the 6 new ones pass.

If any test fails, fix the implementation before continuing. Common gotchas:
- The `Position`/`Row`/`Column` constructors used in tests must match the existing imports — they're already imported in PlanStateTest.
- `materialize` is the existing private/internal method; `commit` must call it through `this.materialize(arrow, length)` (no qualifier needed inside the class).

### Step 7 — Run full domain test suite for regression

```bash
./gradlew :grid:domain:test
```
Expected: BUILD SUCCESSFUL. No existing tests broken by the additions.

### Step 8 — Spotless

```bash
./gradlew :grid:domain:spotlessApply
```
Expected: BUILD SUCCESSFUL.

### Step 9 — Commit

```bash
git add grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/PlanState.kt \
        grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/PlanStateTest.kt
git commit -m "$(cat <<'EOF'
refactor(grid): extend PlanState with integrated-search state

Add letters/placements/usedWords/usedLemmas/themeUsed fields and the
atomic commit(arrow, length, word, clue) + patternFor(arrow, length)
methods that the integrated search will use. All five new fields roll
back through the existing undo log.

Pure additive change — no existing behaviour modified. SkeletonFiller
and SlotPlanner.planVariable still work; this task just makes
IntegratedSearch (next task) possible.

Spec: docs/superpowers/specs/2026-05-11-integrated-generator-design.md

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

Expected: one commit, two files changed.

---

## Task 2 — Create `IntegratedSearch` alongside `SkeletonFiller`

**Files:**
- Create: `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/IntegratedSearch.kt`
- Test: `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/IntegratedSearchTest.kt`

`SkeletonFiller` still exists at this point. Task 2 introduces `IntegratedSearch` as a parallel implementation. Task 3 will switch `GridGenerator` over and delete `SkeletonFiller`.

### Step 1 — Write the failing tests

Create `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/IntegratedSearchTest.kt`:

```kotlin
package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import kotlin.random.Random
import org.junit.jupiter.api.Test

class IntegratedSearchTest {
    private val future: Long get() = Long.MAX_VALUE

    private fun buildState(
        width: Int,
        height: Int,
    ): PlanState {
        val state = PlanState(width, height)
        for (arrow in Skeleton.arrows(width, height)) {
            state.addClueCell(arrow.cluePosition)
            state.addArrow(arrow.cluePosition, arrow.direction)
        }
        return state
    }

    /** A repository that synthesises a deterministic word for every (length, pattern). */
    private object AnyLetterRepository : WordRepository {
        override fun findByLength(length: Int): List<Word> = emptyList()

        override fun findByLengthAndPattern(
            length: Int,
            pattern: Map<Int, Char>,
        ): List<Word> {
            // Produce 26 candidate words for any pattern by varying a free position.
            val freePos = (0 until length).firstOrNull { it !in pattern } ?: return listOf(synth(length, pattern))
            return ('A'..'Z').map { ch ->
                val full = StringBuilder("X".repeat(length))
                for ((i, p) in pattern) full[i] = p
                full[freePos] = ch
                Word(text = full.toString(), lemma = full.toString(), clues = listOf(WordClue(text = "clue-$full")))
            }
        }

        private fun synth(
            length: Int,
            pattern: Map<Int, Char>,
        ): Word {
            val full = StringBuilder("X".repeat(length))
            for ((i, p) in pattern) full[i] = p
            return Word(text = full.toString(), lemma = full.toString(), clues = listOf(WordClue(text = "clue-$full")))
        }

        override fun countByLength(length: Int): Int = 26

        override fun containsLemma(text: String): Boolean = true
    }

    @Test
    fun `solve fills a tiny grid using a permissive repository`() {
        val state = buildState(width = 5, height = 5)
        val search =
            IntegratedSearch(
                repository = AnyLetterRepository,
                cooldownPolicy = ClueCooldownPolicy.Inert,
                clock = SystemClock,
                lengthPolicy = SlotPlanner::validLengths,
            )

        val ok = search.solve(state, Random(0), deadline = future, themeLimits = emptyMap())

        assertThat(ok).isTrue()
        assertThat(state.placements).isNotNull()
        // At least one placement per skeleton arrow that wasn't deactivated.
        assertThat(state.placements.size).isEqualTo(state.slots.size)
    }

    @Test
    fun `solve returns false when deadline is already in the past`() {
        val state = buildState(width = 5, height = 5)
        val search =
            IntegratedSearch(
                repository = AnyLetterRepository,
                cooldownPolicy = ClueCooldownPolicy.Inert,
                clock = SystemClock,
                lengthPolicy = SlotPlanner::validLengths,
            )

        val ok = search.solve(state, Random(0), deadline = 0L, themeLimits = emptyMap())

        assertThat(ok).isFalse()
    }

    @Test
    fun `solve respects theme caps`() {
        // Repository that returns only words whose only clue is themed "compass".
        val compassRepo =
            object : WordRepository {
                override fun findByLength(length: Int): List<Word> = emptyList()

                override fun findByLengthAndPattern(
                    length: Int,
                    pattern: Map<Int, Char>,
                ): List<Word> {
                    val full = StringBuilder("X".repeat(length))
                    for ((i, p) in pattern) full[i] = p
                    return listOf(
                        Word(
                            text = full.toString(),
                            lemma = full.toString(),
                            clues = listOf(WordClue(text = "Direction", theme = "compass")),
                        ),
                    )
                }

                override fun countByLength(length: Int): Int = 1

                override fun containsLemma(text: String): Boolean = true
            }

        val state = buildState(width = 5, height = 5)
        val search =
            IntegratedSearch(
                repository = compassRepo,
                cooldownPolicy = ClueCooldownPolicy.Inert,
                clock = SystemClock,
                lengthPolicy = SlotPlanner::validLengths,
            )

        // Cap of 0 → no compass clue may be placed → search fails to commit anything.
        val ok = search.solve(state, Random(0), deadline = future, themeLimits = mapOf("compass" to 0))

        assertThat(ok).isFalse()
    }
}
```

### Step 2 — Confirm red

```bash
./gradlew :grid:domain:compileTestKotlin
```
Expected: compilation errors referencing `IntegratedSearch` (unresolved). Red phase.

### Step 3 — Create `IntegratedSearch.kt`

Create `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/IntegratedSearch.kt`:

```kotlin
package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import kotlin.random.Random

/** Number of high-frequency candidates that get random shuffling for diversity. */
private const val HEAD_SHUFFLE_SIZE: Int = 64

/**
 * Unified backtracking search that replaces the two-phase
 * [SlotPlanner.planVariable] + `SkeletonFiller.fill` pipeline.
 *
 * Each search step picks an arrow via [PlanState.nextPendingMRV], then tries
 * `(length, word)` combinations atomically: strict-descending length order
 * (longer words preferred), frequency-descending word order with a
 * head-shuffle for diversity. A length is only committed when a fitting word
 * exists right now, so no plan ever becomes infeasible by construction.
 *
 * State lives entirely in [PlanState]; the search itself is stateless.
 */
internal class IntegratedSearch(
    private val repository: WordRepository,
    private val cooldownPolicy: ClueCooldownPolicy,
    private val clock: Clock,
    private val lengthPolicy: (Int) -> List<Int>,
) {
    fun solve(
        state: PlanState,
        random: Random,
        deadline: Long,
        themeLimits: Map<String, Int>,
        metrics: GenerationMetrics? = null,
    ): Boolean {
        if (clock.currentTimeMillis() > deadline) return false

        val next =
            state.nextPendingMRV(lengthPolicy) ?: run {
                return state.validate().ok
            }

        val available = state.availableLength(next)

        // Arrow can't fit a 2+ letter word — only option is to deactivate.
        if (available < 2) {
            val cp = state.checkpoint()
            state.deactivate(next.cluePosition, next.direction)
            if (!state.hasDeadCluesNow() && solve(state, random, deadline, themeLimits, metrics)) {
                return true
            }
            state.rollback(cp)
            metrics?.let { it.slotPlanBacktracks++ }
            return false
        }

        // Strict descending — longer words preferred.
        val lengths = SlotPlanner.orphanSafeLengths(next, available, lengthPolicy).sortedDescending()
        for (length in lengths) {
            val pattern = state.patternFor(next, length)
            metrics?.let { it.fillRepoCalls++ }
            val matches = repository.findByLengthAndPattern(length, pattern)
            val candidates = filterCandidates(matches, state, themeLimits)
            if (candidates.isEmpty()) continue

            if (metrics != null && metrics.fillFirstSlotDomainSize == -1) {
                metrics.fillFirstSlotDomainSize = candidates.size
            }

            for (word in headShuffle(candidates, random)) {
                val clue = pickClue(word, state.themeUsed, themeLimits, random)
                val cp = state.checkpoint()
                if (state.commit(next, length, word, clue) && !state.hasDeadCluesNow()) {
                    if (solve(state, random, deadline, themeLimits, metrics)) return true
                }
                state.rollback(cp)
                metrics?.let { it.fillBacktracks++ }
            }
        }
        return false
    }

    /**
     * Candidates surviving surface-form dedup, lemma dedup, and the
     * [wordHasUsableClue] check (theme cap + cooldown). Mirrors the filter
     * logic previously in `SkeletonFiller.domainFor` but reads the dedup +
     * theme state from [PlanState].
     */
    private fun filterCandidates(
        matches: List<Word>,
        state: PlanState,
        themeLimits: Map<String, Int>,
    ): List<Word> {
        val nothingUsed = state.usedWords.isEmpty() && state.usedLemmas.isEmpty()
        val cooldownActive = cooldownPolicy !== ClueCooldownPolicy.Inert
        val themeActive = themeLimits.isNotEmpty()
        val needsClueCheck = cooldownActive || themeActive

        return when {
            nothingUsed && !needsClueCheck -> matches
            !needsClueCheck ->
                matches.filter { it.text !in state.usedWords && it.lemma !in state.usedLemmas }
            else ->
                matches.filter {
                    (nothingUsed || (it.text !in state.usedWords && it.lemma !in state.usedLemmas)) &&
                        wordHasUsableClue(it, state.themeUsed, themeLimits)
                }
        }
    }

    private fun wordHasUsableClue(
        word: Word,
        themeUsed: Map<String, Int>,
        themeLimits: Map<String, Int>,
    ): Boolean =
        word.clues.any {
            themeAllowed(it.theme, themeUsed, themeLimits) &&
                !cooldownPolicy.isOnCooldown(ClueId(word.text, it.text))
        }

    private fun pickClue(
        word: Word,
        themeUsed: Map<String, Int>,
        themeLimits: Map<String, Int>,
        random: Random,
    ): WordClue {
        val usable =
            word.clues.filter {
                themeAllowed(it.theme, themeUsed, themeLimits) &&
                    !cooldownPolicy.isOnCooldown(ClueId(word.text, it.text))
            }
        if (usable.isEmpty()) {
            error(
                "pickClue invariant violated: word '${word.text}' reached placement with no " +
                    "theme-fitting, non-cooldown clue. filterCandidates should have excluded it.",
            )
        }
        val nonThemed = usable.filter { it.theme == null }
        val pool = if (nonThemed.isNotEmpty()) nonThemed else usable
        return pool.random(random)
    }

    private fun themeAllowed(
        theme: String?,
        themeUsed: Map<String, Int>,
        themeLimits: Map<String, Int>,
    ): Boolean {
        if (theme == null) return true
        val cap = themeLimits[theme] ?: return true
        return (themeUsed[theme] ?: 0) < cap
    }

    private fun headShuffle(
        matches: List<Word>,
        random: Random,
    ): List<Word> {
        if (matches.size <= 1) return matches
        val headSize = HEAD_SHUFFLE_SIZE.coerceAtMost(matches.size)
        if (headSize <= 1) return matches
        return matches.subList(0, headSize).shuffled(random) + matches.subList(headSize, matches.size)
    }
}
```

### Step 4 — Run tests

```bash
./gradlew :grid:domain:test --tests "com.bliss.grid.domain.generation.IntegratedSearchTest"
```
Expected: 3 tests pass.

If `solve fills a tiny grid using a permissive repository` fails: the search may be hitting an unexpected deactivate path. Verify `Skeleton.arrows(5, 5)` produces arrows whose `availableLength` is ≥ 2 for the first letter (it does — `width >= 2 && height >= 2` is asserted).

If `solve respects theme caps` returns `true` instead of `false`: `filterCandidates` is not filtering on the theme path. Verify the `else` branch in `filterCandidates` is reached (it should be when `themeLimits` is non-empty).

### Step 5 — Run full domain test suite

```bash
./gradlew :grid:domain:test
```
Expected: BUILD SUCCESSFUL. `SkeletonFiller` tests still pass (we haven't touched it). All existing tests still pass.

### Step 6 — Spotless

```bash
./gradlew :grid:domain:spotlessApply
```

### Step 7 — Commit

```bash
git add grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/IntegratedSearch.kt \
        grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/IntegratedSearchTest.kt
git commit -m "$(cat <<'EOF'
feat(grid): introduce IntegratedSearch alongside SkeletonFiller

New unified backtracking search that commits (arrow, length, word)
atomically via PlanState.commit. MRV arrow ordering, strict-descending
length preference, frequency-descending head-shuffled words. Helpers
(filterCandidates, pickClue, themeAllowed, wordHasUsableClue,
headShuffle) inlined for now — they will replace SkeletonFiller's
copies in Task 3.

GridGenerator still calls SkeletonFiller at this point; the switch
lands in Task 3.

Spec: docs/superpowers/specs/2026-05-11-integrated-generator-design.md

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

Expected: one commit, two new files.

---

## Task 3 — Switch `GridGenerator` to `IntegratedSearch` and delete the old code

**Files:**
- Modify: `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/GridGenerator.kt`
- Modify: `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/SlotPlanner.kt`
- Delete: `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/SkeletonFiller.kt`
- Delete: `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerTest.kt`
- Delete: `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerCooldownTest.kt`
- Delete: `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerThemeTest.kt`
- Delete: `grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SlotPlannerVariableTest.kt`

This is the breaking change. The switch and the deletion must land in one commit so the build is green at every commit boundary.

### Step 1 — Swap the call site in `GridGenerator.generate`

Open `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/GridGenerator.kt`. Find the existing `generateInterlocked` method body and replace the block from `val slotPlanStart` through `metrics?.fillMs = (clock.nanoTime() - fillStart) / 1_000_000` with:

```kotlin
        val slotPlanStart = clock.nanoTime()
        val state = PlanState(w, h)
        for (arrow in arrows) {
            state.addClueCell(arrow.cluePosition)
            state.addArrow(arrow.cluePosition, arrow.direction)
        }
        val search =
            IntegratedSearch(
                repository = repository,
                cooldownPolicy = cooldownPolicy,
                clock = clock,
                lengthPolicy = lengthPolicy,
            )
        val ok = search.solve(state, random, deadline, constraints.themeLimits, metrics)
        // The integrated search interleaves planning and filling, so we attribute
        // the entire wall-time to fillMs and leave slotPlanMs at 0 (no separate
        // planning phase exists anymore).
        metrics?.slotPlanMs = 0
        metrics?.fillMs = (clock.nanoTime() - slotPlanStart) / 1_000_000
        if (!ok) return null
        if (state.placements.any { it.word.text.length < constraints.minWordLength }) return null
        val placements = state.placements
```

(The trailing `return try { ... Grid.fromPlacements ... } ...` block stays unchanged.)

### Step 2 — Slim `SlotPlanner`

Open `grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/SlotPlanner.kt`. Delete these methods/fields entirely:

- `fun planFullLength(arrows, width, height): List<WordSlot>` (and its KDoc + `maxRunLength` helper)
- `fun letterCells(slots): Set<Position>`
- `fun planVariable(arrows, width, height, random, deadline, clock, metrics, lengthPolicy): List<WordSlot>?`
- `private fun solveVariable(state, random, deadline, clock, metrics, lengthPolicy): List<WordSlot>?`

After deletion, `SlotPlanner` retains exactly these public/internal members:
- `validLengths(available)` — internal
- `orphanSafeLengths(arrow, available, lengthPolicy)` — private (callable from same file's `IntegratedSearch` via the public re-export below)
- `corpusAwareLengthPolicy(repository, minCorpus)` — public
- `orderForBias(candidates, random)` — internal (currently unused by anything; deletion candidate, but leave for now)
- `DEFAULT_MIN_CORPUS` — public const
- The KDoc on `SlotPlanner` should be updated to remove references to the deleted methods.

**Important:** `orphanSafeLengths` is currently `private`. `IntegratedSearch` calls `SlotPlanner.orphanSafeLengths(...)` from outside, so its visibility must be relaxed to `internal`:

```kotlin
    internal fun orphanSafeLengths(
        arrow: ClueArrow,
        available: Int,
        lengthPolicy: (Int) -> List<Int>,
    ): List<Int> {
        // ... existing body unchanged ...
    }
```

### Step 3 — Delete `SkeletonFiller` and its tests

```bash
rm grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/SkeletonFiller.kt
rm grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerTest.kt
rm grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerCooldownTest.kt
rm grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SkeletonFillerThemeTest.kt
rm grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/SlotPlannerVariableTest.kt
```

### Step 4 — Verify compile + tests pass

```bash
./gradlew :grid:domain:test
```
Expected: BUILD SUCCESSFUL. Konsist arch tests still pass. All remaining tests pass.

If a test in `SlotPlannerTest` fails because it referenced a deleted method (e.g., `planFullLength`), delete that specific test. The remaining `SlotPlannerTest` tests should cover `validLengths`, `corpusAwareLengthPolicy`, and `orphanSafeLengths`.

Common compile errors to expect and fix:
- Unused imports in `SlotPlanner.kt` (e.g., `PlanState`, `kotlin.random.Random`, `firstLetter`) — delete them.
- `GridGenerator` may need to import `IntegratedSearch` and `PlanState` — add as needed.

### Step 5 — Run the wider grid-module test suite

```bash
./gradlew :grid:domain:test :grid:application:test :grid:api:compileTestKotlin
```
Expected: BUILD SUCCESSFUL across all three.

### Step 6 — Spotless

```bash
./gradlew :grid:domain:spotlessApply :grid:application:spotlessApply :grid:api:spotlessApply
```

### Step 7 — Commit

```bash
git add -A grid/domain/src/main/kotlin/com/bliss/grid/domain/generation/ \
          grid/domain/src/test/kotlin/com/bliss/grid/domain/generation/
git commit -m "$(cat <<'EOF'
refactor(grid): switch GridGenerator to IntegratedSearch; delete SkeletonFiller

Atomic switch:
- GridGenerator.generate now builds a PlanState, seeds it with the
  Skeleton's boundary arrows, and runs IntegratedSearch.solve.
- SkeletonFiller.kt + its tests are deleted in full.
- SlotPlanner.planVariable, solveVariable, planFullLength, letterCells,
  maxRunLength deleted; SlotPlannerVariableTest deleted.
- SlotPlanner.orphanSafeLengths visibility relaxed to internal so
  IntegratedSearch can call it.

GenerationMetrics: slotPlanMs is now always 0 (no separate planning
phase); fillMs absorbs the integrated search's full wall time.

Spec: docs/superpowers/specs/2026-05-11-integrated-generator-design.md

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

Expected: one commit with multiple file changes (modifications + deletions).

---

## Verification — run the 25-gen bench

Not a commit. Just confirm the exit criterion.

### Step 1 — Run the 25-gen fast bench

```bash
./gradlew :grid:api:test --tests "*25 puzzles fast iteration bench*" -Dincludetags=bench
```

Expected: BUILD SUCCESSFUL within ~3 minutes (capped at the per-puzzle 5s timeout × 25 puzzles).

### Step 2 — Check the success_rate

Read the bench output for `bench_fast_summary` and `bench_shape_collisions` lines:

```
bench_fast_summary label=fast-25 width=15 height=12 n=25 success=??? timeouts=???
bench_shape_collisions label=fast-25 n=25 unique=??? collisions=???
```

**Exit criterion**: `success > 0` (strictly improves over the baseline 0/25). The spec set a concrete target of ≥5/25.

If `success == 0`, the design assumption "integrated commit gives plan diversity" is wrong; do not declare the wave done — investigate via the CSV (`grid/api/data/eval/grid_gen_bench_fast-25_<ts>.csv`):
- `slot_plan_backtracks` should now be > 0 in many puzzles (the integrated search backtracks at the plan level)
- `fill_first_slot_domain` should vary across puzzles (different first slot lengths)
- `shape_hash` collisions should drop (plan diversity)

If those indicators look right but success is still 0/25, the 5s timeout is the limit, not infeasibility — and a future wave (AC-3, backjumping) is needed.

### Step 3 — Done

Wave 6 is complete when:
- 3 commits on `feat/grid-gen-enhance` (PlanState extension, IntegratedSearch, switch + delete).
- `./gradlew :grid:domain:test :grid:application:test` is green.
- 25-gen bench shows `success > 0` AND `shape_collisions < 24` (some plan diversity emerged).

---

## Out of scope (do not implement)

- AC-3 / multi-hop forward propagation.
- Conflict-directed backjumping.
- Skeleton structural variability (boundary arrows still deterministic).
- Production observability (OTel, structured logs of per-attempt outcomes).
- Any change to `GeneratePuzzleUseCase`'s retry loop semantics.
- Reintroducing a feature flag to coexist with the old SkeletonFiller — old code is deleted, not gated.

These are explicitly deferred. If a need arises during implementation, surface it as a follow-up issue.
