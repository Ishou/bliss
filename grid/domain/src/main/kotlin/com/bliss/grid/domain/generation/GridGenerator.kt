package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.Word
import com.bliss.grid.domain.model.WordPlacement
import kotlin.random.Random

private const val GENERATION_TIMEOUT_MS = 5_000L

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

    // ── Interlocked generation: rectangular block CSP ──────────────

    /**
     * Generates a fully interlocked grid by:
     * 1. Partitioning rows into groups (separated by clue rows)
     * 2. Partitioning columns within each group (separated by clue cols)
     * 3. Each row-group × col-segment = a rectangular block
     * 4. Solving each block as a small CSP
     *
     * A block of height H × width W has:
     * - H horizontal words of length W
     * - W vertical words of length H
     * Every letter cell sits at an H×V crossing.
     */
    private fun generateInterlocked(
        constraints: GridConstraints,
        random: Random,
    ): Grid? {
        val w = constraints.width
        val h = constraints.height
        if (w < 4 || h < 4) return null
        val deadline = System.currentTimeMillis() + GENERATION_TIMEOUT_MS
        val minLen = constraints.minWordLength
        val maxLen = maxOf(w, h)

        // Available word lengths (those with actual dictionary words)
        val wordsByLength = (minLen..maxLen).associateWith { repository.findByLength(it) }
            .filterValues { it.isNotEmpty() }
        if (wordsByLength.isEmpty()) return null

        // Partition rows: interior = h-1 cells, split into groups of minLen..maxWordLen
        val maxWordLen = wordsByLength.keys.max()
        val rowPartitions = generatePartitions(h - 1, minLen, maxWordLen)
        if (rowPartitions.isEmpty()) return null

        // Try row partitions
        for (rowPartition in rowPartitions.shuffled(random)) {
            if (System.currentTimeMillis() > deadline) return null
            val rowGroups = computeSlotRanges(rowPartition) // list of (startRow, height)

            // For each row group, try column partitions
            val colPartitions = generatePartitions(w - 1, minLen, maxWordLen)
            if (colPartitions.isEmpty()) continue

            val result = tryRowGroups(
                w, h, rowGroups, 0, colPartitions, wordsByLength,
                mutableSetOf(), mutableListOf(), random, deadline,
            )
            if (result != null) return result
        }
        return null
    }

    /**
     * Recursively assigns column partitions to each row group and
     * solves the resulting blocks.
     */
    private fun tryRowGroups(
        gridWidth: Int,
        gridHeight: Int,
        rowGroups: List<Pair<Int, Int>>, // (startRow in interior, height)
        groupIdx: Int,
        colPartitions: List<List<Int>>,
        wordsByLength: Map<Int, List<Word>>,
        usedWords: MutableSet<String>,
        placements: MutableList<WordPlacement>,
        random: Random,
        deadline: Long,
    ): Grid? {
        if (groupIdx == rowGroups.size) {
            return try {
                Grid.fromPlacements(gridWidth, gridHeight, placements.toList())
            } catch (_: Exception) {
                null
            }
        }
        if (System.currentTimeMillis() > deadline) return null

        val (interiorStartRow, groupHeight) = rowGroups[groupIdx]

        for (colPartition in colPartitions.shuffled(random)) {
            if (System.currentTimeMillis() > deadline) return null

            val colSegments = computeSlotRanges(colPartition) // (startCol in interior, width)
            val blocks = colSegments.map { (startCol, blockWidth) ->
                RectBlock(interiorStartRow, startCol, groupHeight, blockWidth)
            }

            // Check all blocks have words for both dimensions
            val allValid = blocks.all { block ->
                wordsByLength.containsKey(block.width) && wordsByLength.containsKey(block.height)
            }
            if (!allValid) continue

            // Try solving all blocks in this group
            val savedPlacementsSize = placements.size
            val savedUsedWords = usedWords.toMutableSet()
            var allSolved = true

            for (block in blocks.shuffled(random)) {
                val solution = solveRectBlock(block, gridWidth, gridHeight, wordsByLength, usedWords, random, deadline)
                if (solution == null) {
                    allSolved = false
                    break
                }
                placements += solution
                for (p in solution) usedWords += p.word.text
            }

            if (allSolved) {
                val result = tryRowGroups(gridWidth, gridHeight, rowGroups, groupIdx + 1,
                    colPartitions, wordsByLength, usedWords, placements, random, deadline)
                if (result != null) return result
            }

            // Undo this group's placements
            while (placements.size > savedPlacementsSize) placements.removeAt(placements.lastIndex)
            usedWords.clear()
            usedWords += savedUsedWords
        }
        return null
    }

    private data class RectBlock(
        val interiorStartRow: Int, // 0-indexed in interior grid (grid row = this + 1)
        val interiorStartCol: Int, // 0-indexed in interior grid (grid col = this + 1)
        val height: Int,           // number of letter rows
        val width: Int,            // number of letter cols
    )

    /**
     * Solves a rectangular block: H horizontal words of length W,
     * W vertical words of length H, all crossings must agree.
     */
    private fun solveRectBlock(
        block: RectBlock,
        gridWidth: Int,
        gridHeight: Int,
        wordsByLength: Map<Int, List<Word>>,
        usedWords: Set<String>,
        random: Random,
        deadline: Long,
    ): List<WordPlacement>? {
        val hWords = wordsByLength[block.width] ?: return null
        val vWords = wordsByLength[block.height] ?: return null
        val available = hWords.filter { it.text !in usedWords }
        if (available.size < block.height) return null

        val vIndex = buildCharIndex(vWords.filter { it.text !in usedWords })
        val hAssigned = arrayOfNulls<Word>(block.height)
        val localUsed = mutableSetOf<String>()

        if (!fillBlockRows(block, available, vIndex, hAssigned, localUsed, 0, random, deadline)) {
            return null
        }

        // Build placements
        val placements = mutableListOf<WordPlacement>()

        for (i in 0 until block.height) {
            val gridRow = block.interiorStartRow + i + 1
            val word = hAssigned[i]!!

            if (block.interiorStartCol == 0 && block.width == gridWidth - 1) {
                // Full-width word: use DOWN_RIGHT from row above
                val clueRow = if (gridRow > 0) gridRow - 1 else 0
                placements += WordPlacement(word, Position(Row(clueRow), Column(0)), Direction.DOWN_RIGHT)
            } else if (block.interiorStartCol == 0) {
                placements += WordPlacement(word, Position(Row(gridRow), Column(0)), Direction.RIGHT)
            } else {
                val clueGridCol = block.interiorStartCol // separator col in grid coords
                placements += WordPlacement(word, Position(Row(gridRow), Column(clueGridCol)), Direction.RIGHT)
            }
        }

        // Derive vertical words
        val vAvailable = vWords.filter { it.text !in usedWords && it.text !in localUsed }
        for (j in 0 until block.width) {
            val text = String(CharArray(block.height) { i -> hAssigned[i]!!.text[j] })
            val vWord = vAvailable.firstOrNull { it.text == text }
                ?: vWords.firstOrNull { it.text == text && it.text !in usedWords && it.text !in localUsed }
                ?: return null
            localUsed += vWord.text

            val gridCol = block.interiorStartCol + j + 1

            if (block.interiorStartRow == 0 && block.height == gridHeight - 1) {
                // Full-height word: use RIGHT_DOWN from column to the left
                val clueCol = if (gridCol > 0) gridCol - 1 else 0
                placements += WordPlacement(vWord, Position(Row(0), Column(clueCol)), Direction.RIGHT_DOWN)
            } else if (block.interiorStartRow == 0) {
                placements += WordPlacement(vWord, Position(Row(0), Column(gridCol)), Direction.DOWN)
            } else {
                val clueGridRow = block.interiorStartRow // separator row in grid coords
                placements += WordPlacement(vWord, Position(Row(clueGridRow), Column(gridCol)), Direction.DOWN)
            }
        }

        return placements
    }

    private fun fillBlockRows(
        block: RectBlock,
        available: List<Word>,
        vIndex: Map<Pair<Int, Char>, Set<String>>,
        hAssigned: Array<Word?>,
        localUsed: MutableSet<String>,
        row: Int,
        random: Random,
        deadline: Long,
    ): Boolean {
        if (row == block.height) return true
        if (System.currentTimeMillis() > deadline) return false

        for (word in available.filter { it.text !in localUsed }.shuffled(random)) {
            hAssigned[row] = word
            localUsed += word.text

            val viable = (0 until block.width).all { j ->
                countVerticalCandidates(vIndex, hAssigned, j, row + 1, localUsed) > 0
            }

            if (viable && fillBlockRows(block, available, vIndex, hAssigned, localUsed, row + 1, random, deadline)) {
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

    // ── Partition helpers ───────────────────────────────────────────

    /**
     * Generates all valid partitions of [total] cells into word slots
     * of length [minLen]..[maxLen], separated by single clue cells.
     */
    private fun generatePartitions(total: Int, minLen: Int, maxLen: Int): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        partitionHelper(total, minLen, maxLen, mutableListOf(), result)
        return result
    }

    private fun partitionHelper(
        remaining: Int,
        minLen: Int,
        maxLen: Int,
        current: MutableList<Int>,
        result: MutableList<List<Int>>,
    ) {
        if (remaining == 0) {
            if (current.isNotEmpty()) result += current.toList()
            return
        }
        if (remaining < minLen) return
        for (len in minLen..minOf(maxLen, remaining)) {
            current += len
            val left = remaining - len
            if (left == 0) {
                result += current.toList()
            } else if (left >= minLen + 1) {
                partitionHelper(left - 1, minLen, maxLen, current, result)
            }
            current.removeAt(current.lastIndex)
        }
    }

    /**
     * Converts a partition [3, 4] into ranges: [(0, 3), (4, 4)]
     * where each pair is (startIndex, length). The gap between
     * segments is the separator cell.
     */
    private fun computeSlotRanges(partition: List<Int>): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var pos = 0
        for (i in partition.indices) {
            if (i > 0) pos++ // separator
            ranges += pos to partition[i]
            pos += partition[i]
        }
        return ranges
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
