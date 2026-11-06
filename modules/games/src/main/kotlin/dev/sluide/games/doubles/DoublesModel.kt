package dev.sluide.games.doubles

import kotlin.random.Random

/**
 * The Doubles game of one window and what the view shows of it. Outlives the tab (it is owned by the window bean);
 * the view that shows it owns [onChange] and [onMoved] until it is disposed. FX thread, no JavaFX imports.
 */
internal class DoublesModel(
    private val preferences: DoublesPreferences,
    private val random: Random = Random.Default,
    private val gameFactory: (BoardSize, Random) -> DoublesGame = { size, random -> DoublesGame(size, random) },
) {
    /** Called after a new game, a size change and an undo. */
    var onChange: () -> Unit = {}

    /** Called after a successful move, instead of [onChange]. */
    var onMoved: (MoveResult) -> Unit = {}

    private var chosen: BoardSize? = null
    private var current: DoublesGame? = null

    val size: BoardSize get() = chosen ?: preferences.lastSize.also { chosen = it }

    private val game: DoublesGame get() = current ?: gameFactory(size, random).also { current = it }

    val tiles: List<Tile> get() = game.tiles

    val score: Int get() = game.score

    val best: Int get() = preferences.best(size)

    val canUndo: Boolean get() = game.canUndo

    val isOver: Boolean get() = game.isOver

    val statusText: String
        get() =
            when {
                game.isOver && game.canUndo -> "No moves left: U to undo, F2 for a new game"
                game.isOver -> "No moves left: F2 for a new game"
                game.goalReached -> "${size.goal} reached! Keep going, or F2 for a new game"
                else -> "Reach ${size.goal}"
            }

    /** A running game with a score is worth a question before it is abandoned. */
    val needsConfirmToAbandon: Boolean get() = !isOver && score > 0

    fun move(direction: Direction) {
        val result = game.move(direction) ?: return
        // Recorded after every move, so that a score taken back by undo stays the best.
        preferences.recordScore(size, game.score)
        onMoved(result)
    }

    fun undo() {
        if (game.undo()) onChange()
    }

    /** Always starts over; the view asks for confirmation first when [needsConfirmToAbandon]. */
    fun newGame() {
        current = gameFactory(size, random)
        onChange()
    }

    fun chooseSize(size: BoardSize) {
        if (size == this.size) return
        preferences.saveSize(size)
        chosen = size
        current = gameFactory(size, random)
        onChange()
    }
}
