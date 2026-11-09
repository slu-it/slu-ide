package dev.sluide.games.pipepuzzle

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PathGeneratorTest {
    /** The route from the source to the drain through the generated solution, following the only way on. */
    private fun route(puzzle: Puzzle): List<Pos> {
        val side = puzzle.side
        val flow = Flow(side, puzzle.solution, puzzle.source)
        val parent = mutableMapOf<Pos, Pos>()
        for (layer in flow.layers) {
            for (pos in layer) {
                for (dir in puzzle.solution[pos.index(side)].dirs) {
                    val to = pos.step(dir)
                    if (to != puzzle.source && to !in parent && to in flow.filled) parent[to] = pos
                }
            }
        }
        return generateSequence(puzzle.drain) { if (it == puzzle.source) null else parent.getValue(it) }.toList()
    }

    @ParameterizedTest
    @EnumSource(BoardSize::class)
    fun `every path puzzle has a long sealed route with spurs, T pieces and decoys beside it`(size: BoardSize) {
        val side = size.side
        repeat(50) { seed ->
            val puzzle = PathGenerator().generate(side, Random(seed))
            val label = "$size seed $seed\n${render(side, puzzle.solution, puzzle.source, puzzle.drain)}"
            assertEquals(Mode.PATH, puzzle.mode)
            assertEquals(side, puzzle.side)
            assertTrue(puzzle.source.distanceTo(puzzle.drain) >= side, label)

            val flow = Flow(side, puzzle.solution, puzzle.source)
            assertTrue(flow.isSolved(Mode.PATH, puzzle.drain), label)

            val route = route(puzzle)
            assertTrue(route.size * 2 >= 3 * side, "$label: route of ${route.size}")
            val spurs = route.count { puzzle.solution[it.index(side)].count >= 3 }
            assertTrue(spurs >= maxOf(1, side / 3), "$label: $spurs spurs")
            assertTrue(route.any { puzzle.solution[it.index(side)].count == 3 }, "$label: no T on the route")

            val decoys = puzzle.cells().filter { it !in flow.filled }
            val tempting =
                decoys.count { decoy ->
                    Dir.entries.any { dir ->
                        decoy.step(dir) in route &&
                            puzzle.solution[decoy.index(side)].rotations.any { it.has(dir) }
                    }
                }
            assertTrue(tempting * 2 >= side, "$label: $tempting decoys can face the route")

            assertScrambled(puzzle, label)
        }
    }

    @Test
    fun `the same seed gives the same path puzzle`() {
        val a = PathGenerator().generate(9, Random(42))
        val b = PathGenerator().generate(9, Random(42))
        assertEquals(listOf(a.source, a.drain, a.solution, a.start), listOf(b.source, b.drain, b.solution, b.start))
    }
}
