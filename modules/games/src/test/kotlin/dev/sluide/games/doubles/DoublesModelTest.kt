package dev.sluide.games.doubles

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoublesModelTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var preferences: DoublesPreferences

    @BeforeEach
    fun setUp() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        preferences = DoublesPreferences(store, Duration.ZERO) { it.run() }
    }

    private val empty4 = listOf(0, 0, 0, 0)

    /** A model whose game is the given 4x4 board; the spawn after a move goes to the last free cell, as a 2. */
    private fun model(
        vararg rows: List<Int>,
        random: Random = ScriptedRandom(listOf(Int.MAX_VALUE)),
    ): DoublesModel {
        preferences.saveSize(BoardSize.FOUR)
        var first = true
        return DoublesModel(preferences, random) { s, r ->
            // Only the first game is the prepared board; later ones (new game, other size) are real.
            if (first) DoublesGame.of(s, rows.toList(), r).also { first = false } else DoublesGame(s, r)
        }
    }

    /** LEFT merges the two 4s of the last row (score 8) and the spawned 2 fills the last free cell: nothing can move. */
    private fun stuckAfterLeft(lastRow: List<Int> = listOf(4, 4, 2, 8)) =
        model(listOf(2, 4, 2, 4), listOf(4, 2, 4, 2), listOf(2, 4, 2, 4), lastRow)

    private class Events(
        model: DoublesModel,
    ) {
        var changes = 0
        val moves = mutableListOf<MoveResult>()

        init {
            model.onChange = { changes++ }
            model.onMoved = { moves += it }
        }
    }

    @Test
    fun `the size is the saved one and the game starts with two tiles`() {
        preferences.saveSize(BoardSize.FIVE)
        val model = DoublesModel(preferences, Random(1))

        assertEquals(BoardSize.FIVE, model.size)
        assertEquals(2, model.tiles.size)
        assertEquals(0, model.score)
        assertFalse(model.canUndo)
    }

    @Test
    fun `a move fires onMoved and not onChange`() {
        val model = model(listOf(0, 0, 0, 2), empty4, empty4, empty4)
        val events = Events(model)

        model.move(Direction.LEFT)

        assertEquals(0, events.changes)
        assertEquals(1, events.moves.size)
        assertTrue(model.canUndo)
    }

    @Test
    fun `a move that changes nothing fires nothing`() {
        val model = model(listOf(2, 0, 0, 0), empty4, empty4, empty4)
        val events = Events(model)

        model.move(Direction.LEFT)
        model.move(Direction.UP)

        assertEquals(0, events.changes)
        assertEquals(0, events.moves.size)
    }

    @Test
    fun `the best score is recorded on moves and kept after undo`() {
        val model = model(listOf(2, 2, 0, 0), empty4, empty4, empty4)
        assertEquals(0, model.best)

        model.move(Direction.LEFT)
        assertEquals(4, model.score)
        assertEquals(4, model.best)
        model.undo()

        assertEquals(0, model.score)
        assertEquals(4, model.best)
        assertEquals(4, preferences.best(BoardSize.FOUR))
    }

    @Test
    fun `undo fires onChange only when something was undone`() {
        val model = model(listOf(0, 0, 0, 2), empty4, empty4, empty4)
        val events = Events(model)

        model.undo()
        assertEquals(0, events.changes)
        model.move(Direction.LEFT)
        model.undo()
        model.undo()

        assertEquals(1, events.changes)
        assertFalse(model.canUndo)
    }

    @Test
    fun `abandoning needs confirmation only for a running game with a score`() {
        val model = model(listOf(2, 2, 0, 0), empty4, empty4, empty4)
        assertFalse(model.needsConfirmToAbandon)

        model.move(Direction.LEFT)
        assertTrue(model.needsConfirmToAbandon)

        model.undo()
        assertFalse(model.needsConfirmToAbandon)
    }

    @Test
    fun `a game that is over needs no confirmation`() {
        val model = stuckAfterLeft()
        model.move(Direction.LEFT)

        assertTrue(model.isOver)
        assertEquals(8, model.score)
        assertFalse(model.needsConfirmToAbandon)
    }

    @Test
    fun `status texts`() {
        val running = model(listOf(2, 0, 0, 0), empty4, empty4, empty4)
        assertEquals("Reach 2048", running.statusText)

        val goal = model(listOf(1024, 1024, 0, 0), empty4, empty4, empty4)
        goal.move(Direction.LEFT)
        assertEquals("2048 reached! Keep going, or F2 for a new game", goal.statusText)

        val over = stuckAfterLeft()
        over.move(Direction.LEFT)
        assertEquals("No moves left: U to undo, F2 for a new game", over.statusText)
    }

    @Test
    fun `status of a stuck board without undo`() {
        val stuck = model(listOf(2, 4, 2, 4), listOf(4, 2, 4, 2), listOf(2, 4, 2, 4), listOf(4, 2, 4, 2))

        assertTrue(stuck.isOver)
        assertFalse(stuck.canUndo)
        assertEquals("No moves left: F2 for a new game", stuck.statusText)
    }

    @Test
    fun `over wins over goal reached`() {
        val over = stuckAfterLeft(listOf(1024, 1024, 2, 8))
        over.move(Direction.LEFT)

        assertTrue(over.isOver)
        assertEquals("No moves left: U to undo, F2 for a new game", over.statusText)
    }

    @Test
    fun `choosing another size saves it and starts a new game`() {
        val model = model(listOf(2, 2, 0, 0), empty4, empty4, empty4)
        val events = Events(model)
        model.move(Direction.LEFT)

        model.chooseSize(BoardSize.SIX)

        assertEquals(BoardSize.SIX, model.size)
        assertEquals(BoardSize.SIX, preferences.lastSize)
        assertEquals(1, events.changes)
        assertEquals(0, model.score)
        assertEquals(0, model.best)
    }

    @Test
    fun `choosing the same size does nothing`() {
        val model = model(listOf(2, 2, 0, 0), empty4, empty4, empty4)
        val events = Events(model)
        model.move(Direction.LEFT)

        model.chooseSize(BoardSize.FOUR)

        assertEquals(0, events.changes)
        assertEquals(4, model.score)
    }

    @Test
    fun `a new game starts over and fires onChange`() {
        val model = model(listOf(2, 2, 0, 0), empty4, empty4, empty4)
        val events = Events(model)
        model.move(Direction.LEFT)

        model.newGame()

        assertEquals(1, events.changes)
        assertEquals(0, model.score)
        assertEquals(4, model.best)
    }
}
