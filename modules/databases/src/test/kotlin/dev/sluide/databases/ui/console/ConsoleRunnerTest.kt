package dev.sluide.databases.ui.console

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.session.ColumnInfo
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.sql.RunChoice
import dev.sluide.databases.sql.SqlStatement
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.result.ExecutingSession
import dev.sluide.databases.ui.result.FakeCursor
import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The run sequence of a console with a fake session, editor and result target; no toolkit. */
class ConsoleRunnerTest {
    private val config = ConnectionConfig("c1", "c", DatabaseKind.POSTGRESQL)
    private val fake = ExecutingSession(config)
    private val entry = ConnectionEntry(config, "key").apply { connected(fake) }
    private val history = QueryHistory()
    private val shown = mutableListOf<Pair<String, Boolean>>()
    private val logged = mutableListOf<Pair<String, Boolean>>()
    private val marks = mutableListOf<Int?>()
    private val offered = mutableListOf<RunChoice.Choose>()
    private var pick: ((RunChoice.Choose, (List<SqlStatement>) -> Unit) -> Unit)? = null
    private var onShow: () -> Unit = {}

    /** What escaped from a runnable handed to fx: on the FX thread it would reach the uncaught handler. */
    private val escaped = mutableListOf<Throwable>()
    private val fx: (Runnable) -> Unit = {
        try {
            it.run()
        } catch (e: RuntimeException) {
            escaped += e
        }
    }

    @AfterEach
    fun nothingEscaped() {
        assertEquals(emptyList(), escaped)
    }

    private val editor =
        object : RunEditor {
            override var text = ""
            override var selectionStart = 0
            override var selectionEnd = 0
            override var caret = 0

            override fun markError(offset: Int?) {
                marks += offset
            }
        }

    private val runner =
        ConsoleRunner(
            entry,
            editor,
            object : RunTarget {
                override fun show(
                    outcome: StatementOutcome,
                    replaceUnpinned: Boolean,
                ) {
                    shown += outcome.sql to replaceUnpinned
                    onShow()
                }

                override fun log(
                    text: String,
                    error: Boolean,
                ) {
                    logged += text to error
                }
            },
            history,
            { 50 },
            fx,
            { choice, then ->
                offered += choice
                pick?.invoke(choice, then)
            },
        )

    private fun failed(
        sql: String,
        position: Int? = null,
    ) = StatementOutcome.Failed(sql, 1, "boom", "42601", position)

    private fun selectAll() {
        editor.selectionEnd = editor.text.length
    }

    @Test
    fun `a single statement runs with the page size and shows its outcome`() {
        editor.text = "select 1"
        runner.run()
        assertEquals(listOf("select 1" to 50), fake.executed)
        assertEquals(listOf("select 1" to true), shown)
    }

    @Test
    fun `a selection of several statements runs all in order, replacing unpinned only for the first`() {
        editor.text = "select 1; select 2; select 3"
        selectAll()
        runner.run()
        assertEquals(listOf("select 1", "select 2", "select 3"), fake.executed.map { it.first })
        assertEquals(listOf(true, false, false), shown.map { it.second })
    }

    @Test
    fun `each statement waits for the previous one`() {
        editor.text = "select 1; select 2"
        selectAll()
        fake.defer = true
        runner.run()
        assertEquals(1, fake.executed.size)
        fake.deferred[0].complete(StatementOutcome.Updated("select 1", 1, 0))
        assertEquals(2, fake.executed.size)
    }

    @Test
    fun `a failure stops the run and says how many statements were not run`() {
        editor.text = "a; b; c"
        selectAll()
        fake.answer = { if (it == "a") failed(it) else StatementOutcome.Updated(it, 1, 0) }
        runner.run()
        assertEquals(listOf("a"), fake.executed.map { it.first })
        assertEquals(listOf("Stopped: 2 statements not run" to false), logged)
    }

    @Test
    fun `a cancelled statement stops the run too`() {
        editor.text = "a; b"
        selectAll()
        fake.answer = { StatementOutcome.Cancelled(it, 1) }
        runner.run()
        assertEquals(listOf("Stopped: 1 statement not run" to false), logged)
    }

