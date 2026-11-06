package dev.sluide.games.minesweeper

import java.util.Locale
import kotlin.random.Random

/**
 * The game of one window: the board, the clock, the size choice and the cursor, and what the view shows of them.
 * Outlives the tab (it is owned by [dev.sluide.games.Minesweeper]); the view that shows it owns [onChange] until it
 * is disposed.
 * FX thread. [gameFactory] makes the game of a new board (a test seam for fixed mines).
 */
internal class MinesweeperModel(
    private val preferences: MinesweeperPreferences,
    random: Random = Random.Default,
    val clock: GameClock = GameClock(),
    private val gameFactory: (BoardSize) -> MinesweeperGame = { MinesweeperGame(it, random) },
) {
    /** Called after every change of what the view shows. */
    var onChange: () -> Unit = {}

    var choice: SizeChoice = preferences.lastSize
        private set

    var game: MinesweeperGame = gameFactory(choice.size)
        private set

    var cursorX = choice.size.width / 2
        private set

    var cursorY = choice.size.height / 2
        private set

    private var newBest = false

    /** The tab is not shown (automatic pause). */
    var hidden: Boolean
        get() = clock.hidden
        set(value) {
            if (clock.hidden == value) return
            clock.hidden = value
            onChange()
        }

    val canPause: Boolean get() = game.state == GameState.RUNNING

    /** The player paused the game with the button or P. */
    val isManuallyPaused: Boolean get() = canPause && clock.manuallyPaused

    /** The board is covered: paused by the player or because the tab is not shown. */
    val isPaused: Boolean get() = canPause && (clock.hidden || clock.manuallyPaused)

    val isTicking: Boolean get() = clock.isTicking

    val counterText: String get() = game.minesLeft.toString()

    val timerText: String get() = (clock.elapsedMillis / 1000).toString()

    /** The size menu's text: the preset name or "Custom 20×12/40". */
    val sizeLabel: String
        get() =
            when (val c = choice) {
                is SizeChoice.OfPreset -> c.preset.label
                is SizeChoice.Custom -> "Custom ${c.size.width}×${c.size.height}/${c.size.mines}"
            }

    /** The values the Custom dialog starts with. */
    val lastCustom: BoardSize get() = preferences.lastCustom

    val statusText: String
        get() =
            when {
                game.state == GameState.LOST -> {
                    "Boom — try again (F2)"
                }

                game.state == GameState.WON -> {
                    "Cleared in ${seconds(clock.elapsedMillis)}" + if (newBest) " — new best!" else ""
                }

                else -> {
                    (choice as? SizeChoice.OfPreset)
                        ?.let { preferences.bestTime(it.preset) }
                        ?.let { "Best: ${seconds(it)}" }
                        .orEmpty()
                }
            }

    /** Starts a fresh game of [choice] and remembers the choice. */
    fun newGame(choice: SizeChoice = this.choice) {
        this.choice = choice
        game = gameFactory(choice.size)
        clock.reset()
        newBest = false
        cursorX = choice.size.width / 2
        cursorY = choice.size.height / 2
        preferences.saveSize(choice)
        onChange()
    }

    fun reveal(
        x: Int,
        y: Int,
    ) = act { it.reveal(x, y) }

    fun toggleFlag(
        x: Int,
        y: Int,
    ) = act { it.toggleFlag(x, y) }

    fun chord(
        x: Int,
        y: Int,
    ) = act { it.chord(x, y) }

    /** Pauses or resumes by hand; only while the game runs. */
    fun togglePause() {
        if (!canPause) return
        clock.manuallyPaused = !clock.manuallyPaused
        onChange()
    }

    fun moveCursor(
        dx: Int,
        dy: Int,
    ) {
        cursorX = (cursorX + dx).coerceIn(0, game.size.width - 1)
        cursorY = (cursorY + dy).coerceIn(0, game.size.height - 1)
        onChange()
    }

    fun perform(action: KeyAction) {
        when (action) {
            is KeyAction.Move -> moveCursor(action.dx, action.dy)
            KeyAction.Reveal -> reveal(cursorX, cursorY)
            KeyAction.Flag -> toggleFlag(cursorX, cursorY)
            KeyAction.NewGame -> newGame()
            KeyAction.Pause -> togglePause()
        }
    }

    private fun act(action: (MinesweeperGame) -> Boolean) {
        if (isPaused) return
        if (!action(game)) return
        when (game.state) {
            GameState.RUNNING -> {
                clock.start()
            }

            GameState.WON -> {
                clock.start()
                clock.stop()
                (choice as? SizeChoice.OfPreset)?.let {
                    newBest =
                        preferences.recordWin(
                            it.preset,
                            clock.elapsedMillis,
                        )
                }
            }

            GameState.LOST -> {
                clock.start()
                clock.stop()
            }

            GameState.NOT_STARTED -> {}
        }
        onChange()
    }

    private fun seconds(millis: Long) = String.format(Locale.ROOT, "%.1f s", millis / 1000.0)
}
