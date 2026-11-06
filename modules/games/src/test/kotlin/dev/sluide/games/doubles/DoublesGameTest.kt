package dev.sluide.games.doubles

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Draws cells and values from scripts; [cells] are indexes into the free cells, [twos] false makes a 4. */
internal class ScriptedRandom(
    cells: List<Int> = emptyList(),
    twos: List<Boolean> = emptyList(),
) : Random() {
    private val cells = cells.toMutableList()
    private val twos = twos.toMutableList()

    override fun nextBits(bitCount: Int) = 0

    override fun nextInt(until: Int) = if (cells.isEmpty()) 0 else cells.removeFirst().coerceAtMost(until - 1)

    override fun nextDouble() = if (twos.isEmpty() || twos.removeFirst()) 0.5 else 0.95
}

class DoublesGameTest {
    private fun game(
        vararg rows: List<Int>,
        size: BoardSize = BoardSize.FOUR,
        random: Random = ScriptedRandom(),
    ) = DoublesGame.of(size, rows.toList(), random)

    private fun DoublesGame.rows(): List<List<Int>> {
        val byCell = tiles.associateBy { it.cell }
        return List(size.side) { r -> List(size.side) { c -> byCell[Cell(r, c)]?.value ?: 0 } }
    }

    private val empty = listOf(0, 0, 0, 0)

    /** A one-row game (row 0) whose spawn goes to the last free cell, with a 2. */
    private fun rowGame(vararg row: Int): DoublesGame =
        game(row.toList(), empty, empty, empty, random = ScriptedRandom(listOf(Int.MAX_VALUE)))

    private fun firstRowAfter(
        direction: Direction,
        vararg row: Int,
    ): List<Int> {
        // For LEFT/RIGHT the row is the board's first row; spawn lands in the last free cell (bottom right).
        val game = rowGame(*row)
        game.move(direction)
        return game.rows()[0]
    }

    @ParameterizedTest
    @CsvSource(
        "2 2 2 0, LEFT, 4 2 0 0",
        "2 2 2 2, LEFT, 4 4 0 0",
        "4 0 4 8, LEFT, 8 8 0 0",
        "2 2 2 0, RIGHT, 0 0 2 4",
        "2 2 2 2, RIGHT, 0 0 4 4",
        "8 4 0 4, RIGHT, 0 0 8 8",
    )
    internal fun `rows merge from the leading edge`(
        row: String,
        direction: Direction,
        expected: String,
    ) {
        assertEquals(
            expected.split(" ").map {
                it.toInt()
            },
            firstRowAfter(direction, *row.split(" ").map { it.toInt() }.toIntArray()),
        )
    }

    @ParameterizedTest
    @CsvSource("UP", "DOWN", "LEFT", "RIGHT")
    internal fun `merging works in every direction and scores the merged values`(direction: Direction) {
        val game = game(listOf(2, 2, 0, 0), listOf(2, 2, 0, 0), empty, empty)

        val result = assertNotNull(game.move(direction))

        assertEquals(2, result.merged.size)
        assertEquals(8, result.gained)
        assertEquals(8, game.score)
    }

    @Test
    fun `a vertical merge slides to the leading edge`() {
        val game =
            game(
                listOf(2, 0, 0, 0),
                listOf(2, 0, 0, 0),
                listOf(4, 0, 0, 0),
                listOf(4, 0, 0, 0),
                random = ScriptedRandom(listOf(Int.MAX_VALUE)),
            )

        game.move(Direction.UP)

        val column = game.rows().map { it[0] }
        assertEquals(listOf(4, 8, 0, 0), column)

        val down =
            game(
                listOf(2, 0, 0, 0),
                listOf(2, 0, 0, 0),
                listOf(4, 0, 0, 0),
                listOf(4, 0, 0, 0),
                random = ScriptedRandom(listOf(Int.MAX_VALUE)),
            )
        down.move(Direction.DOWN)
        assertEquals(listOf(0, 0, 4, 8), down.rows().map { it[0] })
    }

    @Test
    fun `slides and merged ids, partners included, unmoved tiles left out`() {
        // ids in reading order: 0:(0,0)=4 stays, 1:(0,1)=2, 2:(0,3)=2
        val game = game(listOf(4, 2, 0, 2), empty, empty, empty, random = ScriptedRandom(listOf(0)))

        val result = assertNotNull(game.move(Direction.LEFT))

        assertEquals(listOf(Slide(1, 2, Cell(0, 1), Cell(0, 1)), Slide(2, 2, Cell(0, 3), Cell(0, 1))), result.slides)
        assertEquals(listOf(Tile(3, 4, Cell(0, 1))), result.merged)
        assertEquals(4, result.gained)
        assertEquals(4, game.tiles.first { it.id == 0 }.value)
        assertEquals(null, game.tiles.firstOrNull { it.id == 1 || it.id == 2 })
    }

    @Test
    fun `sliding tiles keep their id and new tiles get increasing ids`() {
        val game = game(listOf(0, 0, 0, 2), empty, empty, empty, random = ScriptedRandom(listOf(0)))

        val result = assertNotNull(game.move(Direction.LEFT))

        assertEquals(listOf(Slide(0, 2, Cell(0, 3), Cell(0, 0))), result.slides)
        assertTrue(result.merged.isEmpty())
        assertEquals(0, game.tiles.first { it.cell == Cell(0, 0) }.id)
        assertEquals(1, result.spawned?.id)
    }

