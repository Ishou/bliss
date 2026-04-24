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
import com.bliss.grid.domain.model.WordPlacement

internal class WorkingGrid(val width: Int, val height: Int) {

    private val cells: Array<Array<Cell>> = Array(height) { Array(width) { EmptyCell as Cell } }
    private val _placements: MutableList<WordPlacement> = mutableListOf()

    val placements: List<WordPlacement> get() = _placements

    fun cellAt(position: Position): Cell = cells[position.row.value][position.column.value]

    fun place(placement: WordPlacement): Boolean {
        if (!fits(placement)) return false

        val cluePos = placement.cluePosition
        val newClue = Clue(placement.word.definition, placement.direction)
        cells[cluePos.row.value][cluePos.column.value] = when (val existing = cells[cluePos.row.value][cluePos.column.value]) {
            EmptyCell -> ClueCell(listOf(newClue))
            is ClueCell -> ClueCell(existing.clues + newClue)
            is LetterCell -> error("unreachable: fits() should have rejected this")
        }
        for ((pos, ch) in placement.letterPositions()) {
            cells[pos.row.value][pos.column.value] = LetterCell(ch)
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

    fun candidatePlacements(minLength: Int, maxLength: Int): List<CandidatePlacement> {
        val candidates = mutableListOf<CandidatePlacement>()
        for (r in 0 until height) {
            for (c in 0 until width) {
                val p = Position(Row(r), Column(c))
                val cell = cells[r][c]
                for (dir in Direction.entries) {
                    if (!cluePositionAvailable(cell, dir)) continue
                    val dr = dir.offset.row.value
                    val dc = dir.offset.column.value
                    var length = 0
                    while (true) {
                        val nr = r + dr * (length + 1)
                        val nc = c + dc * (length + 1)
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

    fun patternAt(cluePosition: Position, direction: Direction, length: Int): Map<Int, Char> {
        val pattern = mutableMapOf<Int, Char>()
        val dr = direction.offset.row.value
        val dc = direction.offset.column.value
        for (i in 0 until length) {
            val r = cluePosition.row.value + dr * (i + 1)
            val c = cluePosition.column.value + dc * (i + 1)
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

    private fun cluePositionAvailable(cell: Cell, dir: Direction): Boolean = when (cell) {
        EmptyCell -> true
        is ClueCell -> cell.clues.none { it.direction == dir }
        is LetterCell -> false
    }

    private fun inBounds(p: Position): Boolean =
        p.row.value in 0 until height && p.column.value in 0 until width

    private fun rebuildCells() {
        for (r in 0 until height) for (c in 0 until width) cells[r][c] = EmptyCell
        val cluesByPosition = mutableMapOf<Position, MutableList<Clue>>()
        for (placement in _placements) {
            cluesByPosition.getOrPut(placement.cluePosition) { mutableListOf() }
                .add(Clue(placement.word.definition, placement.direction))
            for ((pos, ch) in placement.letterPositions()) {
                cells[pos.row.value][pos.column.value] = LetterCell(ch)
            }
        }
        for ((pos, clues) in cluesByPosition) {
            cells[pos.row.value][pos.column.value] = ClueCell(clues.toList())
        }
    }
}
