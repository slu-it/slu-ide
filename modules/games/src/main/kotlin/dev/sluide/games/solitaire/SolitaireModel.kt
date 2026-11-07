package dev.sluide.games.solitaire

import dev.sluide.games.common.GameClock
import dev.sluide.games.solitaire.solver.Deal
import dev.sluide.games.solitaire.solver.SolveResult
import dev.sluide.games.solitaire.ui.TableHost
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Solitaire game of one window: dealing (on [dealing], the next deal of the mode in advance; see [Dealer]), the
 * hint search (on [hinting]), auto-finish paced by [schedule], the clock, the pause, the keyboard [cursor] and what
 * the view shows of them. Every state is confined to the FX thread, so no locks: all methods are called there,
 * background results come back through [fx] and are dropped when a newer request or any change of the table came in
 * between (the searches see only what they captured and a cancel flag). [onEvent] follows the [onChange] of the same
 * step.
 */
internal class SolitaireModel(
    private val preferences: SolitairePreferences,
    findDeal: (Mode, Int, () -> Boolean) -> Deal?,
    dealIfWinnable: (Mode, Int, () -> Boolean) -> Deal?,
    private val solve: (Table, Duration, () -> Boolean) -> SolveResult,
    dealing: Executor,
    private val hinting: Executor,
    private val fx: Executor,
    schedule: (Long, Runnable) -> Cancellable,
    private val clock: GameClock = GameClock(),
    randomNumber: () -> Int = { Random.nextInt(1, Deals.MAX_NUMBER + 1) },
    private val hintBudget: Duration = 2.seconds,
    autoFinishDelayMillis: Long = 90,
) : TableHost {
    /** Called after every change of what the toolbar, the status or the table show. */
    var onChange: () -> Unit = {}

    /** Called after [onChange] for what the table should animate. */
    var onEvent: (TableEvent) -> Unit = {}

    var mode: Mode = preferences.lastMode
        private set

    /** Null until the first deal is made; while a later deal is made the previous game stays. */
    var game: SolitaireGame? = null
        private set

    var isDealing = false
        private set

    /** The deal failed and there is no game to go on with. */
    var isFailed = false
        private set

    var isHinting = false
        private set

    var cursor = Cursor()
        private set

    private val dealer = Dealer(mode, findDeal, dealIfWinnable, dealing, fx, randomNumber)
    private val finisher = AutoFinisher(schedule, autoFinishDelayMillis, { isPaused }, ::autoMove)
    private val rulesByMode = mutableMapOf<Mode, Rules>()
    private var lookingFor: Int? = null
    private var message: String? = null
    private var result: TableEvent.Won? = null
    private var hintRequest = 0
    private var hintCancel: AtomicBoolean? = null

    val isWon: Boolean get() = game?.won == true

    val isAutoFinishing: Boolean get() = finisher.isRunning

    val canPause: Boolean get() = game != null && !isDealing && !isWon

    val isManuallyPaused: Boolean get() = canPause && clock.manuallyPaused

    val isPaused: Boolean get() = canPause && (clock.hidden || clock.manuallyPaused)

    /** The tab is not shown or the window is iconified (automatic pause). */
    var hidden: Boolean
        get() = clock.hidden
        set(value) {
            if (clock.hidden == value) return
            clock.hidden = value
            paused()
        }

    /** The table takes moves: a game, not dealing, not paused, not won, not auto-finishing. */
    val inputEnabled: Boolean get() = game != null && !isDealing && !isPaused && !isWon && !isAutoFinishing

    val canUndo: Boolean get() = inputEnabled && game?.canUndo == true

    val canRedo: Boolean get() = inputEnabled && game?.canRedo == true

    val canRestart: Boolean get() = inputEnabled && (game?.moves ?: 0) > 0

    val canHint: Boolean get() = inputEnabled && !isHinting

    val needsConfirmToAbandon: Boolean get() = game?.let { it.moves > 0 && !it.won } ?: false

    val score: Int get() = game?.score(clock.elapsedMillis) ?: 0

    val moves: Int get() = game?.moves ?: 0

    val elapsedMillis: Long get() = clock.elapsedMillis

    val statusText: String
        get() {
            val g = game
            val won = result
            val number = lookingFor
            val text = message
            return when {
                number != null -> StatusText.looking(number)
                isFailed -> StatusText.FAILED
                isDealing || g == null -> StatusText.DEALING
                text != null -> text
                g.won && won != null -> StatusText.won(won.score, clock.elapsedMillis, won.newHigh)
                else -> StatusText.playing(g.number, preferences.highScore(g.mode))
            }
        }

    override val rules: Rules get() = rulesOf(game?.mode ?: mode)

    /** The game's table; an empty one before the first deal. */
    override val table: Table get() = game?.table ?: Table(mode, emptyMap())

    /** Makes the first deal when there is no game and none is being made; the view calls it when shown. */
    fun ensureGame() {
        if (game == null && !isDealing) newGame()
    }

    /** A new deal of the mode, at once when one is ready; the view asks first when [needsConfirmToAbandon]. */
    fun newGame() {
        beginDealing(null)
        dealer.deal { deal -> if (deal != null) install(deal) else dealingEnded(StatusText.FAILED) }
    }

    fun chooseMode(mode: Mode) {
        if (mode == this.mode) return
        this.mode = mode
        preferences.saveMode(mode)
        dealer.mode = mode
        newGame()
    }

    /** The mode of [variant] with its last draw or suits option. */
    fun chooseVariant(variant: Variant) = chooseMode(preferences.modeFor(variant))

    /**
     * Plays deal [number] (1..[Deals.MAX_NUMBER]) if it is proven winnable; otherwise finds the next winnable one,
     * calls [ask] with (number, alternative) and plays the alternative if it returns true. The current game stays when
     * none is played; the deal fetched in advance stays too.
     */
    fun requestDeal(
        number: Int,
        ask: (Int, Int) -> Boolean,
    ) {
        require(number in 1..Deals.MAX_NUMBER) { "deal number must be 1..${Deals.MAX_NUMBER}: $number" }
        beginDealing(number)
        dealer.request(number) { exact, alternative ->
            when {
                exact != null -> {
                    install(exact)
                }

                alternative != null -> {
                    val generation = dealer.generation
                    val play = ask(number, alternative.number)
                    if (generation != dealer.generation) return@request
                    if (play) install(alternative) else dealingEnded(null)
                }

                else -> {
                    dealingEnded(StatusText.noDealNear(number))
                }
            }
        }
    }

    override fun play(move: Move): Boolean {
        if (!inputEnabled) return false
        val step = game?.move(move) ?: return false
        stepped()
        onEvent(TableEvent.Moved(step, false))
        afterStep()
        return true
    }

    /** Klondike draws or recycles, Spider deals a row (or says why not), FreeCell has no stock. */
    override fun stockClicked() {
        val g = game ?: return
        if (!inputEnabled) return
        when (g.mode.variant) {
            Variant.KLONDIKE -> {
                listOf(Move.Draw, Move.Recycle).firstOrNull(g::canMove)?.let(::play)
            }

            Variant.SPIDER -> {
                val t = g.table
                val blocked = t.ids(PileKind.TABLEAU).any { t.pile(it).cards.isEmpty() }
                if (!play(Move.DealRow) && blocked && t.ids(PileKind.STOCK).any { t.pile(it).cards.isNotEmpty() }) {
                    say(StatusText.FILL_COLUMNS)
                }
            }

            Variant.FREECELL -> {}
        }
    }

    fun undo() {
        if (!canUndo) return
        val step = game?.undo() ?: return
        stepped()
        onEvent(TableEvent.Undone(step))
    }

    fun redo() {
        if (!canRedo) return
        val step = game?.redo() ?: return
        stepped()
        onEvent(TableEvent.Redone(step))
        afterStep()
    }

    /** Back to the start of the deal, the clock running on; the view asks first. */
    fun restart() {
        if (!canRestart) return
        game?.restart()
        stepped()
        onEvent(TableEvent.Restarted)
    }

    /** Searches the next move of a winning line; see [HintSearch]. Any change of the table drops the search. */
    fun hint() {
        if (!canHint) return
        val g = game ?: return
        val mine = ++hintRequest
        val cancel = AtomicBoolean().also { hintCancel = it }
        val table = g.table
        val earlier = g.positions.dropLast(1)
        val budget = hintBudget
        val solve = solve
        isHinting = true
        say(StatusText.THINKING)
        hinting.execute {
            val outcome = HintSearch.search(table, earlier, budget, solve, cancel::get)
            fx.execute { if (mine == hintRequest) hinted(outcome) }
        }
    }

    /** Pauses or resumes by hand; only while a game is played. */
    fun togglePause() {
        if (!canPause) return
        clock.manuallyPaused = !clock.manuallyPaused
        paused()
    }

    /** After a change of the pause: a hint search is dropped when paused, a waiting auto-finish goes on when not. */
    private fun paused() {
        if (isPaused) cancelHint() else finisher.resume()
        onChange()
    }

    /** A key on the table. [KeyAction.NewGame] is left to the view, which asks first when [needsConfirmToAbandon]. */
    fun key(action: KeyAction) {
        when (action) {
            KeyAction.Undo -> undo()
            KeyAction.Redo -> redo()
            KeyAction.Hint -> hint()
            KeyAction.Pause -> togglePause()
            else -> if (inputEnabled) keyCommand(action, cursor, rules, table)?.let(::perform)
        }
    }

    private fun perform(command: KeyCommand) {
        when (command) {
            is KeyCommand.Point -> moveCursor(command.cursor)
            is KeyCommand.Play -> play(command.move)
            is KeyCommand.Drop -> if (!play(command.move)) say(StatusText.CANT_MOVE)
            KeyCommand.Stock -> stockClicked()
        }
    }

    private fun moveCursor(next: Cursor) {
        if (next == cursor) return
        cursor = next
        onChange()
    }

    private fun say(text: String) {
        message = text
        onChange()
    }

    private fun rulesOf(mode: Mode): Rules = rulesByMode.getOrPut(mode) { Rules.of(mode) }

    /** After a move, undo, redo or restart: drops the hint search, the message and the pick. */
    private fun stepped() {
        cancelHint()
        message = null
        cursor = cursor.copy(picked = null).clamped(rules, table)
        onChange()
    }

    /** Ends the game when won, else starts auto-finish when decided. */
    private fun afterStep() {
        val g = game ?: return
        if (g.won) finish(g) else autoFinish(g)
    }

    /** Plays out a decided game. */
    private fun autoFinish(g: SolitaireGame) {
        if (!g.decided || finisher.isRunning) return
        finisher.start(g.autoFinishMoves())
        if (finisher.isRunning) onChange()
    }

    private fun autoMove(move: Move) {
        val g = game
        val step = g?.move(move)
        if (g == null || step == null) {
            finisher.stop()
            onChange()
            return
        }
        cursor = cursor.clamped(rules, table)
        onChange()
        onEvent(TableEvent.Moved(step, true))
        if (g.won) finish(g)
    }

    private fun finish(g: SolitaireGame) {
        finisher.stop()
        clock.stop()
        val points = g.score(clock.elapsedMillis)
        val won = TableEvent.Won(points, preferences.recordScore(g.mode, points))
        result = won
        message = null
        onChange()
        onEvent(won)
    }

    private fun beginDealing(number: Int?) {
        isDealing = true
        isFailed = false
        lookingFor = number
        cancelHint()
        message = null
        finisher.stop()
        // The game on the table waits for the deal (or the answer to Deal Number…): its time does not count.
        clock.held = true
        cursor = cursor.copy(picked = null)
        onChange()
    }

    /** No deal was played: the current game goes on with [text] (if any), or the failure shows when there is none. */
    private fun dealingEnded(text: String?) {
        isDealing = false
        lookingFor = null
        isFailed = game == null
        message = text?.takeIf { game != null }
        clock.held = false
        onChange()
        game?.let(::autoFinish)
    }

    private fun install(deal: Deal) {
        val g = SolitaireGame(deal.mode, deal.number, deal.table, rulesOf(deal.mode))
        game = g
        isDealing = false
        isFailed = false
        lookingFor = null
        message = null
        result = null
        cursor = Cursor()
        clock.reset()
        clock.start()
        onChange()
        onEvent(TableEvent.Dealt(g))
        dealer.prefetch()
    }

    private fun hinted(outcome: HintOutcome) {
        isHinting = false
        hintCancel = null
        when (outcome) {
            is HintOutcome.Next -> {
                game?.hintUsed()
                message = null
                onChange()
                onEvent(TableEvent.Hint(outcome.move))
            }

            is HintOutcome.UndoBack -> {
                say(StatusText.undoBack(outcome.steps))
            }

            HintOutcome.None -> {
                say(StatusText.NO_HINT)
            }
        }
    }

    private fun cancelHint() {
        if (!isHinting) return
        hintRequest++
        hintCancel?.set(true)
        hintCancel = null
        isHinting = false
        if (message == StatusText.THINKING) message = null
    }
}