    @Test
    fun `a move that changes nothing returns null without spawn, score or undo change`() {
        val game = game(listOf(2, 4, 0, 0), empty, empty, empty)
        val before = game.tiles

        assertNull(game.move(Direction.LEFT))
        assertNull(game.move(Direction.UP))

        assertEquals(before.toSet(), game.tiles.toSet())
        assertEquals(0, game.score)
        assertFalse(game.canUndo)
    }

    @Test
    fun `a null move keeps the undo of the last move`() {
        // The 2 slides to (0,0); free cell 3 is (1,0), so the board has a column of two 2s that LEFT cannot change.
        val game = game(listOf(0, 0, 0, 2), empty, empty, empty, random = ScriptedRandom(listOf(3)))
        game.move(Direction.LEFT)

        assertNull(game.move(Direction.LEFT))
        assertTrue(game.canUndo)
    }

    @Test
    fun `a new game places two tiles of 2 or 4 in random cells`() {
        // First tile: free cell 0 (0,0) as a 2; second: free cell 14 of the remaining 15, the last cell, as a 4.
        val game = DoublesGame(BoardSize.FOUR, ScriptedRandom(listOf(0, 14), listOf(true, false)))

        assertEquals(setOf(Tile(0, 2, Cell(0, 0)), Tile(1, 4, Cell(3, 3))), game.tiles.toSet())
    }

    @Test
    fun `a move spawns exactly one tile at the drawn free cell`() {
        // After sliding the 2 to (0,0), 15 cells are free in reading order: (0,1), (0,2), (0,3), (1,0), (1,1), ...; index 4 is (1,1).
        val game = game(listOf(0, 0, 0, 2), empty, empty, empty, random = ScriptedRandom(listOf(4), listOf(false)))

        val result = assertNotNull(game.move(Direction.LEFT))

        assertEquals(Cell(1, 1), result.spawned?.cell)
        assertEquals(4, result.spawned?.value)
        assertEquals(2, game.tiles.size)
    }

    @Test
    fun `spawned values are 2 about nine times in ten`() {
        val random = Random(42)
        val values = List(2000) { DoublesGame(BoardSize.FOUR, random).tiles.first().value }

        assertEquals(setOf(2, 4), values.toSet())
        assertTrue(values.count { it == 2 } in 1700..1900)
    }

    @Test
    fun `over only when the board is full without equal neighbours`() {
        val stuck = game(listOf(2, 4, 2, 4), listOf(4, 2, 4, 2), listOf(2, 4, 2, 4), listOf(4, 2, 4, 2))
        val horizontal = game(listOf(2, 2, 4, 8), listOf(4, 8, 2, 4), listOf(2, 4, 8, 2), listOf(4, 2, 4, 8))
        val vertical = game(listOf(2, 4, 8, 16), listOf(2, 8, 16, 32), listOf(4, 16, 32, 64), listOf(8, 32, 64, 128))
        val three = game(listOf(2, 4, 2), listOf(4, 2, 4), listOf(2, 4, 2), size = BoardSize.THREE)
        val threeMergeable = game(listOf(2, 4, 2), listOf(4, 2, 4), listOf(2, 4, 4), size = BoardSize.THREE)
        val notFull = game(listOf(2, 4, 2, 4), listOf(4, 2, 4, 2), listOf(2, 4, 2, 4), listOf(4, 2, 4, 0))

        assertTrue(stuck.isOver)
        assertFalse(horizontal.isOver)
        assertFalse(vertical.isOver)
        assertFalse(notFull.isOver)
        assertTrue(three.isOver)
        assertFalse(threeMergeable.isOver)
    }

    @ParameterizedTest
    @CsvSource("THREE, 256", "FOUR, 1024", "FIVE, 2048", "SIX, 4096")
    internal fun `the goal is reached by a merge to the size's goal`(
        size: BoardSize,
        half: Int,
    ) {
        val rows = List(size.side) { r -> List(size.side) { c -> if (r == 0 && c < 2) half else 0 } }
        val game = DoublesGame.of(size, rows, ScriptedRandom())
        assertFalse(game.goalReached)

        game.move(Direction.LEFT)

        assertEquals(half * 2 == size.goal, game.goalReached)
    }

    @Test
    fun `undo clears the goal again`() {
        val game = game(listOf(1024, 1024, 0, 0), empty, empty, empty)
        game.move(Direction.LEFT)
        assertTrue(game.goalReached)

        game.undo()

        assertFalse(game.goalReached)
    }

    @Test
    fun `undo restores tiles with ids and score, once only`() {
        val game = game(listOf(2, 2, 4, 0), empty, empty, empty, random = ScriptedRandom(listOf(3)))
        val before = game.tiles.toSet()
        game.move(Direction.LEFT)
        assertEquals(4, game.score)
        assertTrue(game.canUndo)

        assertTrue(game.undo())

        assertEquals(before, game.tiles.toSet())
        assertEquals(0, game.score)
        assertFalse(game.canUndo)
        assertFalse(game.undo())
    }

    @Test
    fun `undo works when the game is over`() {
        // LEFT slides the last row; the spawned 2 fills (3,3) and nothing can move any more.
        val game =
            game(
                listOf(2, 4, 2, 4),
                listOf(4, 2, 4, 2),
                listOf(2, 4, 2, 4),
                listOf(0, 4, 2, 8),
                random = ScriptedRandom(listOf(0)),
            )
        game.move(Direction.LEFT)

        assertTrue(game.isOver)
        assertTrue(game.undo())
        assertFalse(game.isOver)
    }
}
