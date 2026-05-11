package com.bliss.grid.domain.generation

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
