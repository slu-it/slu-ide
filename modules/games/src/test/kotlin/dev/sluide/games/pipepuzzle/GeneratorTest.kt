package dev.sluide.games.pipepuzzle

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random
import kotlin.test.assertEquals

class GeneratorTest {
    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `the generator of the mode makes a puzzle of every size`(mode: Mode) {
        for (size in BoardSize.entries) {
            val puzzle = Generator().generate(mode, size, Random(3))

            assertEquals(mode, puzzle.mode)
            assertEquals(size.side, puzzle.side)
            assertEquals(size.side * size.side, puzzle.solution.size)
        }
    }
}
