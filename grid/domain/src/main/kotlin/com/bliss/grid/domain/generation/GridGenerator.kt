package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import kotlin.random.Random

class GridGenerator(
    private val repository: WordRepository,
) {
    fun generate(
        constraints: GridConstraints,
        random: Random = Random.Default,
    ): Grid? =
        if (constraints.enforceInterlocking) {
            generateInterlocked(constraints, random)
        } else {
            generateGreedy(constraints, random)
        }

    // ── Interlocked generation: block-based CSP ────────────────────

    /**
     * Generates a fully interlocked grid by dividing it into independent
     * square blocks separated by clue/block rows and columns. Each block
     * is a small CSP: N horizontal + N vertical word slots, all of length
     * N, where every letter cell sits at an H×V crossing.
     *
     * Layout for 10×10 with block size 4 (separators at 0, 5):
     * ```
     * row 0: [B ][C↓][C↓][C↓][C↓][B ][C↓][C↓][C↓][C↓]
     * row 1: [C→][ L][ L][ L][ L][C→][ L][ L][ L][ L]
     * row 2: [C→][ L][ L][ L][ L][C→][ L][ L][ L][ L]
     * row 3: [C→][ L][ L][ L][ L][C→][ L][ L][ L][ L]
     * row 4: [C→][ L][ L][ L][ L][C→][ L][ L][ L][ L]
     * row 5: [B ][C↓][C↓][C↓][C↓][B ][C↓][C↓][C↓][C↓]
     * row 6: [C→][ L][ L][ L][ L][C→][ L][ L][ L][ L]
     *  ...
     * ```
     */
    private fun generateInterlocked(
        constraints: GridConstraints,
        random: Random,
    ): Grid? {
        val w = constraints.width
        val h = constraints.height
        val blockSize = pickBlockSize(w, h) ?: return null

        val blocks = enumerateBlocks(w, h, blockSize)
        if (blocks.isEmpty()) return null

        val allWords = repository.findByLength(blockSize)
        if (allWords.size < blockSize * 2) return null

        val usedWords = mutableSetOf<String>()
        val allPlacements = mutableListOf<WordPlacement>()

        for (block in blocks.shuffled(random)) {
            val solution = solveBlock(block, allWords, usedWords, random) ?: return null
            allPlacements += solution
            for (p in solution) usedWords += p.word.text
        }

        return Grid.fromPlacements(w, h, allPlacements)
    }

    /**
     * Picks a block size in [3..7] that evenly tiles the interior of the
     * grid. Interior = (size - 1) cells per dimension after the first
     * clue row/col. We need (interior) to be divisible by (blockSize + 1)
     * with the last segment also being exactly blockSize.
     */
    private fun pickBlockSize(width: Int, height: Int): Int? {
        val interiorW = width - 1   // cells after col 0
        val interiorH = height - 1  // cells after row 0
        // Try each candidate. block + 1 separator tiles the interior.
        // The last block doesn't need a trailing separator, so:
        // interior = n * blockSize + (n - 1) * 1 = n * (blockSize + 1) - 1
        // → n = (interior + 1) / (blockSize + 1), must be integer, blockSize ≤ 7
        for (bs in listOf(3, 4, 5, 6, 7)) {
            val step = bs + 1
            if ((interiorW + 1) % step == 0 && (interiorH + 1) % step == 0) {
                return bs
            }
        }
        return null
    }

    /**
     * Enumerates all independent blocks in the grid given the block size.
     * Separator rows/cols are at positions 0, blockSize+1, 2*(blockSize+1), ...
     */
    private fun enumerateBlocks(width: Int, height: Int, blockSize: Int): List<Block> {
        val step = blockSize + 1
        val blocks = mutableListOf<Block>()
        var sepRow = 0
        while (sepRow + blockSize < height) {
            var sepCol = 0
            while (sepCol + blockSize < width) {
                blocks += Block(
                    rowRange = (sepRow + 1)..(sepRow + blockSize),
                    colRange = (sepCol + 1)..(sepCol + blockSize),
                    hClueCols = listOf(sepCol),  // RIGHT clue at separator col
                    vClueRows = listOf(sepRow),  // DOWN clue at separator row
                )
                sepCol += step
            }
            sepRow += step
        }
        return blocks
    }

    private data class Block(
        val rowRange: IntRange,
        val colRange: IntRange,
        val hClueCols: List<Int>,
        val vClueRows: List<Int>,
    )

    /**
     * Solves a single block using backtracking: assigns horizontal words
     * row by row, forward-checking that every column still has at least
     * one compatible vertical word.
     */
    private fun solveBlock(
        block: Block,
        allWords: List<Word>,
        usedWords: Set<String>,
        random: Random,
    ): List<WordPlacement>? {
        val rows = block.rowRange.toList()
        val cols = block.colRange.toList()
        val n = rows.size
        val available = allWords.filter { it.text !in usedWords }
        if (available.size < n * 2) return null

        val index = buildCharIndex(available)
        val hAssigned = arrayOfNulls<Word>(n)
        val localUsed = mutableSetOf<String>()

        if (!fillBlockRows(rows, cols, available, index, hAssigned, localUsed, 0, random)) {
            return null
        }

        // Build placements
        val placements = mutableListOf<WordPlacement>()
        val hClueCol = block.hClueCols.first()
        val vClueRow = block.vClueRows.first()

        // Horizontal placements
        for (i in rows.indices) {
            placements += WordPlacement(
                hAssigned[i]!!,
                Position(Row(rows[i]), Column(hClueCol)),
                Direction.RIGHT,
            )
        }

        // Derive vertical words
        for (j in cols.indices) {
            val text = String(CharArray(n) { i -> hAssigned[i]!!.text[j] })
            val vWord = available.firstOrNull { it.text == text && it.text !in localUsed }
                ?: return null
            localUsed += vWord.text
            placements += WordPlacement(
                vWord,
                Position(Row(vClueRow), Column(cols[j])),
                Direction.DOWN,
            )
        }

        return placements
    }

    private fun fillBlockRows(
        rows: List<Int>,
        cols: List<Int>,
        available: List<Word>,
        index: Map<Pair<Int, Char>, Set<String>>,
        hAssigned: Array<Word?>,
        localUsed: MutableSet<String>,
        row: Int,
        random: Random,
    ): Boolean {
        if (row == rows.size) return true

        val candidates = available.filter { it.text !in localUsed }.shuffled(random)

        for (word in candidates) {
            hAssigned[row] = word
            localUsed += word.text

            // Forward check: every column must still have ≥1 valid vertical word
            val viable = cols.indices.all { j ->
                countVerticalCandidates(index, hAssigned, j, row + 1, localUsed) > 0
            }

            if (viable && fillBlockRows(rows, cols, available, index, hAssigned, localUsed, row + 1, random)) {
                return true
            }

            localUsed -= word.text
            hAssigned[row] = null
        }
        return false
    }

    private fun countVerticalCandidates(
        index: Map<Pair<Int, Char>, Set<String>>,
        hAssigned: Array<Word?>,
        col: Int,
        assignedCount: Int,
        localUsed: Set<String>,
    ): Int {
        if (assignedCount == 0) return Int.MAX_VALUE
        var candidates: Set<String>? = null
        for (i in 0 until assignedCount) {
            val letter = hAssigned[i]!!.text[col]
            val matching = index[i to letter] ?: return 0
            candidates = if (candidates == null) matching.toSet() else candidates.intersect(matching)
            if (candidates.isEmpty()) return 0
        }
        return candidates!!.count { it !in localUsed }
    }

    private fun buildCharIndex(words: List<Word>): Map<Pair<Int, Char>, Set<String>> {
        val idx = mutableMapOf<Pair<Int, Char>, MutableSet<String>>()
        for (word in words) {
            for (i in word.text.indices) {
                idx.getOrPut(i to word.text[i]) { mutableSetOf() } += word.text
            }
        }
        return idx
    }

    // ── Greedy search (no interlocking guarantee) ──────────────────

    private fun generateGreedy(
        constraints: GridConstraints,
        random: Random,
    ): Grid? {
        val working = WorkingGrid(constraints.width, constraints.height)
        val maxLength = maxOf(constraints.width, constraints.height) - 1
        val attempts = intArrayOf(0)
        if (!searchGreedy(working, constraints, maxLength, mutableSetOf(), attempts, random)) return null
        return working.toGrid()
    }

    private fun searchGreedy(
        working: WorkingGrid,
        constraints: GridConstraints,
        maxLength: Int,
        usedWords: MutableSet<String>,
        attempts: IntArray,
        random: Random,
    ): Boolean {
        if (working.density() >= constraints.targetDensity) return true
        if (attempts[0]++ >= constraints.maxAttempts) return false

        val ranked =
            working
                .candidatePlacements(constraints.minWordLength, maxLength)
                .mapNotNull { candidate ->
                    val pattern = working.patternAt(candidate.cluePosition, candidate.direction, candidate.length)
                    val matches =
                        repository
                            .findByLengthAndPattern(candidate.length, pattern)
                            .filter { it.text !in usedWords }
                    if (matches.isEmpty()) null else candidate to matches
                }.sortedBy { it.second.size }
                .shuffledStableGreedy(random)

        for ((candidate, matches) in ranked) {
            for (word in matches.shuffled(random)) {
                val placement = WordPlacement(word, candidate.cluePosition, candidate.direction)
                if (working.place(placement)) {
                    usedWords += word.text
                    if (searchGreedy(working, constraints, maxLength, usedWords, attempts, random)) return true
                    usedWords -= word.text
                    working.undo(placement)
                }
            }
        }
        return false
    }

    private fun List<Pair<CandidatePlacement, List<Word>>>.shuffledStableGreedy(
        random: Random,
    ): List<Pair<CandidatePlacement, List<Word>>> =
        groupBy { it.second.size }
            .toSortedMap()
            .values
            .flatMap { bucket -> bucket.shuffled(random) }
}
