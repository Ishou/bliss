package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import kotlin.random.Random

/**
 * Bridges the bitmask CSP solver's per-grid mutable state (used words,
 * used lemmas, theme counts) to the immutable [Lexicon].
 *
 * The bitmask domains are keyed only on letter constraints — theme caps
 * and clue cooldown are evaluated lazily at assignment time. A word may
 * survive every letter constraint AND still be rejected here because
 * its only usable clue is on cooldown or its theme would exceed the
 * per-grid cap.
 *
 * Single-use per [BitmaskCsp] search invocation; lives on the stack.
 */
internal class WordAcceptor(
    private val themeLimits: Map<String, Int>,
    private val cooldownPolicy: ClueCooldownPolicy,
) {
    private val usedWords: HashSet<String> = HashSet()
    private val usedLemmas: HashSet<String> = HashSet()
    private val themeUsed: HashMap<String, Int> = HashMap()

    val themeUsedView: Map<String, Int> get() = themeUsed

    /**
     * True iff this [word] is structurally available for placement:
     *  - its surface form is not already placed;
     *  - its lemma is not already placed;
     *  - at least one of its clues has theme within the live cap AND
     *    is not on cooldown.
     */
    fun accepts(word: Word): Boolean {
        if (word.text in usedWords) return false
        if (word.lemma in usedLemmas) return false
        return hasUsableClue(word)
    }

    /**
     * Pick a usable clue for [word] (theme-fitting AND not on cooldown),
     * preferring non-themed clues. Returns null if no usable clue exists
     * (caller skips the word).
     */
    fun pickClue(
        word: Word,
        random: Random,
    ): WordClue? {
        val usable =
            word.clues.filter {
                themeAllowed(it.theme) && !cooldownPolicy.isOnCooldown(ClueId(word.text, it.text))
            }
        if (usable.isEmpty()) return null
        val nonThemed = usable.filter { it.theme == null }
        val pool = if (nonThemed.isNotEmpty()) nonThemed else usable
        return pool.random(random)
    }

    /**
     * Record [word] as placed with [chosenClue]. Caller must invoke
     * [removePlacement] (with the same arguments) on rollback.
     */
    fun recordPlacement(
        word: Word,
        chosenClue: WordClue,
    ) {
        usedWords += word.text
        usedLemmas += word.lemma
        val theme = chosenClue.theme
        if (theme != null) {
            themeUsed[theme] = (themeUsed[theme] ?: 0) + 1
        }
    }

    /** Invert a previous [recordPlacement]. */
    fun removePlacement(
        word: Word,
        chosenClue: WordClue,
    ) {
        usedWords -= word.text
        usedLemmas -= word.lemma
        val theme = chosenClue.theme
        if (theme != null) {
            val prev = themeUsed.getValue(theme)
            if (prev <= 1) themeUsed.remove(theme) else themeUsed[theme] = prev - 1
        }
    }

    private fun hasUsableClue(word: Word): Boolean =
        word.clues.any {
            themeAllowed(it.theme) && !cooldownPolicy.isOnCooldown(ClueId(word.text, it.text))
        }

    private fun themeAllowed(theme: String?): Boolean {
        if (theme == null) return true
        val cap = themeLimits[theme] ?: return true
        return (themeUsed[theme] ?: 0) < cap
    }
}
