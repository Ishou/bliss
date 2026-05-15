package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Word
import kotlin.random.Random

/**
 * Length-bucketed, bitmask-indexed dictionary view over a [WordRepository].
 *
 * For each length `L`, holds:
 *  - `words[L]`: the words of that length, in index order. Index `i` ↔ bit `i`.
 *  - `bitmap[L][p][c]`: a [LongArray] bitset; bit `i` set iff
 *    `words[L][i]`'s letter at position `p` equals letter index `c`.
 *  - `allMask[L]`: a [LongArray] with every bit `0..count(L)-1` set.
 *
 * Bitmask operations:
 *  - **Pattern match**: intersect a slot's current domain `D` with the
 *    "letter `c` at position `p`" constraint via one bitwise AND with
 *    `bitmap[L][p][c]`. With 64-bit words that's `ceil(N/64)` Long ANDs.
 *  - **Allowed letters**: derive the 26-bit mask of letters still
 *    possible at position `p` of a slot with domain `D` via 26 nonzero
 *    intersection tests.
 *
 * Construction copies word references and never mutates the repository.
 * The repository may not be thread-safe; the [Lexicon] is read-only
 * after construction (no shared mutable state).
 */
internal class Lexicon(
    repository: WordRepository,
    maxLen: Int = GenerationKnobs.LEXICON_MAX_LEN,
) {
    /** Per-length list of [Word] objects, in bit-index order. */
    val words: Array<List<Word>>

    /**
     * `bitmap[L][p][c]` = bitmask of words at length L with letter
     * (c-th of A..Z) at position p. Outer array indexed by length;
     * middle by position; inner LongArray of size ceil(N(L)/64).
     */
    private val bitmap: Array<Array<Array<LongArray>>>

    /** `allMask[L]` = a `LongArray` with every bit `0..count(L)-1` set. */
    private val allMask: Array<LongArray>

    /** Per-length word count. */
    private val countByLen: IntArray

    /** The largest length whose corpus count is `≥ GenerationKnobs.L_USEFUL_FLOOR`. */
    val usefulLength: Int

    /** Inclusive upper bound on lengths the lexicon can index. */
    val maxLength: Int = maxLen

    init {
        require(maxLen >= 2) { "maxLen must be ≥ 2, was $maxLen" }
        val perLen: Array<MutableList<Word>> = Array(maxLen + 1) { mutableListOf() }
        for (l in 2..maxLen) {
            for (w in repository.findByLength(l)) {
                // Word.text is already A..Z by Word's init contract.
                if (w.text.length == l && w.text.all { it in 'A'..'Z' }) {
                    perLen[l].add(w)
                }
            }
        }
        words = Array(maxLen + 1) { l -> perLen[l].toList() }
        countByLen = IntArray(maxLen + 1) { l -> words[l].size }

        bitmap =
            Array(maxLen + 1) { l ->
                if (l < 2) {
                    emptyArray()
                } else {
                    val n = countByLen[l]
                    val longCount = longsNeeded(n)
                    Array(l) { p ->
                        Array(LETTER_COUNT) { c ->
                            val mask = LongArray(longCount)
                            val letter = ('A' + c)
                            for ((i, w) in words[l].withIndex()) {
                                if (w.text[p] == letter) {
                                    mask[i ushr 6] = mask[i ushr 6] or (1L shl (i and 63))
                                }
                            }
                            mask
                        }
                    }
                }
            }

        allMask =
            Array(maxLen + 1) { l ->
                if (l < 2) LongArray(0) else fullMaskFor(countByLen[l])
            }

        usefulLength =
            (maxLen downTo 2)
                .firstOrNull { countByLen[it] >= GenerationKnobs.L_USEFUL_FLOOR }
                ?: (maxLen downTo 2).firstOrNull { countByLen[it] >= 1 }
                ?: 2
    }

    /** Number of words at length [l]. */
    fun count(l: Int): Int = if (l in 0..maxLength) countByLen[l] else 0

    /**
     * Longest length `L ≤ [maxLen]` with `count(L) ≥ [threshold]`, or `0`
     * if no length in `[minLen, maxLen]` clears the threshold. Used to size
     * feature slots to what the corpus can actually fill.
     */
    fun featureFeasibleLength(
        maxLen: Int,
        minLen: Int,
        threshold: Int = GenerationKnobs.FEATURE_DICT_THRESHOLD,
    ): Int {
        val upper = maxLen.coerceAtMost(maxLength)
        for (l in upper downTo minLen) {
            if (countByLen[l] >= threshold) return l
        }
        return 0
    }

    /** Fresh defensive copy of the all-bits-set mask for length [l]. */
    fun initialMask(l: Int): LongArray {
        require(l in 2..maxLength) { "length $l out of range [2, $maxLength]" }
        return allMask[l].copyOf()
    }

    /**
     * Intersect `mask` (live domain for some slot of length [l]) with
     * the set of words having letter `c` at position `p`. Result written
     * in-place. Returns `true` iff any bit remains set.
     */
    fun filterByLetterInPlace(
        l: Int,
        p: Int,
        c: Char,
        mask: LongArray,
    ): Boolean {
        val target = bitmap[l][p][c.code - 'A'.code]
        var any = false
        for (i in mask.indices) {
            mask[i] = mask[i] and target[i]
            if (mask[i] != 0L) any = true
        }
        return any
    }

    /**
     * 26-bit mask of letters still possible at position [p] of a slot
     * with current domain [mask], length [l]. Bit `c` set ⇔ at least one
     * word in `mask` has letter `'A' + c` at position `p`.
     */
    fun lettersAt(
        l: Int,
        p: Int,
        mask: LongArray,
    ): Int {
        val perLetter = bitmap[l][p]
        var out = 0
        for (c in 0 until LETTER_COUNT) {
            if (intersectsNonZero(mask, perLetter[c])) out = out or (1 shl c)
        }
        return out
    }

    /**
     * Pre-aggregated mask of words at length [l] whose letter at [p] is
     * in any of the [allowedLetters] (26-bit set). Returned as a new
     * [LongArray] (caller may AND it in-place into a domain).
     */
    fun unionMaskForLetters(
        l: Int,
        p: Int,
        allowedLetters: Int,
    ): LongArray {
        val out = LongArray(longsNeeded(countByLen[l]))
        var bits = allowedLetters
        while (bits != 0) {
            val c = Integer.numberOfTrailingZeros(bits)
            val src = bitmap[l][p][c]
            for (i in out.indices) out[i] = out[i] or src[i]
            bits = bits and (bits - 1)
        }
        return out
    }

    /** [Word] at length [l], bit index [i]. */
    fun wordAt(
        l: Int,
        i: Int,
    ): Word = words[l][i]

    /** Number of set bits across the entire [mask]. */
    fun popcount(mask: LongArray): Int {
        var sum = 0
        for (w in mask) sum += java.lang.Long.bitCount(w)
        return sum
    }

    /**
     * Uniform-over-bits selection of a single set-bit index from [mask].
     * Returns `-1` if [mask] has no set bits.
     */
    fun pickIndex(
        mask: LongArray,
        random: Random,
    ): Int {
        val n = popcount(mask)
        if (n == 0) return -1
        var k = random.nextInt(n)
        for (w in mask.indices) {
            var word = mask[w]
            while (word != 0L) {
                val bit = word and -word
                if (k == 0) return (w shl 6) + java.lang.Long.numberOfTrailingZeros(bit)
                word = word xor bit
                k--
            }
        }
        error("unreachable: popcount said $n bits but bit-walk found fewer")
    }

    /** Iterate the indices of set bits in [mask], low-to-high. */
    fun iterIndices(mask: LongArray): Sequence<Int> =
        sequence {
            for (w in mask.indices) {
                var word = mask[w]
                while (word != 0L) {
                    val bit = word and -word
                    yield((w shl 6) + java.lang.Long.numberOfTrailingZeros(bit))
                    word = word xor bit
                }
            }
        }

    private fun intersectsNonZero(
        a: LongArray,
        b: LongArray,
    ): Boolean {
        // Both arrays have identical length (same N(L)).
        for (i in a.indices) if ((a[i] and b[i]) != 0L) return true
        return false
    }

    companion object {
        const val LETTER_COUNT: Int = 26

        fun longsNeeded(bits: Int): Int = (bits + 63) ushr 6

        private fun fullMaskFor(bits: Int): LongArray {
            val n = longsNeeded(bits)
            if (n == 0) return LongArray(0)
            val arr = LongArray(n) { -1L }
            val tail = bits and 63
            if (tail != 0) {
                arr[n - 1] = (1L shl tail) - 1L
            }
            return arr
        }
    }
}
