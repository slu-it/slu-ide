package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.solver.SolveResult
import dev.sluide.games.solitaire.ui.emptyTable
import dev.sluide.games.solitaire.ui.tableau
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Playing: moves, the stock, auto-finish, the win, hints, pause and the keyboard. */
internal class SolitaireModelPlayTest : SolitaireModelFixture() {
    @Test
    fun `moves, undo, redo and restart send their events and keep the clock running`() {
        val model = readyModel()
        now = 5_000

        assertTrue(model.play(Move.Draw))
        assertFalse(model.play(Move.Recycle))
        val step = (events.single() as TableEvent.Moved).step
        assertEquals(Move.Draw to false, step.move to (events.single() as TableEvent.Moved).auto)
        assertEquals(1, model.moves)
        assertTrue(model.canUndo)
        assertTrue(model.needsConfirmToAbandon)

        model.undo()
        assertTrue(model.canRedo)
        model.redo()
        model.restart()

        assertEquals(
            listOf(
                TableEvent.Moved(step, false),
                TableEvent.Undone(step),
                TableEvent.Redone(step),
                TableEvent.Restarted,
            ),
            events,
        )
        assertEquals(0, model.moves)
        assertFalse(model.canRestart)
        assertEquals(5_000, model.elapsedMillis)
        assertEquals("Deal #100", model.statusText)
    }

    @ParameterizedTest
    @CsvSource("'2C 3C', '', Draw", "'', 'AS 2S', Recycle")
    fun `a click on the Klondike stock draws, or recycles the waste`(
        stockCards: String,
        wasteCards: String,
        expected: String,
    ) {
        tables = { mode, _ -> emptyTable(mode, mapOf(stock to Pile(cards(stockCards)), waste to up(wasteCards))) }
        val model = readyModel()

        model.stockClicked()

        assertEquals(listOf((if (expected == "Draw") Move.Draw else Move.Recycle) to false), movedMoves())
    }

    @Test
    fun `a click on the Spider stock deals a row, or says why not`() {
        readyModel(Mode.SPIDER_1).stockClicked()
        assertEquals(listOf(Move.DealRow to false), movedMoves())
        events.clear()

        val full = Rules.of(Mode.SPIDER_1).deal(Deals.deck(Mode.SPIDER_1, 1))
        tables = { _, _ -> full.with(tableau(0) to Pile.EMPTY, tableau(1) to up("6S"), tableau(2) to up("5S")) }
        val model = readyModel(Mode.SPIDER_1)
        model.stockClicked()

        assertTrue(events.isEmpty())
        assertEquals("Fill every empty column before dealing a row", model.statusText)
        assertTrue(model.play(Move.Transfer(tableau(2), tableau(1))))
        assertEquals("Deal #${model.game?.number}", model.statusText)
    }

    @Test
    fun `a click on the FreeCell table's stock does nothing`() {
        val model = readyModel(Mode.FREECELL)
        changes = 0

        model.stockClicked()

        assertTrue(events.isEmpty())
        assertEquals(0, changes)
    }

    @Test
    fun `a decided game finishes by itself one card at a time and waits while paused`() {
        tables = { _, _ -> nearlyDecided }
        val model = readyModel()

        assertTrue(model.play(jackUp))

        assertTrue(model.isAutoFinishing)
        assertFalse(model.inputEnabled)
        assertFalse(model.canUndo)
        assertEquals(listOf(jackUp to false), movedMoves())
        assertEquals(listOf(90L), scheduled.map { it.delay })

        runScheduled()
        assertEquals(listOf(jackUp to false, Move.Transfer(tableau(1), foundation(3)) to true), movedMoves())

        model.togglePause()
        runScheduled()
        assertEquals(2, movedMoves().size)
        assertTrue(scheduled.isEmpty())
        model.hidden = true
        model.togglePause()
        assertTrue(scheduled.isEmpty())
        model.hidden = false
        assertEquals(1, scheduled.size)

        runScheduled()
        assertEquals(Move.Transfer(tableau(0), foundation(3)) to true, movedMoves().last())
        assertTrue(model.isWon)
        assertFalse(model.isAutoFinishing)
        assertTrue(events.last() is TableEvent.Won)
    }

    @Test
    fun `a New Game stops auto-finish`() {
        tables = { _, _ -> nearlyDecided }
        val model = readyModel()
        model.play(jackUp)

        model.newGame()

        assertFalse(model.isAutoFinishing)
        assertTrue(scheduled.single().cancelled)
        assertEquals(listOf(jackUp to false), movedMoves())
    }

