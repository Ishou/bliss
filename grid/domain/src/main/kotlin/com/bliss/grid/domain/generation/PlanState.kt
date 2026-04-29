package com.bliss.grid.domain.generation

import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row

/**
 * Mutable scratch state for the slot planner's coordinated structural mutation.
 *
 * Tracks per-cell type (clue / letter / unset), the arrows on each clue cell and their
 * lifecycle (PENDING → MATERIALIZED | DEACTIVATED), and the materialised slots.
 *
 * Supports cheap backtracking via [checkpoint] / [rollback], which uses a single undo
 * log of inverse operations. Only state-mutating methods push undo entries.
 *
 * Not thread-safe.
 */
internal class PlanState(
    val width: Int,
    val height: Int,
) {
    enum class CellType { CLUE, LETTER }

    enum class ArrowState { PENDING, MATERIALIZED, DEACTIVATED }

    private val cellType: Array<Array<CellType?>> = Array(height) { arrayOfNulls(width) }

    /**
     * clue position → (direction → state). Both arrows may exist on a dual cell.
     *
     * `LinkedHashMap` (insertion-ordered) makes [nextPending] deterministic for a fixed
     * `random` seed — `Position`'s default `hashCode` would otherwise leak hash-code
     * differences across JVM instances into the planning order, breaking reproducibility
     * of stress-test failures.
     */
    private val arrowState: LinkedHashMap<Position, LinkedHashMap<Direction, ArrowState>> = LinkedHashMap()

    /** letter position → set of slot indices that include this letter. */
    private val letterMembership: HashMap<Position, MutableSet<Int>> = HashMap()

    /** Indexed slot list — index used in [letterMembership] and slot lookups. */
    private val _slots: ArrayList<WordSlot> = ArrayList()
    val slots: List<WordSlot> get() = _slots

    private val undo: ArrayDeque<() -> Unit> = ArrayDeque()

    // ── Mutation API ─────────────────────────────────────────────

    /** Mark [pos] as a clue cell. No-op if already a clue. Errors if already a letter. */
    fun addClueCell(pos: Position) {
        require(inBounds(pos)) { "out of bounds: $pos" }
        val current = cellType[pos.row.value][pos.column.value]
        when (current) {
            CellType.CLUE -> Unit
            CellType.LETTER -> error("cell $pos already a letter; cannot become a clue")
            null -> {
                cellType[pos.row.value][pos.column.value] = CellType.CLUE
                undo.addLast { cellType[pos.row.value][pos.column.value] = null }
            }
        }
    }

    /** Add an arrow on [pos] in PENDING state. Errors if [pos] isn't yet a clue cell. */
    fun addArrow(
        pos: Position,
        direction: Direction,
    ) {
        require(cellType[pos.row.value][pos.column.value] == CellType.CLUE) {
            "cannot add arrow to non-clue cell $pos"
        }
        val map = arrowState.getOrPut(pos) { LinkedHashMap() }
        if (direction in map) return // idempotent
        map[direction] = ArrowState.PENDING
        undo.addLast {
            map.remove(direction)
            if (map.isEmpty()) arrowState.remove(pos)
        }
    }

    /** Walk from [firstLetter(arrow)] along [arrow.direction]'s step until edge or clue cell. */
    fun availableLength(arrow: ClueArrow): Int {
        val first = firstLetter(arrow)
        val dr = arrow.direction.step.row.value
        val dc = arrow.direction.step.column.value
        var n = 0
        var r = first.row.value
        var c = first.column.value
        while (r in 0 until height && c in 0 until width) {
            if (cellType[r][c] == CellType.CLUE) break
            n++
            r += dr
            c += dc
        }
        return n
    }

    fun arrowState(
        pos: Position,
        direction: Direction,
    ): ArrowState? = arrowState[pos]?.get(direction)

    private fun setArrowState(
        pos: Position,
        direction: Direction,
        state: ArrowState,
    ) {
        val map = arrowState.getValue(pos)
        val prev = map.getValue(direction)
        if (prev == state) return
        map[direction] = state
        undo.addLast { map[direction] = prev }
    }

    fun deactivate(
        pos: Position,
        direction: Direction,
    ) {
        setArrowState(pos, direction, ArrowState.DEACTIVATED)
    }

    /**
     * Materialize an arrow with [length] letters. Marks letter cells and, when [length] is
     * less than [availableLength], places a trailing clue cell after the last letter with
     * a same-axis continuation arrow plus a perpendicular continuation arrow (see ADR-0015
     * §2). Returns `true` on success, `false` on a hard structural conflict (e.g. a letter
     * cell would land on an existing clue, or the trailing clue would land on an existing
     * letter). The caller is responsible for `rollback` on `false` — partial mutations
     * remain in the undo log.
     *
     * Pending arrows whose paths the trailing clue blocks are *not* eagerly invalidated
     * here; [solveVariable] picks them up via [nextPending] and deactivates them when
     * their [availableLength] drops below 2. Lazy invalidation keeps this method small
     * and lets the solver decide deactivate-vs-backtrack uniformly.
     */
    fun materialize(
        arrow: ClueArrow,
        length: Int,
    ): Boolean {
        require(length >= 2) { "length must be ≥ 2, was $length" }
        val first = firstLetter(arrow)
        val dr = arrow.direction.step.row.value
        val dc = arrow.direction.step.column.value

        // Mark each cell as a letter (or no-op if already a letter / fail if a clue cell).
        val slotIdx = _slots.size
        for (i in 0 until length) {
            val r = first.row.value + dr * i
            val c = first.column.value + dc * i
            if (r !in 0 until height || c !in 0 until width) return false
            val pos = Position(Row(r), Column(c))
            val ct = cellType[r][c]
            when (ct) {
                CellType.CLUE -> return false
                CellType.LETTER -> Unit // crossing — fine
                null -> {
                    cellType[r][c] = CellType.LETTER
                    undo.addLast { cellType[r][c] = null }
                }
            }
            val members = letterMembership.getOrPut(pos) { HashSet() }
            members += slotIdx
            undo.addLast {
                members -= slotIdx
                if (members.isEmpty()) letterMembership.remove(pos)
            }
        }

        // Add the slot.
        _slots += WordSlot(arrow.cluePosition, arrow.direction, length)
        undo.addLast { _slots.removeAt(_slots.size - 1) }

        // Mark the arrow MATERIALIZED.
        setArrowState(arrow.cluePosition, arrow.direction, ArrowState.MATERIALIZED)

        // If the slot stops short, place trailing clue + continuation arrow(s).
        val trailR = first.row.value + dr * length
        val trailC = first.column.value + dc * length
        if (trailR in 0 until height && trailC in 0 until width) {
            val trail = Position(Row(trailR), Column(trailC))
            val trailExisting = cellType[trailR][trailC]
            when (trailExisting) {
                CellType.CLUE -> Unit // already a clue, no-op
                CellType.LETTER -> return false // trailing-clue would orphan an existing letter
                null -> {
                    addClueCell(trail)
                    // Continuation arrow in the same axis (extends the original word past the
                    // trailing clue) AND a perpendicular arrow (replaces the perpendicular
                    // skeleton arrow that this trailing clue invalidates — see ADR-0015 §2).
                    // The perpendicular arrow's first letter must be in bounds; otherwise the
                    // arrow would be useless and we skip it.
                    val sameAxisDir = continuationDirection(arrow.direction)
                    addArrow(trail, sameAxisDir)

                    val perpDir = perpendicularDirection(arrow.direction)
                    val perpFirstR = trailR + perpDir.startOffset.row.value
                    val perpFirstC = trailC + perpDir.startOffset.column.value
                    if (perpFirstR in 0 until height && perpFirstC in 0 until width) {
                        addArrow(trail, perpDir)
                    }
                }
            }
        }

        return true
    }

    /** Returns the next pending arrow, or null if none remain. */
    fun nextPending(): ClueArrow? {
        for ((cluePos, dirs) in arrowState) {
            for ((dir, st) in dirs) {
                if (st == ArrowState.PENDING) return ClueArrow(cluePos, dir)
            }
        }
        return null
    }

    /**
     * Validate the final plan:
     * - every clue cell has ≥1 MATERIALIZED arrow
     * - every letter cell belongs to ≥1 slot
     * - no UNSET cells (every cell must be claimed by at least one slot or be a clue)
     */
    fun validate(): ValidationResult {
        val dead = ArrayList<Position>()
        val orphans = ArrayList<Position>()
        for (r in 0 until height) {
            for (c in 0 until width) {
                val pos = Position(Row(r), Column(c))
                when (cellType[r][c]) {
                    CellType.CLUE -> {
                        val dirs = arrowState[pos] ?: emptyMap()
                        if (dirs.values.none { it == ArrowState.MATERIALIZED }) dead += pos
                    }
                    CellType.LETTER -> {
                        if (letterMembership[pos].isNullOrEmpty()) orphans += pos
                    }
                    null -> orphans += pos // UNSET — never claimed by any slot
                }
            }
        }
        return ValidationResult(dead, orphans)
    }

    // ── Backtracking ─────────────────────────────────────────────

    fun checkpoint(): Int = undo.size

    fun rollback(cp: Int) {
        while (undo.size > cp) undo.removeLast()()
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun inBounds(pos: Position): Boolean = pos.row.value in 0 until height && pos.column.value in 0 until width

    internal data class ValidationResult(
        val deadClueCells: List<Position>,
        val orphanLetterCells: List<Position>,
    ) {
        val ok: Boolean get() = deadClueCells.isEmpty() && orphanLetterCells.isEmpty()
    }
}

internal fun firstLetter(arrow: ClueArrow): Position =
    Position(
        Row(arrow.cluePosition.row.value + arrow.direction.startOffset.row.value),
        Column(arrow.cluePosition.column.value + arrow.direction.startOffset.column.value),
    )

/**
 * Canonical interior direction for trailing-clue continuation. The boundary directions
 * (DOWN_RIGHT / RIGHT_DOWN) only make sense at row 0 / col 0; once we're inserting a
 * clue cell mid-grid, the continuation flows along the same axis using the standard
 * interior direction (RIGHT or DOWN).
 */
internal fun continuationDirection(direction: Direction): Direction =
    when (direction) {
        Direction.DOWN_RIGHT, Direction.RIGHT -> Direction.RIGHT
        Direction.RIGHT_DOWN, Direction.DOWN -> Direction.DOWN
    }

/**
 * Direction of the *perpendicular* continuation arrow on a trailing clue. A trailing
 * clue cell often replaces the perpendicular skeleton arrow it invalidates (whose
 * word the trailing clue cuts at its first letter or mid-path); the perpendicular
 * arrow on the trail picks up the cells past the trail in the perpendicular axis.
 */
internal fun perpendicularDirection(direction: Direction): Direction =
    when (direction) {
        Direction.DOWN_RIGHT, Direction.RIGHT -> Direction.DOWN
        Direction.RIGHT_DOWN, Direction.DOWN -> Direction.RIGHT
    }
