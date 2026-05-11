package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
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
    private val cooldownPolicy: ClueCooldownPolicy = ClueCooldownPolicy.Inert,
    private val clock: Clock = SystemClock,
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
        themeLimits: Map<String, Int> = emptyMap(),
        metrics: GenerationMetrics? = null,
    ): List<WordPlacement>? {
        if (slots.isEmpty()) return emptyList()
        val letters = HashMap<Position, Char>()
        val usedWords = HashSet<String>()
        // Lemma-level dedup: forbid placing two inflected forms of the same
        // headword in one grid (e.g. "court" + "courait" both → "courir").
        // For corpora that don't carry lemma data, Word.lemma defaults to
        // Word.text and this set is functionally identical to usedWords.
        val usedLemmas = HashSet<String>()
        // Theme-cap counter: per-grid count of placed themed words. Caps
        // come from [GridConstraints.themeLimits]. Words with `theme = null`
        // never touch this map.
        val themeUsed = HashMap<String, Int>()
        val assigned = arrayOfNulls<WordPlacement>(slots.size)
        // Precompute the intersection graph: slot i shares ≥1 cell with slots[intersections[i]].
        // Used by the fill-phase forward-check — after placing a word, only the slots that
        // intersect the just-placed slot can have their domain shrink, so we re-evaluate just
        // those. O(slots²) precompute amortizes over millions of search nodes.
        val intersections = computeIntersections(slots)
        if (!search(
                slots,
                letters,
                usedWords,
                usedLemmas,
                themeUsed,
                themeLimits,
                assigned,
                intersections,
                random,
                deadline,
                metrics,
            )
        ) {
            return null
        }
        return slots.mapIndexed { i, _ -> assigned[i]!! }
    }

    private fun computeIntersections(slots: List<WordSlot>): Array<IntArray> {
        val byPosition = HashMap<Position, MutableList<Int>>()
        for ((idx, slot) in slots.withIndex()) {
            for (pos in slot.letterPositions()) {
                byPosition.getOrPut(pos) { ArrayList() }.add(idx)
            }
        }
        // For each slot, collect the set of OTHER slot indices it shares a cell with.
        return Array(slots.size) { i ->
            val neighbors = HashSet<Int>()
            for (pos in slots[i].letterPositions()) {
                for (other in byPosition[pos] ?: continue) {
                    if (other != i) neighbors.add(other)
                }
            }
            neighbors.toIntArray()
        }
    }

    private fun search(
        slots: List<WordSlot>,
        letters: HashMap<Position, Char>,
        usedWords: HashSet<String>,
        usedLemmas: HashSet<String>,
        themeUsed: HashMap<String, Int>,
        themeLimits: Map<String, Int>,
        assigned: Array<WordPlacement?>,
        intersections: Array<IntArray>,
        random: Random,
        deadline: Long,
        metrics: GenerationMetrics?,
    ): Boolean {
        if (clock.currentTimeMillis() > deadline) return false

        // MRV: scan unassigned slots, pick the one with the smallest domain right now.
        // A domain of size 0 short-circuits to a dead end immediately.
        var bestIdx = -1
        var bestDomain: List<Word>? = null
        var bestSize = Int.MAX_VALUE
        for (i in slots.indices) {
            if (assigned[i] != null) continue
            val domain = domainFor(slots[i], letters, usedWords, usedLemmas, themeUsed, themeLimits, metrics)
            if (domain.size < bestSize) {
                bestSize = domain.size
                bestIdx = i
                bestDomain = domain
                if (bestSize == 0) return false
            }
        }
        if (bestIdx == -1) return true // every slot assigned

        // Capture the root-of-search domain size — useful for diagnosing "is the
        // first slot too wide?" without running a profiler. Only set on the
        // first slot, identified by all-unassigned state at entry.
        if (metrics != null && metrics.fillFirstSlotDomainSize == -1) {
            metrics.fillFirstSlotDomainSize = bestSize
        }

        val slot = slots[bestIdx]
        val ordering = headShuffle(bestDomain!!, random)
        val positions = slot.letterPositions()

        for (word in ordering) {
            // Pick which clue to show now that we know the live theme-cap state.
            // Prefer non-themed clues so themed slots stay free for words whose
            // only candidate is themed (e.g. NE has compass-only). When all
            // remaining clues would exceed caps, fall back to the word's first
            // clue — domainFor only put the word here because *some* clue fit,
            // so this branch indicates a stale state and is recovered by undo.
            val chosenClue = pickClue(word, themeUsed, themeLimits, random)
            assigned[bestIdx] = WordPlacement(word, slot.cluePosition, slot.direction, chosenClue)
            usedWords += word.text
            usedLemmas += word.lemma
            val theme = chosenClue.theme
            if (theme != null) themeUsed[theme] = (themeUsed[theme] ?: 0) + 1
            val newlyPlaced = ArrayList<Position>(positions.size)
            for ((i, pos) in positions.withIndex()) {
                if (pos !in letters) {
                    letters[pos] = word.text[i]
                    newlyPlaced += pos
                }
            }

            // Forward-check: only the slots that intersect bestIdx can have their
            // domain shrink from this placement (other unassigned slots' patterns
            // are unchanged). Re-evaluate just those; if any went empty, skip
            // recursion — the next MRV scan would have discovered the same
            // dead-end at greater cost.
            val placementOk =
                run {
                    for (otherIdx in intersections[bestIdx]) {
                        if (assigned[otherIdx] != null) continue
                        val otherDomain =
                            domainFor(slots[otherIdx], letters, usedWords, usedLemmas, themeUsed, themeLimits, metrics)
                        if (otherDomain.isEmpty()) return@run false
                    }
                    true
                }

            if (placementOk &&
                search(
                    slots,
                    letters,
                    usedWords,
                    usedLemmas,
                    themeUsed,
                    themeLimits,
                    assigned,
                    intersections,
                    random,
                    deadline,
                    metrics,
                )
            ) {
                return true
            }
            if (!placementOk) metrics?.let { it.fillForwardCheckSkips++ }

            // Undo.
            for (pos in newlyPlaced) letters.remove(pos)
            usedWords -= word.text
            usedLemmas -= word.lemma
            if (theme != null) {
                val prev = themeUsed.getValue(theme)
                if (prev <= 1) themeUsed.remove(theme) else themeUsed[theme] = prev - 1
            }
            assigned[bestIdx] = null
            metrics?.let { it.fillBacktracks++ }
        }
        return false
    }

    /**
     * Candidates for [slot] consistent with currently placed [letters], excluding both
     * [usedWords] (surface-form dedup) and [usedLemmas] (lemma-level dedup), and dropping
     * words for which *every* candidate clue would exceed its per-grid cap. The pattern
     * at each fixed position prunes via the repository's position-letter index.
     *
     * Note on theme cap semantics: caps are evaluated against the chosen *clue's* theme
     * at placement time (see `pickClue`), not against the word's set of possible themes.
     * A word with multiple clues — e.g. EST = [verb (no theme), compass] — stays in the
     * domain even when the compass cap is exhausted, because the verb clue still fits.
     */
    private fun domainFor(
        slot: WordSlot,
        letters: Map<Position, Char>,
        usedWords: Set<String>,
        usedLemmas: Set<String>,
        themeUsed: Map<String, Int>,
        themeLimits: Map<String, Int>,
        metrics: GenerationMetrics?,
    ): List<Word> {
        val pattern = HashMap<Int, Char>(slot.length)
        slot.letterPositions().forEachIndexed { i, pos ->
            letters[pos]?.let { pattern[i] = it }
        }
        metrics?.let { it.fillRepoCalls++ }
        val matches = repository.findByLengthAndPattern(slot.length, pattern)
        val nothingUsed = usedWords.isEmpty() && usedLemmas.isEmpty()
        // Themes only kick in when at least one cap is configured; the empty-map
        // case keeps the existing cheap path. The cooldown filter inside
        // wordHasUsableClue must still run even when nothing is used and no themes
        // are active — a word can have all its clues on cooldown at search root.
        val cooldownActive = cooldownPolicy !== ClueCooldownPolicy.Inert
        val themeActive = themeLimits.isNotEmpty()
        val needsClueCheck = cooldownActive || themeActive
        return when {
            nothingUsed && !needsClueCheck -> matches
            !needsClueCheck ->
                matches.filter { it.text !in usedWords && it.lemma !in usedLemmas }
            else ->
                matches.filter {
                    (nothingUsed || (it.text !in usedWords && it.lemma !in usedLemmas)) &&
                        wordHasUsableClue(it, themeUsed, themeLimits)
                }
        }
    }

    /**
     * True iff [word] has at least one clue that:
     *  - fits the live theme caps, AND
     *  - is not on cooldown per [cooldownPolicy].
     *
     * Used by [domainFor] to exclude words whose every clue is unusable, so the
     * silent fallback in [pickClue] (`word.clues.first()`) can be replaced with
     * an assertion — the domain now guarantees at least one usable clue exists.
     */
    private fun wordHasUsableClue(
        word: Word,
        themeUsed: Map<String, Int>,
        themeLimits: Map<String, Int>,
    ): Boolean =
        word.clues.any {
            themeAllowed(it.theme, themeUsed, themeLimits) &&
                !cooldownPolicy.isOnCooldown(ClueId(word.text, it.text))
        }

    /**
     * Pick which of [word]'s clues to render at placement time. Prefer clues whose
     * theme is `null` so themed slots stay free for words whose only candidate is
     * themed (e.g. NE has compass-only). Among clues that fit current caps AND
     * pass the cooldown policy, pick uniformly at random for variety.
     *
     * Domain invariant: [domainFor] only returns words for which at least one
     * usable clue exists (see [wordHasUsableClue]). Reaching the `error(...)`
     * branch means the caller violated that contract.
     */
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
                    "theme-fitting, non-cooldown clue. domainFor should have excluded it.",
            )
        }
        val nonThemed = usable.filter { it.theme == null }
        val pool = if (nonThemed.isNotEmpty()) nonThemed else usable
        return pool.random(random)
    }

    /** True iff placing a clue with [theme] would not exceed its per-grid cap. */
    private fun themeAllowed(
        theme: String?,
        themeUsed: Map<String, Int>,
        themeLimits: Map<String, Int>,
    ): Boolean {
        if (theme == null) return true
        val cap = themeLimits[theme] ?: return true
        return (themeUsed[theme] ?: 0) < cap
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
