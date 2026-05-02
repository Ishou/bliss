package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import kotlin.random.Random

/**
 * Maps a list of [WordSlot]s to concrete words via constraint-satisfaction search.
 *
 * Algorithm: backtracking with the MRV (Minimum Remaining Values) heuristic — at
 * every step we pick the unassigned slot with the smallest candidate set given
 * the letters already placed at intersection cells, then iterate that slot's
 * candidates with a small random head shuffle so consecutive runs don't repeat.
 *
 * Letter constraints propagate through the [WordRepository.findByLengthAndPattern]
 * fast index (per-position character map) — each candidate query is microseconds
 * even on the full corpus, so the search cost is dominated by depth × branching
 * factor, not lookup cost.
 *
 * The filler is repository-agnostic and slot-graph agnostic: it doesn't know or
 * care about the boundary skeleton's shape, only that slots may share cells and
 * the letters at those cells must agree.
 */
internal class SkeletonFiller(
    private val repository: WordRepository,
) {
    /**
     * Try to fill every [slots] entry with a word. Returns one [WordPlacement] per
     * slot in the same order, or `null` if no consistent assignment exists within
     * the time budget.
     */
    fun fill(
        slots: List<WordSlot>,
        random: Random,
        deadline: Long,
    ): List<WordPlacement>? {
        if (slots.isEmpty()) return emptyList()
        val letters = HashMap<Position, Char>()
        val usedWords = HashSet<String>()
        // Lemma-level dedup: forbid placing two inflected forms of the same
        // headword in one grid (e.g. "court" + "courait" both → "courir").
        // For corpora that don't carry lemma data, Word.lemma defaults to
        // Word.text and this set is functionally identical to usedWords.
        val usedLemmas = HashSet<String>()
        val assigned = arrayOfNulls<Word>(slots.size)
        if (!search(slots, letters, usedWords, usedLemmas, assigned, random, deadline)) return null
        return slots.mapIndexed { i, slot ->
            WordPlacement(assigned[i]!!, slot.cluePosition, slot.direction)
        }
    }

    private fun search(
        slots: List<WordSlot>,
        letters: HashMap<Position, Char>,
        usedWords: HashSet<String>,
        usedLemmas: HashSet<String>,
        assigned: Array<Word?>,
        random: Random,
        deadline: Long,
    ): Boolean {
        if (System.currentTimeMillis() > deadline) return false

        // MRV: scan unassigned slots, pick the one with the smallest domain right now.
        // A domain of size 0 short-circuits to a dead end immediately.
        var bestIdx = -1
        var bestDomain: List<Word>? = null
        var bestSize = Int.MAX_VALUE
        for (i in slots.indices) {
            if (assigned[i] != null) continue
            val domain = domainFor(slots[i], letters, usedWords, usedLemmas)
            if (domain.size < bestSize) {
                bestSize = domain.size
                bestIdx = i
                bestDomain = domain
                if (bestSize == 0) return false
            }
        }
        if (bestIdx == -1) return true // every slot assigned

        val slot = slots[bestIdx]
        val ordering = headShuffle(bestDomain!!, random)
        val positions = slot.letterPositions()

        for (word in ordering) {
            assigned[bestIdx] = word
            usedWords += word.text
            usedLemmas += word.lemma
            val newlyPlaced = ArrayList<Position>(positions.size)
            for ((i, pos) in positions.withIndex()) {
                if (pos !in letters) {
                    letters[pos] = word.text[i]
                    newlyPlaced += pos
                }
            }

            if (search(slots, letters, usedWords, usedLemmas, assigned, random, deadline)) return true

            // Undo.
            for (pos in newlyPlaced) letters.remove(pos)
            usedWords -= word.text
            usedLemmas -= word.lemma
            assigned[bestIdx] = null
        }
        return false
    }

    /**
     * Candidates for [slot] consistent with currently placed [letters], excluding both
     * [usedWords] (surface-form dedup) and [usedLemmas] (lemma-level dedup).
     * The pattern at each fixed position prunes via the repository's position-letter index.
     */
    private fun domainFor(
        slot: WordSlot,
        letters: Map<Position, Char>,
        usedWords: Set<String>,
        usedLemmas: Set<String>,
    ): List<Word> {
        val pattern = HashMap<Int, Char>(slot.length)
        slot.letterPositions().forEachIndexed { i, pos ->
            letters[pos]?.let { pattern[i] = it }
        }
        val matches = repository.findByLengthAndPattern(slot.length, pattern)
        // Filter used last — keeps the cheap path (no allocation) when nothing's used yet.
        return if (usedWords.isEmpty() && usedLemmas.isEmpty()) {
            matches
        } else {
            matches.filter { it.text !in usedWords && it.lemma !in usedLemmas }
        }
    }

    /**
     * Shuffle the high-frequency head only (repository returns matches in frequency-descending
     * order). Common words tried first → fewer backtracks; head-shuffle gives variety.
     */
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
