package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordClue
import kotlin.random.Random

/**
 * Bitmask-domain CSP solver for mots-fléchés grid filling (spec §6–§7).
 *
 * The slot graph + per-slot bitmask domains live on the supplied
 * [Slot] list. The solver:
 *  1. Runs initial arc-consistency to fixed point.
 *  2. Iteratively picks the slot with smallest live domain (MRV) +
 *     highest degree (tie-break) + random (final tie-break).
 *  3. Iterates words via sampled-window LCV ordering.
 *  4. After each assignment, propagates constraint cascade via AC-3.
 *  5. On failure, undoes via trail to a checkpointed mark.
 *  6. Stops on deadline, backtrack-budget exceeded, or cancellation.
 */
internal class BitmaskCsp(
    private val slots: List<Slot>,
    private val lexicon: Lexicon,
    private val acceptor: WordAcceptor,
    private val clock: Clock,
    private val random: Random,
) {
    /** Trail of inverse mutations. */
    private val trail: ArrayDeque<TrailEntry> = ArrayDeque()

    /** Cached `crossings[sid]` flat list, per-slot bitset of "in worklist" flags. */
    private val inWorklist: BooleanArray = BooleanArray(slots.size)

    /** Number of slots already bound to a word. */
    private var assignedCount: Int = 0

    /** Per-search backtrack counter. */
    var backtracks: Int = 0
        private set

    /** Per-search assignment counter (succeeded `assign` calls). */
    var assignments: Int = 0
        private set

    sealed interface TrailEntry {
        data class Dom(val sid: Int, val oldMask: LongArray) : TrailEntry {
            override fun equals(other: Any?): Boolean = this === other

            override fun hashCode(): Int = System.identityHashCode(this)
        }

        data class Asg(val sid: Int, val oldWord: Word?, val oldClue: WordClue?) : TrailEntry

        data class Used(val word: Word, val clue: WordClue) : TrailEntry
    }

    enum class Result { OK, TIMEOUT, BUDGET_EXCEEDED }

    /**
     * Run initial AC-3 over every slot. Returns false if any domain
     * collapses to empty during propagation (layout is unfillable).
     */
    fun initialArcConsistency(): Boolean {
        val queue = ArrayDeque<Int>(slots.size)
        for (s in slots) {
            queue += s.sid
            inWorklist[s.sid] = true
        }
        return propagateWorklist(queue)
    }

    /**
     * Main solve loop. Returns [Result.OK] iff every slot is assigned.
     * On any other outcome, the state is rolled back to the value of
     * the trail at entry.
     */
    fun search(
        deadlineNs: Long,
        backtrackBudget: Int,
        cancelled: () -> Boolean,
    ): Result {
        val entryMark = trail.size
        if (assignedCount == slots.size) return Result.OK

        // Frame: (sid, candidate iterator, trail mark to unwind to on pop)
        data class Frame(val sid: Int, val values: ArrayDeque<Int>, val mark: Int)
        val stack = ArrayDeque<Frame>()
        val firstSid = selectSlot()
        if (firstSid < 0) {
            return if (assignedCount == slots.size) Result.OK else Result.OK
        }
        stack += Frame(firstSid, orderValues(firstSid), trail.size)

        while (stack.isNotEmpty()) {
            if (clock.nanoTime() > deadlineNs) {
                undoTo(entryMark)
                return Result.TIMEOUT
            }
            if (cancelled()) {
                undoTo(entryMark)
                return Result.TIMEOUT
            }
            if (backtracks >= backtrackBudget) {
                undoTo(entryMark)
                return Result.BUDGET_EXCEEDED
            }
            val top = stack.last()
            if (top.values.isEmpty()) {
                // Exhausted this frame; backtrack.
                undoTo(top.mark)
                stack.removeLast()
                backtracks++
                continue
            }
            val wi = top.values.removeFirst()
            val subMark = trail.size
            if (assign(top.sid, wi)) {
                if (assignedCount == slots.size) return Result.OK
                val nextSid = selectSlot()
                if (nextSid < 0) return Result.OK
                val nextSlot = slots[nextSid]
                if (lexicon.popcount(nextSlot.domain) == 0) {
                    undoTo(subMark)
                    continue
                }
                stack += Frame(nextSid, orderValues(nextSid), subMark)
            } else {
                undoTo(subMark)
            }
        }
        // Stack drained without finding a full assignment.
        undoTo(entryMark)
        return Result.BUDGET_EXCEEDED
    }

    // ---- selection heuristics ----

    /**
     * MRV (minimum remaining values): smallest live domain; ties broken
     * by highest crossing degree; remaining ties broken at random.
     * Returns -1 if no unassigned slot remains.
     */
    internal fun selectSlot(): Int {
        var bestSid = -1
        var bestSize = Int.MAX_VALUE
        var bestDeg = -1
        for (s in slots) {
            if (s.isAssigned) continue
            val size = lexicon.popcount(s.domain)
            if (size == 0) return s.sid // dead-end signal
            val deg = s.degree
            val better =
                size < bestSize ||
                    (size == bestSize && deg > bestDeg) ||
                    (size == bestSize && deg == bestDeg && random.nextBoolean())
            if (better) {
                bestSid = s.sid
                bestSize = size
                bestDeg = deg
            }
        }
        return bestSid
    }

    /**
     * Sampled-window LCV (least-constraining-value): score up to
     * [GenerationKnobs.LCV_SAMPLE_SIZE] random candidates by sum of
     * remaining crossing-domain sizes after the candidate; sort
     * ascending on (-score) → least constraining first; append
     * unsampled candidates at the tail.
     */
    internal fun orderValues(sid: Int): ArrayDeque<Int> {
        val slot = slots[sid]
        val candidates = lexicon.iterIndices(slot.domain).toMutableList()
        candidates.shuffle(random)
        val sampleSize = minOf(GenerationKnobs.LCV_SAMPLE_SIZE, candidates.size)
        if (sampleSize <= 1) return ArrayDeque(candidates)
        val sample = candidates.subList(0, sampleSize)
        val tail = candidates.subList(sampleSize, candidates.size)

        // Score the sample.
        data class Scored(val wi: Int, val score: Long)
        val scored = ArrayList<Scored>(sample.size)
        for (wi in sample) {
            val word = lexicon.wordAt(slot.length, wi)
            var ok = true
            var total = 0L
            for (p in 0 until slot.length) {
                val letter = word.text[p]
                for ((osid, opos) in slot.crossings[p]) {
                    val other = slots[osid]
                    if (other.isAssigned) {
                        if (other.assigned!!.text[opos] != letter) {
                            ok = false
                            break
                        }
                        continue
                    }
                    // Compute hypothetical pruned domain size.
                    val pruned = other.domain.copyOf()
                    val any = lexicon.filterByLetterInPlace(other.length, opos, letter, pruned)
                    if (!any) {
                        ok = false
                        break
                    }
                    total += lexicon.popcount(pruned)
                }
                if (!ok) break
            }
            if (ok) scored += Scored(wi, total)
        }
        scored.sortByDescending { it.score }
        val result = ArrayDeque<Int>(candidates.size)
        for (s in scored) result += s.wi
        for (wi in tail) result += wi
        return result
    }

    // ---- assignment + propagation ----

    /**
     * Try to assign `wordAt(slot.length, wi)` to slot [sid]. Records
     * inverses on the trail. Returns true on successful propagation
     * (caller should recurse); false on contradiction (caller must
     * undo to its own sub-mark).
     */
    internal fun assign(
        sid: Int,
        wi: Int,
    ): Boolean {
        val slot = slots[sid]
        val word = lexicon.wordAt(slot.length, wi)
        if (!acceptor.accepts(word)) return false
        val clue = acceptor.pickClue(word, random) ?: return false

        // Trail entries — must come before the mutation.
        trail += TrailEntry.Dom(sid, slot.domain)
        trail += TrailEntry.Asg(sid, slot.assigned, slot.chosenClue)
        trail += TrailEntry.Used(word, clue)

        // Mutate the slot.
        val singletonMask = LongArray(slot.domain.size)
        singletonMask[wi ushr 6] = 1L shl (wi and 63)
        slot.domain = singletonMask
        slot.assigned = word
        slot.chosenClue = clue
        acceptor.recordPlacement(word, clue)
        assignedCount++
        assignments++

        // Propagate to crossings via AC-3.
        val queue = ArrayDeque<Int>()
        for (p in 0 until slot.length) {
            val letter = word.text[p]
            for ((osid, opos) in slot.crossings[p]) {
                val other = slots[osid]
                if (other.isAssigned) {
                    if (other.assigned!!.text[opos] != letter) return false
                    continue
                }
                // Filter other's domain to words with `letter` at `opos`.
                val newMask = other.domain.copyOf()
                val any = lexicon.filterByLetterInPlace(other.length, opos, letter, newMask)
                if (!newMask.contentEquals(other.domain)) {
                    trail += TrailEntry.Dom(osid, other.domain)
                    other.domain = newMask
                    if (!any) return false
                    if (!inWorklist[osid]) {
                        queue += osid
                        inWorklist[osid] = true
                    }
                }
            }
        }
        return propagateWorklist(queue)
    }

    /**
     * AC-3 worklist propagation: repeatedly tighten slot domains via
     * crossing-letter constraints until quiescence. Returns false on
     * any empty domain.
     */
    private fun propagateWorklist(queue: ArrayDeque<Int>): Boolean {
        while (queue.isNotEmpty()) {
            val sid = queue.removeFirst()
            inWorklist[sid] = false
            val slot = slots[sid]
            if (slot.isAssigned) continue
            val oldMask = slot.domain
            var dom = oldMask.copyOf()
            for (p in 0 until slot.length) {
                val xs = slot.crossings[p]
                if (xs.isEmpty()) continue
                // Letters allowed at position p, given crossings.
                var allowed = ALL_LETTERS_MASK
                for ((osid, opos) in xs) {
                    val other = slots[osid]
                    allowed =
                        if (other.isAssigned) {
                            allowed and (1 shl (other.assigned!!.text[opos].code - 'A'.code))
                        } else {
                            allowed and lexicon.lettersAt(other.length, opos, other.domain)
                        }
                    if (allowed == 0) return false
                }
                // Restrict dom to words whose letter at p is in `allowed`.
                val unionMask = lexicon.unionMaskForLetters(slot.length, p, allowed)
                var changed = false
                for (i in dom.indices) {
                    val before = dom[i]
                    val after = before and unionMask[i]
                    if (after != before) changed = true
                    dom[i] = after
                }
                if (changed && lexicon.popcount(dom) == 0) {
                    // Save the old mask before reporting failure so undo works.
                    trail += TrailEntry.Dom(sid, oldMask)
                    slot.domain = dom
                    return false
                }
            }
            if (!dom.contentEquals(oldMask)) {
                trail += TrailEntry.Dom(sid, oldMask)
                slot.domain = dom
                // Reschedule neighbours.
                for (xs in slot.crossings) {
                    for ((osid, _) in xs) {
                        if (!inWorklist[osid] && !slots[osid].isAssigned) {
                            queue += osid
                            inWorklist[osid] = true
                        }
                    }
                }
            }
        }
        return true
    }

    // ---- undo ----

    internal fun undoTo(mark: Int) {
        while (trail.size > mark) {
            when (val entry = trail.removeLast()) {
                is TrailEntry.Dom -> slots[entry.sid].domain = entry.oldMask
                is TrailEntry.Asg -> {
                    val slot = slots[entry.sid]
                    if (slot.isAssigned && entry.oldWord == null) {
                        assignedCount--
                    }
                    slot.assigned = entry.oldWord
                    slot.chosenClue = entry.oldClue
                }
                is TrailEntry.Used -> acceptor.removePlacement(entry.word, entry.clue)
            }
        }
    }

    /** Find positions of cells inside hot (smallest-domain) slots. */
    fun hotSlotMiddleCells(topN: Int): List<Pair<Int, Int>> {
        val unsolved = slots.filter { !it.isAssigned }
        val sorted =
            unsolved.sortedBy { lexicon.popcount(it.domain) }
                .take(topN)
                .sortedByDescending { it.length }
        return sorted.map {
            val mid = it.cells[it.length / 2]
            mid.row.value to mid.column.value
        }
    }

    companion object {
        private const val ALL_LETTERS_MASK: Int = (1 shl Lexicon.LETTER_COUNT) - 1
    }
}
