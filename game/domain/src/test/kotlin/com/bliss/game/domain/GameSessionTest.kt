package com.bliss.game.domain

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import com.bliss.game.domain.Fixtures.entry
import com.bliss.game.domain.Fixtures.gameSession
import com.bliss.game.domain.Fixtures.later
import com.bliss.game.domain.Fixtures.now
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class GameSessionTest {
    private val pPos = Position(0, 3)
    private val aPos = Position(0, 4)

    @Test
    fun `isSolved is false when no entries are placed`() {
        assertThat(gameSession().isSolved()).isFalse()
    }

    @Test
    fun `isSolved is false when only some letter cells are filled`() {
        val s = gameSession(entries = mapOf(pPos to entry('P')))
        assertThat(s.isSolved()).isFalse()
    }

    @Test
    fun `isSolved is false when an entry letter does not match the answer`() {
        val s = gameSession(entries = mapOf(pPos to entry('P'), aPos to entry('Z')))
        assertThat(s.isSolved()).isFalse()
    }

    @Test
    fun `isSolved is true when every letter cell entry matches its answer`() {
        val s = gameSession(entries = mapOf(pPos to entry('P'), aPos to entry('A')))
        assertThat(s.isSolved()).isTrue()
    }

    @Test
    fun `solvedPositions returns only the matching cells`() {
        val s = gameSession(entries = mapOf(pPos to entry('P'), aPos to entry('Z')))
        assertThat(s.solvedPositions()).isEqualTo(setOf(pPos))
    }

    @Test
    fun `duration returns now minus startedAt while in progress`() {
        val started = now
        val checkpoint = started.plusSeconds(7)
        assertThat(gameSession(startedAt = started).duration(checkpoint))
            .isEqualTo(7.seconds)
    }

    @Test
    fun `duration is frozen at completedAt once solved`() {
        val s = gameSession(startedAt = now, completedAt = later)
        // 184_250 ms between fixtures.now and fixtures.later
        assertThat(s.duration(Instant.parse("2030-01-01T00:00:00Z")))
            .isEqualTo(184_250.milliseconds)
    }

    @Test
    fun `GameSession rejects a completedAt before startedAt`() {
        assertFailure {
            gameSession(startedAt = later, completedAt = now)
        }.messageContains("before startedAt")
    }
}
