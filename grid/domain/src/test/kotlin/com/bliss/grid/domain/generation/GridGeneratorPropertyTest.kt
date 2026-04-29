package com.bliss.grid.domain.generation

import com.bliss.grid.domain.validation.GridValidator
import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

@OptIn(ExperimentalKotest::class)
class GridGeneratorPropertyTest {
    private val generator = GridGenerator(ListWordRepository(SMALL_FRENCH_WORDS))

    @Test
    fun `every generated grid passes interlocking check`() {
        runBlocking {
            checkAll(
                PropTestConfig(iterations = 20),
                Arb.int(4..6),
                Arb.int(4..6),
            ) { width, height ->
                val grid = generator.generate(GridConstraints(width, height))
                if (grid != null) {
                    val uncrossed = GridValidator.uncrossedCells(grid)
                    check(uncrossed.isEmpty()) {
                        "uncrossed cells $uncrossed for ${width}x$height"
                    }
                }
            }
        }
    }
}
