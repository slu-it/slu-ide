package dev.sluide.games.sudoku

import dev.sluide.games.common.GameClock
import java.util.concurrent.Executor

/** A short effect the view plays after a step. */
sealed interface Flash {
    data class Units(
        val units: List<List<Cell>>,
    ) : Flash

    /** Sent instead of [Units] when a step solves the puzzle. */
    data object Solved : Flash

    /** A hint removed a wrong entry here. */
    data class Wrong(
        val cell: Cell,
    ) : Flash
}

/** Time added to the clock for every hint. */
internal const val HINT_PENALTY_MILLIS = 30_000L

/**
 * The game of one window: the puzzle (generated off the FX thread, the next one in advance), the clock, the selection
 * and what the view shows of them. FX thread; [generate] blocks and runs on [background], its results are handed to
 * [fx]. A result that is no longer wanted (another size, level or New Game meanwhile) is dropped.
 */
internal class SudokuModel(
    private val preferences: SudokuPreferences,
    private val generate: (SudokuSize, Level) -> Puzzle,
    private val background: Executor,
    private val fx: Executor,
    private val clock: GameClock = GameClock(),
) {
    /** Called after every change of what the view shows. */
    var onChange: () -> Unit = {}

    /** Called after a step for an effect on the board. */
    var onFlash: (Flash) -> Unit = {}

    var size: SudokuSize = preferences.lastSize
        private set

    var level: Level = preferences.lastLevel
        private set

    /** Null while generating and before [ensureGame]. */
    var game: SudokuGame? = null
        private set

    var isGenerating = false
        private set

    var notesMode = false
        private set

    private var selectedCell: Cell? = null
    private var wrongDigit: Int? = null
    private var newBest = false

    private var request = 0
    private var prefetched: Puzzle? = null
    private var prefetching = false
    private var prefetchingEpoch = 0
    private var prefetchEpoch = 0

    /** New Game was asked while a matching prefetch runs: install its result when it arrives. */
    private var installPrefetch = false
    private var failed = false

    /** The selected cell; null after a new puzzle. Ignored while generating or paused; clamped to the grid. */
    var selected: Cell?
        get() = selectedCell
        set(value) {
            if (game == null || isPaused) return
            val clamped = value?.let { Cell(it.row.coerceIn(0, size.side - 1), it.col.coerceIn(0, size.side - 1)) }
            if (clamped == selectedCell) return
            wrongDigit = null
            selectedCell = clamped
            onChange()
        }

    val elapsedMillis: Long get() = clock.elapsedMillis + (game?.hints ?: 0) * HINT_PENALTY_MILLIS

    val needsConfirmToAbandon: Boolean get() = game?.let { !it.isSolved && it.hasEntries } ?: false

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

    val statusText: String
        get() {
            val g = game
            val best = preferences.best(size, level)
            return when {
                g == null -> {
                    if (failed) "Could not generate a puzzle — F2 to try again" else "Generating a puzzle…"
                }

                g.isSolved -> {
                    "Solved in ${formatTime(elapsedMillis)}" + hintsText(g.hints) + if (newBest) " — new best!" else ""
                }

                wrongDigit != null -> {
                    "This $wrongDigit was wrong"
                }

                best != null -> {
                    "Best: ${formatTime(best)}"
                }

                else -> {
                    "Fill every row, column and box with 1–${size.side}"
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

    fun moveSelection(
        dRow: Int,
        dCol: Int,
    ) {
        val from = selectedCell
        selected = if (from == null) Cell(0, 0) else Cell(from.row + dRow, from.col + dCol)
    }

    /** Places [digit] on the selected cell, or toggles it as a note in notes mode. */
    fun input(digit: Int) {
        if (digit !in 1..size.side) return
        val g = acting() ?: return
        val cell = selectedCell ?: return
        after(if (notesMode) g.toggleNote(cell, digit) else g.place(cell, digit))
    }

    fun toggleNoteAt(digit: Int) {
        val g = acting() ?: return
        val cell = selectedCell ?: return
        after(g.toggleNote(cell, digit))
    }

    fun erase() {
        val g = acting() ?: return
        val cell = selectedCell ?: return
        after(g.erase(cell))
    }

    fun fillNotes() {
        val g = acting() ?: return
        after(g.fillNotes())
    }

    fun hint() {
        val g = acting() ?: return
        when (val result = g.hint(selectedCell)) {
            null -> {
                return
            }

            is HintResult.Fixed -> {
                selectedCell = result.cell
                wrongDigit = result.wrongDigit
                onChange()
                onFlash(Flash.Wrong(result.cell))
            }

            is HintResult.Filled -> {
                selectedCell = result.cell
                after(result.step)
            }
        }
    }

    fun undo() {
        val g = acting() ?: return
        if (g.undo()) changed()
    }

    fun redo() {
        val g = acting() ?: return
        if (g.redo()) changed()
    }

    /** Back to the givens; the view asks first when [needsConfirmToAbandon]. */
    fun restart() {
        val g = acting() ?: return
        g.restart()
        changed()
    }

    /** A new puzzle of the same size and level, at once when one is ready; the view asks first. */
    fun newGame() {
        val ready = prefetched
        when {
            ready != null -> {
                prefetched = null
                request++
                installPrefetch = false
                install(ready)
            }

            prefetching && prefetchingEpoch == prefetchEpoch -> {
                request++
                installPrefetch = true
                startGenerating()
            }

            else -> {
                generateNew()
            }
        }
    }

    fun chooseSize(size: SudokuSize) {
        if (size == this.size) return
        this.size = size
        chosen()
    }

    fun chooseLevel(level: Level) {
        if (level == this.level) return
        this.level = level
        chosen()
    }

    fun toggleNotesMode() {
        notesMode = !notesMode
        onChange()
    }

    /** Pauses or resumes by hand; only while a puzzle is being played. */
    fun togglePause() {
        if (!canPause) return
        clock.manuallyPaused = !clock.manuallyPaused
        onChange()
    }

    private fun chosen() {
        preferences.save(size, level)
        prefetched = null
        prefetchEpoch++
        generateNew()
    }

    /** The game when input is accepted: a puzzle that is running, not solved and not paused. */
    private fun acting(): SudokuGame? = game?.takeIf { !it.isSolved && !isPaused }

    private fun changed() {
        wrongDigit = null
        onChange()
    }

    /** Handles the result of a step: the solving, the flash of completed units and the change. */
    private fun after(result: StepResult?) {
        if (result == null) return
        wrongDigit = null
        if (result.solved) {
            clock.stop()
            newBest = preferences.recordTime(size, level, elapsedMillis)
        }
        onChange()
        if (result.solved) {
            onFlash(Flash.Solved)
        } else if (result.completedUnits.isNotEmpty()) {
            onFlash(Flash.Units(result.completedUnits))
        }
    }

    private fun startGenerating() {
        isGenerating = true
        failed = false
        game = null
        selectedCell = null
        wrongDigit = null
        clock.reset()
        onChange()
    }

    private fun generateNew() {
        val mine = ++request
        installPrefetch = false
        startGenerating()
        val s = size
        val l = level
        background.execute {
            val puzzle = attempt(s, l)
            fx.execute {
                if (mine != request) return@execute
                if (puzzle != null) {
                    install(puzzle)
                } else {
                    isGenerating = false
                    failed = true
                    onChange()
                }
            }
        }
    }

    private fun attempt(
        s: SudokuSize,
        l: Level,
    ): Puzzle? =
        try {
            generate(s, l)
        } catch (e: Exception) {
            System
                .getLogger(SudokuModel::class.java.name)
                .log(System.Logger.Level.WARNING, "Sudoku puzzle generation failed", e)
            null
        }

    private fun install(puzzle: Puzzle) {
        game = SudokuGame(puzzle)
        isGenerating = false
        selectedCell = null
        wrongDigit = null
        newBest = false
        clock.reset()
        clock.start()
        onChange()
        prefetch()
    }

    private fun prefetch() {
        if (prefetched != null || prefetching) return
        val epoch = prefetchEpoch
        val s = size
        val l = level
        prefetching = true
        prefetchingEpoch = epoch
        background.execute {
            val puzzle = attempt(s, l)
            fx.execute {
                prefetching = false
                val wanted = installPrefetch && epoch == prefetchEpoch
                when {
                    wanted && puzzle != null -> {
                        installPrefetch = false
                        install(puzzle)
                    }

                    wanted -> {
                        generateNew()
                    }

                    puzzle != null && epoch == prefetchEpoch -> {
                        prefetched = puzzle
                    }

                    epoch != prefetchEpoch && game != null -> {
                        prefetch()
                    }
                }
            }
        }
    }
}
