package com.bliss.grid.domain.model

class Grid internal constructor(
    val width: Int,
    val height: Int,
    val cells: Map<Position, Cell>,
    val placements: List<WordPlacement>,
) {
    companion object {
        fun fromPlacements(
            width: Int,
            height: Int,
            placements: List<WordPlacement>,
        ): Grid {
            require(width > 0 && height > 0) { "Grid dimensions must be positive (got ${width}x$height)" }
            requireNoDuplicateWords(placements)

            val letters = mutableMapOf<Position, Char>()
            val clues = mutableMapOf<Position, MutableList<Clue>>()

            for (placement in placements) {
                requireInBounds(placement.cluePosition, width, height)
                clues
                    .getOrPut(placement.cluePosition) { mutableListOf() }
                    .add(Clue(placement.word.definition, placement.direction))

                for ((pos, ch) in placement.letterPositions()) {
                    requireInBounds(pos, width, height)
                    val existing = letters[pos]
                    require(existing == null || existing == ch) {
                        "Words intersect inconsistently at $pos: '$existing' vs '$ch'"
                    }
                    letters[pos] = ch
                }
            }

            val overlap = letters.keys.intersect(clues.keys)
            require(overlap.isEmpty()) {
                "Letter and clue cells overlap at ${overlap.first()}"
            }

            val cells: Map<Position, Cell> =
                buildMap {
                    clues.forEach { (pos, list) -> put(pos, ClueCell(list.toList())) }
                    letters.forEach { (pos, ch) -> put(pos, LetterCell(ch)) }
                }

            return Grid(width, height, cells, placements.toList())
        }

        private fun requireNoDuplicateWords(placements: List<WordPlacement>) {
            val seen = mutableSetOf<String>()
            for (p in placements) {
                require(seen.add(p.word.text)) { "Grid contains duplicate word '${p.word.text}'" }
            }
        }

        private fun requireInBounds(
            p: Position,
            width: Int,
            height: Int,
        ) {
            require(p.row.value in 0 until height && p.column.value in 0 until width) {
                "Position $p is out of bounds for ${width}x$height grid"
            }
        }
    }
}
