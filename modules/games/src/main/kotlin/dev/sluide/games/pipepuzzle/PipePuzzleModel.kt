package dev.sluide.games.pipepuzzle

import dev.sluide.games.common.GameClock
import dev.sluide.games.common.formatTime
import dev.sluide.games.sudoku.HINT_PENALTY_MILLIS
import java.util.concurrent.Executor

/**
 * The game of one window: the puzzle (generated off the FX thread), the clock, the cursor and what the view shows of
 * them. FX thread; [generate] blocks and runs on [background], its result is handed to [fx]. A result that is no longer
 * wanted (another mode, size or New Game meanwhile) is dropped.
 */
internal class PipePuzzleModel(
    private val preferences: PipePuzzlePreferences,
    private val generate: (Mode, BoardSize) -> Puzzle,
    private val background: Executor,
    private val fx: Executor,
    private val clock: GameClock = GameClock(),
) {
    /** Called after every change of what the view shows (status, tiles, pause, generating, look, cursor). */
    var onChange: () -> Unit = {}

    /** Called when the player turned a tile (position, clockwise), for the turn animation. */
    var onTurned: (Pos, Boolean) -> Unit = { _, _ -> }

    /** Called when the puzzle has just been solved, by a turn or a hint, for the flow animation. */
    var onSolved: () -> Unit = {}

    var mode: Mode = preferences.lastMode
        private set

    var size: BoardSize = preferences.lastSize
        private set

    /** The look of the board; setting it stores it and redraws, the game stays. */
    var look: Look = preferences.lastLook
        set(value) {
            if (field == value) return
            field = value
            preferences.lastLook = value
            onChange()
        }

    /** Null while generating and before [ensureGame]. */
    var game: PipePuzzleGame? = null
        private set

    var isGenerating = false
        private set

    private var cursorPos: Pos? = null
    private var turns = 0
    private var newBest = false
    private var failed = false
    private var request = 0

    /** The keyboard cursor; null until first moved. Ignored while generating or paused; clamped to the board. */
    var cursor: Pos?
        get() = cursorPos
        set(value) {
            val side = game?.side ?: return
            if (isPaused) return
            val clamped = value?.let { Pos(it.row.coerceIn(0, side - 1), it.col.coerceIn(0, side - 1)) }
            if (clamped == cursorPos) return
            cursorPos = clamped
            onChange()
        }

    /** True when turns or hints were made since the puzzle (or its restart) began and it is not solved. */
    val needsConfirmToAbandon: Boolean get() = turns > 0 && game?.isSolved == false

    /** Same condition as [needsConfirmToAbandon]: restarting would throw the turns away. */
    val needsConfirmToRestart: Boolean get() = needsConfirmToAbandon

    val canPause: Boolean get() = game?.isSolved == false

    val isManuallyPaused: Boolean get() = canPause && clock.manuallyPaused

    val isPaused: Boolean get() = canPause && (clock.hidden || clock.manuallyPaused)

    /** The tab is not shown (automatic pause). */
    var hidden: Boolean
        get() = clock.hidden
        set(value) {
            if (clock.hidden == value) return
            clock.hidden = value
            onChange()
        }

    /** The clock plus the penalty of the hints taken. */
    val elapsedMillis: Long get() = clock.elapsedMillis + (game?.hints ?: 0) * HINT_PENALTY_MILLIS

    /** The line under the board. */
    val statusText: String
        get() {
            val g = game
            val best = preferences.best(mode, size)
            return when {
                g == null -> {
                    if (failed) "Could not generate a puzzle — F2 to try again" else "Generating a puzzle…"
                }

                g.isSolved -> {
                    "Solved in ${formatTime(elapsedMillis)}" + hintsText(g.hints) + if (newBest) " — new best!" else ""
                }

                best != null -> {
                    "Best: ${formatTime(best)}"
                }

                mode == Mode.NETWORK -> {
                    "Connect every tile to the source"
                }

                else -> {
                    "Lead the flow to the drain without leaks"
                }
            }
        }

    private fun hintsText(hints: Int): String =
        when (hints) {
            0 -> ""
            1 -> " with 1 hint (+${formatTime(HINT_PENALTY_MILLIS)})"
            else -> " with $hints hints (+${formatTime(hints * HINT_PENALTY_MILLIS)})"
        }

    /** Starts the first generation when there is no game and none is running; the view calls it when shown. */
    fun ensureGame() {
        if (game == null && !isGenerating) generateNew()
    }

    /** Moves the cursor; the first move puts it on the centre cell. Clamped to the board. */
    fun moveCursor(
        dRow: Int,
        dCol: Int,
    ) {
        val side = game?.side ?: return
        val from = cursorPos
        cursor = if (from == null) centre(side) else Pos(from.row + dRow, from.col + dCol)
    }

    /** Turns the tile at [pos] a quarter; ignored while paused, generating or solved. */
    fun turn(
        pos: Pos,
        clockwise: Boolean,
    ) {
        val g = acting() ?: return
        if (!g.turn(pos, clockwise)) return
        onTurned(pos, clockwise)
        after()
    }

    /** Turns the tile under the cursor, which starts on the centre cell when it was not moved yet. */
    fun turnAtCursor(clockwise: Boolean) {
        val g = acting() ?: return
        val at = cursorPos ?: centre(g.side).also { cursor = it }
        turn(at, clockwise)
    }

    /** Turns one wrong tile into its solution; costs [HINT_PENALTY_MILLIS] of time. */
    fun hint() {
        val g = acting() ?: return
        if (g.hint() == null) return
        after()
    }

    /** Back to how this puzzle started, with the clock and the hints at 0; the view asks first when needed. */
    fun restart() {
        val g = game ?: return
        if (isPaused) return
        g.restart()
        clock.reset()
        turns = 0
        newBest = false
        onChange()
    }

    /** A new puzzle of the same mode and size; the view asks first when [needsConfirmToAbandon]. */
    fun newGame() = generateNew()

    /** Another mode: remembered, and a new puzzle; the same mode does nothing. */
    fun chooseMode(mode: Mode) {
        if (mode == this.mode) return
        this.mode = mode
        preferences.lastMode = mode
        generateNew()
    }

    /** Another size: remembered, and a new puzzle; the same size does nothing. */
    fun chooseSize(size: BoardSize) {
        if (size == this.size) return
        this.size = size
        preferences.lastSize = size
        generateNew()
    }

    /** Pauses or resumes by hand; only while a puzzle is being played. */
    fun togglePause() {
        if (!canPause) return
        clock.manuallyPaused = !clock.manuallyPaused
        onChange()
    }

    /** Runs what a key asks for; Restart and New Game go straight through, the view asks first. */
    fun handle(action: KeyAction) {
        when (action) {
            is KeyAction.Move -> moveCursor(action.dRow, action.dCol)
            is KeyAction.Turn -> turnAtCursor(action.clockwise)
            KeyAction.Hint -> hint()
            KeyAction.Restart -> restart()
            KeyAction.Pause -> togglePause()
            KeyAction.NewGame -> newGame()
        }
    }

    private fun centre(side: Int) = Pos(side / 2, side / 2)

    /** The game when input is accepted: a puzzle that is not solved and not paused. */
    private fun acting(): PipePuzzleGame? = game?.takeIf { !it.isSolved && !isPaused }

    /** After a turn or a hint: the clock, and the solving. */
    private fun after() {
        val g = game ?: return
        turns++
        clock.start()
        if (g.isSolved) {
            clock.stop()
            newBest = preferences.recordBest(mode, size, elapsedMillis)
            onSolved()
        }
        onChange()
    }

    private fun generateNew() {
        val mine = ++request
        isGenerating = true
        failed = false
        game = null
        cursorPos = null
        turns = 0
        newBest = false
        clock.reset()
        onChange()
        val m = mode
        val s = size
        background.execute {
            val puzzle = attempt(m, s)
            fx.execute {
                if (mine != request) return@execute
                isGenerating = false
                if (puzzle != null) game = PipePuzzleGame(puzzle) else failed = true
                onChange()
            }
        }
    }

    private fun attempt(
        m: Mode,
        s: BoardSize,
    ): Puzzle? =
        try {
            generate(m, s)
        } catch (e: Throwable) {
            // Errors too: without a result the model would wait for the generator forever.
            System
                .getLogger(PipePuzzleModel::class.java.name)
                .log(System.Logger.Level.WARNING, "Pipe Puzzle generation failed", e)
            null
        }
}
