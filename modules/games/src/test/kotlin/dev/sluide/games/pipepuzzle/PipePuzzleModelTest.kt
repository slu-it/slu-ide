package dev.sluide.games.pipepuzzle

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipePuzzleModelTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var preferences: PipePuzzlePreferences
    private var now = 0L
    private val requests = mutableListOf<Pair<Mode, BoardSize>>()
    private var failing = false
    private val sync = Executor { it.run() }

    private val source = Pos(0, 0)
    private val upperRight = Pos(0, 1)
    private val lowerRight = Pos(1, 1)

    // 2x2 ring: source E, drain E, two corners; each corner is one counter-clockwise turn from solved.
    private val puzzle =
        Puzzle(
            Mode.NETWORK,
            2,
            source,
            Pos(1, 0),
            listOf(Openings.of(Dir.E), Openings.of(Dir.S, Dir.W), Openings.of(Dir.E), Openings.of(Dir.N, Dir.W)),
            listOf(Openings.of(Dir.E), Openings.of(Dir.N, Dir.W), Openings.of(Dir.E), Openings.of(Dir.N, Dir.E)),
        )

    private var changes = 0
    private val turned = mutableListOf<Pair<Pos, Boolean>>()
    private var solvedCalls = 0

    @BeforeEach
    fun setUp() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        preferences = PipePuzzlePreferences(store, Duration.ZERO) { it.run() }
    }

    private fun generate(
        mode: Mode,
        size: BoardSize,
    ): Puzzle {
        requests += mode to size
        if (failing) error("no puzzle")
        return puzzle
    }

    private fun model(
        background: Executor = sync,
        fx: Executor = sync,
    ) = PipePuzzleModel(preferences, ::generate, background, fx, GameClock { now }).also {
        it.onChange = { changes++ }
        it.onTurned = { pos, cw -> turned += pos to cw }
        it.onSolved = { solvedCalls++ }
    }

    private fun readyModel() = model().also { it.ensureGame() }

    private fun solve(model: PipePuzzleModel) {
        model.turn(upperRight, false)
        model.turn(lowerRight, false)
    }

    @Test
    fun `ensureGame generates once with the remembered mode and size`() {
        preferences.lastMode = Mode.PATH
        preferences.lastSize = BoardSize.S9
        val model = model()

        assertNull(model.game)
        model.ensureGame()
        model.ensureGame()

        assertNotNull(model.game)
        assertFalse(model.isGenerating)
        assertEquals(listOf(Mode.PATH to BoardSize.S9), requests)
    }

    @Test
    fun `the clock starts with the first turn`() {
        val model = readyModel()
        now = 5000
        assertEquals(0, model.elapsedMillis)

        model.turn(upperRight, false)
        now = 7000

        assertEquals(2000, model.elapsedMillis)
    }

    @Test
    fun `a turn fires onTurned and a fixed tile fires nothing`() {
        val model = readyModel()
        changes = 0

        model.turn(source, true)
        assertTrue(turned.isEmpty())
        assertEquals(0, changes)
        assertFalse(model.needsConfirmToAbandon)

        model.turn(upperRight, false)
        assertEquals(listOf(upperRight to false), turned)
        assertEquals(1, changes)
    }

    @Test
    fun `solving stops the clock records the best time and says so`() {
        val model = readyModel()
        now = 5000
        model.turn(upperRight, false)
        now = 7000
        model.turn(lowerRight, false)
        now = 20000

        assertEquals(1, solvedCalls)
        assertEquals(2000, model.elapsedMillis)
        assertEquals("Solved in 0:02 — new best!", model.statusText)
        assertEquals(2000L, preferences.best(Mode.NETWORK, BoardSize.S7))
        assertFalse(model.needsConfirmToAbandon)

        model.turn(upperRight, true)
        assertEquals(2, turned.size)
        assertEquals(1, solvedCalls)
    }

    @Test
    fun `a slower solve is no new best and a stored one shows before solving`() {
        preferences.recordBest(Mode.NETWORK, BoardSize.S7, 1000)
        val model = readyModel()
        assertEquals("Best: 0:01", model.statusText)

        now = 1
        model.turn(upperRight, false)
        now = 5000
        model.turn(lowerRight, false)

        assertEquals("Solved in 0:04", model.statusText)
        assertEquals(1000L, preferences.best(Mode.NETWORK, BoardSize.S7))
    }

    @Test
    fun `hints add the penalty to the time and the text`() {
        val model = readyModel()
        model.hint()
        now = 2000
        assertEquals(32000, model.elapsedMillis)
        assertEquals(0, solvedCalls)
        model.hint()

        assertEquals(1, solvedCalls)
        assertEquals("Solved in 1:02 with 2 hints (+1:00) — new best!", model.statusText)
    }

    @Test
    fun `one hint reads in the singular`() {
        val model = readyModel()
        model.turn(upperRight, false)
        model.hint()

        assertEquals("Solved in 0:30 with 1 hint (+0:30) — new best!", model.statusText)
    }

    @Test
    fun `restart resets the clock the hints the turns and the confirm flags`() {
        val model = readyModel()
        now = 1000
        model.hint()
        now = 9000
        assertTrue(model.needsConfirmToAbandon)
        assertTrue(model.needsConfirmToRestart)
        changes = 0

        model.restart()

        assertEquals(1, changes)
        assertEquals(0, model.game?.hints)
        assertEquals(0, model.elapsedMillis)
        assertFalse(model.needsConfirmToAbandon)
        assertFalse(model.needsConfirmToRestart)
        assertEquals(puzzle.start[1], model.game?.tile(upperRight))
        now = 20000
        assertEquals(0, model.elapsedMillis)
        assertEquals("Connect every tile to the source", model.statusText)
    }

    @Test
    fun `restart after solving allows a replay`() {
        val model = readyModel()
        solve(model)
        model.restart()
        assertEquals(0, model.elapsedMillis)
        assertTrue(model.canPause)

        solve(model)

        assertEquals(2, solvedCalls)
    }

    @Test
    fun `confirm flags are false before turns and after the solve`() {
        val model = readyModel()
        assertFalse(model.needsConfirmToAbandon)
        model.turn(upperRight, false)
        assertTrue(model.needsConfirmToAbandon)
        model.turn(lowerRight, false)
        assertFalse(model.needsConfirmToAbandon)
        assertFalse(model.needsConfirmToRestart)
    }

    @Test
    fun `choosing the same mode or size does nothing and another one regenerates and remembers`() {
        val model = readyModel()
        requests.clear()

        model.chooseMode(Mode.NETWORK)
        model.chooseSize(BoardSize.S7)
        assertTrue(requests.isEmpty())

        model.chooseMode(Mode.PATH)
        model.chooseSize(BoardSize.S5)

        assertEquals(listOf(Mode.PATH to BoardSize.S7, Mode.PATH to BoardSize.S5), requests)
        assertEquals(Mode.PATH, preferences.lastMode)
        assertEquals(BoardSize.S5, preferences.lastSize)
        assertEquals("Lead the flow to the drain without leaks", model.statusText)
    }

    @Test
    fun `a new game resets the clock the turns and the cursor`() {
        val model = readyModel()
        now = 1000
        model.turn(upperRight, false)
        model.moveCursor(0, 0)
        now = 4000

        model.newGame()

        assertEquals(0, model.elapsedMillis)
        assertFalse(model.needsConfirmToAbandon)
        assertNull(model.cursor)
    }

    @Test
    fun `the look is stored and the game stays`() {
        val model = readyModel()
        val game = model.game
        model.turn(upperRight, false)
        changes = 0

        model.look = Look.POWER
        model.look = Look.POWER

        assertEquals(1, changes)
        assertEquals(Look.POWER, preferences.lastLook)
        assertTrue(game === model.game)
        assertEquals(puzzle.solution[1], model.game?.tile(upperRight))
    }

    @Test
    fun `a stale generation result is ignored`() {
        val background = ArrayDeque<Runnable>()
        val fx = ArrayDeque<Runnable>()
        val model = model({ background.addLast(it) }, { fx.addLast(it) })
        model.ensureGame()
        model.chooseSize(BoardSize.S5)

        background.removeFirst().run()
        background.removeFirst().run()
        fx.removeFirst().run()
        assertNull(model.game)
        assertTrue(model.isGenerating)
        assertEquals("Generating a puzzle…", model.statusText)

        fx.removeFirst().run()
        assertNotNull(model.game)
        assertFalse(model.isGenerating)
    }

    @Test
    fun `a failed generation says so and a new game tries again`() {
        failing = true
        val model = readyModel()
        assertNull(model.game)
        assertFalse(model.isGenerating)
        assertEquals("Could not generate a puzzle — F2 to try again", model.statusText)

        failing = false
        model.handle(KeyAction.NewGame)

        assertNotNull(model.game)
        assertEquals("Connect every tile to the source", model.statusText)
    }

    @Test
    fun `pause blocks turns and hints and the clock`() {
        val model = readyModel()
        now = 1000
        model.turn(upperRight, false)
        model.togglePause()
        assertTrue(model.isPaused)
        assertTrue(model.isManuallyPaused)
        now = 5000
        assertEquals(0, model.elapsedMillis)

        model.turn(lowerRight, false)
        model.hint()
        assertEquals(1, turned.size)
        assertEquals(0, model.game?.hints)

        model.togglePause()
        assertFalse(model.isPaused)
        model.turn(lowerRight, false)
        assertEquals(2, turned.size)
    }

    @Test
    fun `a hidden tab pauses and nothing pauses without a game or when solved`() {
        val model = model()
        model.togglePause()
        assertFalse(model.canPause)
        model.ensureGame()

        model.hidden = true
        assertTrue(model.isPaused)
        assertFalse(model.isManuallyPaused)
        model.hidden = false
        assertFalse(model.isPaused)

        solve(model)
        assertFalse(model.canPause)
        model.togglePause()
        assertFalse(model.isManuallyPaused)
    }

    @Test
    fun `the cursor starts at the centre and is clamped to the board`() {
        val model = readyModel()
        assertNull(model.cursor)

        model.moveCursor(1, 0)
        assertEquals(Pos(1, 1), model.cursor)

        repeat(3) { model.moveCursor(-1, -1) }
        assertEquals(Pos(0, 0), model.cursor)
        repeat(3) { model.moveCursor(1, 1) }
        assertEquals(Pos(1, 1), model.cursor)
    }

    @Test
    fun `turnAtCursor starts at the centre and turns there`() {
        val model = readyModel()
        val before = model.game?.tile(lowerRight)

        model.turnAtCursor(false)

        assertEquals(lowerRight, model.cursor)
        assertEquals(listOf(lowerRight to false), turned)
        assertEquals(before?.rotatedCcw(), model.game?.tile(lowerRight))

        model.cursor = upperRight
        model.turnAtCursor(false)
        assertEquals(listOf(lowerRight to false, upperRight to false), turned)
    }

    @Test
    fun `input is ignored while generating`() {
        val background = ArrayDeque<Runnable>()
        val model = model({ background.addLast(it) })
        model.ensureGame()
        changes = 0

        model.turn(upperRight, true)
        model.turnAtCursor(true)
        model.hint()
        model.restart()
        model.moveCursor(1, 1)
        model.cursor = upperRight

        assertNull(model.game)
        assertNull(model.cursor)
        assertEquals(0, changes)
        assertTrue(turned.isEmpty())
    }

    @Test
    fun `restart is ignored while paused`() {
        val model = readyModel()
        model.turn(upperRight, false)
        model.togglePause()
        changes = 0

        model.restart()

        assertEquals(0, changes)
        assertEquals(puzzle.solution[1], model.game?.tile(upperRight))
        assertTrue(model.needsConfirmToRestart)
    }

    @Test
    fun `a generator that throws an Error still ends in the failure status`() {
        val model =
            PipePuzzleModel(preferences, { _, _ -> throw StackOverflowError() }, sync, sync, GameClock { now })

        model.ensureGame()

        assertFalse(model.isGenerating)
        assertEquals("Could not generate a puzzle — F2 to try again", model.statusText)
    }

    @Test
    fun `handle dispatches the key actions`() {
        val model = readyModel()
        model.handle(KeyAction.Move(0, 1))
        assertEquals(Pos(1, 1), model.cursor)

        model.cursor = lowerRight
        model.handle(KeyAction.Turn(false))
        assertEquals(listOf(lowerRight to false), turned)

        model.handle(KeyAction.Pause)
        assertTrue(model.isManuallyPaused)
        model.handle(KeyAction.Pause)
        assertFalse(model.isManuallyPaused)

        model.handle(KeyAction.Hint)
        assertEquals(1, model.game?.hints)

        model.handle(KeyAction.Restart)
        assertEquals(0, model.game?.hints)
    }
}
