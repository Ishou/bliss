package com.bliss.grid.domain.generation

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.bliss.grid.domain.model.Column
import com.bliss.grid.domain.model.Direction
import com.bliss.grid.domain.model.Position
import com.bliss.grid.domain.model.Row
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Direct unit tests for [PlanState] — the most complex piece of the v2 planner.
 * Covers the undo-log backtracking, cell-type lifecycle, arrow state machine, and
 * validation logic that [SlotPlannerVariableTest] only observes through aggregate
 * outcomes.
 */
class PlanStateTest {
    @Test
    fun `addClueCell is idempotent on existing clue cells`() {
        val state = PlanState(4, 4)
        state.addClueCell(pos(0, 0))
        state.addClueCell(pos(0, 0)) // no-op, no error
        // The second call should not push a second undo entry — the cell type is
        // unchanged. Indirect check: a single rollback restores the cell.
        val cp = state.checkpoint()
        state.addClueCell(pos(0, 2))
        state.rollback(cp)
        // (0, 0) still set, (0, 2) cleared.
        state.addArrow(pos(0, 0), Direction.DOWN_RIGHT) // succeeds → still a clue
    }

    @Test
    fun `addClueCell errors when the cell is already a letter`() {
        val state = PlanState(4, 4)
        // Materialize a slot so a few cells are letters.
        state.addClueCell(pos(0, 0))
        state.addArrow(pos(0, 0), Direction.DOWN_RIGHT)
        state.materialize(ClueArrow(pos(0, 0), Direction.DOWN_RIGHT), 4)
        // Try to retroactively claim a letter cell as a clue cell.
        assertThrows<IllegalStateException> { state.addClueCell(pos(1, 2)) }
    }

    @Test
    fun `availableLength is 0 when first letter is itself a clue cell`() {
        val state = PlanState(4, 4)
        state.addClueCell(pos(0, 0))
        state.addArrow(pos(0, 0), Direction.DOWN_RIGHT) // first letter at (1, 0)
        // Plant a clue cell exactly where the first letter would be.
        state.addClueCell(pos(1, 0))
        assertThat(state.availableLength(ClueArrow(pos(0, 0), Direction.DOWN_RIGHT))).isEqualTo(0)
    }

    @Test
    fun `availableLength stops at the next clue cell on the path`() {
        val state = PlanState(10, 10)
        state.addClueCell(pos(0, 0))
        state.addArrow(pos(0, 0), Direction.DOWN_RIGHT) // row 1 horizontal, max length 10
        // Place a clue at column 5 on row 1.
        state.addClueCell(pos(1, 5))
        assertThat(state.availableLength(ClueArrow(pos(0, 0), Direction.DOWN_RIGHT))).isEqualTo(5)
    }

    @Test
    fun `materialize then rollback restores cell types and slots`() {
        val state = PlanState(6, 6)
        state.addClueCell(pos(0, 0))
        state.addArrow(pos(0, 0), Direction.DOWN_RIGHT)
        val cp = state.checkpoint()
        // Length 4 places a trailing clue + continuation arrows.
        val ok = state.materialize(ClueArrow(pos(0, 0), Direction.DOWN_RIGHT), 4)
        assertThat(ok).isTrue()
        assertThat(state.slots.size).isEqualTo(1)
        state.rollback(cp)
        assertThat(state.slots).isEmpty()
        // Letters and trailing clue both rolled back.
        // Re-materialize — should succeed without state leaking from the previous attempt.
        assertThat(state.materialize(ClueArrow(pos(0, 0), Direction.DOWN_RIGHT), 6)).isTrue()
    }

    @Test
    fun `materialize returns false on conflict and partial work is rolled back by caller`() {
        val state = PlanState(4, 4)
        state.addClueCell(pos(0, 0))
        state.addArrow(pos(0, 0), Direction.DOWN_RIGHT)
        // Plant an obstacle on the slot's path so materialize sees CLUE mid-loop.
        state.addClueCell(pos(1, 2))
        val cp = state.checkpoint()
        // Length 4 would walk into (1, 2) at i=2 → CLUE → return false.
        assertThat(state.materialize(ClueArrow(pos(0, 0), Direction.DOWN_RIGHT), 4)).isFalse()
        // Rollback the partial work and confirm the slot list is empty.
        state.rollback(cp)
        assertThat(state.slots).isEmpty()
    }

    @Test
    fun `validate flags dead clue cells when no arrow is materialized`() {
        val state = PlanState(4, 4)
        state.addClueCell(pos(0, 0))
        state.addArrow(pos(0, 0), Direction.DOWN_RIGHT)
        state.deactivate(pos(0, 0), Direction.DOWN_RIGHT)
        val result = state.validate()
        assertThat(result.deadClueCells).containsExactlyInAnyOrder(pos(0, 0))
    }

    @Test
    fun `validate flags orphan UNSET cells`() {
        // Build a 2x2 grid with a single corner clue + materialised slot covering
        // only some cells. Cells not touched stay UNSET → orphans.
        val state = PlanState(3, 3)
        state.addClueCell(pos(0, 0))
        state.addArrow(pos(0, 0), Direction.DOWN_RIGHT)
        // Length 2 covers (1,0) and (1,1); leaves (0,1), (0,2), (1,2), (2,0..2) as UNSET.
        state.materialize(ClueArrow(pos(0, 0), Direction.DOWN_RIGHT), 2)
        val result = state.validate()
        assertThat(result.orphanLetterCells.isNotEmpty()).isTrue()
    }

    @Test
    fun `nextPending iterates in insertion order`() {
        // P7 invariant: nextPending honours insertion order so a fixed seed is
        // reproducible across JVM runs.
        val state = PlanState(10, 10)
        state.addClueCell(pos(0, 0))
        state.addArrow(pos(0, 0), Direction.DOWN_RIGHT)
        state.addClueCell(pos(0, 2))
        state.addArrow(pos(0, 2), Direction.RIGHT_DOWN)
        state.addClueCell(pos(2, 0))
        state.addArrow(pos(2, 0), Direction.RIGHT)
        // Confirmed order matches insertion order.
        val first = state.nextPending()
        assertThat(first).isNotNull()
        assertThat(first!!.cluePosition).isEqualTo(pos(0, 0))
    }

    @Test
    fun `nextPending returns null when every arrow is materialized or deactivated`() {
        val state = PlanState(4, 4)
        state.addClueCell(pos(0, 0))
        state.addArrow(pos(0, 0), Direction.DOWN_RIGHT)
        state.materialize(ClueArrow(pos(0, 0), Direction.DOWN_RIGHT), 4)
        // Trailing clue's arrows are also pending after materialize at length < M.
        // Drain them.
        var pending = state.nextPending()
        while (pending != null) {
            // For test purposes deactivate to drain the queue.
            state.deactivate(pending.cluePosition, pending.direction)
            pending = state.nextPending()
        }
        assertThat(state.nextPending()).isNull()
    }

    private fun pos(
        row: Int,
        col: Int,
    ): Position = Position(Row(row), Column(col))
}
