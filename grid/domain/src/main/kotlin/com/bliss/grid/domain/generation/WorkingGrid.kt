package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Cell
import com.bliss.grid.domain.model.Clue
import com.bliss.grid.domain.model.ClueCell
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.EmptyCell
import com.bliss.grid.domain.model.Grid
import com.bliss.grid.domain.model.LetterCell
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import com.bliss.grid.domain.model.WordAxis
import com.bliss.grid.domain.model.WordPlacement

internal class WorkingGrid(
    val width: Int,
    val height: Int,
) {
    private val cells: Array<Array<Cell>> = Array(height) { Array(width) { EmptyCell as Cell } }
    private val _placements: MutableList<WordPlacement> = mutableListOf()
    private val horizontalCoverage: MutableSet<Position> = mutableSetOf()
    private val verticalCoverage: MutableSet<Position> = mutableSetOf()

    val placements: List<WordPlacement> get() = _placements

    fun cellAt(position: Position): Cell = cells[position.row.value][position.column.value]

    fun place(placement: WordPlacement): Boolean {
        if (!fits(placement)) return false

        val cluePos = placement.cluePosition
        val newClue = Clue(placement.word.definition, placement.direction)
        cells[cluePos.row.value][cluePos.column.value] =
            when (val existing = cells[cluePos.row.value][cluePos.column.value]) {
                EmptyCell -> ClueCell(listOf(newClue))
                is ClueCell -> ClueCell(existing.clues + newClue)
                is LetterCell -> error("unreachable: fits() should have rejected this")
            }
        val letterPositions = placement.letterPositions()
        for ((pos, ch) in letterPositions) {
            cells[pos.row.value][pos.column.value] = LetterCell(ch)
        }
        val positions = letterPositions.map { it.first }
        when (placement.direction.axis) {
            WordAxis.HORIZONTAL -> horizontalCoverage.addAll(positions)
            WordAxis.VERTICAL -> verticalCoverage.addAll(positions)
        }
        _placements += placement
        return true
    }

    fun undo(placement: WordPlacement) {
        require(_placements.lastOrNull() == placement) {
            "undo must remove the most recently placed word"
        }
        _placements.removeAt(_placements.lastIndex)
        rebuildCells()
    }

    fun candidatePlacements(
        minLength: Int,
        maxLength: Int,
    ): List<CandidatePlacement> {
        val candidates = mutableListOf<CandidatePlacement>()
        for (r in 0 until height) {
            for (c in 0 until width) {
                val p = Position(Row(r), Column(c))
                val cell = cells[r][c]
                for (dir in Direction.entries) {
                    // Edge directions are restricted to the first column/row.
                    if (dir == Direction.DOWN_RIGHT && c != 0) continue
                    if (dir == Direction.RIGHT_DOWN && r != 0) continue

                    if (!cluePositionAvailable(cell, dir)) continue

                    val startR = r + dir.startOffset.row.value
                    val startC = c + dir.startOffset.column.value
                    val dr = dir.step.row.value
                    val dc = dir.step.column.value

                    var length = 0
                    while (true) {
                        val nr = startR + dr * length
                        val nc = startC + dc * length
                        if (nr !in 0 until height || nc !in 0 until width) break
                        val nextCell = cells[nr][nc]
                        if (nextCell is ClueCell) break
                        length++
                        if (length in minLength..maxLength) {
                            candidates += CandidatePlacement(p, dir, length)
                        }
                    }
                }
            }
        }
        return candidates
    }

    fun patternAt(
        cluePosition: Position,
        direction: Direction,
        length: Int,
    ): Map<Int, Char> {
        val pattern = mutableMapOf<Int, Char>()
        val startR = cluePosition.row.value + direction.startOffset.row.value
        val startC = cluePosition.column.value + direction.startOffset.column.value
        val dr = direction.step.row.value
        val dc = direction.step.column.value
        for (i in 0 until length) {
            val r = startR + dr * i
            val c = startC + dc * i
            val cell = cells[r][c]
            if (cell is LetterCell) pattern[i] = cell.letter
        }
        return pattern
    }

    fun density(): Double {
        var letters = 0
        for (row in cells) for (cell in row) if (cell is LetterCell) letters++
        return letters.toDouble() / (width * height).toDouble()
    }

    /**
     * Checks whether every letter cell satisfies the interlocking rule:
     * - interior cells (row > 0 and col > 0) must be in both a horizontal
     *   and a vertical word
     * - edge cells (row == 0 or col == 0) must be in at least one
     */
    fun isFullyInterlocked(): Boolean {
        for (r in 0 until height) {
            for (c in 0 until width) {
                if (cells[r][c] !is LetterCell) continue
                val pos = Position(Row(r), Column(c))
                val inH = pos in horizontalCoverage
                val inV = pos in verticalCoverage
                val isEdge = r == 0 || c == 0
                val valid = if (isEdge) inH || inV else inH && inV
                if (!valid) return false
            }
        }
        return true
    }

    /**
     * Counts how many currently-uncrossed letter cells the given candidate
     * would fix by covering them in its axis. Higher = more urgent to place.
     */
    fun uncrossedFixCount(candidate: CandidatePlacement): Int {
        val coverageForAxis = when (candidate.direction.axis) {
            WordAxis.HORIZONTAL -> horizontalCoverage
            WordAxis.VERTICAL -> verticalCoverage
        }
        val startR = candidate.cluePosition.row.value + candidate.direction.startOffset.row.value
        val startC = candidate.cluePosition.column.value + candidate.direction.startOffset.column.value
        val dr = candidate.direction.step.row.value
        val dc = candidate.direction.step.column.value
        var fixes = 0
        for (i in 0 until candidate.length) {
            val r = startR + dr * i
            val c = startC + dc * i
            if (cells[r][c] !is LetterCell) continue
            val pos = Position(Row(r), Column(c))
            if (pos !in coverageForAxis) fixes++
        }
        return fixes
    }

    fun toGrid(): Grid = Grid.fromPlacements(width, height, _placements.toList())

    private fun fits(placement: WordPlacement): Boolean {
        val cluePos = placement.cluePosition
        if (!inBounds(cluePos)) return false
        val clueCell = cells[cluePos.row.value][cluePos.column.value]
        if (!cluePositionAvailable(clueCell, placement.direction)) return false

        for ((pos, ch) in placement.letterPositions()) {
            if (!inBounds(pos)) return false
            when (val target = cells[pos.row.value][pos.column.value]) {
                EmptyCell -> {}
                is LetterCell -> if (target.letter != ch) return false
                is ClueCell -> return false
            }
        }
        return true
    }

    /** A cell can hold at most one clue per axis (one horizontal, one vertical). */
    private fun cluePositionAvailable(
        cell: Cell,
        dir: Direction,
    ): Boolean =
        when (cell) {
            EmptyCell -> true
            is ClueCell -> cell.clues.none { it.direction.axis == dir.axis }
            is LetterCell -> false
        }

    private fun inBounds(p: Position): Boolean = p.row.value in 0 until height && p.column.value in 0 until width

    private fun rebuildCells() {
        for (r in 0 until height) for (c in 0 until width) cells[r][c] = EmptyCell
        horizontalCoverage.clear()
        verticalCoverage.clear()
        val cluesByPosition = mutableMapOf<Position, MutableList<Clue>>()
        for (placement in _placements) {
            cluesByPosition
                .getOrPut(placement.cluePosition) { mutableListOf() }
                .add(Clue(placement.word.definition, placement.direction))
            val positions = placement.letterPositions()
            for ((pos, ch) in positions) {
                cells[pos.row.value][pos.column.value] = LetterCell(ch)
            }
            val posSet = positions.map { it.first }
            when (placement.direction.axis) {
                WordAxis.HORIZONTAL -> horizontalCoverage.addAll(posSet)
                WordAxis.VERTICAL -> verticalCoverage.addAll(posSet)
            }
        }
        for ((pos, clues) in cluesByPosition) {
            cells[pos.row.value][pos.column.value] = ClueCell(clues.toList())
        }
    }
}
