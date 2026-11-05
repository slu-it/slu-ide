package dev.sluide.games.minesweeper

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class MinesweeperModelTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var preferences: MinesweeperPreferences
    private var now = 0L
    private val clock = GameClock { now }
    private val beginner = SizeChoice.OfPreset(Preset.BEGINNER)
    private val custom = SizeChoice.Custom(BoardSize(5, 5, 5))

    /** A column of mines at x = 4 and one at the top left corner: opening (8,8) and then (0,8) wins. */
    private val columnMines = (0..8).map { 4 to it }.toSet() + (0 to 0)
    private val customMines = (0..4).map { 2 to it }.toSet()

    @BeforeEach
    fun setUp() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        preferences = MinesweeperPreferences(store, Duration.ZERO, Executor { it.run() })
    }

    private fun model() =
        MinesweeperModel(
            preferences,
            clock = clock,
            gameFactory = { size -> MinesweeperGame(size, if (size.mines == 10) columnMines else customMines) },
        )

    private fun MinesweeperModel.win() {
        reveal(8, 8)
        reveal(0, 8)
    }

    @Test
    fun `starts with the last size`() {
        preferences.saveSize(custom)

        val model = model()

        assertEquals(custom, model.choice)
        assertEquals("Custom 5×5/5", model.sizeLabel)
        assertEquals(2, model.cursorX)
    }

    @Test
    fun `the first change to running starts the clock and a flag alone does not`() {
        val model = model()

        model.toggleFlag(0, 8)
        assertFalse(clock.isStarted)
        assertEquals("0", model.timerText)

        model.reveal(8, 8)
        now += 3500

        assertTrue(clock.isStarted)
        assertEquals("3", model.timerText)
        assertEquals("9", model.counterText)
    }

    @Test
    fun `actions are ignored while paused by hand or hidden`() {
        val model = model()
        model.reveal(8, 8)
        val before = model.game.cell(0, 8)

        model.togglePause()
        model.reveal(0, 8)
        model.toggleFlag(1, 8)
        model.chord(8, 8)
        assertEquals(before, model.game.cell(0, 8))
        assertEquals(CellKind.CLOSED, model.game.cell(1, 8))
        assertTrue(model.isPaused)

        model.togglePause()
        model.hidden = true
        model.toggleFlag(1, 8)
        assertEquals(CellKind.CLOSED, model.game.cell(1, 8))
        assertTrue(model.isPaused)
        assertFalse(model.isManuallyPaused)

        model.hidden = false
        model.toggleFlag(1, 8)
        assertEquals(CellKind.FLAGGED, model.game.cell(1, 8))
    }

    @Test
    fun `pause by hand works only while running`() {
        val model = model()
        assertFalse(model.canPause)
        model.togglePause()
        assertFalse(model.isPaused)
        assertFalse(clock.manuallyPaused)

        model.reveal(8, 8)
        assertTrue(model.canPause)
        model.togglePause()
        assertTrue(model.isManuallyPaused)
        // The overlay says it; the status line keeps the normal text.
        assertEquals("", model.statusText)
        model.togglePause()
        assertFalse(model.isPaused)

        model.reveal(4, 0)
        assertFalse(model.canPause)
        assertFalse(model.isPaused)
    }

    @Test
    fun `a won preset game records the best time and says so`() {
        val model = model()
        model.reveal(8, 8)
        now += 42_300

        model.reveal(0, 8)

        assertEquals(GameState.WON, model.game.state)
        assertEquals(42_300L, preferences.bestTime(Preset.BEGINNER))
        assertEquals("Cleared in 42.3 s — new best!", model.statusText)
        assertTrue(clock.isStopped)

        model.newGame()
        assertEquals("Best: 42.3 s", model.statusText)
        model.reveal(8, 8)
        now += 50_000
        model.reveal(0, 8)
        assertEquals("Cleared in 50.0 s", model.statusText)
        assertEquals(42_300L, preferences.bestTime(Preset.BEGINNER))
    }

    @Test
    fun `a won custom game records nothing`() {
        val model = model()
        model.newGame(custom)
        model.reveal(4, 4)
        now += 5000
        model.reveal(0, 4)

        assertEquals(GameState.WON, model.game.state)
        assertEquals("Cleared in 5.0 s", model.statusText)
        assertTrue(Preset.entries.all { preferences.bestTime(it) == null })
    }

    @Test
    fun `a lost game stops the clock and says so`() {
        val model = model()

        model.reveal(4, 0)

        assertEquals(GameState.LOST, model.game.state)
        assertTrue(clock.isStopped)
        assertEquals("Boom — try again (F2)", model.statusText)
    }

    @Test
    fun `a new game resets the clock, the pause and the cursor and saves the size`() {
        val model = model()
        model.reveal(8, 8)
        model.moveCursor(-3, -3)
        model.togglePause()
        now += 2000
        var changes = 0
        model.onChange = { changes++ }
        val old = model.game

        model.newGame(custom)

        assertNotSame(old, model.game)
        assertEquals(GameState.NOT_STARTED, model.game.state)
        assertFalse(clock.isStarted)
        assertFalse(clock.manuallyPaused)
        assertEquals(0, clock.elapsedMillis)
        assertEquals(custom, preferences.lastSize)
        assertEquals(2 to 2, model.cursorX to model.cursorY)
        assertEquals(1, changes)
    }

    @Test
    fun `the cursor stays on the board`() {
        val model = model()

        model.moveCursor(-20, -20)
        assertEquals(0 to 0, model.cursorX to model.cursorY)
        model.moveCursor(20, 20)
        assertEquals(8 to 8, model.cursorX to model.cursorY)
    }

    @Test
    fun `key actions act on the cursor cell`() {
        val model = model()

        model.perform(KeyAction.Move(1, 0))
        model.perform(KeyAction.Flag)
        assertEquals(CellKind.FLAGGED, model.game.cell(5, 4))
        model.perform(KeyAction.Move(3, 4))
        model.perform(KeyAction.Reveal)
        assertEquals(CellKind.OPENED, model.game.cell(8, 8))
        model.perform(KeyAction.Pause)
        assertTrue(model.isManuallyPaused)
        model.perform(KeyAction.NewGame)
        assertEquals(GameState.NOT_STARTED, model.game.state)
    }
}
