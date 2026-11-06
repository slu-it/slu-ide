package dev.sluide.games.sudoku

import dev.sluide.games.common.GameClock
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class SudokuModelTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var preferences: SudokuPreferences
    private val four = SudokuSize.FOUR
    private val solution4 = Grid.of(four, listOf("1234", "3412", "2143", "4321"))
    private val givens4 = Grid.of(four, listOf("12..", "3..2", ".1.3", "4.2."))

    // The empty cells of givens4 with their solution digits.
    private val missing =
        listOf(
            c(0, 2) to 3,
            c(0, 3) to 4,
            c(1, 1) to 4,
            c(1, 2) to 1,
            c(2, 0) to 2,
            c(2, 2) to 4,
            c(3, 1) to 3,
            c(3, 3) to 1,
        )

    private var now = 0L
    private val generated = mutableListOf<Puzzle>()
    private val queue = ArrayDeque<Runnable>()
    private val sync = Executor { it.run() }
    private val queued = Executor { queue.addLast(it) }

    @BeforeEach
    fun setUp() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        preferences = SudokuPreferences(store, Duration.ZERO) { it.run() }
        preferences.save(four, Level.EASY)
    }

    private fun c(
        row: Int,
        col: Int,
    ) = Cell(row, col)

    private fun generate(
        size: SudokuSize,
        level: Level,
    ): Puzzle =
        (
            if (size == four) {
                Puzzle(four, level, givens4, solution4, Tier.T1)
            } else {
                Puzzle(size, level, Grid.empty(size), Grid.empty(size), Tier.T1)
            }
        ).also { generated += it }

    private fun model(background: Executor = sync) =
        SudokuModel(preferences, ::generate, background, sync, GameClock { now })

    private fun readyModel(): SudokuModel = model().also { it.ensureGame() }

    /** Runs the queued tasks, failing instead of hanging when they keep queueing new ones. */
    private fun runQueued() {
        repeat(100) {
            if (queue.isEmpty()) return
            queue.removeFirst().run()
        }
        fail("tasks keep queueing: ${queue.size} left after 100")
    }

    private fun SudokuModel.solve(skip: Set<Cell> = emptySet()) {
        missing.filter { it.first !in skip }.forEach { (cell, digit) ->
            selected = cell
            input(digit)
        }
    }

    private class Events(
        model: SudokuModel,
    ) {
        var changes = 0
        val flashes = mutableListOf<Flash>()

        init {
            model.onChange = { changes++ }
            model.onFlash = { flashes += it }
        }
    }

    @Test
    fun `the size and level come from the preferences`() {
        val model = model()

        assertEquals(four, model.size)
        assertEquals(Level.EASY, model.level)
        assertNull(model.game)
    }

    @Test
    fun `ensureGame generates once in the background and installs the result`() {
        val model = model(queued)
        val events = Events(model)

        model.ensureGame()
        model.ensureGame()

        assertTrue(model.isGenerating)
        assertNull(model.game)
        assertEquals("Generating a puzzle…", model.statusText)
        assertEquals(1, queue.size)
        assertEquals(1, events.changes)

        runQueued()

        assertFalse(model.isGenerating)
        assertNotNull(model.game)
        assertNull(model.selected)
        assertEquals(2, events.changes)
        assertEquals(2, generated.size)
    }

    @Test
    fun `input is ignored while generating`() {
        val model = model(queued)
        model.ensureGame()

        model.input(1)
        model.erase()
        model.hint()
        model.fillNotes()
        model.undo()
        model.moveSelection(1, 1)
        model.togglePause()

        assertNull(model.selected)
        assertFalse(model.isPaused)
    }

    @Test
    fun `a result that is no longer wanted is never installed`() {
        val model = model(queued)
        model.ensureGame()
        model.chooseLevel(Level.HARD)

        queue.removeFirst().run()

        assertNull(model.game)
        assertTrue(model.isGenerating)
        runQueued()
        assertEquals(Level.HARD, model.game?.puzzle?.level)
    }

    @Test
    fun `newGame while the prefetch runs waits for it instead of generating again`() {
        val model = model(queued)
        model.ensureGame()
        queue.removeFirst().run()
        val before = generated.size
        assertEquals(1, queue.size)

        model.newGame()

        assertTrue(model.isGenerating)
        assertNull(model.game)
        assertEquals(1, queue.size)
        runQueued()
        assertEquals(before + 2, generated.size)
        assertSame(generated[before], model.game?.puzzle)
        assertFalse(model.isGenerating)
    }

    @Test
    fun `a stale prefetch that arrives late starts a matching one`() {
        val model = model(queued)
        model.ensureGame()
        queue.removeFirst().run()
        val stale = queue.removeFirst()
        model.chooseLevel(Level.HARD)
        queue.removeFirst().run()
        assertEquals(Level.HARD, model.game?.puzzle?.level)
        assertTrue(queue.isEmpty())

        stale.run()
        assertEquals(1, queue.size)
        runQueued()
        model.newGame()

        assertFalse(model.isGenerating)
        assertSame(generated[3], model.game?.puzzle)
        assertEquals(Level.HARD, model.game?.puzzle?.level)
    }

    @Test
    fun `a failing prefetch is not retried`() {
        var calls = 0
        val failingAfterFirst: (SudokuSize, Level) -> Puzzle = { s, l ->
            calls++
            if (calls == 1) generate(s, l) else error("boom")
        }
        val model = SudokuModel(preferences, failingAfterFirst, queued, sync, GameClock { now })
        model.ensureGame()

        runQueued()

        assertNotNull(model.game)
        assertTrue(queue.isEmpty())
        assertEquals(2, calls)
    }

    @Test
    fun `newGame waiting for a prefetch that fails generates again`() {
        var calls = 0
        val failingSecond: (SudokuSize, Level) -> Puzzle = { s, l ->
            calls++
            if (calls == 2) error("boom") else generate(s, l)
        }
        val model = SudokuModel(preferences, failingSecond, queued, sync, GameClock { now })
        model.ensureGame()
        queue.removeFirst().run()
        model.newGame()

        queue.removeFirst().run()

        assertTrue(model.isGenerating)
        assertEquals(1, queue.size)
        runQueued()
        assertNotNull(model.game)
        assertFalse(model.isGenerating)
    }

    @Test
    fun `a failing generation ends in a message and F2 tries again`() {
        var fail = true
        val model =
            SudokuModel(
                preferences,
                { s, l -> if (fail) error("boom") else generate(s, l) },
                sync,
                sync,
                GameClock { now },
            )

        model.ensureGame()

        assertFalse(model.isGenerating)
        assertNull(model.game)
        assertEquals("Could not generate a puzzle — F2 to try again", model.statusText)
        fail = false
        model.newGame()
        assertNotNull(model.game)
    }

    @Test
    fun `a prefetch that throws an error does not block New Game`() {
        var calls = 0
        val errorOnSecond: (SudokuSize, Level) -> Puzzle = { s, l ->
            calls++
            if (calls == 2) throw StackOverflowError() else generate(s, l)
        }
        val model = SudokuModel(preferences, errorOnSecond, queued, sync, GameClock { now })
        model.ensureGame()
        runQueued()
        val first = model.game

        model.newGame()
        runQueued()

        assertNotNull(model.game)
        assertNotSame(first, model.game)
    }

    @Test
    fun `selecting the selected cell changes nothing`() {
        val model = readyModel()
        model.selected = c(0, 2)
        model.input(4)
        model.hint()
        val events = Events(model)

        model.selected = c(0, 2)

        assertEquals(0, events.changes)
        assertEquals("This 4 was wrong", model.statusText)
    }

    @Test
    fun `an older result never replaces a newer game`() {
        val model = model(queued)
        model.ensureGame()
        model.newGame()
        val older = queue.removeFirst()
        runQueued()
        val installed = model.game

        older.run()

        assertSame(installed, model.game)
    }

    @Test
    fun `the next puzzle is generated in advance and used by newGame`() {
        val model = readyModel()
        assertEquals(2, generated.size)

        model.newGame()

        assertSame(generated[1], model.game?.puzzle)
        assertEquals(3, generated.size)
    }

    @Test
    fun `a prefetch for another level is dropped`() {
        val model = readyModel()
        model.chooseLevel(Level.HARD)
        assertEquals(4, generated.size)

        model.newGame()

        assertEquals(Level.HARD, model.game?.puzzle?.level)
        assertSame(generated[3], model.game?.puzzle)
    }

    @Test
    fun `a prefetch in flight when the level changes is not used`() {
        val model = model(queued)
        model.ensureGame()
        queue.removeFirst().run()

        model.chooseLevel(Level.HARD)
        runQueued()
        model.newGame()

        assertEquals(Level.HARD, model.game?.puzzle?.level)
        assertEquals(Level.HARD, generated.last().level)
        assertTrue(generated.filter { it.level == Level.HARD }.size >= 2)
    }

    @Test
    fun `the clock starts when the puzzle is installed`() {
        val model = model(queued)
        model.ensureGame()
        now += 4000
        assertEquals(0, model.elapsedMillis)

        queue.removeFirst().run()
        now += 5000

        assertEquals(5000, model.elapsedMillis)
    }

    @Test
    fun `a hidden tab and a manual pause stop the clock`() {
        val model = readyModel()
        now += 1000

        model.hidden = true
        now += 3000
        assertTrue(model.isPaused)
        assertFalse(model.isManuallyPaused)
        model.hidden = false
        model.togglePause()
        now += 3000
        assertTrue(model.isPaused)
        assertTrue(model.isManuallyPaused)
        model.togglePause()
        now += 1000

        assertEquals(2000, model.elapsedMillis)
        assertFalse(model.isPaused)
    }

    @Test
    fun `input and selection are ignored while paused`() {
        val model = readyModel()
        model.selected = c(0, 2)
        model.togglePause()

        model.input(3)
        model.moveSelection(1, 0)
        model.hint()

        assertEquals(0, model.game?.digit(c(0, 2)))
        assertEquals(c(0, 2), model.selected)
        assertEquals(0, model.game?.hints)
    }

    @Test
    fun `the hint penalty is part of the elapsed time`() {
        val model = readyModel()
        model.selected = c(2, 2)
        now += 2000

        model.hint()

        assertEquals(32000, model.elapsedMillis)
    }

    @Test
    fun `solving stops the clock and ignores input but not the selection`() {
        val model = readyModel()
        now += 5000

        model.solve()
        now += 9000
        model.selected = c(0, 2)
        model.input(1)
        model.erase()

        assertTrue(model.game?.isSolved == true)
        assertEquals(5000, model.elapsedMillis)
        assertEquals(3, model.game?.digit(c(0, 2)))
        assertEquals(c(0, 2), model.selected)
        model.moveSelection(1, 0)
        assertEquals(c(1, 2), model.selected)
        assertFalse(model.canPause)
    }

    @Test
    fun `the status shows the hint to start with and the best time`() {
        preferences.recordTime(four, Level.EASY, 21000)
        val model = readyModel()
        assertEquals("Best: 0:21", model.statusText)

        preferences.save(four, Level.HARD)
        model.chooseLevel(Level.HARD)
        assertEquals("Fill every row, column and box with 1–4", model.statusText)
    }

    @Test
    fun `the status names the digit of a wrong entry until the selection changes`() {
        val model = readyModel()
        model.selected = c(0, 2)
        model.input(4)

        model.hint()

        assertEquals("This 4 was wrong", model.statusText)
        model.selected = c(0, 3)
        assertEquals("Fill every row, column and box with 1–4", model.statusText)
    }

    @Test
    fun `the status after solving`() {
        val model = readyModel()
        now += 5000

        model.solve()

        assertEquals("Solved in 0:05 — new best!", model.statusText)
        assertEquals(5000L, preferences.best(four, Level.EASY))
    }

    @Test
    fun `a slower solve is no new best`() {
        preferences.recordTime(four, Level.EASY, 1000)
        val model = readyModel()
        now += 5000

        model.solve()

        assertEquals("Solved in 0:05", model.statusText)
        assertEquals(1000L, preferences.best(four, Level.EASY))
    }

    @Test
    fun `the status after solving with hints counts the penalty once and twice`() {
        val model = readyModel()
        now += 5000
        model.selected = c(2, 2)
        model.hint()
        model.solve(skip = setOf(c(2, 2)))

        assertEquals("Solved in 0:35 with 1 hint (+0:30) — new best!", model.statusText)
        assertEquals(35000L, preferences.best(four, Level.EASY))

        model.newGame()
        now += 1000
        model.selected = c(2, 2)
        model.hint()
        model.selected = c(0, 2)
        model.hint()
        model.solve(skip = setOf(c(2, 2), c(0, 2)))

        assertEquals("Solved in 1:01 with 2 hints (+1:00)", model.statusText)
    }

    @Test
    fun `needsConfirmToAbandon follows entries and notes`() {
        val model = readyModel()
        assertFalse(model.needsConfirmToAbandon)

        model.selected = c(0, 2)
        model.toggleNoteAt(3)
        assertTrue(model.needsConfirmToAbandon)

        model.restart()
        assertFalse(model.needsConfirmToAbandon)
        model.solve()
        assertFalse(model.needsConfirmToAbandon)
    }

    @Test
    fun `choosing size and level saves them and starts a new puzzle`() {
        val model = readyModel()

        model.chooseSize(SudokuSize.SIX)
        model.chooseLevel(Level.EXPERT)

        assertEquals(SudokuSize.SIX, preferences.lastSize)
        assertEquals(Level.EXPERT, preferences.lastLevel)
        assertEquals(SudokuSize.SIX, model.game?.size)
        assertEquals(Level.EXPERT, model.game?.puzzle?.level)
    }

    @Test
    fun `choosing the current size or level does nothing`() {
        val model = readyModel()
        val game = model.game
        val calls = generated.size

        model.chooseSize(four)
        model.chooseLevel(Level.EASY)

        assertSame(game, model.game)
        assertEquals(calls, generated.size)
    }

    @Test
    fun `completed units flash`() {
        val model = readyModel()
        val events = Events(model)
        model.selected = c(0, 2)
        model.input(3)
        assertTrue(events.flashes.isEmpty())

        model.selected = c(0, 3)
        model.input(4)

        assertEquals(listOf<Flash>(Flash.Units(listOf(four.units[0]))), events.flashes)
    }

    @Test
    fun `solving flashes Solved instead of units`() {
        val model = readyModel()
        model.solve(skip = setOf(c(3, 3)))
        val events = Events(model)
        model.selected = c(3, 3)

        model.input(1)

        assertEquals(listOf<Flash>(Flash.Solved), events.flashes)
    }

    @Test
    fun `a hint that removes a wrong entry flashes it and selects it`() {
        val model = readyModel()
        model.selected = c(0, 2)
        model.input(4)
        model.selected = c(3, 3)
        val events = Events(model)

        model.hint()

        assertEquals(listOf<Flash>(Flash.Wrong(c(0, 2))), events.flashes)
        assertEquals(c(0, 2), model.selected)
        assertEquals(0, model.game?.digit(c(0, 2)))
    }

    @Test
    fun `the selection moves within the grid`() {
        val model = readyModel()

        model.moveSelection(-1, -1)
        assertEquals(c(0, 0), model.selected)
        model.moveSelection(-1, 0)
        assertEquals(c(0, 0), model.selected)
        model.moveSelection(1, 1)
        model.moveSelection(5, 5)
        assertEquals(c(3, 3), model.selected)
        model.selected = Cell(9, -2)
        assertEquals(c(3, 0), model.selected)
    }

    @Test
    fun `digits above the size are ignored and notes mode toggles notes`() {
        val model = readyModel()
        model.selected = c(0, 2)

        model.input(7)
        assertFalse(model.needsConfirmToAbandon)
        model.toggleNotesMode()
        model.input(3)

        assertTrue(model.notesMode)
        assertEquals(setOf(3), model.game?.notes(c(0, 2)))
        model.toggleNotesMode()
        model.input(3)
        assertEquals(3, model.game?.digit(c(0, 2)))
    }

    @Test
    fun `erase fill notes undo redo and restart act on the game`() {
        val model = readyModel()
        model.selected = c(0, 2)
        model.input(3)

        model.erase()
        assertEquals(0, model.game?.digit(c(0, 2)))
        model.undo()
        assertEquals(3, model.game?.digit(c(0, 2)))
        model.redo()
        assertEquals(0, model.game?.digit(c(0, 2)))
        model.fillNotes()
        assertEquals(setOf(3, 4), model.game?.notes(c(0, 2)))
        model.restart()
        assertEquals(emptySet(), model.game?.notes(c(0, 2)))
    }

    @Test
    fun `every change notifies the view`() {
        val model = readyModel()
        val events = Events(model)

        model.selected = c(0, 2)
        model.input(3)
        model.toggleNotesMode()
        model.togglePause()
        model.hidden = true

        assertEquals(5, events.changes)
    }
}
