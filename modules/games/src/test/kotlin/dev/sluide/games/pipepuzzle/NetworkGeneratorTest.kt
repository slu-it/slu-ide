package dev.sluide.games.pipepuzzle

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkGeneratorTest {
    @ParameterizedTest
    @EnumSource(BoardSize::class)
    fun `every network is a scrambled spanning tree with far-apart leaf ends that the solver solves to the solution`(
        size: BoardSize,
    ) {
        val side = size.side
        val cells = side * side
        repeat(50) { seed ->
            val puzzle = NetworkGenerator().generate(side, Random(seed))
            val label = "$size seed $seed\n${render(side, puzzle.solution, puzzle.source, puzzle.drain)}"
            assertEquals(Mode.NETWORK, puzzle.mode)
            assertEquals(side, puzzle.side)

            val flow = Flow(side, puzzle.solution, puzzle.source)
            assertEquals(cells, flow.filled.size, label)
            assertTrue(flow.leaks.isEmpty(), label)
            assertEquals(2 * (cells - 1), puzzle.solution.sumOf { it.count }, "$label: n - 1 edges make a tree")

            assertEquals(1, puzzle.solution[puzzle.source.index(side)].count, label)
            assertEquals(1, puzzle.solution[puzzle.drain.index(side)].count, label)
            assertTrue(puzzle.source.distanceTo(puzzle.drain) >= side, label)

            val crosses = puzzle.solution.count { it.isCross }
            val ends = puzzle.solution.count { it.count == 1 }
            assertTrue(crosses * 100 <= cells * 5, "$label: $crosses crosses")
            assertTrue(ends * 100 <= cells * 35, "$label: $ends ends")

            val solved = NetworkSolver.solve(side, puzzle.start, setOf(puzzle.source, puzzle.drain))
            assertEquals(NetworkSolver.Result.Solved(puzzle.solution), solved, label)
            assertScrambled(puzzle, label)
        }
    }

    @ParameterizedTest
    @EnumSource(BoardSize::class, names = ["S5", "S7"])
    fun `a search over every turn finds no second solution`(size: BoardSize) {
        repeat(if (size == BoardSize.S5) 50 else 10) { seed ->
            val puzzle = NetworkGenerator().generate(size.side, Random(seed))

            val count = countNetworkSolutions(size.side, puzzle.start, setOf(puzzle.source, puzzle.drain))

            assertEquals(1, count, "$size seed $seed")
        }
    }

    @Test
    fun `the same seed gives the same network`() {
        val a = NetworkGenerator().generate(9, Random(42))
        val b = NetworkGenerator().generate(9, Random(42))
        assertEquals(listOf(a.source, a.drain, a.solution, a.start), listOf(b.source, b.drain, b.solution, b.start))
    }

    @Test
    fun `the slowest of 20 networks of 12x12 is made within 4 seconds`() {
        val times =
            (0 until 20).map { seed ->
                val start = System.nanoTime()
                NetworkGenerator().generate(12, Random(1000 + seed))
                (System.nanoTime() - start) / 1_000_000
            }
        println("12x12 network generation ms: median ${times.sorted()[10]}, max ${times.max()}, all $times")
        assertTrue(times.max() <= 4000, "times $times")
    }
}
