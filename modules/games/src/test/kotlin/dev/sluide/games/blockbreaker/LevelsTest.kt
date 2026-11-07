package dev.sluide.games.blockbreaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class LevelsTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    fun `every layout is well formed, symmetric, big enough and fully reachable`(level: Int) {
        val rows = Levels.layout(level)
        assertTrue(rows.size <= Rules.MAX_ROWS, "at most 10 rows")
        rows.forEach {
            assertEquals(Rules.COLUMNS, it.length, "13 characters in '$it'")
            assertEquals(it, it.reversed(), "palindrome '$it'")
        }
        val grid = BrickGrid().also { it.load(rows) }
        assertTrue(grid.breakableLeft >= 40, "at least 40 breakable bricks, has ${grid.breakableLeft}")
        assertEquals(grid.breakableLeft, reachableBreakables(rows), "every breakable brick is reachable")
    }

    @ParameterizedTest
    @ValueSource(ints = [1, 10, 11, 20, 21])
    fun `a level beyond ten repeats the layouts`(level: Int) {
        assertEquals(Levels.LAYOUTS[(level - 1) % 10], Levels.layout(level))
    }

    /** Flood fill from the open row below the layout through empty and breakable cells; steel blocks. */
    private fun reachableBreakables(rows: List<String>): Int {
        val seen = mutableSetOf<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        for (column in 0 until Rules.COLUMNS) queue.add(column to rows.size)
        while (queue.isNotEmpty()) {
            val (column, row) = queue.removeFirst()
            if (column !in 0 until Rules.COLUMNS || row < 0 || row > rows.size) continue
            if (row < rows.size && rows[row][column] == '#') continue
            if (!seen.add(column to row)) continue
            queue.add(column + 1 to row)
            queue.add(column - 1 to row)
            queue.add(column to row + 1)
            queue.add(column to row - 1)
        }
        return seen.count { (column, row) -> row < rows.size && rows[row][column] != '.' }
    }
}
