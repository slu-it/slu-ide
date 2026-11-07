package dev.sluide.games.solitaire

import dev.sluide.games.common.GameClock
import dev.sluide.games.solitaire.solver.Deal
import dev.sluide.games.solitaire.solver.SolveResult
import dev.sluide.games.solitaire.ui.emptyTable
import dev.sluide.games.solitaire.ui.tableau
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.fail
import kotlin.time.Duration

/** The fakes [SolitaireModel] runs on (deal search, solver, executors, schedule, clock) and helpers to drive it. */
internal abstract class SolitaireModelFixture {
    @TempDir
    lateinit var dir: Path

    protected lateinit var preferences: SolitairePreferences

    protected var now = 0L
    protected var nextNumber = 100
    protected val dealQueue = ArrayDeque<Runnable>()
    protected val hintQueue = ArrayDeque<Runnable>()
    protected val scheduled = ArrayDeque<Scheduled>()
    protected val searches = mutableListOf<Search>()
    protected val solves = mutableListOf<Solve>()

    /** The table of a deal; by default the real one. */
    protected var tables: (Mode, Int) -> Table = { mode, number -> Rules.of(mode).deal(Deals.deck(mode, number)) }
    protected var findable: (Int) -> Boolean = { true }
    protected var failure: Throwable? = null
    protected var winnable: (Int) -> Boolean = { true }
    protected var solveResult: (Table) -> SolveResult = { SolveResult.Unknown }

    protected val events = mutableListOf<TableEvent>()
    protected var changes = 0

    protected class Scheduled(
        val delay: Long,
        val task: Runnable,
    ) {
        var cancelled = false
    }

    protected data class Search(
        val mode: Mode,
        val number: Int,
        val cancelled: () -> Boolean,
    )

    protected data class Solve(
        val table: Table,
        val budget: Duration,
        val cancelled: Boolean,
    )

    @BeforeEach
    fun setUp() {
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList())
        preferences = SolitairePreferences(store, java.time.Duration.ZERO) { it.run() }
    }

    private fun deal(
        mode: Mode,
        number: Int,
    ) = Deal(mode, number, tables(mode, number), emptyList())

    private fun find(
        mode: Mode,
        number: Int,
        cancelled: () -> Boolean,
    ): Deal? {
        searches += Search(mode, number, cancelled)
        failure?.let { throw it }
        return if (findable(number)) deal(mode, number) else null
    }

    private fun ifWinnable(
        mode: Mode,
        number: Int,
        @Suppress("UNUSED_PARAMETER") cancelled: () -> Boolean,
    ): Deal? = if (winnable(number)) deal(mode, number) else null

    private fun solve(
        table: Table,
        budget: Duration,
        cancelled: () -> Boolean,
    ): SolveResult {
        solves += Solve(table, budget, cancelled())
        return solveResult(table)
    }

    private fun schedule(
        delay: Long,
        task: Runnable,
    ): Cancellable {
        val entry = Scheduled(delay, task)
        scheduled += entry
        return Cancellable { entry.cancelled = true }
    }

    protected fun model(mode: Mode = Mode.KLONDIKE_DRAW_1): SolitaireModel {
        preferences.saveMode(mode)
        return SolitaireModel(
            preferences,
            ::find,
            ::ifWinnable,
            ::solve,
            { dealQueue.addLast(it) },
            { hintQueue.addLast(it) },
            Executor { it.run() },
            ::schedule,
            GameClock { now },
            { nextNumber.also { nextNumber += 100 } },
        ).also {
            it.onChange = { changes++ }
            it.onEvent = { event -> events += event }
        }
    }

    /** A model with deal #100 on the table and deal #200 fetched in advance. */
    protected fun readyModel(mode: Mode = Mode.KLONDIKE_DRAW_1): SolitaireModel =
        model(mode).also {
            it.ensureGame()
            runDealing()
            events.clear()
        }

    /** Runs the queued deal searches, failing instead of hanging when they keep queueing new ones. */
    protected fun runDealing() {
        repeat(100) {
            if (dealQueue.isEmpty()) return
            dealQueue.removeFirst().run()
        }
        fail("deal searches keep queueing: ${dealQueue.size} left after 100")
    }

    protected fun runHint() = hintQueue.removeFirst().run()

    /** Runs the first scheduled task that was not cancelled. */
    protected fun runScheduled() {
        while (scheduled.first().cancelled) scheduled.removeFirst()
        scheduled.removeFirst().task.run()
    }

    protected fun movedMoves() = events.filterIsInstance<TableEvent.Moved>().map { it.step.move to it.auto }

    protected fun up(codes: String) = Pile.allFaceUp(cards(codes))

    private fun suitUpTo(
        suit: Char,
        rank: Int,
    ) = up(
        (1..rank).joinToString(" ") {
            listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")[it - 1] +
                suit
        },
    )

    protected val stock = PileId(PileKind.STOCK)
    protected val waste = PileId(PileKind.WASTE)

    protected fun foundation(index: Int) = PileId(PileKind.FOUNDATION, index)

    /** Klondike: all but the spades J, Q, K on the foundations; the Jack on the waste, the King and Queen in columns. */
    protected val nearlyDecided =
        emptyTable(
            Mode.KLONDIKE_DRAW_1,
            mapOf(
                foundation(0) to suitUpTo('C', 13),
                foundation(1) to suitUpTo('D', 13),
                foundation(2) to suitUpTo('H', 13),
                foundation(3) to suitUpTo('S', 10),
                waste to up("JS"),
                tableau(0) to up("KS"),
                tableau(1) to up("QS"),
            ),
            fan = 1,
        )
    protected val jackUp = Move.Transfer(waste, foundation(3))
}
