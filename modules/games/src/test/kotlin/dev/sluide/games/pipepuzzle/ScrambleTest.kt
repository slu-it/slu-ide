package dev.sluide.games.pipepuzzle

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrambleTest {
    /** A snake with three straights: a plain random turn leaves fewer than 60 % of its 7 tiles wrong for many seeds. */
    private val snake =
        board(
            "╶─┐",
            "┌─┘",
            "└─╴",
        )

    /** A short Path route through one straight with six decoys around it, one of them a cross. */
    private val shortRoute =
        board(
            "╶─╴",
            "┼└╷",
            "│┬╵",
        )

    private fun turnableWrong(
        solution: List<Openings>,
        start: List<Openings>,
        fixed: Set<Int>,
    ): Pair<Int, Int> {
        val turnable = solution.indices.filter { it !in fixed && !solution[it].isCross }
        return turnable.count { start[it] != solution[it] } to turnable.size
    }

    @Test
    fun `at least 60 percent of the turnable tiles start wrong, and fixed tiles and crosses keep their turn`() {
        repeat(200) { seed ->
            val start = Scramble.start(Mode.NETWORK, 3, Pos(0, 0), Pos(2, 2), snake, Random(seed))

            val (wrong, turnable) = turnableWrong(snake, start, setOf(0, 8))
            assertTrue(wrong * 10 >= turnable * 6, "seed $seed: $wrong of $turnable wrong")
            assertEquals(snake[0], start[0])
            assertEquals(snake[8], start[8])
            snake.indices.forEach { assertTrue(start[it] in snake[it].rotations, "seed $seed at $it") }
        }
    }

    @Test
    fun `a start that is already solved is rolled again`() {
        repeat(200) { seed ->
            val start = Scramble.start(Mode.PATH, 3, Pos(0, 0), Pos(0, 2), shortRoute, Random(seed))

            assertFalse(Flow(3, start, Pos(0, 0)).isSolved(Mode.PATH, Pos(0, 2)), "seed $seed")
            assertEquals(Openings.CROSS, start[Pos(1, 0).index(3)])
        }
    }

    @Test
    fun `a board whose every turn is solved gives up instead of rolling forever`() {
        val direct = board("╶╴", "─│")

        val error =
            assertFailsWith<IllegalStateException> {
                Scramble.start(Mode.PATH, 2, Pos(0, 0), Pos(0, 1), direct, Random(1))
            }
        assertTrue("already solved" in error.message.orEmpty())
    }

    @Test
    fun `the same seed gives the same start`() {
        val a = Scramble.start(Mode.NETWORK, 3, Pos(0, 0), Pos(2, 2), snake, Random(9))
        val b = Scramble.start(Mode.NETWORK, 3, Pos(0, 0), Pos(2, 2), snake, Random(9))

        assertEquals(a, b)
    }
}
