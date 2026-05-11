package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import kotlin.random.Random

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

        // Length-major iteration: try each length in order (descending from
        // validLengths), exhaust its candidate words via head-shuffle, then
        // next length. Cheaper than flattening the joint (length × word) pair
        // space upfront because most lengths fail forward-check fast.
        val lengths = SlotPlanner.orphanSafeLengths(next, available, lengthPolicy)
        for (length in lengths) {
            val pattern = state.patternFor(next, length)
            // Cheap O(1) viability check before the full pattern lookup.
            if (!patternIsViable(length, pattern)) continue
            metrics?.let { it.fillRepoCalls++ }
            val matches = repository.findByLengthAndPattern(length, pattern)
            val candidates = filterCandidates(matches, state, themeLimits)
            if (candidates.isEmpty()) continue

            if (metrics != null && metrics.fillFirstSlotDomainSize == -1) {
                metrics.fillFirstSlotDomainSize = candidates.size
            }

            for (word in headShuffle(candidates, random)) {
                // Deadline check inside the loop — forward-check pruning means
                // many iterations never recurse (skipping the solve()-entry
                // deadline check), so we'd otherwise blow past the budget on
                // pure forward-check work.
                if (clock.currentTimeMillis() > deadline) return false
                val clue = pickClue(word, state.themeUsed, themeLimits, random)
                val cp = state.checkpoint()
                if (
                    state.commit(next, length, word, clue) &&
                    !state.hasDeadCluesNow() &&
                    forwardCheckPasses(state, themeLimits, next, length)
                ) {
                    if (solve(state, random, deadline, themeLimits, metrics)) return true
                }
                state.rollback(cp)
                metrics?.let { it.fillBacktracks++ }
            }
        }
        return false
    }

    /**
     * Scoped forward-check: after committing a slot, only verify arrows whose
     * path **intersects** at least one cell of the just-committed slot. Arrows
     * elsewhere on the grid have unchanged domains and need no re-check.
     *
     * For each intersecting arrow, scan its valid lengths until one yields a
     * non-empty candidate set. Empty across all lengths → the commit is dead,
     * fail-fast.
     */
    private fun forwardCheckPasses(
        state: PlanState,
        themeLimits: Map<String, Int>,
        committed: ClueArrow,
        committedLength: Int,
    ): Boolean {
        val committedPositions = positionsForSlot(committed, committedLength)

        for (arrow in state.pendingArrows()) {
            val available = state.availableLength(arrow)
            if (available < 2) continue // will be deactivated in its own visit
            if (!arrowPathTouches(arrow, available, committedPositions)) continue

            var hasAny = false
            for (length in SlotPlanner.orphanSafeLengths(arrow, available, lengthPolicy)) {
                val pattern = state.patternFor(arrow, length)
                // Cheap O(1) dead-end check via the precomputed letter table:
                // if any known crossing letter doesn't appear at its position
                // for ANY word of this length, this length is dead — skip the
                // full findByLengthAndPattern + filter work.
                if (!patternIsViable(length, pattern)) continue
                val matches = repository.findByLengthAndPattern(length, pattern)
                val candidates = filterCandidates(matches, state, themeLimits)
                if (candidates.isNotEmpty()) {
                    hasAny = true
                    break
                }
            }
            if (!hasAny) return false
        }
        return true
    }

    /**
     * O(|pattern|) early-reject: every known letter at every known position
     * must appear in at least one word of [length] at that position. Uses the
     * precomputed [WordRepository.lettersAtPosition] table — O(1) per check.
     */
    private fun patternIsViable(
        length: Int,
        pattern: Map<Int, Char>,
    ): Boolean {
        for ((pos, ch) in pattern) {
            if (ch !in repository.lettersAtPosition(length, pos)) return false
        }
        return true
    }

    private fun positionsForSlot(
        arrow: ClueArrow,
        length: Int,
    ): HashSet<Position> {
        val first = firstLetter(arrow)
        val dr = arrow.direction.step.row.value
        val dc = arrow.direction.step.column.value
        val out = HashSet<Position>(length)
        for (i in 0 until length) {
            out += Position(Row(first.row.value + dr * i), Column(first.column.value + dc * i))
        }
        return out
    }

    private fun arrowPathTouches(
        arrow: ClueArrow,
        available: Int,
        positions: Set<Position>,
    ): Boolean {
        val first = firstLetter(arrow)
        val dr = arrow.direction.step.row.value
        val dc = arrow.direction.step.column.value
        var r = first.row.value
        var c = first.column.value
        for (i in 0 until available) {
            if (Position(Row(r), Column(c)) in positions) return true
            r += dr
            c += dc
        }
        return false
    }

    /**
     * Candidates surviving surface-form dedup, lemma dedup, and the
     * [wordHasUsableClue] check (theme cap + cooldown).
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
