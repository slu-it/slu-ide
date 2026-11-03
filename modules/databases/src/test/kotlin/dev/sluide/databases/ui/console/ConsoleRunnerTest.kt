package dev.sluide.databases.ui.console

import dev.sluide.databases.connect.ConnectionConfig
import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.session.StatementOutcome
import dev.sluide.databases.sql.QueryHistory
import dev.sluide.databases.sql.RunChoice
import dev.sluide.databases.sql.SqlStatement
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.result.ExecutingSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The run sequence of a console with a fake session, editor and result target; no toolkit. */
class ConsoleRunnerTest {
    private val config = ConnectionConfig("c1", "c", DatabaseKind.POSTGRESQL)
    private val fake = ExecutingSession(config)
    private val entry = ConnectionEntry(config, "key").apply { connected(fake) }
    private val history = QueryHistory()
    private val shown = mutableListOf<Pair<String, Boolean>>()
    private val logged = mutableListOf<Pair<String, Boolean>>()
    private val marks = mutableListOf<Int>()
    private val offered = mutableListOf<RunChoice.Choose>()
    private var pick: ((RunChoice.Choose, (List<SqlStatement>) -> Unit) -> Unit)? = null

    private val editor =
        object : RunEditor {
            override var text = ""
            override var selectionStart = 0
            override var selectionEnd = 0
            override var caret = 0

            override fun markError(offset: Int) {
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
            { it.run() },
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
        assertEquals(listOf(10 + 7), marks)
    }

    @Test
    fun `no mark when the text changed since the run started`() {
        editor.text = "select x"
        fake.defer = true
        runner.run()
        editor.text = "select y"
        fake.deferred[0].complete(failed("select x", position = 7))
        assertTrue(marks.isEmpty())
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
}