    @Test
    fun `no message when the failing statement was the last`() {
        editor.text = "a; b"
        selectAll()
        fake.answer = { if (it == "b") failed(it) else StatementOutcome.Updated(it, 1, 0) }
        runner.run()
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `without a selection and several statements the chooser decides`() {
        editor.text = "select 1;\nselect 2"
        editor.caret = 12
        pick = { choice, then -> then(listOf(choice.atCaret)) }
        runner.run()
        assertEquals("select 2", offered.single().atCaret.text)
        assertEquals(listOf("select 2"), fake.executed.map { it.first })
    }

    @Test
    fun `a dismissed chooser runs nothing`() {
        editor.text = "select 1; select 2"
        runner.run()
        assertEquals(1, offered.size)
        assertTrue(fake.executed.isEmpty())
    }

    @Test
    fun `history gets the statements that started only`() {
        editor.text = "a; b; c"
        selectAll()
        fake.answer = { if (it == "b") failed(it) else StatementOutcome.Updated(it, 1, 0) }
        runner.run()
        assertEquals(listOf("b", "a"), history.entries("key"))
    }

    @Test
    fun `an error position marks the editor at the statement's offset`() {
        editor.text = "select 1;\nselect x from t"
        editor.caret = 15
        pick = { choice, then -> then(listOf(choice.atCaret)) }
        fake.answer = { failed(it, position = 7) }
        runner.run()
        assertEquals(listOf(null, 10 + 7), marks)
    }

    @Test
    fun `no mark when the text changed since the run started`() {
        editor.text = "select x"
        fake.defer = true
        runner.run()
        editor.text = "select y"
        fake.deferred[0].complete(failed("select x", position = 7))
        assertEquals(listOf<Int?>(null), marks)
    }

    @Test
    fun `without a session it logs Not connected`() {
        entry.ended(null)
        editor.text = "select 1"
        runner.run()
        assertEquals(listOf("Not connected" to true), logged)
        assertTrue(fake.executed.isEmpty())
    }

    @Test
    fun `cancel goes to the session`() {
        runner.cancel()
        assertEquals(listOf("cancel"), fake.calls)
    }

    @Test
    fun `stop sends no further statement and cancels the one in flight`() {
        editor.text = "a; b; c"
        selectAll()
        fake.defer = true
        runner.run()
        runner.stop()
        assertEquals(listOf("cancel"), fake.calls)
        val columns = listOf(ColumnInfo("id", "id", "int4", 4, null, null, false, false))
        val cursor = FakeCursor(columns, listOf(listOf<Any?>(1)))
        fake.deferred[0].complete(StatementOutcome.Rows("a", 1, columns, cursor, cursor.firstPage()))
        assertEquals(1, fake.executed.size)
        assertTrue(shown.isEmpty(), "the answer of a stopped run is ignored")
        assertTrue(cursor.closed, "and its cursor is closed")
        assertFalse(runner.running)
    }

    @Test
    fun `the gate is released when showing an outcome throws`() {
        val gate = RunGate()
        val throwing =
            object : RunTarget {
                override fun show(
                    outcome: StatementOutcome,
                    replaceUnpinned: Boolean,
                ): Unit = error("boom")

                override fun log(
                    text: String,
                    error: Boolean,
                ) {
                    logged += text to error
                }
            }
        val broken = ConsoleRunner(entry, editor, throwing, history, { 50 }, { it.run() }, { _, _ -> }, gate)
        editor.text = "select 1"
        broken.run()
        assertFalse(gate.busy.get())
        assertFalse(broken.running)
        assertEquals(listOf("boom" to true), logged)
    }

    @Test
    fun `stop without a run does nothing`() {
        runner.stop()
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `a second run while one is under way does not execute`() {
        editor.text = "select 1"
        fake.defer = true
        runner.run()
        runner.run()
        assertEquals(1, fake.executed.size)
    }

    @Test
    fun `a run of another console with the same gate waits`() {
        val gate = RunGate()
        val first = ConsoleRunner(entry, editor, nullTarget(), history, { 50 }, { it.run() }, { _, _ -> }, gate)
        val second = ConsoleRunner(entry, editor, nullTarget(), history, { 50 }, { it.run() }, { _, _ -> }, gate)
        editor.text = "select 1"
        fake.defer = true
        first.run()
        second.run()
        assertEquals(1, fake.executed.size)
    }

    @Test
    fun `a run waits while the entry says a statement runs`() {
        editor.text = "select 1"
        entry.setRunning(true)
        runner.run()
        assertTrue(fake.executed.isEmpty())
    }

    @Test
    fun `a failed future (disconnect) logs the error, the statements not run, and allows the next run`() {
        editor.text = "a; b; c"
        selectAll()
        fake.defer = true
        runner.run()
        fake.deferred[0].completeExceptionally(java.sql.SQLException("closed"))
        assertEquals(listOf("closed" to true, "Stopped: 2 statements not run" to false), logged)
        assertFalse(runner.running)
        fake.defer = false
        runner.run()
        assertEquals(4, fake.executed.size)
    }

    @Test
    fun `the session ending between statements stops the sequence`() {
        editor.text = "a; b"
        selectAll()
        fake.defer = true
        runner.run()
        entry.ended(null)
        fake.deferred[0].complete(StatementOutcome.Updated("a", 1, 0))
        assertEquals(1, fake.executed.size)
        assertEquals(listOf("Not connected" to true, "Stopped: 1 statement not run" to false), logged)
    }

    private fun runnerOn(gate: RunGate) =
        ConsoleRunner(entry, editor, nullTarget(), history, { 50 }, { it.run() }, { _, _ -> }, gate)

    private fun assertRunUntouched() {
        marks.clear()
        editor.text = "a; b"
        editor.selectionEnd = 0
        runner.run()
        assertEquals(emptyList(), marks, "the editor's error mark is left alone")
        assertEquals(emptyList(), offered, "no chooser is offered")
    }

    @Test
    fun `running an empty console does nothing`() {
        editor.text = "  "
        runner.run()
        assertEquals(emptyList(), fake.executed)
        assertEquals(emptyList(), logged)
        assertFalse(runner.running)
    }

    @Test
    fun `a run that waits for the same console touches neither editor nor chooser`() {
        editor.text = "x"
        fake.defer = true
        runner.run()
        assertRunUntouched()
    }

    @Test
    fun `a run that waits for the gate touches neither editor nor chooser`() {
        val gate = RunGate()
        val mine =
            ConsoleRunner(entry, editor, nullTarget(), history, { 50 }, { it.run() }, { c, _ -> offered += c }, gate)
        editor.text = "x"
        fake.defer = true
        runnerOn(gate).run()
        marks.clear()
        editor.text = "a; b"
        mine.run()
        assertEquals(emptyList(), marks)
        assertEquals(emptyList(), offered)
    }

    @Test
    fun `a run that waits for the entry touches neither editor nor chooser`() {
        entry.setRunning(true)
        assertRunUntouched()
    }

    @Test
    fun `a run without a session touches neither editor nor chooser`() {
        entry.ended(null)
        assertRunUntouched()
        assertEquals(listOf("Not connected" to true), logged)
    }

    @Test
    fun `a selection that starts in the second statement runs only that one`() {
        editor.text = "select 1; select 2"
        editor.selectionStart = 10
        editor.selectionEnd = editor.text.length
        runner.run()
        assertEquals(listOf("select 2"), fake.executed.map { it.first })
    }

    @Test
    fun `cancel without a session does nothing`() {
        entry.ended(null)
        runner.cancel()
        assertEquals(emptyList(), fake.calls)
    }

    @Test
    fun `stop of a console that does not run leaves the gate of another one shut`() {
        val gate = RunGate()
        editor.text = "x"
        fake.defer = true
        runnerOn(gate).run()
        runnerOn(gate).stop()
        assertTrue(gate.busy.get())
    }

    @Test
    fun `stop opens the gate and ends the run`() {
        val gate = RunGate()
        val mine = runnerOn(gate)
        editor.text = "x"
        fake.defer = true
        mine.run()
        assertTrue(gate.busy.get())
        mine.stop()
        assertFalse(gate.busy.get())
        assertFalse(mine.running)
    }

    @Test
    fun `the answer of a stopped run stays ignored after the next run started`() {
        editor.text = "a"
        fake.defer = true
        runner.run()
        runner.stop()
        runner.run()
        fake.deferred[0].complete(StatementOutcome.Updated("a", 1, 0))
        assertEquals(emptyList(), shown, "the stopped run's answer is not its successor's")
        assertTrue(runner.running)
        fake.deferred[1].complete(StatementOutcome.Updated("a", 1, 0))
        assertEquals(1, shown.size)
        assertFalse(runner.running)
    }

    @Test
    fun `a stop from inside show sends no cancel`() {
        // The app cannot reach this (stop() is only called when the tab closes, and show has no nested event loop);
        // it is the test that kills the inFlight guard of stop().
        editor.text = "a; b"
        selectAll()
        onShow = { runner.stop() }
        runner.run()
        assertEquals(emptyList(), fake.calls)
    }

    @Test
    fun `stop with the session gone does not throw`() {
        editor.text = "a"
        fake.defer = true
        runner.run()
        entry.ended(null)
        runner.stop()
        assertEquals(emptyList(), fake.calls)
        assertFalse(runner.running)
    }

    @Test
    fun `a stopped run ignores failures and plain outcomes and a live one keeps its cursor`() {
        editor.text = "a; b; c"
        selectAll()
        fake.defer = true
        runner.run()
        runner.stop()
        fake.deferred[0].complete(StatementOutcome.Updated("a", 1, 0))
        runner.run()
        runner.stop()
        fake.deferred[1].completeExceptionally(java.sql.SQLException("closed"))
        assertEquals(emptyList(), shown)
        assertEquals(emptyList(), logged)

        val columns = listOf(ColumnInfo("id", "id", "int4", 4, null, null, false, false))
        val cursor = FakeCursor(columns, listOf(listOf<Any?>(1)))
        fake.defer = false
        fake.answer = { StatementOutcome.Rows(it, 1, columns, cursor, cursor.firstPage()) }
        runner.run()
        assertEquals(3, shown.size)
        assertFalse(cursor.closed)
    }

    @Test
    fun `a future without outcome or error says the statement was not run`() {
        editor.text = "a; b"
        selectAll()
        fake.defer = true
        runner.run()
        fake.deferred[0].complete(null)
        assertEquals(listOf("Statement not run" to true, "Stopped: 1 statement not run" to false), logged)
        assertFalse(runner.running)
    }

    @Test
    fun `a finished sequence logs nothing and frees the console and the gate`() {
        editor.text = "a; b; c"
        selectAll()
        runner.run()
        assertEquals(listOf("a", "b", "c"), fake.executed.map { it.first })
        assertFalse(runner.running)
        assertEquals(emptyList(), logged)
    }

    @Test
    fun `a failure in the log of an outcome that cannot be shown does not escape`() {
        val gate = RunGate()
        val broken =
            object : RunTarget {
                override fun show(
                    outcome: StatementOutcome,
                    replaceUnpinned: Boolean,
                ): Unit = error("boom")

                override fun log(
                    text: String,
                    error: Boolean,
                ): Unit = error("log broken")
            }
        val mine = ConsoleRunner(entry, editor, broken, history, { 50 }, fx, { _, _ -> }, gate)
        editor.text = "select 1"
        mine.run()
        assertFalse(gate.busy.get())
        assertFalse(mine.running)
    }

    @Test
    fun `a session that throws on execute logs it and the statements not run`() {
        editor.text = "a; b; c"
        selectAll()
        fake.answer = { error("session broken") }
        runner.run()
        assertEquals(listOf("session broken" to true, "Stopped: 2 statements not run" to false), logged)
        assertFalse(runner.running)
        assertEquals(emptyList(), history.entries("key"), "execute threw before the first statement was recorded")
    }

    @Test
    fun `a chooser that picks nothing runs nothing and frees the console`() {
        editor.text = "a; b"
        pick = { _, then -> then(emptyList()) }
        runner.run()
        assertEquals(emptyList(), fake.executed)
        assertFalse(runner.running)
        assertEquals(emptyList(), logged)
    }

    @Test
    fun `a chooser answered late does not start while the console runs`() {
        var late: (List<SqlStatement>) -> Unit = {}
        pick = { _, then -> late = then }
        editor.text = "a; b"
        runner.run()
        editor.text = "x"
        fake.defer = true
        runner.run()
        late(listOf(SqlStatement("y", 0, 1)))
        assertEquals(listOf("x"), fake.executed.map { it.first })
    }

    @Test
    fun `a chooser answered late does not start while another console holds the gate`() {
        val gate = RunGate()
        var late: (List<SqlStatement>) -> Unit = {}
        val mine =
            ConsoleRunner(entry, editor, nullTarget(), history, { 50 }, { it.run() }, { _, then -> late = then }, gate)
        editor.text = "a; b"
        mine.run()
        editor.text = "x"
        fake.defer = true
        runnerOn(gate).run()
        late(listOf(SqlStatement("y", 0, 1)))
        assertEquals(listOf("x"), fake.executed.map { it.first })
    }

    @Test
    fun `a chooser answered late does not start while the entry runs a statement`() {
        var late: (List<SqlStatement>) -> Unit = {}
        pick = { _, then -> late = then }
        editor.text = "a; b"
        runner.run()
        entry.setRunning(true)
        late(listOf(SqlStatement("y", 0, 1)))
        assertEquals(emptyList(), fake.executed)
    }

    @Test
    fun `a chooser answered after the disconnect logs Not connected`() {
        var late: (List<SqlStatement>) -> Unit = {}
        pick = { _, then -> late = then }
        editor.text = "a; b"
        runner.run()
        entry.ended(null)
        late(listOf(SqlStatement("y", 0, 1)))
        assertEquals(listOf("Not connected" to true), logged)
        assertFalse(runner.running)
    }

    private fun nullTarget() =
        object : RunTarget {
            override fun show(
                outcome: StatementOutcome,
                replaceUnpinned: Boolean,
            ) {}

            override fun log(
                text: String,
                error: Boolean,
            ) {}
        }
}