    @Test
    fun `a message from a failed deal search during auto-finish gives way to the won line`() {
        tables = { _, _ -> nearlyDecided }
        val model = readyModel()
        model.play(jackUp)
        winnable = { false }
        findable = { false }

        model.requestDeal(777) { _, _ -> fail("asked without an alternative") }
        runDealing()
        assertTrue(model.isAutoFinishing)
        assertEquals("No winnable deal found near #777", model.statusText)
        runScheduled()
        runScheduled()

        assertTrue(model.isWon)
        assertTrue(model.statusText.startsWith("Won with "), model.statusText)
    }

    @Test
    fun `a win stops the clock, records the high score and says so`() {
        tables = { _, _ -> nearlyDecided }
        val model = readyModel()
        model.play(jackUp)
        runScheduled()
        now = 42_000
        runScheduled()

        // Three cards to a foundation 30, 4 x 10 s -8, time bonus 700,000 / 42 = 16,666.
        assertEquals(TableEvent.Won(16_688, true), events.last())
        assertEquals(16_688, preferences.highScore(Mode.KLONDIKE_DRAW_1))
        assertEquals("Won with 16,688 points in 0:42 — new high score!", model.statusText)
        assertFalse(model.inputEnabled)
        assertFalse(model.canPause)
        assertFalse(model.needsConfirmToAbandon)
        now = 60_000
        assertEquals(42_000, model.elapsedMillis)
        assertEquals(16_688, model.score)

        model.newGame()
        model.play(jackUp)
        runScheduled()
        now += 120_000
        runScheduled()
        assertEquals(TableEvent.Won(5_839, false), events.last())
        assertEquals("Won with 5,839 points in 2:00", model.statusText)
        assertEquals(16_688, preferences.highScore(Mode.KLONDIKE_DRAW_1))
    }

    @Test
    fun `the status shows the high score of the mode`() {
        preferences.recordScore(Mode.KLONDIKE_DRAW_1, 4_210)

        assertEquals("Deal #100 · High score: 4,210", readyModel().statusText)
    }

    @Test
    fun `a hint shows the next move of a winning line and costs a hint`() {
        val model = readyModel()
        solveResult = { SolveResult.Solved(listOf(Move.Draw, Move.Draw)) }

        model.hint()
        model.hint()

        assertTrue(model.isHinting)
        assertFalse(model.canHint)
        assertTrue(model.inputEnabled)
        assertEquals("Thinking…", model.statusText)
        assertEquals(1, hintQueue.size)
        runHint()
        assertEquals(listOf<TableEvent>(TableEvent.Hint(Move.Draw)), events)
        assertEquals(1, model.game?.hints)
        assertFalse(model.isHinting)
        assertEquals("Deal #100", model.statusText)
        assertEquals(listOf(Solve(model.table, 2.seconds, false)), solves)
    }

    @Test
    fun `without a winning line the hint says how far to undo`() {
        val model = readyModel()
        repeat(3) { model.play(Move.Draw) }
        val positions = assertNotNull(model.game).positions
        solveResult = { if (it == positions[1]) SolveResult.Solved(listOf(Move.Draw)) else SolveResult.Unsolvable }
        events.clear()

        model.hint()
        runHint()

        assertEquals("No winning line from here — undo 2 moves", model.statusText)
        assertEquals(0, model.game?.hints)
        assertTrue(events.isEmpty())
        assertEquals(listOf(positions[3], positions[2], positions[1]), solves.map { it.table })
        assertEquals(listOf(2.seconds, 500.milliseconds, 500.milliseconds), solves.map { it.budget })
        model.play(Move.Draw)
        assertEquals("Deal #100", model.statusText)
    }

