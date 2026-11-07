package dev.sluide.games.solitaire

/** One step of the history, for animating it forward or back. */
internal data class Step(
    val move: Move,
    val before: Table,
    val outcome: Outcome,
)

/**
 * One Solitaire game: the table, its history with undo and redo, hints, restart, the score and auto-finish. Pure: the
 * play time is passed to [score].
 *
 * Score = [Scoring.floor] of: [Scoring.base] + the points of the moves in the current history (undo removes a step's
 * points, redo adds them again) - 1 per undo - 20 per hint + [Scoring.timePoints] + (won ? [Scoring.timeBonus] : 0).
 * Undo and hint penalties survive [restart]; move points do not, as restart clears the history. Auto-finish moves are
 * normal moves with points and count.
 */
internal class SolitaireGame(
    val mode: Mode,
    val number: Int,
    val start: Table,
    private val rules: Rules = Rules.of(mode),
) {
    init {
        require(start.mode == mode) { "table of ${start.mode} for $mode" }
    }

    private val history = mutableListOf<Step>()
    private var index = 0
    private var undos = 0

    var table: Table = start
        private set

    /** Every move; undo and redo count 1 each, auto-finish moves too. */
    var moves: Int = 0
        private set

    var hints: Int = 0
        private set

    val won: Boolean get() = rules.isWon(table)

    val decided: Boolean get() = !won && rules.isDecided(table)

    val canUndo: Boolean get() = index > 0 && !won

    val canRedo: Boolean get() = index < history.size && !won

    /** The positions from the start to now (start first, current last); for re-solving earlier positions. */
    val positions: List<Table> get() = listOf(start) + history.subList(0, index).map { it.outcome.table }

    fun canMove(move: Move): Boolean = !won && rules.canMove(table, move)

    /** Plays [move], clearing the redo steps; null when illegal. */
    fun move(move: Move): Step? {
        if (!canMove(move)) return null
        val step = Step(move, table, rules.apply(table, move))
        history.subList(index, history.size).clear()
        history += step
        index++
        table = step.outcome.table
        moves++
        return step
    }

    /** Takes back the last step (-1 point); the result's `before` is the new table. */
    fun undo(): Step? {
        if (!canUndo) return null
        index--
        undos++
        moves++
        val step = history[index]
        table = step.before
        return step
    }

    /** Plays the undone step again, with its points. */
    fun redo(): Step? {
        if (!canRedo) return null
        val step = history[index]
        index++
        moves++
        table = step.outcome.table
        return step
    }

    /** Back to the start with history, move points and moves cleared; undo and hint penalties are kept. */
    fun restart() {
        history.clear()
        index = 0
        moves = 0
        table = start
    }

    /** Counts a hint and costs 20 points; nothing once the game is won. */
    fun hintUsed() {
        if (won) return
        hints++
    }

    /**
     * The foundation moves that finish a decided game, in order (each legal after the previous); empty when not
     * decided or when they do not reach the win. Moves out of a foundation are never used.
     */
    fun autoFinishMoves(): List<Move> {
        if (!decided) return emptyList()
        val result = mutableListOf<Move>()
        var current = table
        val limit = current.piles.values.sumOf { it.cards.size }
        while (!rules.isWon(current)) {
            if (result.size >= limit) return emptyList()
            val next =
                rules
                    .legalMoves(current)
                    .firstOrNull {
                        it is Move.Transfer && it.to.kind == PileKind.FOUNDATION && it.from.kind != PileKind.FOUNDATION
                    }
                    ?: return emptyList()
            result += next
            current = rules.apply(current, next).table
        }
        return result
    }

    /** The score at [elapsedMillis] of play time. */
    fun score(elapsedMillis: Long): Int {
        val points = history.subList(0, index).sumOf { it.outcome.points }
        val raw =
            Scoring.base(mode) + points - undos - HINT_PENALTY * hints +
                Scoring.timePoints(mode, elapsedMillis) +
                if (won) Scoring.timeBonus(mode, elapsedMillis) else 0
        return Scoring.floor(mode, raw)
    }

    private companion object {
        const val HINT_PENALTY = 20
    }
}