    @Test
    fun `an undecided search finds no hint`() {
        val model = readyModel()

        model.hint()
        runHint()

        assertEquals("No hint found", model.statusText)
        assertEquals(0, model.game?.hints)
        assertTrue(events.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["move", "undo", "redo", "restart", "new game", "mode", "deal number", "pause", "hide"])
    fun `a change of the table or a pause drops the hint search`(change: String) {
        val model = readyModel()
        model.play(Move.Draw)
        model.play(Move.Draw)
        model.undo()
        solveResult = { SolveResult.Solved(listOf(Move.Draw)) }
        model.hint()
        events.clear()

        when (change) {
            "move" -> model.play(Move.Draw)
            "undo" -> model.undo()
            "redo" -> model.redo()
            "restart" -> model.restart()
            "new game" -> model.newGame()
            "mode" -> model.chooseMode(Mode.KLONDIKE_DRAW_3)
            "deal number" -> model.requestDeal(5) { _, _ -> false }
            "pause" -> model.togglePause()
            "hide" -> model.hidden = true
        }
        assertFalse(model.isHinting)
        assertTrue(model.statusText != "Thinking…")
        val before = events.toList()
        runHint()

        assertTrue(solves.single().cancelled)
        assertEquals(before, events)
        assertEquals(0, model.game?.hints)
    }

    @Test
    fun `pause stops the clock and the input, hiding the tab too`() {
        val model = readyModel()
        now = 1_000

        model.togglePause()

        assertTrue(model.isPaused)
        assertTrue(model.isManuallyPaused)
        assertFalse(model.inputEnabled)
        assertFalse(model.canHint)
        assertFalse(model.play(Move.Draw))
        now = 5_000
        assertEquals(1_000, model.elapsedMillis)

        model.togglePause()
        now = 6_000
        assertEquals(2_000, model.elapsedMillis)
        model.hidden = true
        assertTrue(model.isPaused)
        assertFalse(model.isManuallyPaused)
        now = 9_000
        assertEquals(2_000, model.elapsedMillis)
        model.hidden = false
        assertTrue(model.inputEnabled)
    }

    @Test
    fun `there is nothing to pause while the first deal is made`() {
        val model = model()
        model.ensureGame()

        model.togglePause()

        assertFalse(model.canPause)
        assertFalse(model.isPaused)
    }

    /** Klondike: 5H, 6S, AC and 7D in columns 0 to 3, two cards in the stock. */
    private fun keyboardModel(): SolitaireModel {
        tables = { mode, _ ->
            emptyTable(
                mode,
                mapOf(
                    tableau(0) to up("5H"),
                    tableau(1) to up("6S"),
                    tableau(2) to up("AC"),
                    tableau(3) to up("7D"),
                    stock to Pile(cards("2D 3D")),
                ),
            )
        }
        return readyModel()
    }

    private fun SolitaireModel.keys(vararg actions: KeyAction) = actions.forEach(::key)

    private val left = KeyAction.Navigate(Direction.LEFT)
    private val right = KeyAction.Navigate(Direction.RIGHT)
    private val up = KeyAction.Navigate(Direction.UP)

    @Test
    fun `cards are picked up and dropped with the keyboard`() {
        val model = keyboardModel()

        model.key(right)
        assertEquals(Cursor(tableau(0), 1), model.cursor)
        model.keys(KeyAction.PickOrDrop, right)
        assertEquals(Cursor(tableau(1), 1, Pick(tableau(0), 1)), model.cursor)
        model.key(KeyAction.PickOrDrop)

        assertEquals(listOf(Move.Transfer(tableau(0), tableau(1), 1) to false), movedMoves())
        assertEquals(Cursor(tableau(1), 1), model.cursor)

        model.keys(right, KeyAction.PickOrDrop, left, KeyAction.Play)
        assertEquals("Can't move there", model.statusText)
        assertEquals(Pick(tableau(2), 1), model.cursor.picked)
        model.key(KeyAction.Cancel)
        assertNull(model.cursor.picked)
        model.keys(KeyAction.PickOrDrop, KeyAction.PickOrDrop)
        assertNull(model.cursor.picked)
        assertEquals(1, movedMoves().size)
    }

    @Test
    fun `Enter plays the smart move, F sends to a foundation, and both draw on the stock`() {
        val model = keyboardModel()

        model.keys(right, right, right, KeyAction.ToFoundation)
        model.keys(left, KeyAction.Play)
        model.keys(left, up, KeyAction.Play, KeyAction.PickOrDrop)

        assertEquals(
            listOf(
                Move.Transfer(tableau(2), foundation(0)) to false,
                Move.Transfer(tableau(1), tableau(3), 1) to false,
                Move.Draw to false,
                Move.Draw to false,
            ),
            movedMoves(),
        )
        assertEquals(Cursor(stock, 0), model.cursor)
    }

    @Test
    fun `D draws wherever the cursor is`() {
        val model = keyboardModel()

        model.key(KeyAction.Stock)
        model.keys(right, KeyAction.Stock)

        assertEquals(listOf(Move.Draw to false, Move.Draw to false), movedMoves())
        assertEquals(Cursor(tableau(0), 1), model.cursor)
    }

    @Test
    fun `keys do nothing to the table while paused, and New Game is left to the view`() {
        val model = keyboardModel()
        model.togglePause()

        model.keys(right, KeyAction.Play, KeyAction.NewGame)

        assertEquals(Cursor(), model.cursor)
        assertEquals(100, model.game?.number)
        assertTrue(events.isEmpty())
        model.key(KeyAction.Pause)
        assertFalse(model.isPaused)
    }
}
